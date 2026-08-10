# Firmware Reference

Zephyr RTOS **v3.6.0** application for the XIAO nRF52840 Sense. Board name
`xiao_ble_sense`, CMake project `xiao_sense_app`. The Zephyr version is pinned in
`west.yml` so every teammate builds against the same tree.

For pinout, sensor registers and power hardware see
[hardware.md](hardware.md). For the output format see [protocol.md](protocol.md).

---

## Main loop

`firmware/src/main.c` is the whole application. Init order matters:

```
imu_init()  →  max30102_init()  →  battery_init()  →  contact_init()
            →  power_init()     →  ble_init()      →  watchdog_init()
```

The watchdog is armed **last**, deliberately: init can be slow (I2C probes, BLE
stack bring-up) and arming earlier would let a legitimate cold start trip it.

Each iteration then:

1. Feeds the watchdog.
2. Waits up to 200 ms for a PPG_RDY interrupt.
3. Drains the MAX30102 FIFO; for each PPG sample, fetches a matching IMU sample.
4. Formats **one** line and sends it to both the USB console and BLE.
5. Updates contact detection and the idle/power state machine.
6. Polls the battery (internally rate-limited to once per 5 s).

### Two independent recovery mechanisms

These solve different failure modes and neither substitutes for the other:

**Watchdog — the CPU stopped.** `wdt0`, 8 s window, `WDT_FLAG_RESET_SOC`. Fed
once per outer iteration; the loop turns over at least every 200 ms, so if
anything blocks forever (a wedged I2C transfer is the realistic case) the feeds
stop and the SoC resets itself. Configured with `WDT_OPT_PAUSE_HALTED_BY_DBG` but
deliberately **not** `WDT_OPT_PAUSE_IN_SLEEP` — the exact freeze it guards against
idles the CPU on a `K_FOREVER` I2C wait, and pausing in sleep would disarm it
precisely when it is needed.

**Sensor-stall recovery — the CPU is fine, the sensor isn't.** If the FIFO yields
nothing for more than 1 s, `max30102_init()` is re-run. This recovers the I2C bus
and fully reconfigures the chip. The watchdog cannot help here because the main
loop is still happily turning over; without this check the whole pipeline
(serial + BLE + power management) would sit frozen while the MCU looked healthy.

---

## Modules

| File | Responsibility |
|---|---|
| `main.c` | Init sequence, acquisition loop, line formatting, stall recovery |
| `max30102.c/.h` | Register-level I2C driver for the PPG front end |
| `imu.c/.h` | Thin wrapper over Zephyr's `st_lsm6dsl` driver |
| `ble.c/.h` | NUS-compatible GATT service, advertising, MTU negotiation |
| `power.c/.h` | Idle detection, deep sleep, wake-on-motion configuration |
| `contact.c/.h` | Skin-contact detection from the IR DC level |
| `battery.c/.h` | VBAT sampling and LiPo state-of-charge curve |
| `watchdog.c/.h` | Hardware watchdog arm + feed |
| `led.c/.h` | RGB helper over the three onboard LEDs |

### `max30102.c`

Custom driver — there is no Zephyr native MAX30102 driver in use. Covers
registers 0x00–0x0D plus 0xFF (part ID, verified as `0x15` at init).

The interrupt handler gives a `K_SEM`. `max30102_wait_ready(timeout)` ignores the
semaphore take result and **always** clears `INT_STATUS1`, so a missed falling
edge costs one timeout of latency rather than a permanent stall — the timeout
path is a normal, self-healing branch, not an error.

`max30102_fetch()` compares the FIFO write and read pointers and burst-reads
6 bytes (3 Red + 3 IR), masking to 18 bits. Returns `-ENODATA` when the FIFO is
empty, which is how the drain loop terminates.

`max30102_shutdown()` puts the chip in SHDN for the sleep path.

### `ble.c`

The NUS service is built from **raw Zephyr GATT macros**, not `CONFIG_BT_NUS` —
that symbol is Nordic Connect SDK-only and does not exist in plain Zephyr.

- Advertises the 128-bit service UUID in the AD payload and the device name in
  the scan response, so hosts can filter by UUID.
