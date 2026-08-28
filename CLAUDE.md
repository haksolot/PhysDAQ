# CLAUDE.md — PhysDAQ

Agent brief. Full documentation lives in [`docs/`](docs/) — read the relevant
file before changing a layer rather than inferring from code alone.

## What this is

A wearable research instrument built at BCIT: Zephyr firmware on a XIAO
nRF52840 Sense + MAX30102 PPG, a Python DSP bridge, an Electron desktop app for
multi-node live acquisition and recording, and an offline analysis pipeline.

Nodes are **independent BLE peripherals / USB CDC devices**. There is no mesh —
all multi-node aggregation is host-side, one bridge process per device. Do not
describe it as a mesh network. The hub is not an exception: it carries two of
its own PPG sensors, and its satellite roster is configuration it remembers, not
a radio link it uses. Ingestion of remote nodes is designed for and **not
implemented** — say so whenever the roster comes up.

## Layout

| Path | Layer | Doc |
|---|---|---|
| `firmware/` | Zephyr 3.6 app (C) — single-PPG node | [docs/firmware.md](docs/firmware.md), [docs/hardware.md](docs/hardware.md) |
| `firmware-hub/` | Zephyr 3.6 app (C) — dual-PPG + microSD hub, host command channel | [docs/firmware-hub.md](docs/firmware-hub.md) |
| `scripts/bridge.py` | serial/BLE → JSON sidecar | [docs/protocol.md](docs/protocol.md) |
| `app/` | Electron + React desktop app | [docs/desktop-app.md](docs/desktop-app.md) |
| `analysis/` | offline pipeline, logger, explorer | [docs/analysis.md](docs/analysis.md) |
| `hardware/enclosure/` | 3D-printable case | [its README](hardware/enclosure/README.md) |

## Commands

```bash
make build / rebuild / flash / term    # firmware  (rebuild after DTS or Kconfig changes)
make hub / hub-rebuild / hub-flash     # firmware-hub (builds into build-hub/)
make log / process FILE=… / explore FILE=…
make sidecar                           # freeze bridge.py for packaging
cd app && npm run dev / typecheck / build:win
```

`make help` lists everything. A Zephyr toolchain must be active for firmware
targets — see [docs/development.md](docs/development.md).

## Invariants — do not break these

- **Pinout: D4 = P0.04, D5 = P0.05.** Not P0.26/P0.27. P0.26 is the red LED.
- **MAX30102 FIFO rollover stays enabled** (`FIFO_CFG = 0x10`). With it off the
  FIFO wedges permanently the first time the host falls behind.
- **The watchdog must not use `WDT_OPT_PAUSE_IN_SLEEP`** — the freeze it guards
  against idles the CPU on a `K_FOREVER` I2C wait.
- **BLE stays rate-limited to ~25 Hz** in `main.c`. The link carries ~4 kB/s;
  100 Hz would be ~12 kB/s and overflows the TX queue.
- **Only the `ble_tx` thread calls `bt_gatt_notify()`.** It blocks with
  `K_FOREVER` when TX buffers run out (Zephyr `att.c`); called from `main()` it
  starved the watchdog and reset the node mid-session. `ble_send()` only queues.
- **`CONFIG_BT_GAP_AUTO_UPDATE_CONN_PARAMS` stays off** on both firmwares — the
  node crashed within a second of the update being applied on Windows.
- **The watchdog is fed inside the FIFO-drain loop too**, not only per outer
  iteration; the inner loop does not exit while the FIFO keeps refilling.
- **`crashlog.c` must stay the fatal-error handler.** Zephyr's default halts
  with interrupts locked and the crash is invisible; a node that "keeps
  disconnecting" with `Boot: cause=watchdog` and no `Crash:`/`Hang:` line is
  the signature of losing it.
- **Do not `setState` per sample in the renderer.** Samples are coalesced into
  one update per animation frame; per-sample updates froze the UI. Charts write
  to a ref and render from their own rAF loop.
- **The sidecar binary stays named `bridge`.** Both `sidecar.ts` and
  `electron-builder.yml` hardcode `<resourcesPath>/bridge/bridge[.exe]`, and it
  must stay outside the asar — the OS cannot exec from an archive.
- **`PYTHONPATH`/`PYTHONHOME` are stripped** before spawning Python. The Zephyr
  toolchain sets them and it breaks numpy in the venv.
- **BLE discovery is by service UUID, never by device name.** Keep it that way.
  The advertisement also carries a manufacturer-data byte naming the device type
  — that is a *hint for the scan list*, not a discovery filter, and the `ID` line
  is what actually decides once connected.
- **The sample line must keep starting `PPG red=`.** The bridge matches with
  `re.search`, so a hub's longer line still yields sensor 0 to an older bridge.
  Correspondingly, **no other line on either stream may start with `PPG`** — the
  parser silently discards unmatched lines that do, which is why status lines and
  command replies read `Hub:`, `SD`, `REC`, `SAT`, `ID`.
- **On the hub, only the storage thread touches the file system.** Commands post
  a request and block. FATFS is not reentrant across a volume, and the storage
  thread holds the session file open.
- **A hub feeds two body-position slots from one bridge process.** `channelSlots`
  in `sidecar.ts` maps PPG channel → slot, and `activeRecordings` is keyed on the
  slot, not the device. That is what keeps the 17-column session CSV unchanged.

## Gotchas

- The console is a **USB CDC ACM virtual COM port** (set by the board's
  `xiao_ble_common.dtsi`), not a physical UART. The port disappears during deep
  sleep and returns as a new port on wake.
- IMU units are **m/s² and rad/s** (Zephyr sensor API) — not deg/s.
- Kconfig symbol changes need `make rebuild`, not `make build`.
- The desktop app's session CSV and the logger's CSV are **different schemas**;
  `analysis/pipeline.py` only accepts the logger's. Known gap, see
  [docs/roadmap.md](docs/roadmap.md).
- `Documents/MAID_Sessions` is the pre-rename recordings directory. It is listed
  read-only for backwards compatibility; never write to it.
- There are **no tests** in any layer.

## Conventions

- Firmware comments explain *why*, not *what*. Several non-obvious decisions
  (FIFO rollover, watchdog options, the reverted heartbeat-validated contact
  check) are recorded in-source — preserve them when editing.
- LF line endings everywhere except `.ps1`/`.bat`; CAD files under `hardware/`
  are marked `binary`.
- Do not commit `build/`, `build-hub/`, `.west/`, `zephyr/`, `modules/`,
  `.venv/`, `app/node_modules/`, `app/out/`, `app/sidecar/`.
