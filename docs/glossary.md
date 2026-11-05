# Glossary

Short, plain definitions of the terms used across PhysDAQ (the devices, the apps,
the CSV files and the analysis), with the numbers this project actually uses.
Grouped by topic. Details live in the linked reference docs.

---

## 1. The sensor and the signal

**PPG (photoplethysmography)**
Measuring blood flow with light. A light shines into the skin and a detector
measures how much comes back. Each heartbeat pushes a little more blood under
the sensor, which absorbs more light, so the returned light rises and falls
with every pulse. That rising and falling wave is the "PPG signal".

**MAX30102**
The pulse sensor chip on the device. It has two LEDs (red and infrared) and one
light detector, and it reports 18-bit numbers.

**Red / IR (infrared)**
The two LED colours. Each gives its own PPG signal. Infrared (IR) goes deeper
and gives the cleaner pulse wave, so the app uses IR for heart rate. Red and IR
together are what blood-oxygen (SpO2) estimates are built from.

**Raw counts**
The unprocessed numbers straight from the sensor's converter: whole numbers
with no units. A bigger number means more light came back. "IR, raw counts" on
the screen is the IR signal exactly as the chip reported it. On skin it sits
around 28,000; in open air it is only about 100 to 300.

**18-bit / saturation**
The sensor's numbers run from 0 to 262,143 (2^18 minus 1). If the light is too
strong the value hits that ceiling and stops carrying information. That is
called saturation. In this project's recordings it never happened.

**DC and AC**
Borrowed from electronics. The DC part of the PPG is the slow, steady level
(how much light comes back on average, set by skin, pressure and LED power).
The AC part is the small up-and-down wiggle caused by the heartbeat. The pulse
you want is the AC part, riding on top of a much bigger DC level. Filtering is
mostly about separating them.

**Contact / worn**
Whether the sensor is on skin. The app calls it worn when the average of the
last 30 IR readings is above 5,000. The same threshold is used in the firmware,
the old bridge and the analysis pipeline.

**Motion artifact**
Error caused by movement. Moving the sensor changes the light path far more than
a heartbeat does, so movement can swamp or imitate a pulse.

**SpO2**
Estimated blood-oxygen saturation, worked out from the ratio of red to IR pulse
sizes. The offline pipeline computes it; the app does not show it. It is a
rough research estimate, not a medical reading.

---

## 2. Heart-rate words

**BPM (beats per minute)**
Heart rate. The app shows it large, next to the pulsing heart.

**IBI (inter-beat interval)**
The time between two consecutive beats, in milliseconds. 800 ms between beats
is 75 BPM.

**HRV (heart-rate variability) and RMSSD**
How much the beat-to-beat time varies. RMSSD is one common number for it. It
needs beat times accurate to a few milliseconds. Over Bluetooth in this project
timing is only good to about 30 to 40 ms, so HRV from these recordings is not
trustworthy even though heart rate is.

**Spectral (frequency) method**
How the app finds heart rate: instead of spotting individual beats, it looks at
which repeat rate is strongest in the last ~8 seconds of signal. A steady pulse
shows up as a clear peak at its frequency. 1 Hz is 60 BPM, so 1.2 Hz is 72 BPM.

**Band (0.7 to 3.0 Hz)**
The only frequencies the heart-rate search looks at, which equals 42 to 180 BPM.

**SNR gate (signal-to-noise)**
A safety check. The app accepts a heart-rate estimate only if its peak is at
least 3 times stronger than the typical level in the search band. Otherwise it
reports nothing rather than guessing.

**Smoothing (+/- 4 BPM)**
The displayed value can move by at most 4 BPM per update, so it does not jump
around. It is dropped after 5 failed estimates in a row.

---

## 3. Signal processing

**DSP (digital signal processing)**
Maths applied to a stream of numbers, here the filtering and heart-rate
calculation done on the phone.

**Sample / sample rate / Hz**
A sample is one reading (one row of the CSV). Hz means per second. The device
sends about 24.26 samples per second over Bluetooth, even though the sensor
itself samples at 100 Hz. The firmware deliberately limits the Bluetooth stream
because the link cannot carry more.

**Filter, band-pass, high-pass, low-pass**
A filter keeps some frequencies and removes others. A high-pass removes slow
drift (the DC level), a low-pass removes fast noise, and a band-pass does both,
leaving only the heartbeat range. The live waveform on screen is filtered that
way.

**EMA (exponential moving average)**
A very simple filter that blends each new value with the previous result. Used
for the on-screen waveform because it is cheap and needs no stored history.

