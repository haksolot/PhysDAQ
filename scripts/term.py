#!/usr/bin/env python3
"""Auto-detect Xiao serial port and launch miniterm at 115200."""

import sys

try:
    import serial
    import serial.tools.list_ports
    import serial.tools.miniterm
except ImportError:
    print("ERROR: pyserial not installed.")
    print("Install: pip install pyserial")
    sys.exit(1)


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
        print("No serial ports found. Is the Xiao connected via USB?")
        return None

    print("Available serial ports:")
    for i, p in enumerate(ports):
        marker = " <-- likely Xiao" if p in candidates else ""
        print(f"  [{i}] {p.device} — {p.description}{marker}")

    choice = input("Enter port number or full device path: ").strip()
    if choice.isdigit():
        idx = int(choice)
        if 0 <= idx < len(ports):
            return ports[idx].device
    return choice if choice else None


def main():
    port = find_xiao_port()
    if not port:
        sys.exit(1)

    print(f"\nOpening {port} @ 115200 ...")
    print("Press Ctrl+] then q to quit\n")

    sys.argv = ["miniterm", port, "115200"]
    serial.tools.miniterm.main()


if __name__ == "__main__":
    main()
