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
The boot banner and the `ID` line go out once init completes.

Each iteration then:

1. Feeds the watchdog.
2. Waits up to 200 ms for a PPG_RDY interrupt.
3. Drains the MAX30102 FIFO; for each PPG sample, fetches a matching IMU sample.
4. Formats **one** line and sends it to the USB console at full rate, and to BLE
   **rate-limited to one line per 40 ms (~25 Hz)** — the link carries about
   4 kB/s and 100 Hz would overflow the TX queue.
5. Re-sends the `ID` line if BLE has just become connected.
6. Updates contact detection and the idle/power state machine.
7. Polls the battery (internally rate-limited to once per 5 s).

Status and recovery messages are **dual-sent** the same way: the battery line,
the power line, the stall-recovery notice and the `ID` line all go to both the
console and BLE, so a wireless host sees the same diagnostics as a wired one.

### Two independent recovery mechanisms

These solve different failure modes and neither substitutes for the other:

**Watchdog — the CPU stopped.** `wdt0`, 8 s window, `WDT_FLAG_RESET_SOC`. Fed
at the top of the outer loop **and once per sample inside the FIFO-drain loop** —
that inner loop only exits once the FIFO is empty, and when a sample's worth of
work takes longer than the 10 ms sample period it never does, so an outer-only
feed reset a node that was streaming perfectly. Configured with
`WDT_OPT_PAUSE_HALTED_BY_DBG` but deliberately **not** `WDT_OPT_PAUSE_IN_SLEEP` —
the exact freeze it guards against idles the CPU on a `K_FOREVER` I2C wait, and
pausing in sleep would disarm it precisely when it is needed.

A kernel timer in `watchdog.c` watches the feeds: after 3 s without one it records
where `main()` is (the `watchdog_set_stage()` marker), what the kernel thinks of
it, and which thread holds the CPU with its PC — into noinit RAM via
`crashlog.c`, then to the console. The record survives the reset and is sent to
the host on the next boot as a `Hang:` line.

**Fatal errors — the CPU crashed.** Vanilla Zephyr's fatal handler ends in
`arch_system_halt()`: interrupts locked, spin forever. On this board that made a
crash indistinguishable from a hang: the dump sat in the USB CDC ring buffer with
the USB interrupt unable to run, the host saw a supervision timeout, the watchdog
reset 8 s later and the next boot said `cause=watchdog`. `crashlog.c` overrides
`k_sys_fatal_error_handler()` to store reason, PC, LR and thread in noinit RAM
and reboot at once; the next boot reports it as a `Crash:` line on both
transports (`Boot: cause=…` always accompanies it). Resolve addresses with
`arm-zephyr-eabi-addr2line -e build/zephyr/zephyr.elf 0x…`.

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
| `power.c/.h` | Idle detection, deep sleep, wake-on-motion configuration. Reads DTR on the CDC console (`CONFIG_UART_LINE_CTRL`) so a host holding the USB port open holds off sleep like a BLE link does |
| `contact.c/.h` | Skin-contact detection from the IR DC level |
| `battery.c/.h` | VBAT sampling and LiPo state-of-charge curve |
| `watchdog.c/.h` | Hardware watchdog arm + feed, plus the starvation monitor (kernel timer) |
| `crashlog.c/.h` | Fatal-error handler override, noinit-RAM crash/hang records, reported on the next boot |
| `led.c/.h` | RGB helper over the three onboard LEDs |
| `version.h` | `PHYSDAQ_FW_STRING` — the single source for the `ID` line and, on the hub, session-file headers |

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
- Also advertises a **manufacturer-specific field** under company ID `0xFFFF`
  carrying four bytes — AD protocol version, device type (`0x01` node, `0x02`
  hub), PPG count, and a flags byte whose bit 0 means "has an SD card". That
  brings the AD payload to 29 of the 31 available bytes. It is a **hint for the
  host's scan list only**: discovery still filters on the service UUID, and the
  `ID` line is what actually decides once connected.
