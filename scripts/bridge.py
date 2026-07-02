#!/usr/bin/env python3
"""JSON bridge for Electron interface.

Reads raw serial/BLE data from the MAID wearable, processes it in real-time
(Madgwick AHRS filter for 3D orientation, ZUPT drift correction, and FFT-based BPM),
and outputs clean JSON lines to stdout for Electron.

Usage:
    python scripts/bridge.py --ble
    python scripts/bridge.py --ble-addr=DA:10:C0:EF:FD:F9
    python scripts/bridge.py [PORT]
"""

import sys
import re
import json
import time
import collections
import threading
import numpy as np

# Try importing dependencies
try:
    import imufusion
except ImportError:
    print(json.dumps({"error": "imufusion not installed. Run 'pip install imufusion'"}), flush=True)
    sys.exit(1)

try:
    import serial
    import serial.tools.list_ports
except ImportError:
    print(json.dumps({"error": "pyserial not installed. Run 'pip install pyserial'"}), flush=True)
    sys.exit(1)

try:
    from scipy.signal import butter, sosfiltfilt
    _SCIPY_OK = True
except ImportError:
    _SCIPY_OK = False

# Config
BAUD = 115200
SAMPLE_RATE = 100  # Hz

BPM_WINDOW_S = 8.0
BPM_WINDOW_N = int(BPM_WINDOW_S * SAMPLE_RATE)
BPM_SNR_MIN = 3.0
BPM_MAX_STEP = 4.0
BPM_LOST_TICKS = 20

CONTACT_IR_MIN = 5000
CONTACT_CHECK_N = 30

GYRO_STILL_THRESHOLD = 0.05
ZUPT_MIN_STILL_SAMPLES = 20

# Parse CLI Args
USE_BLE = "--ble" in sys.argv
BLE_ADDR = next((a.split("=", 1)[1] for a in sys.argv if a.startswith("--ble-addr=")), None)
if BLE_ADDR:
    USE_BLE = True

PORT = None
if not USE_BLE:
    for arg in sys.argv[1:]:
        if not arg.startswith("-"):
            PORT = arg
            break

# Handle utility commands
if "--list-ports" in sys.argv:
    import serial.tools.list_ports
    ports = list(serial.tools.list_ports.comports())
    out = []
    for p in ports:
        out.append({
            "port": p.device,
            "desc": p.description,
            "hwid": p.hwid
        })
    print(json.dumps(out))
    sys.exit(0)

if "--scan" in sys.argv:
    import asyncio
    from bleak import BleakScanner
    NUS_SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    
    async def run_scan():
        try:
            devices = await BleakScanner.discover(timeout=3.0)
            out = []
            for d in devices:
                out.append({
                    "address": d.address,
                    "name": d.name or "Unknown Device"
                })
            print(json.dumps(out))
        except Exception as err:
            print(json.dumps({"error": str(err)}))
        sys.exit(0)
        
    try:
        asyncio.run(run_scan())
    except Exception as e:
        print(json.dumps({"error": str(e)}))
        sys.exit(1)

PATTERN = re.compile(
    r'PPG\s+red=(\d+)\s+ir=(\d+)\s+\|\s+IMU\s+'
    r'ax=(-?[\d.]+)\s+ay=(-?[\d.]+)\s+az=(-?[\d.]+)\s+'
    r'gx=(-?[\d.]+)\s+gy=(-?[\d.]+)\s+gz=(-?[\d.]+)'
)

# Real-time DSP
if _SCIPY_OK:
    _BPM_BP_SOS = butter(4, [0.7, 3.0], btype="band", fs=SAMPLE_RATE, output="sos")

def _parabolic_peak(spec, k):
    if k <= 0 or k >= len(spec) - 1:
        return float(k)
    y0, y1, y2 = spec[k - 1], spec[k], spec[k + 1]
    denom = (y0 - 2 * y1 + y2)
    if denom == 0:
        return float(k)
    offset = 0.5 * (y0 - y2) / denom
    return k + float(np.clip(offset, -0.5, 0.5))

def rt_bpm_raw(arr):
    if not _SCIPY_OK or len(arr) < BPM_WINDOW_N:
        return None

    sig = sosfiltfilt(_BPM_BP_SOS, arr)
    hann = np.hanning(len(sig))
    spec = np.abs(np.fft.rfft(sig * hann))
    freqs = np.fft.rfftfreq(len(sig), 1.0 / SAMPLE_RATE)
    band = (freqs >= 0.7) & (freqs <= 3.0)
    if not band.any():
        return None

    band_spec = spec[band]
    peak_mag = band_spec.max()
    if peak_mag < 1e-6 or peak_mag < BPM_SNR_MIN * np.median(band_spec):
        return None

    band_idx = np.where(band)[0]
    k = band_idx[np.argmax(band_spec)]
    bin_freq = _parabolic_peak(spec, k)
    freq = bin_freq * (freqs[1] - freqs[0])
    return float(freq) * 60.0

