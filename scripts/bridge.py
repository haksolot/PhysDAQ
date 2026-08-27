#!/usr/bin/env python3
"""JSON bridge for Electron interface.

Reads raw serial/BLE data from the PhysDAQ wearable, processes it in real-time
(Madgwick AHRS filter for 3D orientation, ZUPT drift correction, and FFT-based BPM),
and outputs clean JSON lines to stdout for Electron.

Usage:
    python scripts/bridge.py --ble
    python scripts/bridge.py --ble-addr=DA:10:C0:EF:FD:F9
    python scripts/bridge.py [PORT]
"""

import sys
import os
import re
import json
import time
import zlib
import base64
import binascii
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

GYRO_STILL_THRESHOLD = 0.02
ZUPT_MIN_STILL_SAMPLES = 20

# Parse CLI Args
USE_BLE = "--ble" in sys.argv
BLE_ADDR = next((a.split("=", 1)[1] for a in sys.argv if a.startswith("--ble-addr=")), None)
if BLE_ADDR:
    USE_BLE = True

# Device class. Optional: the ID line the firmware emits is what actually
# decides, and channels also grow on their own the first time a two-sensor
# sample arrives. Passing it up front only means the app does not have to wait
# for the first sample to know how many channels this node will produce.
DEVICE_TYPE = next(
    (a.split("=", 1)[1].strip().lower()
     for a in sys.argv if a.startswith("--device-type=")),
    None,
)

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

if "--scan" in sys.argv or "--scan-all" in sys.argv:
    import asyncio
    from bleak import BleakScanner
    NUS_SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"

    # Nodes advertise the NUS UUID in the AD payload precisely so scanners can
    # filter on it (firmware/src/ble.c). Without this an unfiltered discover()
    # returns every phone and pair of headphones in the room, which buries the
    # handful of entries that are actually ours. --scan-all skips the filter, as
    # an escape hatch if an advertisement ever arrives truncated.
    SCAN_ALL = "--scan-all" in sys.argv

    # Manufacturer-specific AD payload, company ID 0xFFFF (the SIG's "local
    # use" value). Firmware writes: proto_ver, device_type, ppg_count, flags.
    # Absent on firmware older than proto 2, in which case the device is a
    # single-PPG node — that is the only thing that existed back then.
    PHYSDAQ_COMPANY_ID = 0xFFFF
    DEV_TYPES = {0x01: "node", 0x02: "hub"}
    DEV_FLAG_SD = 0x01

    UNKNOWN = {"device_type": "node", "ppg_count": 1, "has_sd": False}

    def decode_mfg(adv):
        """Device class from the advertisement, or the pre-proto-2 default.

        bleak keys manufacturer_data by company ID and strips those two bytes
        from the value, so blob is [proto, type, ppg_count, flags].
        """
        blob = (adv.manufacturer_data or {}).get(PHYSDAQ_COMPANY_ID)
        if not blob or len(blob) < 4 or blob[0] < 2:
            # No payload, truncated, or a protocol we do not know how to read.
            # Falling back to "node" is safe in a way the reverse is not: it
            # under-reports a hub, which the ID line then corrects on connect,
            # whereas guessing "hub" would have the app ask for a second
            # channel that never arrives.
            return dict(UNKNOWN)
        return {
            "device_type": DEV_TYPES.get(blob[1], "node"),
            "ppg_count": blob[2] or 1,
            "has_sd": bool(blob[3] & DEV_FLAG_SD),
        }

    async def run_scan():
        try:
            found = await BleakScanner.discover(timeout=3.0, return_adv=True)
            out = []
            for device, adv in found.values():
                uuids = [u.lower() for u in (adv.service_uuids or [])]
                if not SCAN_ALL and NUS_SERVICE_UUID not in uuids:
                    continue
                entry = {
                    "address": device.address,
                    "name": adv.local_name or device.name or "Unknown Device",
                    "rssi": adv.rssi,
                }
                entry.update(decode_mfg(adv))
                out.append(entry)
            # Strongest signal first — the node in your hand is the one you are
            # most likely about to configure.
            out.sort(key=lambda d: d["rssi"] if d["rssi"] is not None else -999, reverse=True)
            print(json.dumps(out))
        except Exception as err:
            print(json.dumps({"error": str(err)}))
        sys.exit(0)

    try:
        asyncio.run(run_scan())
    except Exception as e:
        print(json.dumps({"error": str(e)}))
        sys.exit(1)

