#!/usr/bin/env python3
"""Low-level BLE NUS receiver for MAID.

Discovers the MAID device by NUS service UUID, connects, and streams
received lines to stdout — same format as the USB serial output.

Used directly by make ble-log and make ble-plot, or standalone:
    python scripts/ble-reader.py
    python scripts/ble-reader.py | python analysis/logger.py --stdin

Dependencies: pip install bleak
"""

import asyncio
import sys

try:
    from bleak import BleakScanner, BleakClient
    from bleak.backends.characteristic import BleakGATTCharacteristic
except ImportError:
    print("ERROR: bleak not installed.  pip install bleak")
    sys.exit(1)

NUS_SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
NUS_TX_UUID      = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"  # device → PC (notify)
NUS_RX_UUID      = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"  # PC → device (write)


async def find_maid(timeout: float = 8.0) -> str:
    print("Scanning for MAID (NUS UUID)...", file=sys.stderr)
    found = await BleakScanner.discover(timeout=timeout,
                                        service_uuids=[NUS_SERVICE_UUID])
    if not found:
        print("ERROR: no MAID device found. "
              "Is the board powered on and not sleeping?", file=sys.stderr)
        sys.exit(1)

    device = found[0]
    if len(found) > 1:
        print(f"Warning: {len(found)} MAID devices found — using first",
              file=sys.stderr)
    print(f"Found: {device.name}  ({device.address})", file=sys.stderr)
    return device.address


async def run() -> None:
    address = await find_maid()

    # Buffer for incomplete lines split across BLE packets
    buf = ""

    def on_notify(char: BleakGATTCharacteristic, data: bytearray) -> None:
        nonlocal buf
        buf += data.decode("utf-8", errors="replace")
        while "\n" in buf:
            line, buf = buf.split("\n", 1)
            print(line, flush=True)

    print(f"Connecting to {address}...", file=sys.stderr)
    async with BleakClient(address, timeout=10.0) as client:
        print("Connected. Streaming data (Ctrl+C to stop)...\n",
              file=sys.stderr)
        await client.start_notify(NUS_TX_UUID, on_notify)
        try:
            while True:
                await asyncio.sleep(0.5)
        except KeyboardInterrupt:
            pass
        await client.stop_notify(NUS_TX_UUID)
    print("\nDisconnected.", file=sys.stderr)


if __name__ == "__main__":
    asyncio.run(run())
