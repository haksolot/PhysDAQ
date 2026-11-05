---
hide:
  - navigation
  - toc
---

<div class="hero" markdown>

<img class="logo" src="assets/physdaq.svg#only-light" alt="PhysDAQ logo">
<img class="logo" src="assets/physdaq-dark.svg#only-dark" alt="PhysDAQ logo">

# PhysDAQ

<p class="tagline"><strong>Wearable multi-point pulse and motion recording, for research.</strong><br>
Place the sensors, press record, get clean CSV. No wiring and no code.</p>

[Get started](user-guide.md){ .md-button .md-button--primary }
[Desktop app](https://github.com/haksolot/PhysDAQ/releases){ .md-button }
[Android app (APK)](https://github.com/haksolot/PhysDAQ/releases){ .md-button }

</div>

!!! warning "Research instrument, not a medical device"
    Nothing here is validated for diagnosis, and no claim of clinical accuracy is made.

<div class="grid2" markdown>

<div markdown>

### Sensors

A battery-powered node that clips to the body: a 6-axis motion sensor and a pulse
oximeter (red + infrared light) in a 3D-printed case. A hub version carries two pulse
sensors and a microSD card.

![PhysDAQ sensor, exploded view](assets/images/hardware/device-exploded.png)

</div>

<div markdown>

### Desktop app

Windows, Mac and Linux. Finds sensors over USB or Bluetooth, pins each to a body
position, plots live signals and a 3D orientation view, and records one CSV per
sensor.

![PhysDAQ desktop dashboard](assets/images/software/desktop-dashboard.png)

</div>

<div markdown>

### Android app

Record away from a desk. Links each sensor to a body position, shows live heart
rate, tilt and signals, records over Bluetooth, and keeps a library of sessions with
per-sensor statistics and a side-by-side heart-rate comparison.

[About the Android app](android-app.md) · download the APK from
[Releases](https://github.com/haksolot/PhysDAQ/releases)

</div>

<div markdown>

### Offline analysis

A Python pipeline for heart rate, heart-rate variability (RMSSD), blood-oxygen
estimation and motion cancellation, plus an interactive explorer.

[Analysis pipeline](analysis.md)

</div>

</div>

## What you get

- **Pulse and motion together**, 100 times per second over USB (about 25 over
  Bluetooth): raw red and infrared light signals plus acceleration and rotation.
- **Several sensors at once**, each pinned to a spot on a body map, all on one
  session clock.
- **Live plots while you record**, so a bad placement shows immediately.
- **Plain CSV files**, one per sensor, openable in Excel, R, MATLAB or pandas. Every
  column is documented in [Data Contracts](protocol.md).

## What you need

| Component | Specification |
|---|---|
| Sensor board | Seeed Studio XIAO nRF52840 Sense (motion sensor built in) |
| Pulse sensor | Maxim MAXREFDES117# (MAX30102), over I2C |
| Recording rate | 100 Hz over USB, about 25 Hz over Bluetooth |
| Connection | USB cable, or Bluetooth Low Energy |
| Power | Single-cell LiPo battery, about 0.4 µA asleep, wakes on motion |

Parts, pin map and assembly: [Hardware Reference](hardware.md).

## Where to go next

| If you want to | Read |
|---|---|
| Set up a sensor and record a session | [User Guide](user-guide.md) |
| Record from a phone | [User Guide: phone app](user-guide.md#recording-with-the-phone-app) |
| Understand the data format | [Data Contracts](protocol.md) |
| Process recordings offline | [Analysis Pipeline](analysis.md) |
| Look up a term | [Glossary](glossary.md) |
| Work on the code | [Development Guide](development.md) |
| See what is planned | [Roadmap](roadmap.md) |

Built at **BCIT** (Vancouver, BC). Source on
[GitHub](https://github.com/haksolot/PhysDAQ), MIT licence.
