#!/usr/bin/env python3
"""Offline processing pipeline for MAID sensor data.

Takes a raw logger CSV and produces two enriched files:

  <stem>_enriched.csv  — per-sample derived signals and orientation
  <stem>_beats.csv     — per-heartbeat metrics

Per-sample columns added:
  red_filt, ir_filt       — bandpass-filtered PPG (0.5–8 Hz, AC / pulse signal)
  ir_clean, red_clean      — red_filt/ir_filt with motion artifact regressed out
  red_dc,   ir_dc         — low-pass baseline (DC component)
  roll_deg, pitch_deg, yaw_deg — Madgwick orientation (degrees)
  qw, qx, qy, qz          — orientation quaternion
  accel_mag_g              — dynamic acceleration magnitude (g), gravity removed
  motion                   — 1 if accel_mag_g > threshold (motion artifact risk)
  contact                  — 1 if the IR DC level indicates skin contact
                              (see CONTACT_IR_MIN — open air reads ~100-300
                              counts on this hardware, skin contact ~29000+)

Per-beat columns:
  beat_time   — timestamp of the peak (s)
  ibi_ms      — inter-beat interval (ms)
  bpm         — instantaneous heart rate (BPM)
  rmssd_ms    — HRV short-term metric over last 5 beats (ms)
  spo2_pct    — SpO2 estimate via Red/IR ratio model (%)
  motion_artifact — 1 if any motion detected in the beat window

Beats and spectral BPM windows during periods without skin contact
(see CONTACT_IR_MIN) are dropped entirely, not just flagged — an
unworn sensor has no physiological signal to report.

Usage:
    make process FILE=logs/2026-06-15_14-32.csv
    python analysis/pipeline.py logs/2026-06-15_14-32.csv

Dependencies: pip install numpy scipy pandas imufusion
"""

import sys
import numpy as np
from pathlib import Path

try:
    import pandas as pd
except ImportError:
    print("ERROR: pandas not installed.  pip install pandas")
    sys.exit(1)

try:
    from scipy.signal import butter, sosfiltfilt, find_peaks
except ImportError:
    print("ERROR: scipy not installed.  pip install scipy")
    sys.exit(1)

try:
    import imufusion
except ImportError:
    print("ERROR: imufusion not installed.  pip install imufusion")
    sys.exit(1)

# ── DSP parameters ────────────────────────────────────────────────────────────
FS             = 100.0  # Hz — MAX30102 sample rate
PPG_LOW_HZ     = 0.5   # bandpass lower edge (30 BPM)
PPG_HIGH_HZ    = 8.0   # bandpass upper edge — 8 Hz preserves up to 6th harmonic
                        # at 80 BPM (1.33 Hz × 6 = 8 Hz), giving proper pulse shape
MOTION_HIGH_HZ = 3.5   # accel reference bandpass — only motion band, not cardiac harmonics
PPG_DC_HZ      = 0.1   # low-pass for DC baseline
FILTER_ORDER   = 4

# Beat-detection band: narrower than the 0.5–8 Hz display band on purpose.
# The wide band keeps harmonics for a realistic pulse *shape*, but those same
# harmonics + the dicrotic notch create secondary peaks that fool find_peaks
# into double-counting beats. Fundamental-only filtering gives one clean
# peak per cardiac cycle for reliable IBI/BPM extraction.
BEAT_LOW_HZ  = 0.7   # 42 BPM
BEAT_HIGH_HZ = 3.0   # 180 BPM

# Adaptive peak threshold: local RMS computed over this window, peaks must
# exceed RMS_HEIGHT_FACTOR × local RMS to count (handles varying contact
# pressure / signal amplitude over a recording instead of one global cutoff).
RMS_WINDOW_S      = 4.0
RMS_HEIGHT_FACTOR = 0.5

# Reject a beat if its IBI deviates from the running median of the last
# IBI_MEDIAN_WINDOW valid beats by more than IBI_MAX_DEVIATION — catches
# residual false/missed peaks that slip past the amplitude gate.
IBI_MEDIAN_WINDOW = 5
IBI_MAX_DEVIATION = 0.3

# spectral_bpm(): skip a window rather than report a bin if the motion-
# penalised peak isn't clearly above the noise floor (no finger / signal
# swamped by motion) — avoids locking onto the band edge, e.g. 210 BPM.
SPECTRAL_SNR_MIN = 2.0

