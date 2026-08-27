#!/usr/bin/env python3
"""Wrapper around west build that auto-cleans stale build directories."""

import os
import sys
import shutil
import subprocess

BOARD    = sys.argv[1] if len(sys.argv) > 1 else "xiao_ble_sense"
APP      = sys.argv[2] if len(sys.argv) > 2 else "firmware"
PRISTINE = "always" if "--pristine" in sys.argv else "auto"

# Optional --build-dir=DIR. The hub firmware builds into its own directory so
# the two apps can coexist without pristine-rebuilding each other every time
# you switch. Defaults to "build" so existing invocations are unchanged.
BUILD    = next((a.split("=", 1)[1] for a in sys.argv
                 if a.startswith("--build-dir=")), "build")


def is_stale_build():
    cache = os.path.join(BUILD, "CMakeCache.txt")
    if not os.path.exists(cache):
        return False

    with open(cache, "r", encoding="utf-8", errors="ignore") as f:
        content = f.read()

    # If the build references an external Nordic SDK path (e.g. C:/ncs/v3.0.2)
    # while our current workspace is elsewhere, it's stale.
    content_norm = content.replace("\\", "/")
    cwd_norm = os.getcwd().replace("\\", "/")

    # Detect old ncs paths that don't belong to current workspace
    if "C:/ncs/" in content_norm and "C:/ncs/" not in cwd_norm:
        return True
    if "/ncs/" in content_norm and "/ncs/" not in cwd_norm:
        return True

    # Detect ZEPHYR_BASE pointing outside current tree
    for line in content_norm.splitlines():
        if "ZEPHYR_BASE" in line or "zephyr/cmake" in line or "ZephyrConfig.cmake" in line:
            if cwd_norm not in line:
                return True

    return False


def main():
    if os.path.isdir(BUILD) and is_stale_build():
        print("[build-wrapper] Stale build detected (references external workspace). Cleaning...")
        shutil.rmtree(BUILD)

    if not shutil.which("west"):
        print("ERROR: 'west' not found in PATH.")
        print("  → Launch the Zephyr toolchain shell first:")
        print("    nrfutil toolchain-manager launch --terminal --ncs-version v3.3.0")
        print("    . .\\scripts\\setup-env.ps1   (inside that shell)")
        sys.exit(1)

    cmd = ["west", "build", "--pristine", PRISTINE, "-b", BOARD, APP,
           "-d", BUILD]
    print(f"[build-wrapper] Running: {' '.join(cmd)}")
    subprocess.run(cmd, check=True)
    print(f"[build-wrapper] OK: {BUILD}/zephyr/zephyr.uf2")


if __name__ == "__main__":
    main()
