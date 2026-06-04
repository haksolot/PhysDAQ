#!/usr/bin/env python3
"""Real-time gyroscope plotter for Xiao BLE Sense.

Dependencies: pip install pyqtgraph PyQt6 pyserial
"""

import sys
import re
import collections
import threading

try:
    import pyqtgraph as pg
    from pyqtgraph.Qt import QtCore, QtWidgets
except ImportError:
    print("ERROR: pyqtgraph not installed.")
    print("Install: pip install pyqtgraph PyQt6")
    sys.exit(1)

try:
    import serial
    import serial.tools.list_ports
except ImportError:
    print("ERROR: pyserial not installed.")
    print("Install: pip install pyserial")
    sys.exit(1)

WINDOW = 200
BAUD = 115200
PATTERN = re.compile(r'GX:(-?[\d.]+)\s+GY:(-?[\d.]+)\s+GZ:(-?[\d.]+)')

gx = collections.deque([0.0] * WINDOW, maxlen=WINDOW)
gy = collections.deque([0.0] * WINDOW, maxlen=WINDOW)
gz = collections.deque([0.0] * WINDOW, maxlen=WINDOW)


def find_xiao_port():
    ports = list(serial.tools.list_ports.comports())
    candidates = []
    for p in ports:
        desc = (p.description or "").upper()
        hwid = (p.hwid or "").upper()
        if any(x in desc or x in hwid for x in ["XIAO", "SEEED", "NRF52840", "J-LINK"]):
            candidates.append(p)

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

    print(f"Reading from {port} @ {BAUD} baud...")
    while True:
        try:
            line = ser.readline().decode("utf-8", errors="replace").strip()
            m = PATTERN.search(line)
            if m:
                gx.append(float(m.group(1)))
                gy.append(float(m.group(2)))
                gz.append(float(m.group(3)))
        except Exception:
            pass


def main():
    port = find_xiao_port()
    if not port:
        sys.exit(1)

    thread = threading.Thread(target=serial_reader, args=(port,), daemon=True)
    thread.start()

    app = QtWidgets.QApplication(sys.argv)

    pg.setConfigOptions(antialias=True, background="#1e1e2e", foreground="#cdd6f4")

    win = pg.GraphicsLayoutWidget(title="Xiao Sense — Gyroscope Live")
    win.resize(1000, 500)
    win.show()

    plot = win.addPlot(title="Gyroscope (rad/s)")
    plot.showGrid(x=True, y=True, alpha=0.2)
    plot.setLabel("left", "rad/s")
    plot.setLabel("bottom", f"derniers {WINDOW} échantillons")
    plot.addLegend(offset=(10, 10))

    x = list(range(WINDOW))
    curve_gx = plot.plot(x, list(gx), pen=pg.mkPen("#f38ba8", width=2), name="GX")
    curve_gy = plot.plot(x, list(gy), pen=pg.mkPen("#a6e3a1", width=2), name="GY")
    curve_gz = plot.plot(x, list(gz), pen=pg.mkPen("#89b4fa", width=2), name="GZ")

    def update():
        curve_gx.setData(x, list(gx))
        curve_gy.setData(x, list(gy))
        curve_gz.setData(x, list(gz))

    timer = QtCore.QTimer()
    timer.timeout.connect(update)
    timer.start(50)  # 20 FPS

    sys.exit(app.exec())


if __name__ == "__main__":
    main()
