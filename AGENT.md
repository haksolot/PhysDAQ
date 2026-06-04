# AGENT.md — Xiao nRF52840 Sense Firmware Project

## Objective

Transform the current messy directory into a **clean, OS-agnostic Zephyr firmware repository** that any teammate can clone and use on Windows, macOS, or Linux with a single `make` command.

## Current State Analysis

The working directory currently contains:
- `dev/` — scattered source files (`CMakeLists.txt`, `prj.conf`, `src/main.c`)
- `zephyr/` — Zephyr OS clone at v3.6.0
- `modules/`, `.west/`, `build/` — workspace artifacts
- `zephyr-env.ps1` — hardcoded Windows environment script with stale paths
- No version lock, no cross-platform scripts, no Makefile

## Target Repository Structure

Create the following tree. **Every file listed below must be created with the exact content provided.**

```
.
├── AGENT.md              # this file
├── Makefile              # primary interface (make build, make flash, ...)
├── README.md             # human onboarding
├── west.yml              # Zephyr manifest (version lock)
├── .gitignore
├── firmware/             # application code
│   ├── CMakeLists.txt
│   ├── prj.conf
│   ├── boards/
│   │   └── xiao_ble_sense.overlay
│   └── src/
│       └── main.c
└── scripts/              # cross-platform helpers
    ├── build-wrapper.py
    ├── uf2-flash.py
    ├── term.py
    ├── setup-env.ps1
    └── setup-env.sh
```

---

## Step 1 — Clean Up & Migrate Existing Code

### 1.1 Remove stale artifacts

Delete these directories and files unconditionally:
- `build/`
- `zephyr-env.ps1` (at repo root)
- `dev/` (after migrating its contents, see 1.2)

### 1.2 Migrate source files from `dev/` to `firmware/`

Move or copy:
- `dev/CMakeLists.txt` → `firmware/CMakeLists.txt` (overwrite with target content below)
- `dev/prj.conf` → `firmware/prj.conf` (overwrite with target content below)
- `dev/src/main.c` → `firmware/src/main.c` (overwrite with target content below)

After migration, delete the `dev/` directory.

---

## Step 2 — Create All Files

### 2.1 `west.yml` (repo root)

This pins Zephyr v3.6.0 for every teammate, regardless of their host OS.

```yaml
manifest:
  remotes:
    - name: zephyrproject
      url-base: https://github.com/zephyrproject-rtos

  projects:
    - name: zephyr
      remote: zephyrproject
      revision: v3.6.0
      import: true

  self:
    path: firmware
```

### 2.2 `.gitignore` (repo root)

```gitignore
# Build artifacts
build/
*.uf2

# Zephyr workspace (auto-populated by west)
.west/
zephyr/
modules/
bootloader/
tools/

# Python
*.pyc
__pycache__/
```

### 2.3 `firmware/CMakeLists.txt`

```cmake
cmake_minimum_required(VERSION 3.20.0)
find_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})
project(xiao_sense_app)

target_sources(app PRIVATE src/main.c)
```

### 2.4 `firmware/prj.conf`

```
CONFIG_GPIO=y
CONFIG_SERIAL=y
CONFIG_UART_CONSOLE=y
```

### 2.5 `firmware/boards/xiao_ble_sense.overlay`

```dts
/*
 * XIAO BLE Sense board overlay
 * Defines the onboard red LED on P0.26
 */

/ {
    aliases {
        led0 = &led0;
    };

    leds {
        compatible = "gpio-leds";
        led0: led_0 {
            gpios = <&gpio0 26 GPIO_ACTIVE_LOW>;
            label = "Red LED";
        };
    };
};
```

### 2.6 `firmware/src/main.c`

