# Roadmap

What PhysDAQ does not do yet, and why. Items are grouped by how much they block
real use, not by how hard they are.

---

## Known gaps

### CSV schema mismatch between the app and the analysis pipeline

`analysis/pipeline.py` expects the logger schema
(`timestamp,red,ir,ax…gz`, float timestamp). The desktop app records a richer but
incompatible schema (ISO-8601 string timestamp, `ppg_red`/`ppg_ir`, plus
quaternion, BPM and contact columns). Feeding an app session to `make process`
raises on the timestamp conversion.

**Options:** a converter script, or teach `pipeline.py` to sniff the header and
normalise. The second is better — it removes the trap rather than documenting
around it. Until then, use `make log` for anything destined for offline analysis.

See [analysis.md](analysis.md) and [protocol.md](protocol.md#4-logger-csv-analysis-input).

### No export from the desktop app

There is no save dialog, no format conversion, no session summary. Data leaves
the app only as the raw CSV files already sitting in `Documents/PhysDAQ_Sessions`.
For a research tool that is thin — an export step (filtered range, chosen
channels, maybe EDF or Parquet) is the natural next feature, and it would pair
well with fixing the schema mismatch above.

### No test suite anywhere

No firmware tests, no Python tests, no renderer tests, no CI. The system is
verified by running it. Highest-value first targets, in order:

1. `bridge.py` line parsing and DSP against recorded fixtures — pure functions,
   easy to pin, and the layer most likely to break silently.
2. `pipeline.py` beat detection against a hand-annotated recording.
3. `sidecar.ts` CSV writing and session enumeration.

### No downlink command channel

The BLE RX characteristic (`6e400002-…`) is registered and writable, but its
handler in `firmware/src/ble.c` is a no-op — explicitly reserved for future
commands. Wiring it up would enable, without reflashing: LED/status control,
runtime sleep-timeout changes, sample-rate or LED-current adjustment, time sync
across nodes, and a "start/stop streaming" command that would let a node stay
paired but silent.

Time sync in particular matters for multi-node work: right now, alignment
between nodes rests on the host's arrival timestamps, which BLE latency makes
imprecise.

---

## Smaller items

| Item | Notes |
|---|---|
| Status LEDs unused | `firmware/src/led.c` is compiled but never called from `main.c`. A wearable with no display should signal connection and contact state locally. |
| `disconnect-all` unreachable | The IPC handler exists in `sidecar.ts` but is not exposed through preload, so nothing can invoke it. |
| Scaffold leftovers | `app/src/renderer/src/components/Versions.tsx` and the electron-vite `ping` IPC channel are unused. |
| No code signing | Installers are unsigned; SmartScreen and antivirus flag them. Needs a certificate before wider distribution. |
| No enclosure yet | `hardware/enclosure/models/` is empty — the case is not designed. See its [README](../hardware/enclosure/README.md). |

---

## Accepted limitations

These are consequences of the hardware, not bugs to be fixed:

**Contact detection can be fooled.** `contact.c` is a DC-level check on the IR
channel, so a non-skin reflective surface at the right distance registers as
worn. An earlier version additionally required a validated heartbeat, but the
embedded peak detector never found one reliably on real hardware and it was
reverted. Revisiting this would need either a better embedded detector or a
second sensing modality (capacitive, temperature).

**Yaw drifts.** There is no magnetometer on the XIAO nRF52840 Sense, so absolute
heading is unobservable. ZUPT suppresses drift while a node is still, but does
not eliminate it. Roll and pitch are gravity-referenced and stay accurate.

**BLE delivers ~25 Hz, not 100 Hz.** A BLE link at default connection parameters
carries roughly 4 kB/s; the full stream needs ~12 kB/s. The rate limit is
deliberate. Faster would require connection-parameter negotiation, a binary
packing format instead of ASCII, or both — worth doing if wireless full-rate
capture becomes a requirement.

**SpO2 is uncalibrated.** The `110 − 25·R` Mendelson model on an uncalibrated
sensor gives relative trends, not clinical values.

---

## Longer-term directions

- **On-device storage.** The nRF52840 has QSPI flash on this board. Logging
  locally would decouple recording from having a host present — the single
  biggest capability gap for field studies.
- **Full-rate wireless.** Binary packing plus connection-parameter tuning, if
  100 Hz over BLE becomes necessary.
- **Live HRV and SpO2 in the app.** Both are computed offline already; the
  algorithms exist, they just are not in the bridge's real-time path.
- **Multi-node time sync.** A prerequisite for any analysis that compares
  waveform timing across body positions (pulse transit time, for example).
