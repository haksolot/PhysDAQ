# PhysDAQ — OS-agnostic Makefile (firmware, tooling, desktop-app sidecar)
# Requires: west, python3, and an activated Zephyr toolchain

BOARD := xiao_ble_sense
APP   := firmware
BUILD := build
UF2   := $(BUILD)/zephyr/zephyr.uf2

# Hub firmware (dual PPG + microSD). Separate app and build directory so the
# two firmwares coexist instead of pristine-rebuilding each other on every
# switch. See docs/firmware-hub.md.
HUB_APP   := firmware-hub
HUB_BUILD := build-hub
HUB_UF2   := $(HUB_BUILD)/zephyr/zephyr.uf2

# Auto-detect virtualenv using Make's wildcard (no shell — works on Windows/Linux/macOS).
# Checks .venv/Scripts/python.exe (Windows), then .venv/bin/python (Unix), then system python.
PYTHON := $(or $(wildcard .venv/Scripts/python.exe),$(wildcard .venv/bin/python),python)

.PHONY: all help setup build rebuild flash term plot log process explore ble-log ble-plot sidecar icons clean env hub hub-rebuild hub-flash hub-clean

all: build

help:
	@echo "PhysDAQ — Available targets"
	@echo ""
	@echo "  make setup    Initialize west workspace (run once after clone)"
	@echo "  make build    Compile firmware (incremental)"
	@echo "  make rebuild  Pristine build — forces CMake reconfiguration (use after overlay/DTS changes)"
	@echo "  make flash    Copy UF2 to XIAO-SENSE bootloader drive"
	@echo ""
	@echo "  make hub          Compile the hub firmware (dual PPG + microSD)"
	@echo "  make hub-rebuild  Pristine build of the hub firmware"
	@echo "  make hub-flash    Copy the hub UF2 to the bootloader drive"
	@echo "  make hub-clean    Remove the hub build directory"
	@echo "  make term    Open serial terminal (115200, auto-detect port)"
	@echo "  make plot    Live gyro plot + 3D orientation (requires: pip install pyqtgraph PyQt6 PyOpenGL imufusion pyserial)"
	@echo "  make log     Record PPG+IMU to logs/YYYY-MM-DD_HH-MM-SS.csv"
	@echo "  make process FILE=logs/....csv   Filter + compute BPM/SpO2/HRV/orientation"
	@echo "  make explore FILE=logs/....csv   Interactive viewer (zoom/pan, all signals)"
	@echo "  make ble-log   Record PPG+IMU via BLE (wireless)"
	@echo "  make ble-plot  Live plot via BLE (wireless)"
	@echo "  make sidecar   Freeze scripts/bridge.py into app/sidecar/ for the desktop app"
	@echo "  make icons     Regenerate the desktop app icons from the project mark (requires Pillow)"
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
	@$(PYTHON) scripts/build-wrapper.py $(BOARD) $(APP)

rebuild:
	@$(PYTHON) scripts/build-wrapper.py $(BOARD) $(APP) --pristine

flash:
	@$(PYTHON) scripts/uf2-flash.py $(UF2)

hub:
	@$(PYTHON) scripts/build-wrapper.py $(BOARD) $(HUB_APP) --build-dir=$(HUB_BUILD)

hub-rebuild:
	@$(PYTHON) scripts/build-wrapper.py $(BOARD) $(HUB_APP) --build-dir=$(HUB_BUILD) --pristine

hub-flash:
	@$(PYTHON) scripts/uf2-flash.py $(HUB_UF2)

hub-clean:
	@$(PYTHON) -c "import shutil, os; shutil.rmtree('$(HUB_BUILD)') if os.path.isdir('$(HUB_BUILD)') else print('Nothing to clean.')"

# The Zephyr toolchain puts its own stdlib on PYTHONPATH, which shadows the
# venv and breaks numpy. It has to be cleared before Python starts, so an
# in-script fix is impossible. This used to be an inline `PYTHONPATH= cmd`
# prefix, which is sh syntax — on Windows make spawns cmd.exe and the recipe
# died with "'PYTHONPATH' n'est pas reconnu". A target-specific export puts it
# in the recipe's environment directly, so no shell has to parse it.
# Deliberately not global: `build`/`hub` invoke west, which NEEDS the
# toolchain's PYTHONPATH.
term plot log ble-log ble-plot process explore sidecar icons: export PYTHONPATH :=

term:
	@$(PYTHON) scripts/term.py

plot:
	@$(PYTHON) scripts/plotter.py

log:
	@$(PYTHON) analysis/logger.py

ble-log:
	@$(PYTHON) analysis/logger.py --ble $(if $(ADDR),--ble-addr=$(ADDR),)

ble-plot:
	@$(PYTHON) scripts/plotter.py --ble $(if $(ADDR),--ble-addr=$(ADDR),)

process:
ifndef FILE
	@echo "Usage: make process FILE=logs/<filename>.csv"
else
	@$(PYTHON) analysis/pipeline.py $(FILE)
endif

explore:
ifndef FILE
	@echo "Usage: make explore FILE=logs/<filename>.csv"
else
	@$(PYTHON) analysis/explore.py $(FILE)
endif

sidecar:
	@$(PYTHON) scripts/build-sidecar.py $(if $(CLEAN),--clean,)

icons:
	@$(PYTHON) scripts/make-icons.py

clean:
	@$(PYTHON) -c "import shutil, os; shutil.rmtree('$(BUILD)') if os.path.isdir('$(BUILD)') else print('Nothing to clean.')"

env:
	@echo "--- Windows ---"
	@echo "  1. Install nrfutil: https://www.nordicsemi.com/Products/Development-tools/nRF-Util"
	@echo "  2. nrfutil install toolchain-manager"
	@echo "  3. nrfutil toolchain-manager install --ncs-version v3.3.0"
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