```c
#include <zephyr/kernel.h>
#include <zephyr/drivers/gpio.h>
#include <zephyr/drivers/uart.h>
#include <zephyr/sys/printk.h>

#define LED0_NODE DT_ALIAS(led0)
static const struct gpio_dt_spec led = GPIO_DT_SPEC_GET(LED0_NODE, gpios);
const struct device *uart = DEVICE_DT_GET(DT_CHOSEN(zephyr_console));

int main(void)
{
    if (!gpio_is_ready_dt(&led)) {
        printk("LED not ready\n");
        return 0;
    }
    gpio_pin_configure_dt(&led, GPIO_OUTPUT_INACTIVE);

    if (!device_is_ready(uart)) {
        printk("UART not ready\n");
        return 0;
    }

    printk("\n=== Xiao Sense Interactive ===\n");
    printk("Commands: 1=ON  0=OFF  t=TOGGLE\n\n");

    while (1) {
        unsigned char c;
        if (uart_poll_in(uart, &c) == 0) {
            switch (c) {
            case '1':
                gpio_pin_set_dt(&led, 1);
                printk(">>> LED ON\n");
                break;
            case '0':
                gpio_pin_set_dt(&led, 0);
                printk(">>> LED OFF\n");
                break;
            case 't':
            case 'T':
                gpio_pin_toggle_dt(&led);
                printk(">>> LED TOGGLED\n");
                break;
            }
        }
        k_sleep(K_MSEC(50));
    }

    return 0;
}
```

### 2.7 `scripts/build-wrapper.py`

```python
#!/usr/bin/env python3
"""Wrapper around west build that auto-cleans stale build directories."""

import os
import sys
import shutil
import subprocess

BOARD = sys.argv[1] if len(sys.argv) > 1 else "xiao_ble_sense"
APP = sys.argv[2] if len(sys.argv) > 2 else "firmware"
BUILD = "build"


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

    cmd = ["west", "build", "-p", "-b", BOARD, APP]
    print(f"[build-wrapper] Running: {' '.join(cmd)}")
    subprocess.run(cmd, check=True)
    print(f"[build-wrapper] OK: {BUILD}/zephyr/zephyr.uf2")


if __name__ == "__main__":
    main()
```

### 2.8 `scripts/uf2-flash.py`

```python
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
        import ctypes
        bitmask = ctypes.windll.kernel32.GetLogicalDrives()
        for i in range(26):
            if bitmask & (1 << i):
                drive = f"{chr(65 + i)}:\\"
                try:
                    result = os.popen(f"cmd /c dir {drive[:2]}").read()
                    if "XIAO" in result.upper() or "SENSE" in result.upper():
                        return drive
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
```

### 2.9 `scripts/term.py`

```python
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
```

### 2.10 `scripts/setup-env.ps1` (Windows template)

```powershell
# Zephyr environment setup for Windows (nRF Connect SDK toolchain)
# Usage: . .\scripts\setup-env.ps1

$toolchainRoot = "C:\ncs\toolchains\0b393f9e1b"

if (-not (Test-Path $toolchainRoot)) {
    Write-Error "Toolchain not found at $toolchainRoot"
    Write-Host "Install: nrfutil toolchain-manager install --ncs-version v3.3.0"
    return
}

$env:PATH = "$toolchainRoot;$toolchainRoot\mingw64\bin;$toolchainRoot\bin;$toolchainRoot\opt\bin;$toolchainRoot\opt\bin\Scripts;$toolchainRoot\opt\nanopb\generator-bin;$toolchainRoot\opt\zephyr-sdk\arm-zephyr-eabi\bin;$toolchainRoot\opt\zephyr-sdk\riscv64-zephyr-elf\bin;" + $env:PATH
$env:PYTHONPATH = "$toolchainRoot\opt\bin;$toolchainRoot\opt\bin\Lib;$toolchainRoot\opt\bin\Lib\site-packages"
$env:ZEPHYR_TOOLCHAIN_VARIANT = "zephyr"
$env:ZEPHYR_SDK_INSTALL_DIR = "$toolchainRoot\opt\zephyr-sdk"
$env:ZEPHYR_BASE = "$PSScriptRoot\..\zephyr"

Write-Host "Zephyr environment loaded for Windows."
Write-Host "ZEPHYR_BASE = $env:ZEPHYR_BASE"
```

