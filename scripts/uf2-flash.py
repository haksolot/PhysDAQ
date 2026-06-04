#!/usr/bin/env python3
"""Cross-platform UF2 flasher for XIAO-SENSE mass-storage bootloader."""

import os
import sys
import shutil
import platform

UF2 = sys.argv[1] if len(sys.argv) > 1 else "build/zephyr/zephyr.uf2"


def find_uf2_drive():
    system = platform.system()

    if system == "Windows":
        import subprocess
        try:
            out = subprocess.check_output(
                ["powershell", "-NoProfile", "-Command",
                 "Get-Volume | Where-Object {$_.FileSystemLabel -match 'XIAO|SENSE'} | Select-Object -ExpandProperty DriveLetter"],
                text=True, timeout=10
            )
            letter = out.strip()
            if letter:
                return letter + ":\\"
        except Exception:
            pass
        return None

    elif system == "Darwin":
        vol = "/Volumes/XIAO-SENSE"
        if os.path.isdir(vol):
            return vol
        for d in os.listdir("/Volumes"):
            if "XIAO" in d.upper():
                return os.path.join("/Volumes", d)
        return None

    else:  # Linux
        user = os.environ.get("USER", "")
        candidates = [
            f"/media/{user}/XIAO-SENSE",
            f"/run/media/{user}/XIAO-SENSE",
            "/media/XIAO-SENSE",
        ]
        for p in candidates:
            if os.path.isdir(p):
                return p
        for root in [f"/media/{user}", "/run/media", "/media"]:
            if os.path.isdir(root):
                for d in os.listdir(root):
                    if "XIAO" in d.upper():
                        return os.path.join(root, d)
        return None


def main():
    if not os.path.exists(UF2):
        print(f"ERROR: {UF2} not found. Run 'make build' first.")
        sys.exit(1)

    print("=" * 50)
    print("UF2 FLASH UTILITY")
    print("=" * 50)
    print("1. Double-click the RST button on your Xiao BLE Sense")
    print("2. A drive named XIAO-SENSE should appear")
    print("3. Press ENTER to flash (or Ctrl+C to cancel)")
    try:
        input()
    except KeyboardInterrupt:
        print("\nAborted.")
        sys.exit(0)

    dest = find_uf2_drive()
    if dest:
        dest_file = os.path.join(dest, "zephyr.uf2")
        print(f"Copying to {dest} ...")
        shutil.copy2(UF2, dest_file)
        print("Done! The board will reboot automatically.")
    else:
        print("ERROR: XIAO-SENSE drive not found.")
        print(f"Manually copy this file: {os.path.abspath(UF2)}")
        sys.exit(1)


if __name__ == "__main__":
    main()