- Names itself `PhysDAQ-XXXX` at boot from the factory device ID, so a scan can
  tell two nodes apart. Needs `CONFIG_BT_DEVICE_NAME_DYNAMIC` and
  `CONFIG_HWINFO`; if hwinfo fails it falls back to the plain configured name
  rather than refusing to advertise.
- Requests an MTU exchange on connect and logs the negotiated value.
- `ble_send()` chunks payloads by `MTU − 3` (clamped to 20–244 bytes) and drops
  the remainder on `-ENOMEM` rather than blocking the acquisition loop.
- Re-advertises automatically on disconnect.
- `ble_stop()` shuts the controller down cleanly before System Off.
- The RX characteristic (host → device) is registered but its handler is a no-op,
  reserved for future downlink commands.

### `led.c`

Compiled and functional, but **not currently called from `main.c`** — the status
LEDs are unused. Kept because a wearable with no display wants some local status
indication eventually; see [roadmap.md](roadmap.md).

---

## Configuration

### `firmware/prj.conf`

| Symbol | Purpose |
|---|---|
| `CONFIG_GPIO`, `CONFIG_SERIAL`, `CONFIG_UART_CONSOLE` | Console plumbing |
| `CONFIG_I2C`, `CONFIG_SENSOR`, `CONFIG_LSM6DSL` | IMU |
| `CONFIG_ADC` | VBAT sensing |
| `CONFIG_WATCHDOG` | Hardware watchdog |
| `CONFIG_PHYSDAQ_IDLE_TIMEOUT_SEC` | Seconds before deep sleep (default 10) |
| `CONFIG_BT`, `CONFIG_BT_PERIPHERAL` | BLE stack |
| `CONFIG_BT_DEVICE_NAME="PhysDAQ"` | Advertised name |
| `CONFIG_BT_MAX_CONN=1` | One central at a time |
| `CONFIG_BT_GATT_CLIENT=y` | Needed for `bt_gatt_exchange_mtu()` **even on a peripheral** |
| `CONFIG_BT_L2CAP_TX_MTU=247`, `CONFIG_BT_BUF_ACL_*_SIZE=251` | Fit a full data line in one notification |

### `firmware/Kconfig`

Declares `PHYSDAQ_IDLE_TIMEOUT_SEC`, default 10, **range 5–300**, with help text.
Changing a Kconfig symbol requires `make rebuild` (pristine), not `make build`.

### `firmware/boards/xiao_ble_sense.overlay`

Adds the `max30102@57` node to `&i2c1` with
`int-gpios = <&gpio0 3 (GPIO_ACTIVE_LOW | GPIO_PULL_UP)>`, declares the
`battery_divider` voltage-divider node, enables `&wdt0`, and configures ADC
`channel@7` (gain 1/6, internal reference, 12-bit, `NRF_SAADC_AIN7`).

The custom binding at `firmware/dts/bindings/sensor/maxim,max30102.yaml` is made
visible by `list(APPEND DTS_ROOT …)` in `CMakeLists.txt`, which must appear
**before** `find_package(Zephyr)`.

---

## The console is USB, not a UART

`prj.conf` only sets `CONFIG_UART_CONSOLE`, which reads like a physical UART. It
is not. The board's `xiao_ble_common.dtsi` sets
`zephyr,console = &usb_cdc_acm_uart`, so the console is a **USB CDC ACM virtual
COM port**.

Consequences worth knowing:

- The 115200 baud rate everything quotes is nominal — it is ignored by USB.
- The COM port **disappears** when the node enters deep sleep and reappears as a
  fresh port on wake. Reconnect `make term` (or the desktop app) afterwards.
- Flow control is USB's, not the UART's, so the device can block on a host that
  stops reading.

---

## Building

```bash
make build      # incremental
make rebuild    # pristine — required after overlay/DTS/Kconfig changes
make flash      # double-tap RST, then copy build/zephyr/zephyr.uf2
make term       # console
```

`scripts/build-wrapper.py` sits in front of `west build` and auto-detects stale
CMake caches that reference an external Nordic SDK path (`C:/ncs/...`), purging
them instead of failing with an opaque `pristine.cmake` error.
