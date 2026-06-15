#!/usr/bin/env python3
"""Real-time IMU + PPG plotter for MAID wearable (Xiao BLE Sense + MAX30102).

Layout:
  Left top  : Gyroscope time series (GX/GY/GZ in rad/s)
  Left bot  : PPG waveform (Red and IR, raw 18-bit ADC counts)
  Right     : 3D board orientation via Madgwick filter (imufusion)

Transports:
  make plot      — USB serial (auto-detect)
  make ble-plot  — BLE NUS (discovers MAID by service UUID)

Dependencies: pip install pyqtgraph PyQt6 pyserial imufusion PyOpenGL bleak
NOTE: yaw drifts over time without a magnetometer — pitch/roll are stable.
"""

import sys
import re
import time
import collections
import threading
import queue
import numpy as np

USE_BLE  = "--ble" in sys.argv
BLE_ADDR = next((a.split("=", 1)[1] for a in sys.argv if a.startswith("--ble-addr=")), None)
if BLE_ADDR:
    USE_BLE = True

try:
    import pyqtgraph as pg
    from pyqtgraph.Qt import QtCore, QtWidgets, QtGui
except ImportError:
    print("ERROR: pyqtgraph not installed.\nInstall: pip install pyqtgraph PyQt6")
    sys.exit(1)

try:
    import pyqtgraph.opengl as gl
except ImportError:
    print("ERROR: PyOpenGL not installed.\nInstall: pip install PyOpenGL")
    sys.exit(1)

try:
    import imufusion
except ImportError:
    print("ERROR: imufusion not installed.\nInstall: pip install imufusion")
    sys.exit(1)

try:
    import serial
    import serial.tools.list_ports
except ImportError:
    print("ERROR: pyserial not installed.\nInstall: pip install pyserial")
    sys.exit(1)

# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------
WINDOW      = 300       # samples shown in plots (3 s at 100 Hz)
BAUD        = 115200
SAMPLE_RATE = 100       # Hz — driven by MAX30102 PPG_RDY interrupt

GYRO_STILL_THRESHOLD   = 0.05  # rad/s — below this = stationary
ZUPT_MIN_STILL_SAMPLES = 20    # consecutive still samples before ZUPT

# Matches: "PPG red=NNN ir=NNN | IMU ax=... ay=... az=... gx=... gy=... gz=..."
# Gyro is in rad/s (Zephyr sensor API), accel in m/s².
PATTERN = re.compile(
    r'PPG\s+red=(\d+)\s+ir=(\d+)\s+\|\s+IMU\s+'
    r'ax=(-?[\d.]+)\s+ay=(-?[\d.]+)\s+az=(-?[\d.]+)\s+'
    r'gx=(-?[\d.]+)\s+gy=(-?[\d.]+)\s+gz=(-?[\d.]+)'
)

# ---------------------------------------------------------------------------
# Shared state (serial thread → UI thread)
# ---------------------------------------------------------------------------
gx_buf  = collections.deque([0.0] * WINDOW, maxlen=WINDOW)
gy_buf  = collections.deque([0.0] * WINDOW, maxlen=WINDOW)
gz_buf  = collections.deque([0.0] * WINDOW, maxlen=WINDOW)
red_buf = collections.deque([0.0] * WINDOW, maxlen=WINDOW)
ir_buf  = collections.deque([0.0] * WINDOW, maxlen=WINDOW)

ahrs = imufusion.Ahrs()
try:
    ahrs.settings = imufusion.Settings(
        imufusion.Convention.NWU,
        0.5,
        2000,
        10,
        10,
        5 * SAMPLE_RATE,
    )
except Exception:
    pass

_UPDATE_METHOD = next(
    (m for m in ("update_no_magnet", "update_no_magnetometer", "update_imu")
     if hasattr(ahrs, m)),
    None
)
if _UPDATE_METHOD is None:
    print(f"ERROR: imufusion API not recognized (dir: {[x for x in dir(ahrs) if not x.startswith('_')]})")
    print("Try: pip install --upgrade imufusion")
    sys.exit(1)
print(f"imufusion AHRS method: {_UPDATE_METHOD}")

