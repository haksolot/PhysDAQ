#!/usr/bin/env python3
"""Serial data logger — writes raw PPG+IMU samples to a timestamped CSV.

Usage:
    make log
    python analysis/logger.py [PORT]

Output: logs/YYYY-MM-DD_HH-MM-SS.csv
Columns: timestamp(s), red, ir, ax(m/s²), ay, az, gx(rad/s), gy, gz
"""

import sys
import re
import csv
import time
import datetime
from pathlib import Path

try:
    import serial
    import serial.tools.list_ports
except ImportError:
    print("ERROR: pyserial not installed.  pip install pyserial")
    sys.exit(1)

BAUD    = 115200
PATTERN = re.compile(
    r'PPG\s+red=(\d+)\s+ir=(\d+)\s+\|\s+IMU\s+'
    r'ax=(-?[\d.]+)\s+ay=(-?[\d.]+)\s+az=(-?[\d.]+)\s+'
    r'gx=(-?[\d.]+)\s+gy=(-?[\d.]+)\s+gz=(-?[\d.]+)'
)
HEADER = ["timestamp", "red", "ir", "ax", "ay", "az", "gx", "gy", "gz"]


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
        print("No serial ports found.")
        return None
    print("Available ports:")
    for i, p in enumerate(ports):
        marker = " <-- likely Xiao" if p in candidates else ""
        print(f"  [{i}] {p.device} — {p.description}{marker}")
    choice = input("Enter port number: ").strip()
    if choice.isdigit() and 0 <= int(choice) < len(ports):
        return ports[int(choice)].device
    return choice or None


def main():
    port = sys.argv[1] if len(sys.argv) > 1 else find_xiao_port()
    if not port:
        sys.exit(1)

    logs_dir = Path("logs")
    logs_dir.mkdir(exist_ok=True)
    stamp    = datetime.datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
    out_path = logs_dir / f"{stamp}.csv"

    try:
        ser = serial.Serial(port, BAUD, timeout=1)
    except serial.SerialException as e:
        print(f"Cannot open {port}: {e}")
        sys.exit(1)

    print(f"Logging → {out_path}   (Ctrl+C to stop)")

    count = 0
    t0    = time.monotonic()

    try:
        with open(out_path, "w", newline="") as f:
            writer = csv.writer(f)
            writer.writerow(HEADER)
            while True:
                try:
                    line = ser.readline().decode("utf-8", errors="replace").strip()
                except Exception:
                    continue
                m = PATTERN.search(line)
                if not m:
                    continue
                t = time.monotonic() - t0
                writer.writerow([f"{t:.4f}"] + list(m.groups()))
                count += 1
                if count % 100 == 0:
                    elapsed = time.monotonic() - t0
                    print(f"  {count} samples  ({elapsed:.0f}s)", end="\r", flush=True)
    except KeyboardInterrupt:
        print(f"\nStopped — {count} samples saved to {out_path}")


if __name__ == "__main__":
    main()
