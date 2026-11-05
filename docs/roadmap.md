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
around it. Until then, use `make log` or the Android app for anything destined for
offline analysis: the Android app writes the logger schema, so its recordings are
not affected. (The Android Library already reads both layouts by column name.)

See [analysis.md](analysis.md) and [protocol.md](protocol.md#4-logger-csv-analysis-input).

### No export from the desktop app

There is no save dialog, no format conversion, no session summary. Data leaves
the app only as the raw CSV files already sitting in `Documents/PhysDAQ_Sessions`.
For a research tool that is thin — an export step (filtered range, chosen
channels, maybe EDF or Parquet) is the natural next feature, and it would pair
well with fixing the schema mismatch above.

### Tests exist only in the Android app

`android/` has JVM unit tests (`./gradlew test`): line parsing, heart rate against
the Python reference, file naming, CSV analysis, tilt and more. Everything else has
none: no firmware tests, no Python tests, no renderer tests. CI exists — it builds
both firmware images and all three installers, and cuts a draft release — but it
runs the Android tests only when it builds the APK for a release tag
(`android-apk.yml`). Running `./gradlew test` on every push is the cheapest next
step. Highest-value targets after that, in order:

1. `bridge.py` line parsing and DSP against recorded fixtures — pure functions,
   easy to pin, and the layer most likely to break silently.
2. `pipeline.py` beat detection against a hand-annotated recording.
3. `sidecar.ts` CSV writing and session enumeration.

### No reader for the hub's `.BIN` files

The app can list, erase and download session files off a hub's card, but nothing
in this repo parses them. `analysis/pipeline.py` reads the logger's CSV and knows
nothing about the 512-byte-header / 16-byte-record format in
[firmware-hub.md](firmware-hub.md#file-format). A downloaded file is currently a
raw deliverable.

The reader is the missing half of the download feature and the prerequisite for
anything the hub records reaching the offline pipeline. It pairs naturally with
the CSV schema work above — both are "teach the pipeline a second input format".

### The command channel is hub-only

`firmware-hub/src/command.c` implements the downlink; the single-PPG node's
`on_rx_write()` is still a no-op. Extending it to the node would enable, without
reflashing: LED/status control, runtime sleep-timeout changes, sample-rate or
LED-current adjustment, and a "start/stop streaming" command that would let a
node stay paired but silent.

Time sync remains unaddressed on either device, and it is what multi-node work
actually needs: alignment between nodes still rests on the host's arrival
timestamps, which BLE latency makes imprecise. A first two-device recording on the
Android app (2026-10-22, two fingers) lined the files up within about one sample
(median 43 ms by pulse-wave cross-correlation); whether that is the devices or the
body's left/right pulse difference is open. The tap test in
[android/tools](../android/tools/README.md#sync_checkpy) measures devices only and
has not been run yet.

---

## Android app

Built: BLE acquisition from several devices, heart rate, tilt, body positions and a
body map, session recording in the logger schema, a session Library with
per-node statistics, charts and node comparison, the sleep hint and the
pre-record check. See [android-app.md](android-app.md).

Not built yet, roughly in order of value:

| Item | Notes |
|---|---|
| Two-device timing test | Record the tap test and run `sync_check.py`; decides whether pulse-timing comparisons across positions are possible |
| Event markers | A Mark button during recording, saved to a separate `_events.csv` so the main CSV schema stays unchanged; shown on the Library charts |
| Share a session | Zip a session's CSVs and open the Android share sheet |
| Session notes | Free text per session, saved as a `.txt` next to the CSVs |
| Low-battery warning | Amber/red battery and a warning in the pre-record check |
| Copy log | One tap to copy the system log |
| Signal quality | A Good / Fair / Poor score per node, live and per recording |
| Hub second sensor | The app reads sensor 0 of a hub only |
| Settings screen | Contact threshold, keep screen on, default session name |

---

## Smaller items

| Item | Notes |
|---|---|
| Status LEDs unused | `firmware/src/led.c` is compiled but never called from `main.c`. A wearable with no display should signal connection and contact state locally. |
| `disconnect-all` unreachable | The IPC handler exists in `sidecar.ts` but is not exposed through preload, so nothing can invoke it. |
| `make log` records one channel of a hub | `analysis/logger.py` now parses a hub's line and warns, but its CSV is the pipeline's single-channel schema, so the second sensor is dropped. Record from the desktop app to capture both. |
| Scaffold leftovers | `app/src/renderer/src/components/Versions.tsx` and the electron-vite `ping` IPC channel are unused. |
| No code signing | Installers are unsigned; SmartScreen and antivirus flag them. Needs a certificate before wider distribution. |
| Enclosure CAD has no editable source | The case and lid are filed as `hardware/enclosure/models/physdaq-{case,lid}-v1.step`, but STEP only: no `.f3d` master and no printable `.stl`, so a revision means re-importing rather than editing. |

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
deliberate. Connection-parameter negotiation is now in place (15–30 ms preferred
interval, 5 s supervision timeout), which bought link stability rather than
throughput; **binary packing instead of ASCII is the remaining lever**, worth
doing if wireless full-rate capture becomes a requirement.

**HRV over BLE is not reliable.** Over BLE every timestamp is the host's arrival
time, quantised by the connection interval (about 30 ms, jitter about 12 ms). Heart
rate is fine; beat-to-beat intervals are not. Use USB at 100 Hz for HRV.

**SpO2 is uncalibrated.** The `110 − 25·R` Mendelson model on an uncalibrated
sensor gives relative trends, not clinical values.

---

## Longer-term directions

- **On-device storage on the *node*.** The hub records to microSD already; the
  single-PPG node has nowhere to log. The nRF52840 has QSPI flash on this board,
  and logging locally would decouple recording from having a host present.
- **Full-rate wireless.** Binary packing, if 100 Hz over BLE becomes necessary —
  the connection-parameter half of this is already done.
- **Live HRV and SpO2 in the app.** Both are computed offline already; the
  algorithms exist, they just are not in the bridge's real-time path.
- **Multi-node time sync.** A prerequisite for any analysis that compares
  waveform timing across body positions (pulse transit time, for example), and
  the specific thing blocking the hub's Phase 2 radio ingestion. The satellite
  roster is configurable today; nothing is ingested until remote timestamps can
  be resolved onto the hub's base with a bounded error below roughly one sample
  period. See [firmware-hub.md](firmware-hub.md#phase-2--not-implemented).