**FFT / DFT (Fourier transform)**
The maths that turns a stretch of signal into "how strong is each frequency".
It is how the app finds the heartbeat peak.

**Hann window and detrend**
Two preparation steps before the Fourier transform. Detrend removes the average
level; the Hann window tapers the ends of the stretch so they do not create
false frequencies.

**Parabolic refinement**
A trick that places the heart-rate peak between frequency bins instead of on
them, so the answer is not stuck in steps.

**Window**
The stretch of recent samples being analysed (here about 200 samples, roughly
8 seconds). The app makes a new estimate every 25 samples, about once a second.

**AHRS / Madgwick filter**
A method that combines the gyroscope (fast, but drifts) and the accelerometer
(knows which way is down, but noisy) into an orientation estimate. Madgwick's
filter is a popular one; the Android app uses its 6-axis form with β = 0.1.

**Roll, pitch, yaw**
The three tilt angles. Roll is rotation about the board's X axis, pitch about Y,
yaw about Z (turning flat on a table). Roll and pitch are anchored by gravity and
do not drift. Yaw has nothing to anchor it (there is no compass on the board), so
it slowly drifts and only means "turned since connecting".

**Quaternion**
Four numbers (w, x, y, z) that describe an orientation without the ambiguities of
angles. The desktop CSV has quaternion columns; the phone computes one internally
and shows roll, pitch and yaw.

**Zero-velocity update (ZUPT)**
Noting when a device is still so that drift can be corrected. Not used in the
apps.

---

## 4. Motion sensor (IMU)

**IMU**
Inertial measurement unit: the motion sensor built into the board. It has an
accelerometer and a gyroscope.

**Accelerometer (ax, ay, az)**
Measures acceleration in three directions, in m/s^2. At rest it reads about
9.8 m/s^2 along "down" because of gravity.

**Gyroscope (gx, gy, gz)**
Measures how fast the device is rotating around three axes, in rad/s
(radians per second). Zero when still.

**|gyro| (magnitude)**
The total rotation speed, combining the three axes. It is the same no matter how
the device is oriented, which is why the two-device timing test uses it.

---

## 5. Bluetooth

**BLE (Bluetooth Low Energy)**
The low-power Bluetooth the device uses. Not the same as the audio Bluetooth in
headphones.

**Peripheral and central**
The device is the peripheral: it advertises and waits. The phone is the central:
it scans and connects.

**Advertising / scanning**
Advertising is the device repeatedly broadcasting "I'm here". Scanning is the
phone listening for those broadcasts. That is what the Scan button does.

**NUS (Nordic UART Service)**
A standard way of sending text over Bluetooth like a serial cable. PhysDAQ sends
its readings as lines of text through it. The app finds devices by this
service's ID (UUID), never by the device's name.

**UUID**
A long unique ID that names a Bluetooth service. The NUS ID starts 6E400001.

**GATT, characteristic, notification, subscribe (CCCD)**
GATT is the structure of data a Bluetooth device offers. A characteristic is one
item in it. The app subscribes to the TX characteristic, which turns on
notifications: the device pushes each new line without being asked. "CCCD" is
the setting that is written to switch that on.

**MTU**
The biggest chunk of data per Bluetooth message. The app asks for 247 bytes so
a whole line fits in one message.

**Connection interval**
How often the phone and device exchange data. Here about 30 ms. It is why
readings arrive in clusters about 30 ms apart instead of perfectly evenly.

**Supervision timeout / "status 8"**
If nothing is heard for this long, the link is declared dead. The node asks for
5 seconds. Android reports it as "status 8". It means the link went silent. It
does not by itself say whether the radio faded or the device crashed.

**Reconnect (backoff)**
After a drop the app tries again automatically, waiting 1, 2, 4, then 5 seconds
between tries.

**Foreground service**
An Android feature that lets an app keep working with the screen off, shown by a
notification. It is why the 30-minute test kept streaming with the phone locked.

---

## 6. Devices

**Node**
One PhysDAQ device with a single pulse sensor.

**Hub**
A larger device with two pulse sensors and an SD card. The Android app reads only
its first sensor (sensor 0); the desktop app reads both, as two body positions.

**Deep sleep**
A node switches itself off (about 0.4 µA) after 10 s with no motion above about
0.1 rad/s, no skin contact and nothing connected. Moving it wakes it with a full
restart. While asleep its USB port and Bluetooth link both disappear, even if the
cable is plugged in. The delay is `CONFIG_PHYSDAQ_IDLE_TIMEOUT_SEC` (5–300 s);
see [hardware.md](hardware.md).

