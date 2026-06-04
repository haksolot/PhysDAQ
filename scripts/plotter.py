#!/usr/bin/env python3
"""Real-time IMU plotter + 3D orientation visualizer for Xiao BLE Sense.

Left panel : gyroscope time series (GX/GY/GZ)
Right panel: 3D board rotating in real time via Madgwick filter (imufusion)

Dependencies: pip install pyqtgraph PyQt6 pyserial imufusion
NOTE: yaw drifts over time without a magnetometer — pitch/roll are stable.

Future: migrate filter to firmware (option 3) for lower latency + stable yaw
        with an external magnetometer.
"""

import sys
import re
import time
import collections
import threading
import numpy as np

try:
    import pyqtgraph as pg
    from pyqtgraph.Qt import QtCore, QtWidgets, QtGui
except ImportError:
    print("ERROR: pyqtgraph not installed.\nInstall: pip install pyqtgraph PyQt6")
    sys.exit(1)

try:
    import pyqtgraph.opengl as gl
except ImportError:
    print("ERROR: PyOpenGL not installed (required for 3D view).\nInstall: pip install PyOpenGL")
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
WINDOW = 200       # samples shown in the gyro plot
BAUD   = 115200
SAMPLE_RATE = 20   # Hz — matches firmware k_sleep(50ms)

GYRO_STILL_THRESHOLD  = 0.05  # rad/s — below this = board considered stationary
ZUPT_MIN_STILL_SAMPLES = 10   # consecutive still samples before ZUPT kicks in

PATTERN = re.compile(
    r'AX:(-?[\d.]+)\s+AY:(-?[\d.]+)\s+AZ:(-?[\d.]+)\s+\|\s+'
    r'GX:(-?[\d.]+)\s+GY:(-?[\d.]+)\s+GZ:(-?[\d.]+)'
)

# ---------------------------------------------------------------------------
# Shared state (serial thread → UI thread)
# ---------------------------------------------------------------------------
gx_buf = collections.deque([0.0] * WINDOW, maxlen=WINDOW)
gy_buf = collections.deque([0.0] * WINDOW, maxlen=WINDOW)
gz_buf = collections.deque([0.0] * WINDOW, maxlen=WINDOW)

ahrs = imufusion.Ahrs()
try:
    ahrs.settings = imufusion.Settings(
        imufusion.Convention.NWU,
        0.5,              # gain
        2000,             # gyroscope range deg/s
        10,               # acceleration rejection (g)
        10,               # magnetic rejection (unused)
        5 * SAMPLE_RATE,  # recovery trigger period (samples)
    )
except Exception:
    pass  # settings API varies between versions

# Detect the correct update method name (changed between imufusion releases)
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

ahrs_lock = threading.Lock()
current_quat = np.array([1.0, 0.0, 0.0, 0.0])  # [w, x, y, z] — identity


# ---------------------------------------------------------------------------
# Serial port detection (same logic as term.py)
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


# ---------------------------------------------------------------------------
# Serial reader thread
# ---------------------------------------------------------------------------
def serial_reader(port):
    global current_quat
    try:
        ser = serial.Serial(port, BAUD, timeout=1)
    except serial.SerialException as e:
        print(f"Cannot open {port}: {e}")
        sys.exit(1)

    print(f"Reading from {port} @ {BAUD} baud...")
    last_t      = time.monotonic()
    still_count = 0
    was_still   = False
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
                print("Too many serial errors — reconnecting...")
                try:
                    ser.close()
                except Exception:
                    pass
                time.sleep(1.0)
                try:
                    ser = serial.Serial(port, BAUD, timeout=1)
                    error_count = 0
                    print(f"Reconnected to {port}")
                except serial.SerialException as re:
                    print(f"Reconnect failed: {re}")
            continue

        m = PATTERN.search(line)
        if not m:
            continue

        try:
            ax, ay, az = float(m.group(1)), float(m.group(2)), float(m.group(3))
            gx, gy, gz = float(m.group(4)), float(m.group(5)), float(m.group(6))
        except ValueError:
            continue

        now = time.monotonic()
        dt  = max(now - last_t, 1e-4)
        last_t = now

        gx_buf.append(gx)
        gy_buf.append(gy)
        gz_buf.append(gz)

        # imufusion expects accel in g, gyro in deg/s
        accel    = np.array([ax, ay, az]) / 9.81
        gyro_deg = np.array([gx, gy, gz]) * (180.0 / np.pi)

        # ZUPT: pass zero gyro to Madgwick when stationary → stops drift integration
        gyro_norm = float(np.linalg.norm(np.array([gx, gy, gz])))
        if gyro_norm < GYRO_STILL_THRESHOLD:
            still_count += 1
        else:
            still_count = 0

        zupt_active = still_count >= ZUPT_MIN_STILL_SAMPLES
        if zupt_active != was_still:
            print("ZUPT: ON — drift frozen" if zupt_active else "ZUPT: OFF — tracking")
            was_still = zupt_active

        gyro_input = np.zeros(3) if zupt_active else gyro_deg

        try:
            with ahrs_lock:
                getattr(ahrs, _UPDATE_METHOD)(gyro_input, accel, dt)
                q = ahrs.quaternion
                current_quat[0] = float(q.w)
                current_quat[1] = float(q.x)
                current_quat[2] = float(q.y)
                current_quat[3] = float(q.z)
        except Exception as e:
            print(f"AHRS error: {e}")