ahrs_lock    = threading.Lock()
current_quat = np.array([1.0, 0.0, 0.0, 0.0])  # [w, x, y, z] — identity


# ---------------------------------------------------------------------------
# Shared sample processing (serial and BLE both feed here)
# ---------------------------------------------------------------------------
def process_line(line: str) -> None:
    global current_quat
    m = PATTERN.search(line)
    if not m:
        return
    try:
        red = float(m.group(1))
        ir  = float(m.group(2))
        ax, ay, az = float(m.group(3)), float(m.group(4)), float(m.group(5))
        gx, gy, gz = float(m.group(6)), float(m.group(7)), float(m.group(8))
    except ValueError:
        return

    red_buf.append(red)
    ir_buf.append(ir)
    gx_buf.append(gx)
    gy_buf.append(gy)
    gz_buf.append(gz)

    accel    = np.array([ax, ay, az]) / 9.81
    gyro_deg = np.array([gx, gy, gz]) * (180.0 / np.pi)

    gyro_norm   = float(np.linalg.norm(np.array([gx, gy, gz])))
    still_count = process_line._still_count
    was_still   = process_line._was_still

    if gyro_norm < GYRO_STILL_THRESHOLD:
        still_count += 1
    else:
        still_count = 0

    zupt_active = still_count >= ZUPT_MIN_STILL_SAMPLES
    if zupt_active != was_still:
        print("ZUPT: ON — drift frozen" if zupt_active else "ZUPT: OFF — tracking")

    process_line._still_count = still_count
    process_line._was_still   = zupt_active

    gyro_input = np.zeros(3) if zupt_active else gyro_deg

    try:
        dt = max(time.monotonic() - process_line._last_t, 1e-4)
        process_line._last_t = time.monotonic()
        with ahrs_lock:
            getattr(ahrs, _UPDATE_METHOD)(gyro_input, accel, dt)
            q = ahrs.quaternion
            current_quat[0] = float(q.w)
            current_quat[1] = float(q.x)
            current_quat[2] = float(q.y)
            current_quat[3] = float(q.z)
    except Exception as e:
        print(f"AHRS error: {e}")

process_line._still_count = 0
process_line._was_still   = False
process_line._last_t      = time.monotonic()


# ---------------------------------------------------------------------------
# Serial transport
# ---------------------------------------------------------------------------
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
        print("No serial ports found. Is the Xiao connected?")
        return None
    print("Available ports:")
    for i, p in enumerate(ports):
        marker = " <-- likely Xiao" if p in candidates else ""
        print(f"  [{i}] {p.device} — {p.description}{marker}")
    choice = input("Enter port number: ").strip()
    if choice.isdigit() and 0 <= int(choice) < len(ports):
        return ports[int(choice)].device
    return choice or None


def serial_reader(port):
    try:
        ser = serial.Serial(port, BAUD, timeout=1)
    except serial.SerialException as e:
        print(f"Cannot open {port}: {e}")
        sys.exit(1)

    print(f"Transport: USB serial ({port} @ {BAUD})")
    error_count = 0
    while True:
        try:
            line = ser.readline().decode("utf-8", errors="replace").strip()
            error_count = 0
        except Exception as e:
            error_count += 1
            if error_count == 1:
                print(f"Serial error: {e}")
            if error_count >= 5:
                print("Reconnecting...")
                try:
                    ser.close()
                except Exception:
                    pass
                time.sleep(1.0)
                try:
                    ser = serial.Serial(port, BAUD, timeout=1)
                    error_count = 0
                    print(f"Reconnected to {port}")
                except serial.SerialException as exc:
                    print(f"Reconnect failed: {exc}")
            continue
        process_line(line)


# ---------------------------------------------------------------------------
# BLE transport
# ---------------------------------------------------------------------------
NUS_SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
NUS_TX_UUID      = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"