# Skin-contact detection: the MAX30102 IR photodiode reads a near-baseline
# DC level with nothing in front of it (only ambient light) and a much
# higher one once skin is backscattering the LED. Measured on this hardware
# (6.2 mA LED, see CLAUDE.md): ~100-300 counts in open air vs ~29000+ with
# a finger on the sensor — a comfortable 2 orders of magnitude apart, so a
# single fixed threshold is reliable here. If you change LED current/gain
# in prj.conf, recheck with `make term` (open air vs finger) and adjust.
CONTACT_IR_MIN  = 5000   # raw ADC counts
CONTACT_DC_HZ   = 2.0    # lowpass for the contact-detection DC estimate —
                          # faster than PPG_DC_HZ so contact loss is caught
                          # within ~1 cardiac cycle instead of ~10 s

# Motion detection: dynamic accel magnitude threshold (g, gravity removed)
MOTION_THRESH_G = 0.05

# SpO2 linear model: SpO2 = A - B * R  (Mendelson empirical approximation)
# R = (AC_red/DC_red) / (AC_ir/DC_ir)
SPO2_A = 110.0
SPO2_B = 25.0

# HRV RMSSD: number of consecutive IBI pairs used per beat
RMSSD_WINDOW = 5


# ── Filter helpers ─────────────────────────────────────────────────────────────
def bandpass(sig, low, high, fs=FS, order=FILTER_ORDER):
    sos = butter(order, [low, high], btype="band", fs=fs, output="sos")
    return sosfiltfilt(sos, sig)


def lowpass(sig, cutoff, fs=FS, order=FILTER_ORDER):
    sos = butter(order, cutoff, btype="low", fs=fs, output="sos")
    return sosfiltfilt(sos, sig)


def motion_cancel(ppg_filt, accel_xyz_filt, order=8):
    """Wiener-optimal motion artifact removal via least-squares regression.

    Models the motion artifact as a linear combination of `order` delayed
    samples of each accel axis and subtracts the optimal estimate from ppg.
    Both inputs must already be bandpass-filtered to the PPG band.

    order=8 @ 100 Hz covers 80 ms of motion lag — sufficient for wrist motion.
    """
    N    = len(ppg_filt)
    cols = []
    for ch in range(3):
        for d in range(order):
            col = np.roll(accel_xyz_filt[:, ch], d)
            col[:d] = 0.0
            cols.append(col)
    X       = np.column_stack(cols)         # (N, 3*order)
    coeffs, _, _, _ = np.linalg.lstsq(X, ppg_filt, rcond=None)
    return ppg_filt - X @ coeffs


def _parabolic_peak(spec, k):
    """Quadratic interpolation around bin k for sub-bin peak accuracy —
    turns the raw FFT bin spacing (e.g. 7.5 BPM at an 8 s window) into a
    much finer-grained estimate instead of a stair-stepped one."""
    if k <= 0 or k >= len(spec) - 1:
        return float(k)
    y0, y1, y2 = spec[k - 1], spec[k], spec[k + 1]
    denom = y0 - 2 * y1 + y2
    if denom == 0:
        return float(k)
    offset = 0.5 * (y0 - y2) / denom
    return k + float(np.clip(offset, -0.5, 0.5))