def rt_bpm_smoothed(raw_bpm, state):
    if raw_bpm is None:
        state["lost"] += 1
        if state["lost"] >= BPM_LOST_TICKS:
            state["value"] = None
        return state["value"]

    state["lost"] = 0
    if state["value"] is None:
        state["value"] = raw_bpm
    else:
        step = np.clip(raw_bpm - state["value"], -BPM_MAX_STEP, BPM_MAX_STEP)
        state["value"] += step
    return state["value"]

# State variables
ir_bpm_buf = collections.deque(maxlen=BPM_WINDOW_N)
bpm_state = {"value": None, "lost": 0}
ppg_filter_state = {"dc": None, "lp": None}

ahrs = imufusion.Ahrs()
try:
    ahrs.settings = imufusion.Settings(
        imufusion.Convention.NWU, 0.5, 2000, 10, 10, 5 * SAMPLE_RATE
    )
except Exception:
    pass

_UPDATE_METHOD = next(
    (m for m in ("update_no_magnet", "update_no_magnetometer", "update_imu")
     if hasattr(ahrs, m)),
    None
)

_still_count = 0
_was_still = False
_last_t = time.monotonic()
current_quat = [1.0, 0.0, 0.0, 0.0]

BATT_PATTERN = re.compile(r'Battery:\s+(\d+)%\s+\((\d+)\s+mV\)')

def process_sample(line: str) -> None:
    global _still_count, _was_still, _last_t, current_quat, ppg_filter_state
    
    # Check for battery line
    bm = BATT_PATTERN.search(line)
    if bm:
        try:
            pct = int(bm.group(1))
            mv = int(bm.group(2))
            print(json.dumps({"type": "battery", "pct": pct, "mv": mv}), flush=True)
        except ValueError:
            pass
        return

    m = PATTERN.search(line)
    if not m:
        if line.strip() and not line.startswith("PPG"):
            sys.stderr.write(f"FW: {line}\n")
        return
    try:
        red = float(m.group(1))
        ir = float(m.group(2))
        ax, ay, az = float(m.group(3)), float(m.group(4)), float(m.group(5))
        gx, gy, gz = float(m.group(6)), float(m.group(7)), float(m.group(8))
    except ValueError:
        return

    # Append to BPM buffer
    ir_bpm_buf.append(ir)

    accel = np.array([ax, ay, az]) / 9.81
    gyro_deg = np.array([gx, gy, gz]) * (180.0 / np.pi)

    gyro_norm = float(np.linalg.norm(np.array([gx, gy, gz])))

    if gyro_norm < GYRO_STILL_THRESHOLD:
        _still_count += 1
    else:
        _still_count = 0

    zupt_active = _still_count >= ZUPT_MIN_STILL_SAMPLES
    gyro_input = np.zeros(3) if zupt_active else gyro_deg

    try:
        now = time.monotonic()
        dt = max(now - _last_t, 1e-4)
        _last_t = now
        getattr(ahrs, _UPDATE_METHOD)(gyro_input, accel, dt)
        q = ahrs.quaternion
        current_quat = [float(q.w), float(q.x), float(q.y), float(q.z)]
    except Exception as e:
        sys.stderr.write(f"AHRS Error: {e}\n")

    # BPM calculation
    bpm = None
    has_contact = len(ir_bpm_buf) >= CONTACT_CHECK_N and np.mean(list(ir_bpm_buf)[-CONTACT_CHECK_N:]) > CONTACT_IR_MIN
    if has_contact:
        raw_bpm = rt_bpm_raw(np.array(ir_bpm_buf))
        bpm = rt_bpm_smoothed(raw_bpm, bpm_state)
    else:
        bpm_state["value"] = None
        bpm_state["lost"] = 0

    # Filtered PPG value (BPM AC Waveform) - Pure Python sample-by-sample EMA bandpass filter
    if ppg_filter_state["dc"] is None:
        ppg_filter_state["dc"] = ir
        ppg_filter_state["lp"] = 0.0

    # DC baseline estimate (high-pass filter)
    ppg_filter_state["dc"] = ppg_filter_state["dc"] * 0.985 + ir * 0.015
    ac_raw = ir - ppg_filter_state["dc"]

    # Low-pass filter (remove high-frequency noise)
    ppg_filter_state["lp"] = ppg_filter_state["lp"] * 0.75 + ac_raw * 0.25
    ppg_filt = ppg_filter_state["lp"]

    # Output JSON sample to stdout
    output = {
        "type": "sample",
        "red": red,
        "ir": ir,
        "ppg_filt": ppg_filt,
        "ax": ax,
        "ay": ay,
        "az": az,
        "gx": gx,
        "gy": gy,
        "gz": gz,
        "quat": current_quat,
        "bpm": round(bpm, 1) if bpm is not None else None,
        "contact": bool(has_contact)
    }
    print(json.dumps(output), flush=True)

# Serial transport
def find_xiao_port():
    ports = list(serial.tools.list_ports.comports())
    candidates = [
        p for p in ports
        if any(x in (p.description or "").upper() or x in (p.hwid or "").upper()
               for x in ["XIAO", "SEEED", "NRF52840", "J-LINK"])
    ]
    if len(candidates) == 1:
        return candidates[0].device
    if not ports:
        return None
    # If multiple, auto-return the candidate if available, else first port
    return candidates[0].device if candidates else ports[0].device