async def _ble_find_device(addr_hint, timeout=8.0):
    """Discover by NUS UUID (not by name). Interactive pick when multiple found."""
    from bleak import BleakScanner
    if addr_hint:
        return addr_hint
    print("BLE: scanning for sensors (NUS UUID)...")
    found = await BleakScanner.discover(timeout=timeout,
                                        service_uuids=[NUS_SERVICE_UUID])
    if not found:
        print("ERROR: no sensor found. Is the board on and not sleeping?")
        sys.exit(1)
    if len(found) == 1:
        print(f"BLE: found {found[0].name}  {found[0].address}")
        return found[0].address
    print(f"\n{len(found)} sensors found:")
    for i, d in enumerate(found):
        print(f"  [{i}] {d.address}  {d.name}")
    print("Tip: use --ble-addr=<address> to skip this prompt.")
    choice = input("Select sensor [0]: ").strip()
    idx = int(choice) if choice.isdigit() and int(choice) < len(found) else 0
    return found[idx].address


def ble_reader():
    try:
        import asyncio
        from bleak import BleakClient
    except ImportError:
        print("ERROR: bleak not installed.  pip install bleak")
        sys.exit(1)

    buf        = [""]
    last_data  = [time.monotonic()]

    def on_notify(_, data):
        buf[0] += data.decode("utf-8", errors="replace")
        while "\n" in buf[0]:
            line, buf[0] = buf[0].split("\n", 1)
            process_line(line)
            last_data[0] = time.monotonic()

    async def _stream(addr):
        from bleak import BleakClient
        async with BleakClient(addr, timeout=10.0) as client:
            await client.start_notify(NUS_TX_UUID, on_notify)
            last_data[0] = time.monotonic()  # start watchdog only after subscribe
            print(f"Transport: BLE NUS ({addr}) — streaming")
            while client.is_connected:
                await asyncio.sleep(0.2)
                if time.monotonic() - last_data[0] > 10.0:
                    print("BLE: no data for 10 s — reconnecting...")
                    break
            try:
                await client.stop_notify(NUS_TX_UUID)
            except Exception:
                pass

    async def _run():
        addr = await _ble_find_device(BLE_ADDR)
        while True:
            try:
                await _stream(addr)
            except Exception as e:
                print(f"BLE: {e}")
            print("BLE: reconnecting in 3 s...")
            await asyncio.sleep(3.0)

    import asyncio
    asyncio.run(_run())


# ---------------------------------------------------------------------------
# 3D board mesh
# ---------------------------------------------------------------------------
def make_board_mesh():
    w, h, t = 2.1, 1.75, 0.12
    v = np.array([
        [-w/2, -h/2, -t/2], [ w/2, -h/2, -t/2],
        [ w/2,  h/2, -t/2], [-w/2,  h/2, -t/2],
        [-w/2, -h/2,  t/2], [ w/2, -h/2,  t/2],
        [ w/2,  h/2,  t/2], [-w/2,  h/2,  t/2],
    ], dtype=np.float32)
    f = np.array([
        [0,1,2],[0,2,3],
        [4,5,6],[4,6,7],
        [0,1,5],[0,5,4],
        [2,3,7],[2,7,6],
        [0,3,7],[0,7,4],
        [1,2,6],[1,6,5],
    ])
    colors = np.tile([0.12, 0.38, 0.12, 1.0], (len(f), 1)).astype(np.float32)
    colors[2:4] = [0.20, 0.62, 0.20, 1.0]
    md = gl.MeshData(vertexes=v, faces=f, faceColors=colors)
    return gl.GLMeshItem(meshdata=md, smooth=False,
                         drawEdges=True, edgeColor=(0.45, 0.9, 0.45, 0.9))


