# Desktop App

`app/` is an Electron application that acquires from several PhysDAQ nodes at
once, plots them live, and records synchronised CSV sessions.

**Stack:** electron-vite 5 · Electron 39 · React 19 · TypeScript 5.9 ·
Tailwind v4 · shadcn/ui (new-york, Radix) · three.js · lucide-react.

It does **not** talk to hardware itself. All serial and BLE I/O happens in
`scripts/bridge.py`, which the main process spawns as a child — see
[protocol.md](protocol.md) for the JSON it emits.

---

## Process architecture

```
┌─ main process ────────────────────────────────────────────────┐
│  index.ts     window, app lifecycle                            │
│  sidecar.ts   one bridge child process per connected sensor    │
│               ├─ stdout → parse JSON → IPC 'sensor-data'       │
│               ├─ stderr → IPC 'sidecar-log'                    │
│               └─ if recording → append row to <position>.csv   │
└───────────────────────────┬───────────────────────────────────┘
                            │ contextBridge (preload/index.ts)
┌───────────────────────────▼───────────────────────────────────┐
│  renderer   React — App.tsx holds all state                    │
│             samples coalesced per animation frame              │
└───────────────────────────────────────────────────────────────┘
```

One child process **per sensor**, tracked in
`activeSensors: Map<id, {process, mode, target, position}>`.

### How the bridge is located

`getBridgeInvocation()` in `sidecar.ts`:

| Build | Invocation |
|---|---|
| Development | `<repo>/.venv/Scripts/python.exe scripts/bridge.py` (or `.venv/bin/python`) |
| Packaged | `<process.resourcesPath>/bridge/bridge[.exe]` — the PyInstaller bundle |

`getRepoRoot()` finds the repo by walking up from `app.getAppPath()` looking for
`scripts/bridge.py`, so the checkout can live anywhere and be named anything.

If the packaged binary is missing, the error names the expected path and tells
you to run `npm run build:sidecar` — a silent failure here is very hard to
diagnose otherwise.

### PYTHONPATH is stripped deliberately

`getPythonEnv()` removes `PYTHONPATH` and `PYTHONHOME` from the child's
environment. The Zephyr toolchain setup script (`scripts/setup-env.ps1`) points
both at the Nordic toolchain's Python, which breaks numpy's C extensions in the
venv. If you launch the app from a shell where you have sourced the Zephyr env,
this is what keeps it working.

---

## IPC surface

Exposed to the renderer as `window.api` through `contextBridge`; typed as
`SidecarAPI` in `app/src/preload/index.d.ts`.

| Channel | Kind | Payload → Result |
|---|---|---|
| `get-serial-ports` | `handle` | → `[{port, desc, hwid}]` |
| `scan-ble` | `handle` | → `[{address, name}]` |
| `connect-sensor` | `on` | `{id, mode: 'serial'\|'ble', target, position}` |
| `disconnect-sensor` | `on` | `id` |
| `disconnect-all` | `on` | — *(not exposed in preload; currently unreachable)* |
| `start-recording` | `handle` | `sessionName` → `{success, sessionPath?, error?}` |
| `stop-recording` | `handle` | → `{success, error?}` |
| `get-recordings` | `handle` | → session list |
| `get-recording-data` | `handle` | `(sessionPath, filename)` → `{success, data[]}` |
| `delete-recording` | `handle` | `sessionPath` → `{success}` |
| `sensor-data` | `send` (M→R) | one bridge JSON object + `sensorId`, `position` |
| `sensor-status` | `send` (M→R) | `{sensorId, status, code?, error?}` |
| `sidecar-log` | `send` (M→R) | `{sensorId, log}` |
| `ping` | `on` | scaffold leftover, unused |

The three `on*` subscribers return unsubscribe closures.

---

## Session storage