# One regex for both the single-PPG node and the dual-PPG hub. The PPG1
# section is optional, so a node's line and a hub's line go through the same
# path and produce the same shape — the hub just fills in a second channel.
#
# Groups: 1,2 = sensor 0 red/ir | 3,4 = sensor 1 red/ir (None on a node)
#         5..10 = ax ay az gx gy gz
PATTERN = re.compile(
    r'PPG\s+red=(\d+)\s+ir=(\d+)\s+\|\s+'
    r'(?:PPG1\s+red=(\d+)\s+ir=(\d+)\s+\|\s+)?'
    r'IMU\s+'
    r'ax=(-?[\d.]+)\s+ay=(-?[\d.]+)\s+az=(-?[\d.]+)\s+'
    r'gx=(-?[\d.]+)\s+gy=(-?[\d.]+)\s+gz=(-?[\d.]+)'
)

# Identity line, emitted at boot and on every new BLE connection. This is the
# authority on what the device is: the advertisement's manufacturer data says
# the same thing but does not exist on the USB transport.
ID_PATTERN = re.compile(
    r'^ID\s+model=(\S+)\s+proto=(\d+)\s+fw=(\S+)\s+ppg=(\d+)\s+sd=(\d+)'
    r'(?:\s+name=(\S*))?'
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
#
# Everything downstream of a PPG sensor is per-sensor: its own BPM window, its
# own BPM smoother, its own filter baseline. The hub has two sensors that sit
# at different body sites, so sharing any of this between them would blend two
# unrelated pulses into one meaningless number. The AHRS below stays shared —
# there is only ever one IMU on the board, whatever the PPG count.
class PpgChannel:
    """DSP state for one PPG sensor."""

    def __init__(self, index: int):
        self.index = index
        self.ir_buf = collections.deque(maxlen=BPM_WINDOW_N)
        self.bpm_state = {"value": None, "lost": 0}
        self.filter = {"dc": None, "lp": None}

    def update(self, red: float, ir: float) -> dict:
        self.ir_buf.append(ir)

        # Contact gate: BPM off an unworn sensor is noise shaped like a pulse,
        # and the smoother would hold that value for BPM_LOST_TICKS after the
        # sensor is picked up again.
        has_contact = (
            len(self.ir_buf) >= CONTACT_CHECK_N
            and np.mean(list(self.ir_buf)[-CONTACT_CHECK_N:]) > CONTACT_IR_MIN
        )
        if has_contact:
            bpm = rt_bpm_smoothed(rt_bpm_raw(np.array(self.ir_buf)), self.bpm_state)
        else:
            self.bpm_state["value"] = None
            self.bpm_state["lost"] = 0
            bpm = None

        # EMA band-pass: DC baseline estimate, then a light low-pass on the AC
        # residual. Sample-by-sample and pure Python, so it costs nothing and
        # needs no scipy.
        if self.filter["dc"] is None:
            self.filter["dc"] = ir
            self.filter["lp"] = 0.0
        self.filter["dc"] = self.filter["dc"] * 0.985 + ir * 0.015
        ac_raw = ir - self.filter["dc"]
        self.filter["lp"] = self.filter["lp"] * 0.75 + ac_raw * 0.25

        return {
            "i": self.index,
            "red": red,
            "ir": ir,
            "ppg_filt": self.filter["lp"],
            "bpm": round(bpm, 1) if bpm is not None else None,
            "contact": bool(has_contact),
        }


ppg_channels = [PpgChannel(0)]


def ensure_channels(n: int) -> None:
    """Grow the channel list to n. Called from the ID line and from the first
    two-sensor sample, whichever arrives first."""
    while len(ppg_channels) < n:
        ppg_channels.append(PpgChannel(len(ppg_channels)))

ahrs = imufusion.Ahrs()
try:
    # imufusion 1.x exposes the convention as a module constant
    # (CONVENTION_NWU); 2.x+ moved it into a Convention enum. Resolve
    # whichever exists so the settings are actually applied — a silent
    # fallback to library defaults here went unnoticed for months.
    # NB: not `getattr(...) or ...` — CONVENTION_NWU is the integer 0, which
    # is falsy and would wrongly fall through to the 2.x+ enum.
    if hasattr(imufusion, "CONVENTION_NWU"):
        _NWU = imufusion.CONVENTION_NWU
    else:
        _NWU = imufusion.Convention.NWU
    ahrs.settings = imufusion.Settings(_NWU, 0.5, 2000, 10, 10, 5 * SAMPLE_RATE)
except Exception as e:
    sys.stderr.write(f"AHRS: settings not applied ({e}) — using library defaults\n")

_UPDATE_METHOD = next(
    (m for m in ("update_no_magnet", "update_no_magnetometer", "update_imu")
     if hasattr(ahrs, m)),
    None
)

_still_count = 0
_was_still = False
_last_t = time.monotonic()
current_quat = [1.0, 0.0, 0.0, 0.0]

# Link-rate monitor: one stderr line every 10 s with the measured sample rate
# (nominal ~100 Hz serial, ~25 Hz BLE). Surfaces in the app's System Logs, so
# a degrading radio link shows up as a falling number *before* the disconnect
# instead of as an unexplained freeze.
_rate_count = 0
_rate_t0 = time.monotonic()

BATT_PATTERN = re.compile(r'Battery:\s+(\d+)%\s+\((\d+)\s+mV\)')

# The hub's 5-second health line. Everything in it is key=value except the
# per-sensor "ok"/"DEAD" word and the rate, so it is parsed by pulling the
# pieces out rather than by one big pattern that would break on any reorder.
HUB_PATTERN = re.compile(r'^Hub:\s+up=(\d+)s')
HUB_SENSOR_RE = re.compile(
    r's(\d)\s+(ok|DEAD)\s+n=(\d+)\s+([\d.]+)Hz\s+ovf=(\d+)\s+ri=(\d+)\s+ir=(\d+)')
# The negative lookahead matters: without it the run of key=value pairs after
# "sd" swallows the trailing "sat=1/8" too, and the satellite count shows up as
# a storage statistic.
HUB_SD_RE = re.compile(r'sd\s+((?:(?!sat=)\w+=\d+\s*)+)')
HUB_SAT_RE = re.compile(r'sat=(\d+)/(\d+)')


def process_hub_status(line: str) -> bool:
    m = HUB_PATTERN.match(line)
    if not m:
        return False

    sensors = []
    for sm in HUB_SENSOR_RE.finditer(line):
        sensors.append({
            "i": int(sm.group(1)),
            "alive": sm.group(2) == "ok",
            "samples": int(sm.group(3)),
            "rate_hz": float(sm.group(4)),
            "overflows": int(sm.group(5)),
            "reinits": int(sm.group(6)),
            "ir": int(sm.group(7)),
        })

    out = {"type": "hub_status", "uptime_s": int(m.group(1)), "sensors": sensors}

    sd = HUB_SD_RE.search(line)
    if sd:
        out["sd"] = {k: int(v) for k, v in KV_RE.findall(sd.group(1))}

    sat = HUB_SAT_RE.search(line)
    if sat:
        out["satellites"] = {"n": int(sat.group(1)), "max": int(sat.group(2))}

    print(json.dumps(out), flush=True)
    return True

def emit_identity(model, proto, fw, ppg_count, has_sd, name, source):
    """Announce what this bridge is talking to, and size the channel list."""
    ensure_channels(max(1, ppg_count))
    print(json.dumps({
        "type": "identity",
        "model": model,
        "proto": proto,
        "fw": fw,
        "ppg_count": ppg_count,
        "has_sd": has_sd,
        "name": name,
        "source": source,
    }), flush=True)


def process_sample(line: str) -> None:
    global _still_count, _was_still, _last_t, current_quat
    global _rate_count, _rate_t0
    
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

    # Device replies to commands, and the hub's periodic health line. Checked
    # before the sample regex because they are far rarer, and after battery
    # because that one is the hottest of the three.
    if process_reply(line):
        return
    if process_hub_status(line):
        return

    # Identity line — the authority on what this device is.
    im = ID_PATTERN.search(line)
    if im:
        try:
            emit_identity(
                model=im.group(1),
                proto=int(im.group(2)),
                fw=im.group(3),
                ppg_count=int(im.group(4)),
                has_sd=im.group(5) == "1",
                name=im.group(6) or "",
                source="device",
            )
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
        # Groups 3/4 are absent on a single-PPG node.
        red1 = float(m.group(3)) if m.group(3) is not None else None
        ir1 = float(m.group(4)) if m.group(4) is not None else None
        ax, ay, az = float(m.group(5)), float(m.group(6)), float(m.group(7))
        gx, gy, gz = float(m.group(8)), float(m.group(9)), float(m.group(10))
    except ValueError:
        return

    _rate_count += 1
    _rate_now = time.monotonic()
    if _rate_now - _rate_t0 >= 10.0:
        rate = _rate_count / (_rate_now - _rate_t0)
        sys.stderr.write(f"Link rate: {rate:.1f} samples/s\n")
        _rate_count = 0
        _rate_t0 = _rate_now

    # Per-channel DSP. Channels grow on demand: the ID line normally sizes
    # them first, but a hub whose ID line was missed still gets a second
    # channel here on its first two-sensor sample.
    if red1 is not None:
        ensure_channels(2)
    channels = [ppg_channels[0].update(red, ir)]
    if red1 is not None:
        channels.append(ppg_channels[1].update(red1, ir1))

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
        # Clamp dt: after a link outage the gap since the previous sample can
        # be tens of seconds, and integrating the current gyro reading over
        # that whole gap snaps the orientation to garbage in one step.
        dt = min(max(now - _last_t, 1e-4), 0.1)
        _last_t = now
        getattr(ahrs, _UPDATE_METHOD)(gyro_input, accel, dt)
        q = ahrs.quaternion
        current_quat = [float(q.w), float(q.x), float(q.y), float(q.z)]
    except Exception as e:
        sys.stderr.write(f"AHRS Error: {e}\n")

    # Output JSON sample to stdout.
    #
    # The top level stays exactly what it always was, carrying channel 0. That
    # is what keeps a single-PPG node's output byte-identical and what lets the
    # app's CSV writer and charts work unchanged. "ch" is purely additive: a
    # hub fills in two entries, a node one.
    ch0 = channels[0]
    output = {
        "type": "sample",
        "red": ch0["red"],
        "ir": ch0["ir"],
        "ppg_filt": ch0["ppg_filt"],
        "ax": ax,
        "ay": ay,
        "az": az,
        "gx": gx,
        "gy": gy,
        "gz": gz,
        "quat": current_quat,
        "bpm": ch0["bpm"],
        "contact": ch0["contact"],
        "ch": channels,
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
    global _send_command
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

            # pyserial guards writes with its own lock, so the stdin thread can
            # call this directly. Captured rather than referencing `ser`, which
            # is rebound on every reconnect.
            def _write(text, _port=ser):
                _port.write(text.encode("utf-8"))

            _send_command = _write

            while True:
                line = ser.readline().decode("utf-8", errors="replace").strip()
                if line:
                    process_sample(line)
        except Exception as e:
            sys.stderr.write(f"Serial error: {e}\n")
            _send_command = None
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
    NUS_RX_UUID      = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"

    async def _run():
        nonlocal addr_hint
        global _send_command

        # bleak's client belongs to this loop and must not be touched from the
        # stdin thread, so commands are handed over through a queue and written
        # by a task running here. call_soon_threadsafe is the one bleak-safe
        # way to cross that boundary.
        loop = asyncio.get_running_loop()
        outbox = asyncio.Queue()

        def enqueue(text):
            loop.call_soon_threadsafe(outbox.put_nowait, text)

        _send_command = enqueue

        async def writer(client):
            while True:
                text = await outbox.get()
                try:
                    # write_without_response: the RX characteristic is declared
                    # WRITE_WITHOUT_RESP, and a command is small enough to fit
                    # one ATT packet at the negotiated MTU.
                    await client.write_gatt_char(
                        NUS_RX_UUID, text.encode("utf-8"), response=False)
                except Exception as e:
                    sys.stderr.write(f"BLE: command write failed: {e}\n")
                    print(json.dumps({
                        "type": "sd_result", "verb": None, "ok": False,
                        "error": str(e),
                    }), flush=True)
        
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
                        sys.stderr.write(f"BLE: {addr_hint} not found, retrying in 1 s...\n")
                        print(json.dumps({"status": "connecting"}), flush=True)
                        await asyncio.sleep(1.0)
                        continue
                else:
                    sys.stderr.write("BLE: scanning for PhysDAQ wearable...\n")
                    print(json.dumps({"status": "connecting"}), flush=True)
                    found = await BleakScanner.discover(timeout=4.0, service_uuids=[NUS_SERVICE_UUID])
                    if not found:
                        # Unfiltered fallback
                        all_devs = await BleakScanner.discover(timeout=3.0)
                        found = [d for d in all_devs if NUS_SERVICE_UUID in [u.lower() for u in (d.metadata.get("uuids") or [])]]
                    if not found:
                        sys.stderr.write("BLE: no sensor found, retrying in 1 s...\n")
                        print(json.dumps({"status": "connecting"}), flush=True)
                        await asyncio.sleep(1.0)
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

                    # Drop anything queued while disconnected: a command sent
                    # to the previous link is stale by definition, and
                    # replaying it after a reconnect would surprise the user.
                    while not outbox.empty():
                        outbox.get_nowait()

                    writer_task = asyncio.create_task(writer(client))
                    try:
                        while client.is_connected:
                            await asyncio.sleep(0.5)
                    finally:
                        writer_task.cancel()
            except Exception as e:
                sys.stderr.write(f"BLE Error: {e}\n")
            
            # Short retry delay: a node that watchdog-reset is advertising
            # again within ~2 s, and the scan/connect timeouts above already
            # bound how long each attempt can take. A long sleep here was the
            # main contributor to 30 s reconnection gaps in recorded sessions.
            sys.stderr.write("BLE: disconnected — retrying in 1 s...\n")
            print(json.dumps({"status": "disconnected"}), flush=True)
            # A transfer cut short by the link dropping leaves a half-written
            # file; discarding it is honest, and sd.get takes an offset so the
            # app can resume rather than start over.
            download.cancel()
            await asyncio.sleep(1.0)

    asyncio.run(_run())

# ── Device replies ───────────────────────────────────────────────────────
#
# Every reply is one ASCII line, and none of them begins with "PPG" — the
# sample parser discards unmatched lines starting with those three characters,
# so a reply that did would vanish without trace.

SD_STAT_RE = re.compile(r'^SD stat mounted=(\d+)(.*)$')
SD_FILE_RE = re.compile(r'^SD file (\S+) (\d+)')
SD_LIST_END_RE = re.compile(r'^SD list end n=(\d+)')
SD_OK_RE = re.compile(r'^SD ok (\S+)\s*(.*)$')
SD_ERR_RE = re.compile(r'^SD err (\S+) (\d+)')
SD_DATA_RE = re.compile(r'^SD data (\S+) (\d+) (\d+) (\S+)$')
SD_DATA_END_RE = re.compile(r'^SD data end (\S+) (\d+) crc=([0-9a-fA-F]+)')
SD_DATA_ABORT_RE = re.compile(r'^SD data abort (\S+) (\d+)')
REC_RE = re.compile(r'^REC state=(on|off)(?:\s+sess=(\d+))?')
SAT_N_RE = re.compile(r'^SAT n=(\d+) max=(\d+)')
SAT_ENTRY_RE = re.compile(
    r'^SAT entry (\d+) addr=(\S+) src=0x([0-9a-fA-F]+) label=(.*)$')
SAT_END_RE = re.compile(r'^SAT list end')
SAT_OK_RE = re.compile(r'^SAT ok (\S+)\s*(.*)$')
SAT_ERR_RE = re.compile(r'^SAT err (\S+) (\d+)')

# Key=value tail of the SD stat line, e.g. "used=123 total=456 sess=4 open=1".
KV_RE = re.compile(r'(\w+)=(\d+)')


class Download:
    """Reassembles an sd.get transfer straight to a file on the host.

    The chunks never reach the app: a one-hour session is ~11 MB, and pumping
    that through Electron IPC and into React state would cost far more than
    the transfer itself. Only progress is forwarded.
    """

    def __init__(self):
        self.reset()

    def reset(self):
        self.name = None
        self.dest = None
        self.fh = None
        self.written = 0
        self.crc = 0

    def begin(self, name, dest):
        self.close()
        self.name = name
        self.dest = dest
        self.written = 0
        self.crc = 0
        self.fh = None
        if dest:
            try:
                os.makedirs(os.path.dirname(dest) or ".", exist_ok=True)
                self.fh = open(dest, "wb")
            except OSError as e:
                sys.stderr.write(f"Bridge: cannot open {dest}: {e}\n")
                self.fh = None

    def close(self):
        if self.fh:
            try:
                self.fh.close()
            except OSError:
                pass
        self.fh = None

    def cancel(self):
        self.close()
        self.reset()

    def chunk(self, name, offset, length, b64):
        if self.name is None:
            # A transfer we did not start — a leftover from a previous run of
            # the app against the same still-streaming device. Ignore rather
            # than write it somewhere unexpected.
            return
        try:
            raw = base64.b64decode(b64, validate=True)
        except (ValueError, binascii.Error) as e:
            sys.stderr.write(f"Bridge: bad base64 chunk at {offset}: {e}\n")
            return
        if len(raw) != length:
            sys.stderr.write(
                f"Bridge: chunk length mismatch at {offset} "
                f"({len(raw)} != {length})\n")
            return

        self.crc = zlib.crc32(raw, self.crc)
        if self.fh:
            self.fh.write(raw)
        self.written += len(raw)

        print(json.dumps({
            "type": "sd_progress",
            "file": name,
            "bytes": self.written,
        }), flush=True)

    def finish(self, name, total, crc_hex):
        self.close()
        device_crc = int(crc_hex, 16)
        # The CRC is the whole point of doing this over a text protocol: a
        # dropped or corrupted chunk is otherwise indistinguishable from a
        # short file.
        ok = (self.written == total) and (self.crc == device_crc)
        print(json.dumps({
            "type": "sd_result",
            "verb": "sd.get",
            "ok": ok,
            "file": name,
            "path": self.dest,
            "bytes": self.written,
            "expected": total,
            "crc": f"{self.crc:08x}",
            "device_crc": f"{device_crc:08x}",
            "error": None if ok else "checksum or length mismatch",
        }), flush=True)
        self.reset()


download = Download()


def process_reply(line: str) -> bool:
    """Parse one device reply. True if the line was ours."""
    m = SD_DATA_RE.match(line)
    if m:
        download.chunk(m.group(1), int(m.group(2)), int(m.group(3)), m.group(4))
        return True

    m = SD_DATA_END_RE.match(line)
    if m:
        download.finish(m.group(1), int(m.group(2)), m.group(3))
        return True

    m = SD_DATA_ABORT_RE.match(line)
    if m:
        download.cancel()
        print(json.dumps({"type": "sd_result", "verb": "sd.get", "ok": False,
                          "file": m.group(1), "error": "aborted"}), flush=True)
        return True

    m = SD_STAT_RE.match(line)
    if m:
        out = {"type": "sd_stat", "mounted": m.group(1) == "1"}
        out.update({k: int(v) for k, v in KV_RE.findall(m.group(2))})
        print(json.dumps(out), flush=True)
        return True

    m = SD_FILE_RE.match(line)
    if m:
        print(json.dumps({"type": "sd_file", "name": m.group(1),
                          "size": int(m.group(2))}), flush=True)
        return True

    m = SD_LIST_END_RE.match(line)
    if m:
        print(json.dumps({"type": "sd_list_end", "n": int(m.group(1))}),
              flush=True)
        return True

    m = SD_OK_RE.match(line)
    if m:
        print(json.dumps({"type": "sd_result", "verb": m.group(1),
                          "ok": True, "detail": m.group(2).strip()}),
              flush=True)
        return True

    m = SD_ERR_RE.match(line)
    if m:
        print(json.dumps({"type": "sd_result", "verb": m.group(1),
                          "ok": False, "errno": int(m.group(2))}), flush=True)
        return True

    m = REC_RE.match(line)
    if m:
        print(json.dumps({
            "type": "rec_state",
            "recording": m.group(1) == "on",
            "session": int(m.group(2)) if m.group(2) else None,
        }), flush=True)
        return True

    m = SAT_N_RE.match(line)
    if m:
        print(json.dumps({"type": "sat_begin", "n": int(m.group(1)),
                          "max": int(m.group(2))}), flush=True)
        return True

    m = SAT_ENTRY_RE.match(line)
    if m:
        print(json.dumps({
            "type": "sat_entry",
            "slot": int(m.group(1)),
            "addr": m.group(2),
            "source_id": int(m.group(3), 16),
            "label": m.group(4).strip(),
        }), flush=True)
        return True

    if SAT_END_RE.match(line):
        print(json.dumps({"type": "sat_end"}), flush=True)
        return True

    m = SAT_OK_RE.match(line)
    if m:
        print(json.dumps({"type": "sat_result", "verb": m.group(1),
                          "ok": True, "detail": m.group(2).strip()}),
              flush=True)
        return True

    m = SAT_ERR_RE.match(line)
    if m:
        print(json.dumps({"type": "sat_result", "verb": m.group(1),
                          "ok": False, "errno": int(m.group(2))}), flush=True)
        return True

    return False


# ── Downlink: host → device ──────────────────────────────────────────────
#
# The app writes one JSON command per line to this process's stdin; we turn it
# into the firmware's ASCII "CMD ..." line and push it out over whichever
# transport is live. Replies come back through the normal read path and are
# parsed alongside samples, so nothing new is needed there.
#
# The transports own the actual write: serial can be written from any thread,
# but bleak's client belongs to the asyncio loop, so BLE goes through a queue
# the loop drains. `_send_command` is set by whichever reader is running.
_send_command = None


def _cmd_to_wire(cmd: dict) -> str:
    """Translate one JSON command into the firmware's line grammar."""
    verb = str(cmd.get("cmd", "")).strip()
    if not verb:
        return ""

    if verb == "sd.del":
        return f"CMD sd.del {cmd.get('file', '')}"
    if verb == "sd.get":
        off = int(cmd.get("offset", 0) or 0)
        return f"CMD sd.get {cmd.get('file', '')} {off}"
    if verb == "sat.add":
        # Label last and unquoted: the firmware takes the rest of the line as
        # the label, so spaces survive. A newline would split the command in
        # two, so it is flattened rather than passed through.
        label = str(cmd.get("label", "")).replace("\n", " ").strip()
        return f"CMD sat.add {cmd.get('addr', '')} {label}".rstrip()
    if verb == "sat.del":
        return f"CMD sat.del {cmd.get('addr', '')}"

    # Everything else is a bare verb: id, sd.stat, sd.list, sd.format,
    # rec.start, rec.stop, sd.abort, sat.list, sat.clear.
    return f"CMD {verb}"


def handle_stdin_line(raw: str) -> None:
    try:
        cmd = json.loads(raw)
    except ValueError:
        sys.stderr.write(f"Bridge: ignoring non-JSON stdin line: {raw[:80]}\n")
        return

    if not isinstance(cmd, dict):
        return

    # Download destination is a host-side concern; the firmware neither knows
    # nor cares. Remember it before the wire form drops it.
    if cmd.get("cmd") == "sd.get":
        download.begin(cmd.get("file", ""), cmd.get("dest"))
    elif cmd.get("cmd") == "sd.abort":
        download.cancel()

    wire = _cmd_to_wire(cmd)
    if not wire:
        return

    if _send_command is None:
        print(json.dumps({
            "type": "sd_result",
            "verb": cmd.get("cmd"),
            "ok": False,
            "error": "not connected",
        }), flush=True)
        return

    try:
        _send_command(wire + "\n")
    except Exception as e:
        sys.stderr.write(f"Bridge: command write failed: {e}\n")
        print(json.dumps({
            "type": "sd_result",
            "verb": cmd.get("cmd"),
            "ok": False,
            "error": str(e),
        }), flush=True)


def stdin_watchdog():
    # Two jobs on one thread. Reading line by line makes stdin the command
    # channel; EOF still means the parent Electron process is gone.
    for raw in sys.stdin:
        raw = raw.strip()
        if raw:
            handle_stdin_line(raw)

    sys.stderr.write("Bridge: stdin closed, exiting...\n")
    # os._exit(), not sys.exit(): this runs in a daemon thread, where
    # sys.exit() only raises SystemExit in *this* thread and leaves the main
    # thread (serial reader / asyncio BLE loop) running forever — orphaning the
    # process and keeping the serial port / BLE connection locked. os._exit()
    # terminates the whole process immediately, releasing those resources.
    os._exit(0)

def main():
    # Start stdin watchdog thread
    threading.Thread(target=stdin_watchdog, daemon=True).start()

    # If the app told us what it is connecting to, say so before the first
    # sample arrives. The device's own ID line supersedes this the moment it
    # lands; announcing it up front just means the UI can lay out two channels
    # immediately instead of reflowing when the first sample shows up.
    if DEVICE_TYPE:
        is_hub = DEVICE_TYPE == "hub"
        emit_identity(
            model=DEVICE_TYPE,
            proto=None,
            fw=None,
            ppg_count=2 if is_hub else 1,
            has_sd=is_hub,
            name="",
            source="cli",
        )

    if USE_BLE:
        ble_reader(BLE_ADDR)
    else:
        serial_reader(PORT)

if __name__ == "__main__":
    main()
