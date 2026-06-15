#!/usr/bin/env python3
"""Data logger — writes raw PPG+IMU samples to a timestamped CSV.

Supports two transports:
  USB serial (default):  make log
  BLE NUS:               make ble-log

Usage:
    make log                       # USB serial, auto-detect port
    make ble-log                   # BLE, discovers MAID by NUS UUID
    python analysis/logger.py [PORT]
    python analysis/logger.py --ble

Output: logs/YYYY-MM-DD_HH-MM-SS.csv
Columns: timestamp(s), red, ir, ax(m/s²), ay, az, gx(rad/s), gy, gz
"""

import sys
import re
import csv
import time
import datetime
import queue
import threading
from pathlib import Path

BAUD    = 115200
PATTERN = re.compile(
    r'PPG\s+red=(\d+)\s+ir=(\d+)\s+\|\s+IMU\s+'
    r'ax=(-?[\d.]+)\s+ay=(-?[\d.]+)\s+az=(-?[\d.]+)\s+'
    r'gx=(-?[\d.]+)\s+gy=(-?[\d.]+)\s+gz=(-?[\d.]+)'
)
HEADER = ["timestamp", "red", "ir", "ax", "ay", "az", "gx", "gy", "gz"]

USE_BLE   = "--ble" in sys.argv
BLE_ADDR  = next((a.split("=", 1)[1] for a in sys.argv if a.startswith("--ble-addr=")), None)
if BLE_ADDR:
    USE_BLE = True


# ── Serial transport ──────────────────────────────────────────────────────────

def _serial_lines(port):
    try:
        import serial
        import serial.tools.list_ports
    except ImportError:
        print("ERROR: pyserial not installed.  pip install pyserial")
        sys.exit(1)

    if port is None:
        ports = list(serial.tools.list_ports.comports())
        candidates = [
            p for p in ports
            if any(x in (p.description or "").upper() or x in (p.hwid or "").upper()
                   for x in ["XIAO", "SEEED", "NRF52840", "J-LINK"])
        ]
        if len(candidates) == 1:
            port = candidates[0].device
        elif not ports:
            print("No serial ports found.")
            sys.exit(1)
        else:
            print("Available ports:")
            for i, p in enumerate(ports):
                marker = " <-- likely Xiao" if p in candidates else ""
                print(f"  [{i}] {p.device} — {p.description}{marker}")
            choice = input("Enter port number: ").strip()
            port = ports[int(choice)].device if choice.isdigit() else choice

    try:
        ser = serial.Serial(port, BAUD, timeout=1)
    except serial.SerialException as e:
        print(f"Cannot open {port}: {e}")
        sys.exit(1)

    print(f"Transport: USB serial ({port})")
    while True:
        try:
            yield ser.readline().decode("utf-8", errors="replace").strip()
        except Exception:
            continue


# ── BLE transport ─────────────────────────────────────────────────────────────

NUS_SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
NUS_TX_UUID      = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"


async def _ble_find_device(addr_hint, timeout=8.0):
    """Discover by NUS UUID. Retries forever if not found (board may be sleeping)."""
    try:
        from bleak import BleakScanner
    except ImportError:
        print("ERROR: bleak not installed.  pip install bleak")
        sys.exit(1)

    if addr_hint:
        return addr_hint

    import asyncio
    attempt = 0
    while True:
        attempt += 1
        print(f"BLE: scanning (attempt {attempt})...")
        found = await BleakScanner.discover(timeout=timeout,
                                            service_uuids=[NUS_SERVICE_UUID])
        if not found:
            all_devs = await BleakScanner.discover(timeout=4.0)
            found = [d for d in all_devs
                     if NUS_SERVICE_UUID in
                     [u.lower() for u in (d.metadata.get("uuids") or [])]]

        if not found:
            print("BLE: no sensor found — board may be sleeping, move it to wake. Retrying in 5 s...")
            await asyncio.sleep(5.0)
            continue

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


def _ble_lines(addr_hint):
    try:
        import asyncio
        from bleak import BleakClient
    except ImportError:
        print("ERROR: bleak not installed.  pip install bleak")
        sys.exit(1)

    line_queue = queue.Queue()
    buf        = [""]

    def on_notify(_, data):
        buf[0] += data.decode("utf-8", errors="replace")
        while "\n" in buf[0]:
            line, buf[0] = buf[0].split("\n", 1)
            line_queue.put(line)

    async def _run():
        import asyncio
        addr = await _ble_find_device(addr_hint)
        connected = False
        while True:
            try:
                async with BleakClient(addr, timeout=10.0) as client:
                    if not connected:
                        print(f"BLE: connected to {addr} — streaming (Ctrl+C to stop)\n")
                        line_queue.put(None)  # sentinel: connection ready
                        connected = True
                    await client.start_notify(NUS_TX_UUID, on_notify)
                    while client.is_connected:
                        await asyncio.sleep(0.2)
                    try:
                        await client.stop_notify(NUS_TX_UUID)
                    except Exception:
                        pass
            except Exception as e:
                print(f"BLE: {e}")
            print("BLE: reconnecting in 3 s...")
            await asyncio.sleep(3.0)

    def _thread():
        import asyncio
        asyncio.run(_run())

    threading.Thread(target=_thread, daemon=True).start()
    line_queue.get()  # wait for connection before yielding

    print("Transport: BLE NUS")
    while True:
        item = line_queue.get()
        if item is not None:
            yield item


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    port = None
    if not USE_BLE and len(sys.argv) > 1 and sys.argv[1] != "--ble":
        port = sys.argv[1]

    lines = _ble_lines(BLE_ADDR) if USE_BLE else _serial_lines(port)

    logs_dir = Path("logs")
    logs_dir.mkdir(exist_ok=True)
    stamp    = datetime.datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
    out_path = logs_dir / f"{stamp}.csv"

    print(f"Logging → {out_path}   (Ctrl+C to stop)")

    count = 0
    t0    = time.monotonic()

    try:
        with open(out_path, "w", newline="") as f:
            writer = csv.writer(f)
            writer.writerow(HEADER)
            for line in lines:
                m = PATTERN.search(line)
                if not m:
                    continue
                t = time.monotonic() - t0
                writer.writerow([f"{t:.4f}"] + list(m.groups()))
                count += 1
                if count % 100 == 0:
                    print(f"  {count} samples  ({time.monotonic()-t0:.0f}s)",
                          end="\r", flush=True)
    except KeyboardInterrupt:
        print(f"\nStopped — {count} samples saved to {out_path}")


if __name__ == "__main__":
    main()
