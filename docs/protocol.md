# Data Contracts

Three formats hold this system together. They are the real interfaces between
firmware, bridge, desktop app and analysis code — change one and something
downstream breaks, so they are documented here in one place.

1. [Device → host: the ASCII line protocol](#1-device--host-ascii-line-protocol)
2. [Bridge → app: JSON on stdout](#2-bridge--app-json-lines)
3. [App → disk: the session CSV](#3-app--disk-session-csv)

Plus [the logger CSV](#4-logger-csv-analysis-input), which is a fourth, older
format the offline pipeline consumes.

---

## 1. Device → host: ASCII line protocol

Both transports carry **exactly the same** newline-delimited ASCII. The BLE path
is not a separate binary protocol — `firmware/src/main.c` formats each line once
and hands the same buffer to `printk()` and `ble_send()`.

| | USB | BLE |
|---|---|---|
| Carrier | USB CDC ACM virtual COM port, 115200 8N1 (nominal) | Nordic UART Service notifications |
| Rate | 100 Hz (every sample) | ~25 Hz (one line per 40 ms) |

The BLE path is rate-limited on purpose: a BLE link at default Windows connection
parameters carries roughly 4 kB/s, while 100 Hz × ~120 B would be 12 kB/s and
overflows the TX queue immediately. USB gets every sample; BLE gets a display-rate
subset.

### Sample line

```
PPG red=<uint> ir=<uint> | IMU ax=±D.DDD ay=±D.DDD az=±D.DDD gx=±D.DDD gy=±D.DDD gz=±D.DDD
```

| Field | Range / unit |
|---|---|
| `red`, `ir` | 0 … 262143 (18-bit ADC counts) |
| `ax` `ay` `az` | m/s² |
| `gx` `gy` `gz` | **rad/s** (Zephyr sensor API — not deg/s) |

Numbers are formatted as fixed 3-decimal "integer.milli" from
`sensor_value.val1`/`val2`. A full line is about 120 bytes; the firmware buffer
is 160.

### Auxiliary lines on the same stream

Everything below shares the stream with sample lines. The bridge matches sample
and battery lines with regexes and forwards **all other lines to stderr**, which
the desktop app surfaces in its System Logs pane.

```
Battery: 71% (3940 mV)
Power: idle 4s/10s | skin: yes (dc=29050) | peak ~12mrad/s (thresh 100mrad/s)
Power: 10 s idle — entering deep sleep (wake on motion)
MAX30102: ready (SpO2, 100 Hz, 18-bit ADC)
MAX30102: no data for >1s — reinitialising sensor
BLE: connected
BLE: MTU 247 bytes (244 payload)
BLE: disconnected (reason 19) — advertising
Watchdog: armed (8000 ms, reset-on-hang)
```

Plus the boot banner:

```
=== PhysDAQ: PPG + IMU acquisition ===
PPG: SpO2 mode, 100 Hz, 18-bit ADC
IMU: accel [m/s^2], gyro [rad/s]
BLE: NUS advertising as PhysDAQ
Battery: VBAT via P0.31/AIN7, sampled every 5 s
Power: sleep after 10 s idle
```

### BLE identifiers

Stock Nordic UART Service UUIDs — the device does not define custom ones:

| Role | UUID |
|---|---|
| Service | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` |
| TX (device → host, notify) | `6e400003-b5a3-f393-e0a9-e50e24dcca9e` |
| RX (host → device, write w/o response) | `6e400002-b5a3-f393-e0a9-e50e24dcca9e` |

The advertised name is `PhysDAQ` (`CONFIG_BT_DEVICE_NAME`), but **no host code
matches on the name** — every Python client discovers by service UUID, which is
advertised in the AD payload precisely so scanners can filter on it. Renaming the
device does not break discovery.

The RX characteristic is wired up and writable but its handler is currently a
no-op, reserved for a future downlink command channel.

---

## 2. Bridge → app: JSON lines

`scripts/bridge.py` writes one JSON object per line to stdout, flushed
immediately. Four shapes:

### `sample`

```json
{
  "type": "sample",
  "red": 28451, "ir": 29033,
  "ppg_filt": -12.47,
  "ax": 0.213, "ay": -9.706, "az": 0.884,
  "gx": 0.001, "gy": -0.004, "gz": 0.002,
  "quat": [0.9993, 0.0121, -0.0344, 0.0058],
  "bpm": 72.4,
  "contact": true
}
```

- `quat` is `[w, x, y, z]` in the **NWU** convention (North-West-Up, Z up).
- `bpm` is `null` when there is no contact or no confident spectral peak.
- `ppg_filt` is the AC component of the IR channel after a single-sample EMA
  band-pass; it is what the app's main waveform chart displays.

### `battery`

```json
{"type": "battery", "pct": 71, "mv": 3940}
```

### `status`

```json
{"status": "connecting", "port": "COM7"}
{"status": "connected",  "ble_addr": "E1:23:45:67:89:AB"}
{"status": "disconnected"}
```

### `error`

```json
{"error": "bleak not installed"}
```

Anything the bridge cannot parse from the device goes to **stderr**, prefixed
`FW: `, and is displayed in the app rather than being silently dropped.

### Bridge invocation

| Argument | Behaviour |
|---|---|
| *(none)* | Serial, port auto-detected by USB descriptor |
| `<PORT>` | Serial on an explicit port |
| `--ble` | BLE, first device advertising the NUS UUID |
| `--ble-addr=<addr>` | BLE, specific address |
| `--list-ports` | Print `[{port, desc, hwid}]` and exit |
| `--scan` | Print `[{address, name}]` and exit |

The bridge exits when its stdin closes (EOF), which is how the desktop app
guarantees no orphaned processes keep a serial port or BLE link open.

---

## 3. App → disk: session CSV

Written by `app/src/main/sidecar.ts` while recording.

**Location:** `Documents/PhysDAQ_Sessions/session_<ISO-date>_<name>/<position>.csv`

- The ISO timestamp has `:` and `.` replaced by `-` to be filesystem-safe.
- `<name>` is the user's session name with everything outside `[A-Za-z0-9_-]`
  replaced by `_`.
- `<position>` is the body slot: `head`, `chest`, `wrist_left`, `wrist_right`,
  `finger_left`, `finger_right`, `ankle_left`, `ankle_right`. **One file per
  connected node**, all sharing a common session start time.

**Header (17 columns):**

```
timestamp,elapsed_s,ax,ay,az,gx,gy,gz,qw,qx,qy,qz,ppg_red,ppg_ir,ppg_filt,bpm,contact
```

| Column | Notes |
|---|---|
| `timestamp` | ISO-8601 **string** |
| `elapsed_s` | seconds since session start, float |
| `ax…gz` | as on the wire (m/s², rad/s) |
| `qw qx qy qz` | orientation quaternion, NWU |
| `ppg_red`, `ppg_ir` | raw 18-bit counts |
| `ppg_filt` | band-passed IR AC component |
| `bpm` | **blank** when unavailable |
| `contact` | `0` or `1` |

> Sessions recorded before the project was renamed live in
> `Documents/MAID_Sessions`. The app still lists them read-only; it only ever
> writes to `PhysDAQ_Sessions`.

---

## 4. Logger CSV (analysis input)

`analysis/logger.py` writes a **different, older and simpler** format to
`logs/YYYY-MM-DD_HH-MM-SS.csv`:

```
timestamp,red,ir,ax,ay,az,gx,gy,gz
```

Here `timestamp` is a **float** (seconds), and the PPG columns are `red`/`ir`,
not `ppg_red`/`ppg_ir`.

> ⚠️ **These two CSV formats are not interchangeable.** `analysis/pipeline.py`
> does `pd.read_csv(path).astype(float)` and expects the logger schema — it will
> raise on an app-recorded session's ISO timestamp, and the `red`/`ir` columns it
> wants do not exist there. There is currently no converter in the repo. See
> [roadmap.md](roadmap.md).
