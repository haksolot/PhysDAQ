# Hardware Reference

Everything about the physical PhysDAQ node: what it is made of, how it is wired,
how each sensor is configured, and how it manages power.

---

## Bill of materials

| Component | Part | Role |
|-----------|------|------|
| MCU board | Seeed Studio XIAO nRF52840 Sense | Main controller, BLE radio, onboard IMU |
| PPG module | Maxim MAXREFDES117# | MAX30102 pulse-oximetry front end |
| Battery | Single-cell LiPo (3.7 V nominal) | Untethered operation |

The MAXREFDES117# is wired to the XIAO connector with four conductors
(3V3, GND, SDA, SCL) plus one interrupt line.

---

## XIAO nRF52840 Sense — connector pin map

Verified against `seeed_xiao_connector.dtsi` and `xiao_ble-pinctrl.dtsi` in the
vendored Zephyr tree.

| Connector | nRF52840 pin | Alternate function | Connected to |
|-----------|--------------|--------------------|--------------|
| D0 | P0.02 | GPIO | *unused* |
| D1 | P0.03 | GPIO | MAX30102 INT |
| D2 | P0.28 | GPIO | — |
| D3 | P0.29 | GPIO / ADC | — |
| D4 | **P0.04** | I2C1 SDA | MAX30102 SDA |
| D5 | **P0.05** | I2C1 SCL | MAX30102 SCL |
| D6 | P1.11 | UART TX | — |
| D7 | P1.12 | UART RX | — |
| 3V3 | — | Power | MAX30102 VCC |
| GND | — | Ground | MAX30102 GND |

> ⚠️ **Common mistake:** D4 = P0.04 and D5 = P0.05 — **not** P0.26/P0.27.
> P0.26 is the onboard red LED, which does not conflict with I2C1. Wiring the
> PPG module to P0.26/P0.27 is the single most common bring-up error on this
> board.

**Onboard LEDs** (already declared in `xiao_ble_common.dtsi`, no overlay needed):

| Alias | Pin | Colour |
|-------|-----|--------|
| led0 | P0.26 | Red |
| led1 | P0.30 | Green |
| led2 | P0.06 | Blue |

**Other pins used by the firmware:**

| Pin | Purpose |
|-----|---------|
| P0.31 / AIN7 | VBAT sense through the onboard 1 MΩ / 510 kΩ divider |
| P0.14 | Divider enable (active low) — gated so the divider does not drain the cell |
| P0.11 | LSM6DS3TR-C INT1 — wake-on-motion source out of deep sleep |
| P1.08 | LSM6DS3TR_C_EN regulator (board-managed) |

---

## I2C bus layout

| Bus | Compatible | Pins | Device | Address |
|-----|-----------|------|--------|---------|
| i2c0 | `nordic,nrf-twim` | SDA=P0.07, SCL=P0.27 | LSM6DS3TR-C (IMU, onboard) | 0x6A |
| i2c1 | `nordic,nrf-twi` | SDA=D4/P0.04, SCL=D5/P0.05 | MAX30102 (PPG, external) | 0x57 |

`i2c0` is wired internally on the board and is not exposed on the connector.
`i2c1` is the external connector bus; its pin control is defined in
`xiao_ble-pinctrl.dtsi`. The MAX30102 node is added in
`firmware/boards/xiao_ble_sense.overlay` at standard bit rate (100 kHz).

---

## MAX30102 PPG front end (MAXREFDES117#)

### Power control

The MAXREFDES117# has **no accessible EN/SHDN pin** — confirmed both by hardware
debugging and by Maxim's documentation. The MAX30102 is powered whenever VIN is
connected; there is no software power gating on this module. D0/P0.02 is **not
used** — do not add an `en-gpios` property to the DTS or drive one in firmware.

