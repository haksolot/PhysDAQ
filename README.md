# PhysDAQ

**Phys**iological **D**ata **AcQ**uisition — a wearable research instrument for
multi-point photoplethysmography (PPG) and inertial motion capture, developed at
**BCIT** (Vancouver, BC).

A PhysDAQ node is a Seeed Studio XIAO nRF52840 Sense paired with a MAX30102
pulse-oximetry front end. It streams raw red/IR PPG plus 6-axis IMU data at
100 Hz over USB or Bluetooth LE. A desktop application acquires from **several
nodes at once**, places them on a body map, plots them live, and records
synchronised CSV sessions for offline analysis.

> **Scope note.** Nodes do not talk to each other. Each one is an independent BLE
> peripheral / USB CDC device; the multi-node aggregation happens entirely on the
> host, one acquisition process per node. There is no mesh.

---

## System architecture

```
 ┌─────────────────────────────────────────────────────────────────┐
 │  SENSOR NODE                    Zephyr RTOS 3.6 · nRF52840      │
 │  MAX30102 (PPG, I2C1)  ──┐                                      │
 │  LSM6DS3TR-C (IMU, I2C0) ─┴─►  100 Hz acquisition loop           │
 └──────────────┬──────────────────────────┬───────────────────────┘
                │ USB CDC ACM @115200      │ BLE — Nordic UART Service
                │ (100 Hz)                 │ (rate-limited to ~25 Hz)
                └────────────┬─────────────┘
                             ▼   ASCII lines: "PPG red=… ir=… | IMU ax=… …"
 ┌─────────────────────────────────────────────────────────────────┐
 │  BRIDGE (scripts/bridge.py)                       Python + DSP  │
 │  parse · AHRS fusion (+ZUPT) · FFT heart rate · PPG band-pass    │
 └─────────────────────────────┬───────────────────────────────────┘
                               ▼   JSON lines on stdout
 ┌─────────────────────────────────────────────────────────────────┐
 │  DESKTOP APP (app/)                    Electron · React · Vite   │
 │  body map · live charts · 3D orientation · session recording     │
 └─────────────────────────────┬───────────────────────────────────┘
                               ▼   CSV per node, per session
 ┌─────────────────────────────────────────────────────────────────┐
 │  OFFLINE ANALYSIS (analysis/)         motion cancellation ·      │
 │  beat detection · HRV (RMSSD) · SpO2 · interactive explorer      │
 └─────────────────────────────────────────────────────────────────┘
```

The bridge is shipped two ways: run straight from source during development, and
frozen with PyInstaller into a standalone binary for installed copies of the app,
so end users never install Python.

## Hardware at a glance

| | |
|---|---|
| MCU board | Seeed Studio XIAO nRF52840 Sense (with onboard LSM6DS3TR-C IMU) |
| PPG front end | Maxim MAXREFDES117# (MAX30102), I2C @ 0x57 |
| Sample rate | 100 Hz, 18-bit ADC, red + IR |
| Power | Single-cell LiPo, ~0.4 µA in deep sleep, wake on motion |
| Transports | USB CDC ACM (virtual COM @115200) and BLE Nordic UART Service |

Full pin map, bus assignments and register configuration: **[docs/hardware.md](docs/hardware.md)**.

---

## Quick start

### I want to use the device

Install the desktop app, plug in or pair a node, and record. See the
**[User Guide](docs/user-guide.md)**.

### I want to build the firmware

```bash
make setup      # one-time: fetch Zephyr v3.6.0 + modules (~2–3 GB)
make build      # compile  → build/zephyr/zephyr.uf2
make flash      # double-tap RST, then copy the UF2 to the XIAO-SENSE drive
make term       # serial console @115200, port auto-detected
```

A Zephyr toolchain must be active in your shell first — see
**[Development Guide](docs/development.md)**.

### I want to run the desktop app from source

```bash
python -m venv .venv && .venv/Scripts/activate   # Windows; use bin/activate elsewhere
pip install -r requirements.txt
cd app && npm install && npm run dev
```

### I want to look at recorded data

```bash
make log                              # record a CSV from a connected node
make process FILE=logs/<file>.csv     # filter, detect beats, compute HRV/SpO2
make explore FILE=logs/<file>.csv     # interactive viewer
```

Run `make help` for the full target list.

---

## Documentation

| Document | What's in it |
|---|---|
| [User Guide](docs/user-guide.md) | Flash a node, connect it, record and review sessions |
| [Development Guide](docs/development.md) | Toolchain setup, build system, dev loop, packaging |
| [Hardware Reference](docs/hardware.md) | BOM, pin map, I2C buses, sensor configuration, power |
| [Firmware Reference](docs/firmware.md) | Module-by-module walkthrough, Kconfig, console output |
| [Data Contracts](docs/protocol.md) | Wire format, bridge JSON, session CSV schema |
| [Desktop App](docs/desktop-app.md) | Process architecture, IPC surface, UI internals |
| [Analysis Pipeline](docs/analysis.md) | Offline DSP, parameter rationale, known caveats |
| [Roadmap](docs/roadmap.md) | Known gaps and planned work |
| [Enclosure](hardware/enclosure/README.md) | 3D-printable case: design constraints and print settings |

## Repository layout

```
firmware/           Zephyr application (C)
  src/              acquisition loop, drivers, power management, BLE
  boards/           DeviceTree overlay for xiao_ble_sense
  dts/bindings/     custom MAX30102 binding
app/                Electron desktop application
  src/main/         main process — spawns and supervises bridge instances
  src/renderer/     React UI — body map, live charts, session browser
scripts/            bridge, build/flash helpers, live plotter
analysis/           CSV logger, offline pipeline, interactive explorer
hardware/enclosure/ 3D-printable case (models + printing guide)
docs/               documentation
west.yml            Zephyr v3.6.0 version lock
requirements.txt    Python dependencies
```

## Status

Active research development. The firmware, bridge and desktop app are working
end-to-end; see [docs/roadmap.md](docs/roadmap.md) for what is deliberately not
built yet.

> Research instrument — **not a medical device**. Nothing here is validated for
> diagnosis, and no claim of clinical accuracy is made.
