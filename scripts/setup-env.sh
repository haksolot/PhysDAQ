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
