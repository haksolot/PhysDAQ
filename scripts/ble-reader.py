#!/usr/bin/env python3
"""BLE NUS receiver for MAID.

Discovers devices by NUS service UUID (not by name — name is project-specific
and will change).  When multiple sensors are in range, shows a list and lets
you pick, or use --ble-addr to target a specific device directly.

Usage:
    python scripts/ble-reader.py                        # auto / interactive
    python scripts/ble-reader.py --ble-addr=DA:10:C0:EF:FD:F9

Output: raw data lines on stdout (same format as USB serial).

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


async def find_device(addr_hint: str | None = None, timeout: float = 8.0) -> str:
    """Return the BLE address to connect to.

    If addr_hint is given, use it directly.
    If one device is found, use it automatically.
    If several are found, show a numbered list and ask the user to pick.
    """
    if addr_hint:
        return addr_hint

    print("Scanning for MAID sensors (NUS UUID)...", file=sys.stderr)
    found = await BleakScanner.discover(timeout=timeout,
                                        service_uuids=[NUS_SERVICE_UUID])
    if not found:
        print("ERROR: no sensor found. Is the board on and not sleeping?",
              file=sys.stderr)
        sys.exit(1)

    if len(found) == 1:
        d = found[0]
        print(f"Found: {d.name}  {d.address}", file=sys.stderr)
        return d.address

    # Multiple sensors — let the user choose which patient's data to stream
    print(f"\n{len(found)} sensors found:", file=sys.stderr)
    for i, d in enumerate(found):
        print(f"  [{i}] {d.address}  {d.name}", file=sys.stderr)
    print("Tip: use --ble-addr=<address> to skip this prompt.", file=sys.stderr)
    choice = input("Select sensor [0]: ").strip()
    idx = int(choice) if choice.isdigit() and int(choice) < len(found) else 0
    return found[idx].address


async def run(addr_hint: str | None) -> None:
    address = await find_device(addr_hint)
    buf = ""

    def on_notify(char: BleakGATTCharacteristic, data: bytearray) -> None:
        nonlocal buf
        buf += data.decode("utf-8", errors="replace")
        while "\n" in buf:
            line, buf = buf.split("\n", 1)
            print(line, flush=True)

    print(f"Connecting to {address}...", file=sys.stderr)
    async with BleakClient(address, timeout=10.0) as client:
        print("Connected. Streaming (Ctrl+C to stop)...\n", file=sys.stderr)
        await client.start_notify(NUS_TX_UUID, on_notify)
        try:
            while True:
                await asyncio.sleep(0.5)
        except KeyboardInterrupt:
            pass
        await client.stop_notify(NUS_TX_UUID)
    print("\nDisconnected.", file=sys.stderr)


if __name__ == "__main__":
    addr = next((a.split("=", 1)[1] for a in sys.argv[1:]
                 if a.startswith("--ble-addr=")), None)
    asyncio.run(run(addr))
