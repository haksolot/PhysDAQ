# Xiao nRF52840 Sense Firmware — OS-agnostic Makefile
# Requires: west, python3, and an activated Zephyr toolchain

BOARD := xiao_ble_sense
APP   := firmware
BUILD := build
UF2   := $(BUILD)/zephyr/zephyr.uf2

.PHONY: all help setup build flash term plot clean env

all: build

help:
	@echo "Xiao Sense Firmware — Available targets"
	@echo ""
	@echo "  make setup   Initialize west workspace (run once after clone)"
	@echo "  make build   Compile firmware"
	@echo "  make flash   Copy UF2 to XIAO-SENSE bootloader drive"
	@echo "  make term    Open serial terminal (115200, auto-detect port)"
	@echo "  make plot    Live gyroscope plot (requires: pip install pyqtgraph PyQt6)"
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
	@python scripts/build-wrapper.py $(BOARD) $(APP)

flash: build
	@python scripts/uf2-flash.py $(UF2)

term:
	@python scripts/term.py

plot:
	@python scripts/plotter.py

clean:
	@python -c "import shutil, os; shutil.rmtree('$(BUILD)') if os.path.isdir('$(BUILD)') else print('Nothing to clean.')"

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