### 2.11 `scripts/setup-env.sh` (Linux / macOS)

```bash
#!/bin/bash
# Zephyr environment setup for Linux/macOS
# Usage: source scripts/setup-env.sh

# Auto-detect Zephyr SDK if installed in home
if [ -z "$ZEPHYR_SDK_INSTALL_DIR" ]; then
    for d in "$HOME"/zephyr-sdk-*; do
        if [ -d "$d" ]; then
            export ZEPHYR_SDK_INSTALL_DIR="$d"
            break
        fi
    done
fi

if [ ! -d "$ZEPHYR_SDK_INSTALL_DIR" ]; then
    echo "ERROR: Zephyr SDK not found."
    echo "Install from: https://github.com/zephyrproject-rtos/sdk-ng/releases"
    return 1
fi

export ZEPHYR_TOOLCHAIN_VARIANT=zephyr
export ZEPHYR_BASE="$(pwd)/zephyr"

# Add toolchain binaries to PATH
export PATH="${ZEPHYR_SDK_INSTALL_DIR}/arm-zephyr-eabi/bin:${PATH}"

echo "Zephyr environment loaded."
echo "ZEPHYR_SDK_INSTALL_DIR = ${ZEPHYR_SDK_INSTALL_DIR}"
echo "ZEPHYR_BASE = ${ZEPHYR_BASE}"
```

### 2.12 `Makefile` (repo root)

```makefile
# Xiao nRF52840 Sense Firmware — OS-agnostic Makefile
# Requires: west, python3, and an activated Zephyr toolchain

BOARD := xiao_ble_sense
APP   := firmware
BUILD := build
UF2   := $(BUILD)/zephyr/zephyr.uf2

.PHONY: all help setup build flash term clean env

all: build

help:
	@echo "Xiao Sense Firmware — Available targets"
	@echo ""
	@echo "  make setup   Initialize west workspace (run once after clone)"
	@echo "  make build   Compile firmware"
	@echo "  make flash   Copy UF2 to XIAO-SENSE bootloader drive"
	@echo "  make term    Open serial terminal (115200, auto-detect port)"
	@echo "  make clean   Remove build directory"
	@echo "  make env     Show environment setup hints"
	@echo ""
	@echo "Prerequisite: activate your Zephyr toolchain before building."

setup:
	@if [ -d ".west" ]; then \
		echo "Workspace already initialized."; \
	else \
		echo "Initializing west workspace..."; \
		west init -l . && west update; \
	fi

build:
	@python3 scripts/build-wrapper.py $(BOARD) $(APP)

flash: build
	@python3 scripts/uf2-flash.py $(UF2)

term:
	@python3 scripts/term.py

clean:
	@python3 -c "import shutil, os; shutil.rmtree('$(BUILD)') if os.path.isdir('$(BUILD)') else print('Nothing to clean.')"

env:
	@echo "--- Windows ---"
	@echo "  1. Install nrfutil: https://www.nordicsemi.com/Products/Development-tools/nRF-Util"
	@echo "  2. nrfutil install toolchain-manager"
	@echo "  3. nrfutil sdk-manager install v3.3.0"
	@echo "  4. Run: nrfutil toolchain-manager launch --terminal --ncs-version v3.3.0"
	@echo "  5. Inside that shell: cd <repo> && . .\scripts\setup-env.ps1"
	@echo ""
	@echo "--- Linux ---"
	@echo "  1. Install Zephyr SDK: https://docs.zephyrproject.org/latest/develop/toolchains/zephyr_sdk.html"
	@echo "  2. source scripts/setup-env.sh"
	@echo ""
	@echo "--- macOS ---"
	@echo "  1. brew install cmake ninja gperf python3 ccache dtc"
	@echo "  2. Install Zephyr SDK or use arm-none-eabi-gcc"
	@echo "  3. source scripts/setup-env.sh"
```

### 2.13 `README.md` (repo root)