def spectral_bpm(signal, accel_mag, contact, fs=FS, window_s=8.0, step_s=2.0):
    """Sliding-window FFT heart rate estimator (motion-robust).

    Suppresses FFT bins where the accelerometer spectrum dominates so the
    selected peak corresponds to the cardiac frequency, not motion. Windows
    without a clear cardiac peak (no finger, or motion swamping the signal)
    are skipped rather than reported — without this gate the argmax can
    lock onto the band edge (e.g. a flat 210 BPM ceiling) on pure noise.
    Windows mostly out of skin contact are skipped outright.

    Returns (times_s, bpms) arrays aligned to window centres.
    """
    win  = int(window_s * fs)
    step = int(step_s  * fs)
    N    = len(signal)
    if N < win:
        return np.array([]), np.array([])

    freqs = np.fft.rfftfreq(win, 1.0 / fs)
    band  = (freqs >= PPG_LOW_HZ) & (freqs <= BEAT_HIGH_HZ)
    band_idx = np.where(band)[0]
    hann  = np.hanning(win)

    times, bpms = [], []
    for start in range(0, N - win, step):
        if contact[start:start + win].mean() < 0.5:
            continue  # mostly no skin contact in this window — nothing to estimate

        ppg_spec   = np.abs(np.fft.rfft(signal[start:start + win]    * hann))
        accel_spec = np.abs(np.fft.rfft(accel_mag[start:start + win] * hann))

        ppg_spec   /= (ppg_spec[band].max()   + 1e-10)
        accel_spec /= (accel_spec[band].max() + 1e-10)

        # Penalise bins dominated by motion before peak selection
        score          = np.clip(ppg_spec - 0.5 * accel_spec, 0, None)
        band_score     = score[band_idx]
        peak_score     = band_score.max()
        if peak_score < 1e-6 or peak_score < SPECTRAL_SNR_MIN * (np.median(band_score) + 1e-9):
            continue  # no dominant cardiac peak this window — skip, don't guess

        k        = band_idx[np.argmax(band_score)]
        bin_freq = _parabolic_peak(ppg_spec, k) * (freqs[1] - freqs[0])

        times.append((start + win // 2) / fs)
        bpms.append(bin_freq * 60.0)

    return np.array(times), np.array(bpms)


# ── Madgwick orientation ──────────────────────────────────────────────────────
def run_madgwick(df):
    """Returns (quats N×4 [w,x,y,z], eulers N×3 [roll,pitch,yaw] degrees)."""
    ahrs = imufusion.Ahrs()
    try:
        ahrs.settings = imufusion.Settings(
            imufusion.Convention.NWU, 0.5, 2000, 10, 10, int(5 * FS)
        )
    except Exception:
        pass

    update_fn = next(
        (m for m in ("update_no_magnet", "update_no_magnetometer", "update_imu")
         if hasattr(ahrs, m)),
        None,
    )
    if update_fn is None:
        raise RuntimeError("imufusion API not recognized — pip install --upgrade imufusion")

    N        = len(df)
    quats    = np.zeros((N, 4))
    eulers   = np.zeros((N, 3))
    ts       = df["timestamp"].to_numpy()
    dt_arr   = np.diff(ts, prepend=ts[0])
    dt_arr[0] = 1.0 / FS
    accel_g  = df[["ax", "ay", "az"]].to_numpy() / 9.81
    gyro_deg = df[["gx", "gy", "gz"]].to_numpy() * (180.0 / np.pi)

    for i in range(N):
        getattr(ahrs, update_fn)(gyro_deg[i], accel_g[i], max(float(dt_arr[i]), 1e-4))
        q        = ahrs.quaternion
        w, x, y, z = q.w, q.x, q.y, q.z
        quats[i] = [w, x, y, z]

        # Quaternion → Euler (intrinsic ZYX / NWU convention), degrees
        roll  = np.degrees(np.arctan2(2*(w*x + y*z), 1 - 2*(x*x + y*y)))
        pitch = np.degrees(np.arcsin(np.clip(2*(w*y - z*x), -1, 1)))
        yaw   = np.degrees(np.arctan2(2*(w*z + x*y), 1 - 2*(y*y + z*z)))
        eulers[i] = [roll, pitch, yaw]

    return quats, eulers


def rolling_rms(sig, window_s, fs=FS):
    """Centered rolling RMS, used as a local amplitude reference for
    adaptive peak detection (handles drifting signal amplitude)."""
    win = max(int(window_s * fs), 1)
    s   = pd.Series(sig ** 2)
    return np.sqrt(s.rolling(win, center=True, min_periods=1).mean().to_numpy())


def compute_contact(ir_raw, fs=FS):
    """Skin-contact mask from the raw IR DC level — see CONTACT_IR_MIN."""
    dc = lowpass(ir_raw, CONTACT_DC_HZ, fs=fs)
    return dc > CONTACT_IR_MIN


# ── Beat detection & per-beat metrics ─────────────────────────────────────────
def compute_beats(ts, ir_beat, ir_filt, red_filt, ir_dc, red_dc, motion, contact):
    """Detect heartbeats on the narrowband `ir_beat` signal (fundamental
    only — robust against harmonics/dicrotic notch), then pull SpO2/AC
    amplitude from the wideband `ir_filt`/`red_filt` over each beat's
    segment (those need the full pulse shape, not just the fundamental).
    Peaks outside skin contact (see `contact`) are discarded up front —
    an unworn sensor has no heartbeat to find."""
    min_dist = int(FS * 0.33)             # minimum 0.33 s between peaks (max 180 BPM)
    height   = RMS_HEIGHT_FACTOR * rolling_rms(ir_beat, RMS_WINDOW_S)

    peaks, _ = find_peaks(ir_beat, height=height, distance=min_dist)
    peaks    = peaks[contact[peaks]]
    if len(peaks) < 2:
        return pd.DataFrame()

    rows     = []
    ibi_hist = []

    for i in range(1, len(peaks)):
        p0, p1 = peaks[i - 1], peaks[i]
        ibi_ms = (ts[p1] - ts[p0]) * 1000.0

        if not (250 < ibi_ms < 2000):     # keep 30–240 BPM range
            continue

        # Reject IBIs that jump too far from the recent running median —
        # catches stray peaks the amplitude gate let through.
        if len(ibi_hist) >= 3:
            median_ibi = float(np.median(ibi_hist[-IBI_MEDIAN_WINDOW:]))
            if abs(ibi_ms - median_ibi) > IBI_MAX_DEVIATION * median_ibi:
                continue

        bpm = 60_000.0 / ibi_ms
        ibi_hist.append(ibi_ms)

        # RMSSD over last RMSSD_WINDOW IBI pairs
        rmssd = float("nan")
        if len(ibi_hist) >= 2:
            w     = np.array(ibi_hist[-RMSSD_WINDOW:])
            rmssd = float(np.sqrt(np.mean(np.diff(w) ** 2)))

        # SpO2 over the inter-beat segment
        seg    = slice(p0, p1 + 1)
        ac_red = float(np.ptp(red_filt[seg]))
        ac_ir  = float(np.ptp(ir_filt[seg]))
        dc_red = float(np.mean(red_dc[seg]))
        dc_ir  = float(np.mean(ir_dc[seg]))
        spo2   = float("nan")
        if dc_red > 0 and dc_ir > 0 and ac_ir > 0:
            R    = (ac_red / dc_red) / (ac_ir / dc_ir)
            spo2 = float(np.clip(SPO2_A - SPO2_B * R, 80.0, 100.0))

        artifact = bool(np.any(motion[seg]))

        rows.append({
            "beat_time":       round(float(ts[p1]), 4),
            "ibi_ms":          round(ibi_ms, 1),
            "bpm":             round(bpm, 1),
            "rmssd_ms":        round(rmssd, 2) if not np.isnan(rmssd) else "",
            "spo2_pct":        round(spo2, 1)  if not np.isnan(spo2)  else "",
            "motion_artifact": int(artifact),
        })

    return pd.DataFrame(rows)


# ── Main ──────────────────────────────────────────────────────────────────────
def main():
    if len(sys.argv) < 2:
        print(f"Usage: python {sys.argv[0]} <logfile.csv>")
        print("       make process FILE=logs/2026-06-15_14-32.csv")
        sys.exit(1)

    in_path = Path(sys.argv[1])
    if not in_path.exists():
        print(f"File not found: {in_path}")
        sys.exit(1)

    print(f"Loading {in_path} ...")
    df = pd.read_csv(in_path).astype(float)
    N  = len(df)
    print(f"  {N} samples  (~{N / FS:.1f} s)")

    if N < int(FS * 5):
        print("WARNING: less than 5 s of data — beat detection may be unreliable")

    # ── PPG filtering ──────────────────────────────────────────────────────────
    print("Filtering PPG...")
    red_raw  = df["red"].to_numpy()
    ir_raw   = df["ir"].to_numpy()
    red_filt = bandpass(red_raw, PPG_LOW_HZ, PPG_HIGH_HZ)
    ir_filt  = bandpass(ir_raw,  PPG_LOW_HZ, PPG_HIGH_HZ)
    red_dc   = lowpass(red_raw,  PPG_DC_HZ)
    ir_dc    = lowpass(ir_raw,   PPG_DC_HZ)
    contact  = compute_contact(ir_raw)
    if not contact.any():
        print("WARNING: no skin contact detected anywhere in this recording "
              "— was the sensor worn?")

    # ── IMU orientation ────────────────────────────────────────────────────────
    print("Running Madgwick filter...")
    quats, eulers = run_madgwick(df)

    # ── Motion detection (gravity-removed accel magnitude) ─────────────────────
    accel_g   = df[["ax", "ay", "az"]].to_numpy() / 9.81
    gravity   = np.column_stack([lowpass(accel_g[:, k], 0.1) for k in range(3)])
    dynamic_g = accel_g - gravity
    accel_mag = np.linalg.norm(dynamic_g, axis=1)
    motion    = accel_mag > MOTION_THRESH_G

    # ── Motion artifact removal ────────────────────────────────────────────────
    print("Removing motion artifacts (Wiener regression)...")
    # Accel reference band-limited to motion frequencies only (not cardiac harmonics)
    # to avoid regressing out the heartbeat signal itself.
    accel_filt = np.column_stack([
        bandpass(accel_g[:, k], PPG_LOW_HZ, MOTION_HIGH_HZ) for k in range(3)
    ])
    ir_clean  = motion_cancel(ir_filt,  accel_filt)
    red_clean = motion_cancel(red_filt, accel_filt)

    # ── Spectral BPM track (motion-robust FFT) ─────────────────────────────────
    print("Computing spectral BPM track...")
    bpm_times, bpm_vals = spectral_bpm(ir_clean, accel_mag, contact)

    # ── Beat metrics (fundamental-only signal for peak picking) ───────────────
    print("Detecting heartbeats...")
    ts      = df["timestamp"].to_numpy()
    ir_beat = bandpass(ir_clean, BEAT_LOW_HZ, BEAT_HIGH_HZ)
    beats_df = compute_beats(ts, ir_beat, ir_clean, red_clean, ir_dc, red_dc, motion, contact)

    if not beats_df.empty:
        mean_bpm = beats_df["bpm"].mean()
        clean    = beats_df[beats_df["motion_artifact"] == 0]
        print(f"  {len(beats_df)} beats  —  avg BPM (peak): {mean_bpm:.1f}"
              f"  ({len(clean)} clean / {len(beats_df) - len(clean)} with motion artifact)")
        if not clean.empty and clean["spo2_pct"].replace("", float("nan")).notna().any():
            spo2_vals = pd.to_numeric(clean["spo2_pct"], errors="coerce").dropna()
            if not spo2_vals.empty:
                print(f"  SpO2 (clean beats): {spo2_vals.mean():.1f}%  "
                      f"[{spo2_vals.min():.1f}–{spo2_vals.max():.1f}]")
    else:
        print("  No beats detected — was the finger on the sensor during recording?")

    if len(bpm_vals) > 0:
        print(f"  Spectral BPM: {bpm_vals.mean():.1f} avg  "
              f"[{bpm_vals.min():.1f}–{bpm_vals.max():.1f}]  "
              f"over {len(bpm_vals)} windows")

    # ── Build enriched per-sample CSV ──────────────────────────────────────────
    enriched = df.copy()
    enriched["red_filt"]    = np.round(red_filt,      2)
    enriched["ir_filt"]     = np.round(ir_filt,       2)
    enriched["ir_clean"]    = np.round(ir_clean,      2)   # motion-cancelled IR
    enriched["red_clean"]   = np.round(red_clean,     2)   # motion-cancelled Red
    enriched["red_dc"]      = np.round(red_dc,        2)
    enriched["ir_dc"]       = np.round(ir_dc,         2)
    enriched["roll_deg"]    = np.round(eulers[:, 0],  3)
    enriched["pitch_deg"]   = np.round(eulers[:, 1],  3)
    enriched["yaw_deg"]     = np.round(eulers[:, 2],  3)
    enriched["qw"]          = np.round(quats[:, 0],   5)
    enriched["qx"]          = np.round(quats[:, 1],   5)
    enriched["qy"]          = np.round(quats[:, 2],   5)
    enriched["qz"]          = np.round(quats[:, 3],   5)
    enriched["accel_mag_g"] = np.round(accel_mag,     4)
    enriched["motion"]      = motion.astype(int)
    enriched["contact"]     = contact.astype(int)

    # ── Save ───────────────────────────────────────────────────────────────────
    stem          = in_path.stem
    enriched_path = in_path.parent / f"{stem}_enriched.csv"
    beats_path    = in_path.parent / f"{stem}_beats.csv"

    enriched.to_csv(enriched_path, index=False)
    print(f"Saved: {enriched_path}")

    if not beats_df.empty:
        beats_df.to_csv(beats_path, index=False)
        print(f"Saved: {beats_path}")

    if len(bpm_vals) > 0:
        spectral_path = in_path.parent / f"{stem}_bpm_spectral.csv"
        pd.DataFrame({"time_s": np.round(bpm_times, 2),
                      "bpm":    np.round(bpm_vals,  1)}).to_csv(spectral_path, index=False)
        print(f"Saved: {spectral_path}")


if __name__ == "__main__":
    main()