Low-power operation is achieved instead by putting the chip into its own SHDN
mode over I2C (see [Power management](#power-management) below).

### Interrupt line

- Active **LOW**, open-drain — requires a pull-up, configured via `GPIO_PULL_UP`
  in the overlay.
- Wired to D1 / P0.03.
- Fires on `PPG_RDY`: one interrupt per new FIFO sample, i.e. 100 Hz.

### Register configuration

| Parameter | Value | Register |
|-----------|-------|----------|
| Mode | SpO2 (Red + IR) | `MODE_CFG` = 0x03 |
| Sample rate | 100 Hz | `SPO2_CFG` = 0x67 |
| ADC resolution | 18-bit | `SPO2_CFG` |
| ADC range | 3 (full scale) | `SPO2_CFG` |
| LED pulse width | 411 µs | `SPO2_CFG` |
| LED amplitude (Red + IR) | 0x1F ≈ 6.2 mA | `LED1_PA`, `LED2_PA` |
| FIFO rollover | **enabled** | `FIFO_CFG` = 0x10 |
| Part ID (register 0xFF) | 0x15 | read-only, checked at init |

> **FIFO rollover must stay enabled.** With rollover off, the 32-deep FIFO wedges
> permanently the first time the host falls behind, and acquisition never
> recovers.

**Driver:** custom register-level I2C driver in `firmware/src/max30102.c`. There
is no Zephyr native MAX30102 driver in use. The DTS binding lives at
`firmware/dts/bindings/sensor/maxim,max30102.yaml`.

---

## IMU — LSM6DS3TR-C (onboard)

- Bound in Zephyr DTS as `compatible = "st,lsm6dsl"` (compatible superset).
- Located at `i2c0 @ 0x6A`, powered through a `regulator-fixed` node
  (`LSM6DS3TR_C_EN` on P1.08).
- Driver: Zephyr built-in, `CONFIG_LSM6DSL=y`.
- Configured at **104 Hz ODR** for both accelerometer and gyroscope.
- Wrapper: `firmware/src/imu.c` — `imu_init()`, `imu_fetch_sample()`,
  `imu_print_sample()`.

**Units** (Zephyr sensor API): accelerometer in **m/s²**, gyroscope in **rad/s**
— *not* degrees per second. This trips people up when comparing against
datasheets or other IMU libraries. The bridge converts to g and deg/s internally
before feeding the AHRS filter.

There is **no magnetometer** on this board. Absolute heading is therefore
unavailable and yaw drifts over time; see [docs/analysis.md](analysis.md) for how
the software mitigates this.

---

## Battery sensing

VBAT is measured through the board's onboard resistor divider (1 MΩ / 510 kΩ)
into AIN7 / P0.31. The divider is gated by P0.14 (active low) so it does not
continuously drain the cell.

- Sampled every 5 s (`battery_poll()` is internally rate-limited).
- State of charge from an 11-point single-cell LiPo discharge curve with linear
  interpolation between points: **4200 mV = 100 %**, **3300 mV = 0 %**.
- Reported on both transports as a plain line:

```
Battery: 71% (3940 mV)
```

Source: `firmware/src/battery.c`. The overlay declares the `battery_divider`
`voltage-divider` node and the ADC channel (gain 1/6, internal reference,
12-bit, `NRF_SAADC_AIN7`).

---

## Power management

The firmware enters **nRF System Off** (~0.4 µA) after a configurable period of
inactivity. "Inactivity" means **both** no gyroscope motion **and** no IR
contact — so wearing the device while sitting perfectly still (resting, sleeping)
does *not* trigger sleep. Only setting it down and leaving it unworn does.

### Contact detection

`contact_is_skin()` in `firmware/src/contact.c` is a DC-level check on the IR
channel: an exponential moving average `dc += 0.05 × (ir − dc)` (~0.8 Hz corner,
~200 ms response) compared against `CONTACT_DC_MIN = 5000`. Open air reads
roughly 100–300 counts; something resting on the sensor reads 29 000+.

The threshold is deliberately the same number as `CONTACT_IR_MIN` in
`scripts/bridge.py` and `analysis/pipeline.py`, so host and device agree on what
"worn" means.

> An earlier version additionally required a validated heartbeat (consecutive
> peak intervals in a plausible cardiac range) to reject inert reflective
> surfaces. The embedded peak detector never found one reliably on real hardware,
> so it was reverted to the DC-only check already proven on the host side.
> **Known limitation:** a non-skin reflective surface at the right distance still
> registers as contact.

### Sleep sequence

1. No gyro motion above 0.1 rad/s **and** no IR contact for
   `CONFIG_PHYSDAQ_IDLE_TIMEOUT_SEC` seconds.
2. BLE is stopped (`ble_stop()`) — the controller must be idle before System Off.
3. MAX30102 is put in SHDN mode (LEDs off, ~0.7 µA, I2C still alive).
4. LSM6DS3TR-C is reconfigured for accel-only 26 Hz wake-up detection on INT1
   (P0.11), threshold ~125 mg.
5. The nRF enters System Off via `nrf_power_system_off()` — all GPIO go
   high-impedance.

### Wake sequence

- Physical movement → IMU asserts INT1 high → nRF GPIO sense DETECT → **full CPU
  reset**.
- The firmware boots from scratch; sensors re-initialise normally. There is no
  state preserved across sleep.
- The USB CDC serial port disappears during sleep and reappears on wake — restart
  `make term` (or reconnect in the desktop app) after waking a node.

### Configuring the timeout

Edit `firmware/prj.conf`:

```
CONFIG_PHYSDAQ_IDLE_TIMEOUT_SEC=10   # then: make rebuild && make flash
```

Valid range is 5–300 s (enforced in `firmware/Kconfig`). No source changes are
needed. Because this is a Kconfig symbol, use `make rebuild` rather than
`make build` if you have just renamed or added symbols.

While awake and idle, a status line is printed every 5 s so you can watch the
countdown:

```
Power: idle 4s/10s | skin: yes (dc=29050) | peak ~12mrad/s (thresh 100mrad/s)
```