# ---------------------------------------------------------------------------
# 3D board mesh  (roughly XIAO BLE Sense proportions)
# ---------------------------------------------------------------------------
def make_board_mesh():
    w, h, t = 2.1, 1.75, 0.12   # width, height, thickness (normalised)
    v = np.array([
        [-w/2, -h/2, -t/2], [ w/2, -h/2, -t/2],
        [ w/2,  h/2, -t/2], [-w/2,  h/2, -t/2],
        [-w/2, -h/2,  t/2], [ w/2, -h/2,  t/2],
        [ w/2,  h/2,  t/2], [-w/2,  h/2,  t/2],
    ], dtype=np.float32)
    f = np.array([
        [0,1,2],[0,2,3],  # bottom
        [4,5,6],[4,6,7],  # top
        [0,1,5],[0,5,4],  # front
        [2,3,7],[2,7,6],  # back
        [0,3,7],[0,7,4],  # left
        [1,2,6],[1,6,5],  # right
    ])
    colors = np.tile([0.12, 0.38, 0.12, 1.0], (len(f), 1)).astype(np.float32)
    colors[2:4] = [0.20, 0.62, 0.20, 1.0]  # top face slightly brighter
    md = gl.MeshData(vertexes=v, faces=f, faceColors=colors)
    return gl.GLMeshItem(meshdata=md, smooth=False,
                         drawEdges=True, edgeColor=(0.45, 0.9, 0.45, 0.9))


# ---------------------------------------------------------------------------
# Apply orientation from quaternion [w, x, y, z] to a GL item
# ---------------------------------------------------------------------------
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
    port = find_xiao_port()
    if not port:
        sys.exit(1)

    threading.Thread(target=serial_reader, args=(port,), daemon=True).start()

    app = QtWidgets.QApplication(sys.argv)
    pg.setConfigOptions(antialias=True, background="#1e1e2e", foreground="#cdd6f4")

    win = QtWidgets.QWidget()
    win.setWindowTitle("Xiao Sense — IMU Live")
    win.resize(1200, 560)
    layout = QtWidgets.QHBoxLayout(win)
    layout.setContentsMargins(6, 6, 6, 6)
    layout.setSpacing(6)

    # ---- left: gyro plot ----
    plot = pg.PlotWidget(title="Gyroscope (rad/s)")
    plot.showGrid(x=True, y=True, alpha=0.2)
    plot.setLabel("left", "rad/s")
    plot.setLabel("bottom", f"derniers {WINDOW} échantillons  (@20 Hz = {WINDOW//20}s)")
    plot.addLegend(offset=(10, 10))
    xs = list(range(WINDOW))
    c_gx = plot.plot(xs, list(gx_buf), pen=pg.mkPen("#f38ba8", width=2), name="GX")
    c_gy = plot.plot(xs, list(gy_buf), pen=pg.mkPen("#a6e3a1", width=2), name="GY")
    c_gz = plot.plot(xs, list(gz_buf), pen=pg.mkPen("#89b4fa", width=2), name="GZ")
    layout.addWidget(plot, stretch=1)

    # ---- right: 3D view ----
    view = gl.GLViewWidget()
    view.setMinimumWidth(480)
    view.setCameraPosition(distance=6, elevation=25, azimuth=45)
    view.setBackgroundColor("#1e1e2e")

    # World-frame reference grid + axes
    grid = gl.GLGridItem()
    grid.setSize(8, 8)
    grid.setSpacing(1, 1)
    grid.setColor((80, 80, 100, 60))
    view.addItem(grid)

    world_axes = gl.GLAxisItem()
    world_axes.setSize(1.5, 1.5, 1.5)
    view.addItem(world_axes)

    # Board mesh + body-frame axes (rotate together)
    board = make_board_mesh()
    view.addItem(board)

    body_axes = gl.GLAxisItem()
    body_axes.setSize(2.5, 2.5, 2.5)
    view.addItem(body_axes)

    layout.addWidget(view, stretch=1)
    win.show()

    # ---- timer: update both panels ----
    def update():
        c_gx.setData(xs, list(gx_buf))
        c_gy.setData(xs, list(gy_buf))
        c_gz.setData(xs, list(gz_buf))

        with ahrs_lock:
            q = current_quat.copy()

        apply_quat(board,      q)
        apply_quat(body_axes,  q)

    timer = QtCore.QTimer()
    timer.timeout.connect(update)
    timer.start(50)  # 20 FPS

    sys.exit(app.exec())


if __name__ == "__main__":
    main()