- Names itself `PhysDAQ-XXXX` at boot from the factory device ID, so a scan can
  tell two nodes apart. Needs `CONFIG_BT_DEVICE_NAME_DYNAMIC` and
  `CONFIG_HWINFO`; if hwinfo fails it falls back to the plain configured name
  rather than refusing to advertise.
- Requests an MTU exchange on connect and logs the negotiated value.
- `ble_send()` **never touches the radio.** It copies the line into a bounded
  message queue (16 × 160 B, `K_NO_WAIT`, dropped when full — `ble_tx_dropped()`
  counts them, shown as `dropped N` in the `Power:` line) and a dedicated
  low-priority TX thread drains it, chunking by `MTU − 3`. This is not a style
  choice: `bt_gatt_notify()` allocates its PDU with `K_FOREVER`
  (`zephyr/subsys/bluetooth/host/att.c`, `bt_att_chan_create_pdu`) and blocks
  indefinitely once the ACL TX buffers are exhausted. Called from `main()`, that
  sleep starved the watchdog and reset the node mid-session.
- The controller runs LL Data Length Extension (`CONFIG_BT_CTLR_DATA_LENGTH_MAX=251`).
  Without it the 27 B LL default split each ~120 B line into five packets and
  saturated the link at Windows' 30 ms interval. TX pools are 6 deep.
- `CONFIG_BT_GAP_AUTO_UPDATE_CONN_PARAMS` is **off**. With it on, the node crashed
  within a second of the central applying the update, every time, on Windows.
  The `BT_PERIPHERAL_PREF_*` values are kept in `prj.conf` for when that is
  understood.
- Re-advertises automatically on disconnect, and remembers the HCI reason. It
  is sent to the host on the next connection (`BLE: previous link ended
  reason=…`) via `ble_flush_diagnostics()`, called from the main loop — the
  callbacks only record, they never notify. The granted connection parameters
  are reported the same way.
- `ble_stop()` shuts the controller down cleanly before System Off.
- `ble_is_connected()` reports link state — the power state machine uses it as a
  "do not sleep" condition. `ble_is_subscribed()` additionally requires the CCC
  write, and that is the edge `main.c` watches to re-send the `ID` line: on the
  bare connection edge the host has not enabled notifications yet and the line
  was silently dropped.
- `ble_device_name()` returns the resolved `PhysDAQ-XXXX` name for the `ID` line.
- A `le_param_updated` callback logs the interval the central actually granted,
  which is the only way to tell a negotiated connection from a requested one.
- The RX characteristic (host → device) is registered but its handler is a no-op
  on this firmware. The hub wires the same characteristic to its command parser —
  see [firmware-hub.md](firmware-hub.md#command-channel) — so extending the node
  is a matter of reusing that code, not designing a channel.

The node emits an identity line at boot and again whenever a host connects:

```
ID model=node proto=2 fw=1.2.0 ppg=1 sd=0 name=PhysDAQ-FDF9
```

Field meanings are in [protocol.md](protocol.md#identity-line). The version comes
from `version.h`, which is also what stamps the hub's session-file headers, so
the two can never disagree.

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
| `CONFIG_BT_DEVICE_NAME_DYNAMIC`, `CONFIG_BT_DEVICE_NAME_MAX=24`, `CONFIG_HWINFO` | Per-device `PhysDAQ-XXXX` naming at boot |
| `CONFIG_BT_GAP_AUTO_UPDATE_CONN_PARAMS=y` | What makes the `PREF_*` row below take effect at all |
| `CONFIG_BT_MAX_CONN=1` | One central at a time |
| `CONFIG_BT_GATT_CLIENT=y` | Needed for `bt_gatt_exchange_mtu()` **even on a peripheral** |
| `CONFIG_BT_L2CAP_TX_MTU=247`, `CONFIG_BT_BUF_ACL_*_SIZE=251` | Fit a full data line in one notification |
| `CONFIG_BT_CTLR_TX_PWR_PLUS_8=y` | +8 dBm radio — margin against body shadowing on a worn node |
| `CONFIG_BT_PERIPHERAL_PREF_*` | Request 15–30 ms interval + **5 s supervision timeout** so a radio fade degrades throughput instead of dropping the link |

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