Recordings are written to `Documents/PhysDAQ_Sessions/`, one folder per session,
one CSV per connected node. Schema in [protocol.md](protocol.md#3-app--disk-session-csv).

`get-recordings` scans **two** base directories and merges the results:
`PhysDAQ_Sessions` and the pre-rename `MAID_Sessions`. The legacy directory is
listed but never written to, so sessions recorded before the rename remain
readable. Entries from it carry `legacy: true`. Every entry returns an absolute
`path`, which is why `get-recording-data` and `delete-recording` need no
knowledge of which directory a session came from.

Session metadata is derived by parsing the folder name (`session_<date>_<name>`)
and reading the **last line** of each CSV for an approximate duration — no index
file is maintained.

`get-recording-data` parses by header index, tolerating missing columns, and
**backfills `ppg_filt`** if it is absent by recomputing the same EMA band-pass the
bridge uses (`dc = dc*0.985 + ir*0.015; lp = lp*0.75 + (ir−dc)*0.25`). This keeps
older recordings displayable.

> There is currently no export feature — no save dialog, no format conversion.
> Data leaves the app as the raw CSV files already on disk. See [roadmap.md](roadmap.md).

---

## Renderer

`App.tsx` is a single component holding all state, with three pages selected by
`currentPage` (no router).

### Sensor model

A fixed 8-slot constant, `SENSOR_POSITIONS`, each with SVG coordinates on the
body map: `head`, `chest`, `wrist_left`, `wrist_right`, `finger_left`,
`finger_right`, `ankle_left`, `ankle_right`. All default to BLE except `chest`,
which defaults to serial.

Each `SensorNode` carries `{status, battery{pct,mv}, bpm, contact, quat, ax…gz,
logs[]}`; logs are capped at 50 entries, newest first.

### Pages

**Body Network** — session recording controls (name, Record/Stop, `mm:ss` timer)
and a node inventory on the left; on the right an SVG human wireframe with 8
positioned markers. Marker colour encodes state: emerald = connected and worn,
amber = connected but not worn, primary = connecting, muted = disconnected.

**Node Detail** — three live charts (filtered PPG, raw red/IR, gyro), a three.js
board visualiser driven by the AHRS quaternion, and cards for heart rate, wear
status, battery and raw telemetry, plus a System Logs pane fed from the bridge's
stderr.

**Session Database** — saved sessions on the left; on the right a per-sensor file
selector and a timeline scrubber (draggable viewport over a downsampled
`ppg_filt` sparkline, with zoom, pan and numeric range inputs). The selected
range is downsampled to ≤1000 points and rendered by the same chart component in
static mode.

### Performance design

Two decisions that look like premature optimisation but are not — both fixed real
UI freezes:

**Sample coalescing.** Incoming samples land in `pendingSamplesRef` and are
flushed with **one `setSensors` per animation frame**. Calling `setState` per
sample at 100 Hz × N sensors let the IPC backlog snowball until the UI locked up.

**Charts bypass React entirely.** `RealTimeChart` pushes data into a plain ref
(`dataRef`, a 300-sample ring buffer) that its own `requestAnimationFrame` loop
reads from the canvas — no re-render per sample. Its config is carried through
`cfgRef` so the effect runs **once per mount**; rebuilding it per sample caused
canvas reallocation churn. Live data is only pushed when the detail page for that
exact sensor is open.

### Components

| File | Role |
|---|---|
| `components/RealTimeChart.tsx` | DPI-aware 2D-canvas plotter, own rAF loop, autoscale; used for both live and static plots |
| `components/BoardVisualizer.tsx` | three.js board model; parent group rotated −π/2 on X to map NWU (Z-up) to three.js Y-up; falls back to an idle spin on an identity quaternion |
| `components/ui/*` | shadcn primitives (alert, button, card, dialog, input, select, separator) |
| `components/Versions.tsx` | unused electron-vite scaffold leftover |

### Theme

`assets/main.css` defines a Tailwind v4 `@theme` block with a hardcoded
**Catppuccin Mocha** palette, plus the `animate-heartbeat` keyframes and custom
scrollbar styling.

---

## Building

See [development.md](development.md#packaging-the-desktop-app) for the full
sidecar + installer chain.
