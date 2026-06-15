#!/usr/bin/env python3
"""Offline processing pipeline for MAID sensor data.

Takes a raw logger CSV and produces two enriched files:

  <stem>_enriched.csv  — per-sample derived signals and orientation
  <stem>_beats.csv     — per-heartbeat metrics

Per-sample columns added:
  red_filt, ir_filt       — bandpass-filtered PPG (0.5–4 Hz, AC / pulse signal)
  red_dc,   ir_dc         — low-pass baseline (DC component)
  roll_deg, pitch_deg, yaw_deg — Madgwick orientation (degrees)
  qw, qx, qy, qz          — orientation quaternion
  accel_mag_g              — dynamic acceleration magnitude (g), gravity removed
  motion                   — 1 if accel_mag_g > threshold (motion artifact risk)

Per-beat columns:
  beat_time   — timestamp of the peak (s)
  ibi_ms      — inter-beat interval (ms)
  bpm         — instantaneous heart rate (BPM)
  rmssd_ms    — HRV short-term metric over last 5 beats (ms)
  spo2_pct    — SpO2 estimate via Red/IR ratio model (%)
  motion_artifact — 1 if any motion detected in the beat window

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
FS           = 100.0   # Hz — MAX30102 sample rate
PPG_LOW_HZ   = 0.5    # bandpass lower edge (30 BPM)
PPG_HIGH_HZ  = 4.0    # bandpass upper edge (240 BPM)
PPG_DC_HZ    = 0.1    # low-pass for DC baseline
FILTER_ORDER = 4

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
        q         = ahrs.quaternion
        quats[i]  = [q.w, q.x, q.y, q.z]
        e         = ahrs.euler
        eulers[i] = [e.roll, e.pitch, e.yaw]

    return quats, eulers


# ── Beat detection & per-beat metrics ─────────────────────────────────────────
def compute_beats(ts, ir_filt, red_filt, ir_dc, red_dc, motion):
    sig_range  = np.ptp(ir_filt)
    prominence = max(sig_range * 0.10, 10.0)
    min_dist   = int(FS * 0.33)           # minimum 0.33 s between peaks (max 180 BPM)

    peaks, _ = find_peaks(ir_filt, prominence=prominence, distance=min_dist)
    if len(peaks) < 2:
        return pd.DataFrame()

    rows     = []
    ibi_hist = []

    for i in range(1, len(peaks)):
        p0, p1 = peaks[i - 1], peaks[i]
        ibi_ms = (ts[p1] - ts[p0]) * 1000.0

        if not (250 < ibi_ms < 2000):     # keep 30–240 BPM range
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

    # ── IMU orientation ────────────────────────────────────────────────────────
    print("Running Madgwick filter...")
    quats, eulers = run_madgwick(df)

    # ── Motion detection (gravity-removed accel magnitude) ─────────────────────
    accel_g   = df[["ax", "ay", "az"]].to_numpy() / 9.81
    gravity   = np.column_stack([lowpass(accel_g[:, k], 0.1) for k in range(3)])
    dynamic_g = accel_g - gravity
    accel_mag = np.linalg.norm(dynamic_g, axis=1)
    motion    = accel_mag > MOTION_THRESH_G

    # ── Beat metrics ───────────────────────────────────────────────────────────
    print("Detecting heartbeats...")
    ts       = df["timestamp"].to_numpy()
    beats_df = compute_beats(ts, ir_filt, red_filt, ir_dc, red_dc, motion)

    if not beats_df.empty:
        mean_bpm = beats_df["bpm"].mean()
        clean    = beats_df[beats_df["motion_artifact"] == 0]
        print(f"  {len(beats_df)} beats  —  avg BPM: {mean_bpm:.1f}"
              f"  ({len(clean)} clean / {len(beats_df) - len(clean)} with motion artifact)")
        if not clean.empty and clean["spo2_pct"].replace("", float("nan")).notna().any():
            spo2_vals = pd.to_numeric(clean["spo2_pct"], errors="coerce").dropna()
            if not spo2_vals.empty:
                print(f"  SpO2 (clean beats): {spo2_vals.mean():.1f}%  "
                      f"[{spo2_vals.min():.1f}–{spo2_vals.max():.1f}]")
    else:
        print("  No beats detected — was the finger on the sensor during recording?")

    # ── Build enriched per-sample CSV ──────────────────────────────────────────
    enriched = df.copy()
    enriched["red_filt"]    = np.round(red_filt,      2)
    enriched["ir_filt"]     = np.round(ir_filt,       2)
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

    # ── Save ───────────────────────────────────────────────────────────────────
    stem          = in_path.stem
    enriched_path = in_path.parent / f"{stem}_enriched.csv"
    beats_path    = in_path.parent / f"{stem}_beats.csv"

    enriched.to_csv(enriched_path, index=False)
    print(f"Saved: {enriched_path}")

    if not beats_df.empty:
        beats_df.to_csv(beats_path, index=False)
        print(f"Saved: {beats_path}")


if __name__ == "__main__":
    main()
