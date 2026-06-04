# MAID Firmware

Zephyr RTOS firmware for the **MAID** project — a mesh network of PPG sensors for multi-point physiological data acquisition across the body.

Target hardware: Seeed Studio **Xiao BLE Sense** (nRF52840).

## Quick start

See [SETUP.md](SETUP.md) for environment setup (required before first build).

```
make build    # compile → build/zephyr/zephyr.uf2
make flash    # flash via UF2 bootloader
make term     # open serial terminal at 115200
make clean    # remove build/
```

## Repository structure

```
firmware/src/main.c                  application code
firmware/boards/xiao_ble_sense.overlay  board-specific pin config
scripts/                             build / flash / terminal helpers
west.yml                             Zephyr v3.6.0 version lock
```