def serial_reader(port):
    print(json.dumps({"status": "connecting"}), flush=True)
    ser = None
    while True:
        active_port = port
        if not active_port:
            active_port = find_xiao_port()
            if not active_port:
                sys.stderr.write("Serial: Xiao port not found, retrying in 2 s...\n")
                print(json.dumps({"status": "connecting"}), flush=True)
                time.sleep(2.0)
                continue

        try:
            sys.stderr.write(f"Serial: Opening {active_port}...\n")
            print(json.dumps({"status": "connecting"}), flush=True)
            ser = serial.Serial(active_port, BAUD, timeout=1)
            sys.stderr.write(f"Bridge connected to Serial {active_port}\n")
            print(json.dumps({"status": "connected", "port": active_port}), flush=True)
            
            while True:
                line = ser.readline().decode("utf-8", errors="replace").strip()
                if line:
                    process_sample(line)
        except Exception as e:
            sys.stderr.write(f"Serial error: {e}\n")
            if ser:
                try:
                    ser.close()
                except Exception:
                    pass
                ser = None
            sys.stderr.write("Serial: disconnected — reconnecting in 2 s...\n")
            print(json.dumps({"status": "disconnected"}), flush=True)
            time.sleep(2.0)

# BLE transport
def ble_reader(addr_hint):
    print(json.dumps({"status": "connecting"}), flush=True)
    try:
        import asyncio
        from bleak import BleakScanner, BleakClient
    except ImportError:
        print(json.dumps({"error": "bleak not installed. Run 'pip install bleak'"}), flush=True)
        sys.exit(1)

    NUS_SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    NUS_TX_UUID      = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"

    async def _run():
        nonlocal addr_hint
        
        while True:
            try:
                # Always resolve a BLEDevice via a scan before connecting.
                # On Windows (WinRT) BleakClient cannot connect to a bare
                # address that hasn't been discovered in this session — it
                # raises "Device with address ... was not found". This applies
                # even when the address comes from the app's own --scan step,
                # because that scan ran in a separate process. Resolving the
                # device object here (find_device_by_address / discover) is the
                # reliable cross-platform pattern.
                device = None
                if addr_hint:
                    sys.stderr.write(f"BLE: looking up {addr_hint}...\n")
                    print(json.dumps({"status": "connecting"}), flush=True)
                    device = await BleakScanner.find_device_by_address(addr_hint, timeout=10.0)
                    if device is None:
                        sys.stderr.write(f"BLE: {addr_hint} not found, retrying in 3 s...\n")
                        print(json.dumps({"status": "connecting"}), flush=True)
                        await asyncio.sleep(3.0)
                        continue
                else:
                    sys.stderr.write("BLE: scanning for MAID wearable...\n")
                    print(json.dumps({"status": "connecting"}), flush=True)
                    found = await BleakScanner.discover(timeout=4.0, service_uuids=[NUS_SERVICE_UUID])
                    if not found:
                        # Unfiltered fallback
                        all_devs = await BleakScanner.discover(timeout=3.0)
                        found = [d for d in all_devs if NUS_SERVICE_UUID in [u.lower() for u in (d.metadata.get("uuids") or [])]]
                    if not found:
                        sys.stderr.write("BLE: no sensor found, retrying in 3 s...\n")
                        print(json.dumps({"status": "connecting"}), flush=True)
                        await asyncio.sleep(3.0)
                        continue
                    device = found[0]

                active_addr = device.address
                sys.stderr.write(f"BLE: connecting to {active_addr}...\n")
                print(json.dumps({"status": "connecting"}), flush=True)

                buf = ""
                def on_notify(char, data):
                    nonlocal buf
                    buf += data.decode("utf-8", errors="replace")
                    while "\n" in buf:
                        line, buf = buf.split("\n", 1)
                        process_sample(line)

                async with BleakClient(device, timeout=10.0) as client:
                    sys.stderr.write(f"Bridge connected to BLE {active_addr}\n")
                    print(json.dumps({"status": "connected", "ble_addr": active_addr}), flush=True)
                    await client.start_notify(NUS_TX_UUID, on_notify)
                    while client.is_connected:
                        await asyncio.sleep(0.5)
            except Exception as e:
                sys.stderr.write(f"BLE Error: {e}\n")
            
            sys.stderr.write("BLE: disconnected — retrying in 3 s...\n")
            print(json.dumps({"status": "disconnected"}), flush=True)
            await asyncio.sleep(3.0)

    asyncio.run(_run())

def stdin_watchdog():
    # Watch stdin, when parent Electron closes, stdin gets EOF and we exit
    sys.stdin.read()
    sys.stderr.write("Bridge: stdin closed, exiting...\n")
    sys.exit(0)

def main():
    # Start stdin watchdog thread
    threading.Thread(target=stdin_watchdog, daemon=True).start()

    if USE_BLE:
        ble_reader(BLE_ADDR)
    else:
        serial_reader(PORT)

if __name__ == "__main__":
    main()