```markdown
# Xiao nRF52840 Sense Firmware

Zephyr RTOS firmware for the Seeed Studio Xiao BLE Sense (nRF52840).

## Quick Start

### 1. Clone & Setup

```bash
git clone <repo-url>
cd xiao-sense-firmware
make setup        # downloads Zephyr v3.6.0 + modules (~2-3 GB)
```

### 2. Activate Zephyr Toolchain

**Windows (nRF Connect SDK):**
```powershell
nrfutil toolchain-manager launch --terminal --ncs-version v3.3.0
# Then inside the launched shell:
cd <path-to-repo>
. .\scripts\setup-env.ps1
```

**Linux / macOS:**
```bash
source scripts/setup-env.sh
```

### 3. Build

```bash
make build
```

### 4. Flash

1. Double-click the **RST** button on the Xiao to enter UF2 bootloader mode.
2. A drive named **XIAO-SENSE** appears.
3. Run:

```bash
make flash
```

### 5. Serial Terminal

```bash
make term
```

Type `1` to turn the LED on, `0` to turn it off, `t` to toggle.

## Project Structure

| Path | Description |
|------|-------------|
| `firmware/src/main.c` | Application code |
| `firmware/boards/` | DeviceTree overlays |
| `scripts/` | Cross-platform build/flash helpers |
| `west.yml` | Zephyr version lock |
```

---

## Step 3 — Re-initialize Workspace

After creating `west.yml`, the existing `.west/` directory may still point to the old manual clone. Re-initialize cleanly:

```bash
rm -rf .west/ zephyr/ modules/ bootloader/ tools/
west init -l .
west update
```

On Windows (PowerShell):
```powershell
Remove-Item -Recurse -Force .west, zephyr, modules, bootloader, tools
west init -l .
west update
```

---

## Step 4 — Verification

Run these commands in order to verify the repo is healthy:

1. `make setup` — should create `.west/`, `zephyr/`, `modules/`
2. `make build` — should produce `build/zephyr/zephyr.uf2`
3. `make clean` — should remove `build/`
4. `make build` again — should rebuild from scratch without stale-cache errors

If `make build` fails with `CMake Error: Error processing file: C:/ncs/v3.0.2/zephyr/cmake/pristine.cmake`, run `make clean` and retry. The `build-wrapper.py` is designed to auto-detect and purge this stale state.

---

## Step 5 — Commit

After all files are created and verified:

```bash
git add -A
git commit -m "chore: restructure as OS-agnostic Zephyr repo with Makefile"
```

---

## Troubleshooting Reference

| Symptom | Cause | Fix |
|---------|-------|-----|
| `west: unknown command 'build'` | Not inside a workspace | Run `make setup` |
| `CMake Error: C:/ncs/v3.0.2/...` | Stale build cache | Run `make clean` |
| `No board named 'xiao_ble_sense'` | Wrong board name | Use `xiao_ble_sense` (not `xiao_ble/nrf52840/sense` in Zephyr 3.6) |
| `nrfutil not recognized` | PATH issue | Add nrfutil folder to PATH or use absolute path |
| `pyserial not installed` | Missing Python dep | `pip install pyserial` |
| UF2 flash fails on Windows | Drive letter locked | Use `make flash` (manual copy) instead of `west flash -r uf2` |

---

## Notes for the Agent

- Do NOT commit `build/`, `.west/`, `zephyr/`, `modules/` — these are in `.gitignore`.
- The `dev/` directory must be fully deleted after migration.
- The `zephyr-env.ps1` at repo root must be deleted (replaced by `scripts/setup-env.ps1`).
- `make` on Windows requires the MinGW `make.exe` bundled with the Nordic toolchain, or GNU Make from Git Bash / MSYS2. The `scripts/setup-env.ps1` adds MinGW to PATH.
- If the user prefers `nmake` or another build system, the Python scripts in `scripts/` can still be invoked directly: `python3 scripts/build-wrapper.py`, `python3 scripts/uf2-flash.py`, etc.