def apply_quat(item, wxyz):
    w, x, y, z = float(wxyz[0]), float(wxyz[1]), float(wxyz[2]), float(wxyz[3])
    mat = QtGui.QMatrix4x4(
        1-2*(y*y+z*z),   2*(x*y-w*z),   2*(x*z+w*y), 0,
          2*(x*y+w*z), 1-2*(x*x+z*z),   2*(y*z-w*x), 0,
          2*(x*z-w*y),   2*(y*z+w*x), 1-2*(x*x+y*y), 0,
        0,               0,             0,             1,
    )
    item.setTransform(pg.Transform3D(mat))


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
def main():
    if USE_BLE:
        threading.Thread(target=ble_reader, daemon=True).start()
    else:
        port = find_xiao_port()
        if not port:
            sys.exit(1)
        threading.Thread(target=serial_reader, args=(port,), daemon=True).start()

    app = QtWidgets.QApplication(sys.argv)
    pg.setConfigOptions(antialias=True, background="#1e1e2e", foreground="#cdd6f4")

    win = QtWidgets.QWidget()
    win.setWindowTitle("MAID — IMU + PPG Live")
    win.resize(1400, 700)

    layout = QtWidgets.QHBoxLayout(win)
    layout.setContentsMargins(6, 6, 6, 6)
    layout.setSpacing(6)

    xs = list(range(WINDOW))

    # ── left column: gyro (top) + PPG (bottom) ──────────────────────────
    left_widget = QtWidgets.QWidget()
    left_layout = QtWidgets.QVBoxLayout(left_widget)
    left_layout.setContentsMargins(0, 0, 0, 0)
    left_layout.setSpacing(4)

    gyro_plot = pg.PlotWidget(title="Gyroscope (rad/s)")
    gyro_plot.showGrid(x=True, y=True, alpha=0.2)
    gyro_plot.setLabel("left", "rad/s")
    gyro_plot.setLabel("bottom", f"derniers {WINDOW} échantillons  (@{SAMPLE_RATE} Hz = {WINDOW//SAMPLE_RATE}s)")
    gyro_plot.addLegend(offset=(10, 10))
    c_gx = gyro_plot.plot(xs, list(gx_buf), pen=pg.mkPen("#f38ba8", width=2), name="GX")
    c_gy = gyro_plot.plot(xs, list(gy_buf), pen=pg.mkPen("#a6e3a1", width=2), name="GY")
    c_gz = gyro_plot.plot(xs, list(gz_buf), pen=pg.mkPen("#89b4fa", width=2), name="GZ")
    left_layout.addWidget(gyro_plot, stretch=1)

    ppg_plot = pg.PlotWidget(title="PPG — brut 18-bit (pose le doigt sur le capteur)")
    ppg_plot.showGrid(x=True, y=True, alpha=0.2)
    ppg_plot.setLabel("left", "ADC counts")
    ppg_plot.setLabel("bottom", f"derniers {WINDOW} échantillons  (@{SAMPLE_RATE} Hz = {WINDOW//SAMPLE_RATE}s)")
    ppg_plot.addLegend(offset=(10, 10))
    c_red = ppg_plot.plot(xs, list(red_buf), pen=pg.mkPen("#f38ba8", width=2), name="Red")
    c_ir  = ppg_plot.plot(xs, list(ir_buf),  pen=pg.mkPen("#cba6f7", width=2), name="IR")
    left_layout.addWidget(ppg_plot, stretch=1)

    layout.addWidget(left_widget, stretch=2)

    # ── right column: 3D orientation ────────────────────────────────────
    view = gl.GLViewWidget()
    view.setMinimumWidth(420)
    view.setCameraPosition(distance=6, elevation=25, azimuth=45)
    view.setBackgroundColor("#1e1e2e")

    grid = gl.GLGridItem()
    grid.setSize(8, 8)
    grid.setSpacing(1, 1)
    grid.setColor((80, 80, 100, 60))
    view.addItem(grid)

    world_axes = gl.GLAxisItem()
    world_axes.setSize(1.5, 1.5, 1.5)
    view.addItem(world_axes)

    board = make_board_mesh()
    view.addItem(board)

    body_axes = gl.GLAxisItem()
    body_axes.setSize(2.5, 2.5, 2.5)
    view.addItem(body_axes)

    layout.addWidget(view, stretch=1)
    win.show()

    # ── timer: refresh all panels at 20 FPS ─────────────────────────────
    def update():
        c_gx.setData(xs, list(gx_buf))
        c_gy.setData(xs, list(gy_buf))
        c_gz.setData(xs, list(gz_buf))
        c_red.setData(xs, list(red_buf))
        c_ir.setData(xs, list(ir_buf))

        with ahrs_lock:
            q = current_quat.copy()
        apply_quat(board,     q)
        apply_quat(body_axes, q)

    timer = QtCore.QTimer()
    timer.timeout.connect(update)
    timer.start(50)  # 20 FPS

    sys.exit(app.exec())


if __name__ == "__main__":
    main()
