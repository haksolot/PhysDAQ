<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="assets/physdaq-dark.svg">
    <img src="assets/physdaq.svg" alt="PhysDAQ" width="88" height="88">
  </picture>
</p>

<h1 align="center">PhysDAQ</h1>

<p align="center">
  <strong>Wearable multi-point pulse and motion recording, for research.</strong><br>
  Place the sensors, press record, get clean CSV. No wiring and no code.
</p>

<p align="center">
  <a href="LICENSE"><img alt="Licence" src="https://img.shields.io/badge/licence-MIT-blue"></a>
  <img alt="Zephyr" src="https://img.shields.io/badge/Zephyr-3.6-informational">
  <img alt="MCU" src="https://img.shields.io/badge/MCU-nRF52840-informational">
</p>

<p align="center">
  <img src="assets/images/hardware/device-exploded.png" width="420" alt="PhysDAQ sensor — exploded view"/>
</p>
<p align="center"><sub><i>PhysDAQ sensor node — exploded view showing the XIAO nRF52840, MAX30102 pulse oximeter, LiPo battery and 3D-printed enclosure.</i></sub></p>

---

## Quick start

1. **Install the desktop app.** Download it from the
   [Releases page](https://github.com/haksolot/PhysDAQ/releases) — `.exe` for
   Windows, `.dmg` for Mac, `.AppImage` or `.deb` for Linux. The downloads are
   unsigned, so your system will warn you once; the
   [User Guide](docs/user-guide.md#installing-the-app) says what to click.
2. **Connect a sensor.** Plug it into a USB port, or pair it over Bluetooth from
   the app. The app finds it either way — nothing to configure.
3. **Name your session and press Record.** Every connected sensor is recorded at
   once, and you get one spreadsheet-ready CSV file per sensor in
   `Documents/PhysDAQ_Sessions/`.

Step by step, including where to place a sensor on the body and how to read the
live view: **[User Guide](docs/user-guide.md)**.

---

## Overview

<table>
<tr>
<td width="50%" valign="top">

### Hardware

A compact, battery-powered sensor node that clips to the body. Each unit combines a 6-axis IMU and a clinical-grade pulse oximeter in a 3D-printed enclosure.

<p align="center">
  <img src="assets/images/hardware/device-assembled-side.png" width="340" alt="Assembled PhysDAQ sensor"/>
</p>

</td>
<td width="50%" valign="top">

### Desktop App

A cross-platform acquisition interface that discovers sensors over USB or BLE, displays live signals, and exports timestamped CSVs.

<p align="center">
  <img src="assets/images/software/desktop-dashboard.png" width="420" alt="PhysDAQ desktop dashboard"/>
</p>

</td>
</tr>
</table>

---

## What you get

- **Pulse and motion together**, 100 times per second: the raw red and infrared
  light signals a pulse oximeter measures, plus 6-axis movement (acceleration
  and rotation).
- **Several sensors at once**, each pinned to a spot on an on-screen body map,
  all sharing one session clock.
- **Live plots and a 3D orientation view** while you record, so you can see a bad
  placement immediately instead of after the session.
- **Plain CSV files** — one per sensor, openable in Excel, R, MATLAB or pandas.
  Columns are documented, nothing is proprietary.
- **An offline analysis pipeline** for heart rate, heart-rate variability (RMSSD),
  blood-oxygen estimation and motion cancellation. See
  [Analysis Pipeline](docs/analysis.md) — note the CSV format caveat there.

---

## What you need

<p align="center">
  <img src="assets/images/hardware/device-assembled-top.png" width="380" alt="PhysDAQ sensor — top view"/>
</p>

| Component      | Specification                                             |
| -------------- | --------------------------------------------------------- |
| Sensor board   | Seeed Studio XIAO nRF52840 Sense (motion sensor built in) |
| Pulse sensor   | Maxim MAXREFDES117# (MAX30102), connected over I2C        |
| Recording rate | 100 Hz, red + infrared, 18-bit                            |
| Connection     | USB cable, or Bluetooth Low Energy                        |
| Power          | Single-cell LiPo battery                                  |
| Sleep current  | ~0.4 µA between sessions, wake-on-motion                  |

Full parts list, pin map and register configuration: **[Hardware Reference](docs/hardware.md)**.  
Enclosure CAD (STEP) and printing instructions: **[Enclosure](hardware/enclosure/README.md)**.

---

## Documentation

| If you want to                       | Read                                      |
| ------------------------------------ | ----------------------------------------- |
| Set up a sensor and record a session | [User Guide](docs/user-guide.md)          |
| Build a sensor from parts            | [Hardware Reference](docs/hardware.md)    |
| Print the case                       | [Enclosure](hardware/enclosure/README.md) |
| Understand the recorded data format  | [Data Contracts](docs/protocol.md)        |
| Process recordings offline           | [Analysis Pipeline](docs/analysis.md)     |
| Set up a development machine         | [Development Guide](docs/development.md)  |
| Modify the firmware                  | [Firmware Reference](docs/firmware.md)    |
| Modify the desktop app               | [Desktop App](docs/desktop-app.md)        |
| See what is planned                  | [Roadmap](docs/roadmap.md)                |

---

## Status

Active research development at **BCIT** (Vancouver, BC). The firmware, the
acquisition bridge and the desktop app work end to end; see the
[Roadmap](docs/roadmap.md) for what is deliberately not built yet.

> **Research instrument — not a medical device.** Nothing here is validated for
> diagnosis, and no claim of clinical accuracy is made.

---

## Working on PhysDAQ

```bash
make setup           # one-time: fetch Zephyr v3.6.0 and modules (~2–3 GB)
make build && make flash
cd app && npm install && npm run dev
make help            # every other target
```

Toolchain setup, the Python environment, the build system and packaging:
**[Development Guide](docs/development.md)**.

---

## Licence

[MIT](LICENSE).