**Firmware**
The software that runs on the device itself. This project leaves it unchanged.

**Watchdog**
A hardware timer that resets the device if its software freezes. A node that
keeps rebooting may be tripping it.

**`ID` line**
The first line a device sends after connecting. It states its model, firmware
version and sensor count. The app uses it, not the name, to know what it is.

---

## 7. Recordings and analysis

**CSV**
A plain text table. One row per sample, one column per value.

**Columns**
`timestamp` (seconds since Record was pressed), `red`, `ir` (raw counts),
`ax ay az` (m/s^2), `gx gy gz` (rad/s).

**Schema**
The exact list and order of columns a file has. The desktop app's session files
and the analysis program disagree on it; the Android app writes the one the
analysis program expects. See [protocol.md](protocol.md).

**Timestamp**
The moment the phone received each reading, taken from the phone's own steady
clock. Not the time the sensor measured it, which is why Bluetooth delay shows
up as jitter.

**Jitter**
Small unevenness in when readings arrive. Here the typical error is about 12 ms.
It does not affect heart rate but it limits HRV.

**Gap / dropout**
A period with no data. In the 30-minute test the longest was 124 ms.

**Drift**
A slow change over time, here the sample rate between the start and the end of
a recording. It was +0.01 %: effectively none.

**Offline pipeline (`analysis/pipeline.py`)**
The Python program that cleans a recording and finds beats, heart rate and SpO2
after the fact. It now works out the sample rate from the file's timestamps.

**Bridge (`scripts/bridge.py`)**
The Python program the desktop app runs to read each device. The Android app
talks to the devices directly and does not need it.

---

## 8. Two-device timing

**Synchronisation (sync)**
Whether two devices' recordings line up in time. Each device's data reaches the
phone with its own delay, so the same event can appear at slightly different
times in the two files.

**Shared clock / time origin**
Both devices' timestamps are measured from the same moment, the Record press, so
their files can be compared.

**Lag**
How far one device's timeline is behind the other's, in milliseconds.

**Cross-correlation**
A way to find the lag: slide one signal against the other until they match best.
Used on two fingers' pulse waves to estimate how well two recordings line up.

**Tap test**
The device-only timing check: hold two devices together and tap them on a desk.
Each tap is the same instant for both, so the difference between tap times in the
two files is the timing error. `android/tools/sync_check.py` measures it.

---

## 9. App and build terms

**Kotlin**
The programming language the app is written in.

**Jetpack Compose**
Android's toolkit for building the screen in code. The charts redraw on their
own timer so the screen does not rebuild for every sample.

**Gradle**
The build tool that compiles the app and runs the tests (`gradlew test`).

**Unit test**
A small automatic check of one piece of the code. They run on the PC without a
phone.

**Manifest (`AndroidManifest.xml`)**
The file where the app declares its name, icon and the permissions it needs.

**Permissions (Nearby devices)**
What Android makes you allow before an app may scan for and connect to
Bluetooth devices.

---

## 10. Recording words

**Body position**
Where a device is worn: Ear / Head, Chest, Left/Right Wrist, Left/Right Finger,
Left/Right Ankle. The phone remembers each device's position.

**Position slug**
The short name of a position used in file names: `head`, `chest`, `wrist-left`,
`wrist-right`, `finger-left`, `finger-right`, `ankle-left`, `ankle-right`.
Hyphens only, so it is always the part after the last underscore.

**Session**
One recording take: every device recorded at once, sharing one start time. On the
phone each device gets its own file, and the files share the same timestamp and
session name, for example
`2026-10-22_12-17-44_PhysDAQ_Session_finger-left.csv`.

**Session name**
The name typed before recording (default `PhysDAQ_Session`). It goes in the file
names.

**Library**
The phone app's tab that lists every session on the phone and shows statistics
and charts for each.

**Download/PhysDAQ (MediaStore)**
Where the phone saves recordings. MediaStore is Android's shared-files system; it
needs no storage permission, but an app can only list the files it created, so
recordings made before a reinstall must be opened with the file picker.

**Worn %**
Share of samples with IR above the contact threshold (5000), shown per recording.

**Spread (Compare nodes)**
At each second, the highest heart rate across nodes minus the lowest. The median
spread says how closely the nodes agree; the worst spread usually marks a moment of
movement.

**Sleep hint**
The amber "Device may have gone to sleep" line on the phone after a status 8 drop.

**Pre-record check**
The list of linked positions that are not worn or not connected, shown when Record
is pressed.
