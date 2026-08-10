#!/usr/bin/env python3
"""BLE NUS receiver for PhysDAQ.

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
    """Discover by NUS UUID. Retries forever if not found (board may be sleeping)."""
    if addr_hint:
        return addr_hint

    attempt = 0
    while True:
        attempt += 1
        print(f"Scanning for sensors (NUS UUID, attempt {attempt})...", file=sys.stderr)
        found = await BleakScanner.discover(timeout=timeout,
                                            service_uuids=[NUS_SERVICE_UUID])
        if not found:
            # Fallback: unfiltered scan + manual UUID match (more reliable on Windows)
            all_devs = await BleakScanner.discover(timeout=4.0)
            found = [d for d in all_devs
                     if NUS_SERVICE_UUID in
                     [u.lower() for u in (d.metadata.get("uuids") or [])]]

        if not found:
            print("No sensor found — board may be sleeping, move it to wake. Retrying in 5 s...",
                  file=sys.stderr)
            await asyncio.sleep(5.0)
            continue

        if len(found) == 1:
            d = found[0]
            print(f"Found: {d.name}  {d.address}", file=sys.stderr)
            return d.address

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

    first = True
    while True:
        try:
            print(f"{'Connecting' if first else 'Reconnecting'} to {address}...", file=sys.stderr)
            async with BleakClient(address, timeout=10.0) as client:
                if first:
                    print("Connected. Streaming (Ctrl+C to stop)...\n", file=sys.stderr)
                    first = False
                await client.start_notify(NUS_TX_UUID, on_notify)
                while client.is_connected:
                    await asyncio.sleep(0.5)
                try:
                    await client.stop_notify(NUS_TX_UUID)
                except Exception:
                    pass
        except KeyboardInterrupt:
            print("\nStopped.", file=sys.stderr)
            return
        except Exception as e:
            print(f"BLE: {e}", file=sys.stderr)
        print("BLE: disconnected — retrying in 3 s...", file=sys.stderr)
        await asyncio.sleep(3.0)


if __name__ == "__main__":
    addr = next((a.split("=", 1)[1] for a in sys.argv[1:]
                 if a.startswith("--ble-addr=")), None)
    asyncio.run(run(addr))
