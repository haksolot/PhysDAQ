#!/usr/bin/env python3
"""Interactive data explorer for MAID sensor logs.

Loads a raw or enriched CSV and displays linked, pannable/zoomable plots.
Auto-detects the enriched and beats counterparts from any file in the log set.

Usage:
    make explore FILE=logs/2026-06-15_10-49-03_enriched.csv
    python analysis/explore.py logs/2026-06-15_10-49-03.csv

Controls:
    Mouse drag    : pan
    Scroll wheel  : zoom
    Right-click   : reset view / options
    A             : auto-fit all axes
"""

import sys
from pathlib import Path
import numpy as np

try:
    import pandas as pd
except ImportError:
    print("ERROR: pandas not installed.  pip install pandas")
    sys.exit(1)

try:
    import pyqtgraph as pg
    from pyqtgraph.Qt import QtCore, QtWidgets
except ImportError:
    print("ERROR: pyqtgraph not installed.  pip install pyqtgraph PyQt6")
    sys.exit(1)

COLORS = {
    "red":   "#f38ba8",
    "ir":    "#cba6f7",
    "gx":    "#f38ba8",
    "gy":    "#a6e3a1",
    "gz":    "#89b4fa",
    "roll":  "#f38ba8",
    "pitch": "#a6e3a1",
    "yaw":   "#89b4fa",
    "mag":      "#fab387",
    "bpm":      "#a6e3a1",
    "spectral": "#f9e2af",
    "rmssd":    "#89b4fa",
    "beat":     "#a6e3a1",
}


# ── File loading ──────────────────────────────────────────────────────────────

def load_files(path_arg):
    p    = Path(path_arg)
    if not p.exists():
        print(f"File not found: {p}")
        sys.exit(1)

    base = p.stem.removesuffix("_enriched").removesuffix("_beats")
    dir_ = p.parent

    enriched_path  = dir_ / f"{base}_enriched.csv"
    beats_path     = dir_ / f"{base}_beats.csv"
    spectral_path  = dir_ / f"{base}_bpm_spectral.csv"
    raw_path       = dir_ / f"{base}.csv"

    if enriched_path.exists():
        df    = pd.read_csv(enriched_path)
        label = enriched_path.name
    elif raw_path.exists():
        df    = pd.read_csv(raw_path)
        label = raw_path.name
    else:
        df    = pd.read_csv(p)
        label = p.name

    beats_df    = pd.read_csv(beats_path)    if beats_path.exists()    else None
    spectral_df = pd.read_csv(spectral_path) if spectral_path.exists() else None
    return df, beats_df, spectral_df, label


# ── Plot helpers ──────────────────────────────────────────────────────────────

def new_plot(glw, row, title, y_label, ref=None, last=False):
    p = glw.addPlot(row=row, col=0, title=title)
    p.showGrid(x=True, y=True, alpha=0.2)
    p.setLabel("left", y_label)
    if last:
        p.setLabel("bottom", "temps (s)")
    else:
        p.hideAxis("bottom")
    if ref is not None:
        p.setXLink(ref)
    return p


def shade_regions(plot, t, mask, brush):
    """Add transparent regions where mask == 1."""
    m       = mask.astype(bool)
    padded  = np.concatenate([[False], m, [False]])
    edges   = np.diff(padded.astype(int))
    starts  = np.where(edges ==  1)[0]
    ends    = np.where(edges == -1)[0]
    for s, e in zip(starts, ends):
        region = pg.LinearRegionItem(
            [float(t[min(s, len(t)-1)]), float(t[min(e, len(t)-1)])],
            movable=False, brush=brush, pen=pg.mkPen(None)
        )
        plot.addItem(region)


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    if len(sys.argv) < 2:
        print(f"Usage: python {sys.argv[0]} <logfile.csv>")
        print("       make explore FILE=logs/....csv")
        sys.exit(1)

    df, beats_df, spectral_df, label = load_files(sys.argv[1])
    df = df.apply(pd.to_numeric, errors="coerce")

    t            = df["timestamp"].to_numpy()
    N            = len(t)
    duration     = float(t[-1] - t[0])
    has_enriched = "red_filt" in df.columns
    has_contact  = "contact" in df.columns
    has_beats    = beats_df is not None and not beats_df.empty
    has_spectral = spectral_df is not None and not spectral_df.empty

    beats_df    = beats_df.apply(pd.to_numeric, errors="coerce")    if has_beats    else None
    spectral_df = spectral_df.apply(pd.to_numeric, errors="coerce") if has_spectral else None

    print(f"Loaded : {label}  ({N} samples, {duration:.1f} s)")
    if has_enriched:
        print("  Colonnes enrichies détectées (PPG filtré, orientation, motion)")
    if has_contact and not df["contact"].any():
        print("  ATTENTION : aucun contact peau détecté sur tout l'enregistrement")
    if has_beats:
        print(f"  Fichier beats : {len(beats_df)} battements")
    if has_spectral:
        print(f"  Piste BPM spectrale : {len(spectral_df)} fenêtres")

    app = QtWidgets.QApplication(sys.argv)
    pg.setConfigOptions(antialias=True, background="#1e1e2e", foreground="#cdd6f4")

    win = QtWidgets.QWidget()
    win.setWindowTitle(f"MAID Explorer — {label}")
    win.resize(1400, 950)

    root = QtWidgets.QVBoxLayout(win)
    root.setContentsMargins(8, 8, 8, 4)
    root.setSpacing(4)

    # Info bar
    beats_info = f"  |   {len(beats_df)} battements" if has_beats else ""
    info = QtWidgets.QLabel(
        f"  {label}   |   {N} samples   |   {duration:.1f} s"
        f"   |   {'enriched' if has_enriched else 'raw'}{beats_info}"
    )
    info.setStyleSheet(
        "color:#cdd6f4; background:#313244; border-radius:6px;"
        "padding:4px 12px; font-family:monospace; font-size:12px;"
    )
    root.addWidget(info)

    glw = pg.GraphicsLayoutWidget()
    root.addWidget(glw)

    # Build panel list to know which is last
    panels = ["ppg_raw"]
    if has_enriched:
        panels += ["ppg_filt", "orientation", "motion"]
    if has_beats or has_spectral:
        panels.insert(panels.index("ppg_filt") + 1 if "ppg_filt" in panels else 1, "bpm")
    panels.append("gyro")

    ref = None
    row = 0

    for panel in panels:
        is_last = (panel == panels[-1])

        # ── PPG Raw ────────────────────────────────────────────────────────
        if panel == "ppg_raw":
            p = new_plot(glw, row, "PPG — brut (ADC 18-bit)", "ADC counts",
                         ref, last=is_last)
            ref = p
            leg = p.addLegend(offset=(10, 10))
            p.plot(t, df["red"].to_numpy(), pen=pg.mkPen(COLORS["red"], width=1),
                   name="Red")
            p.plot(t, df["ir"].to_numpy(),  pen=pg.mkPen(COLORS["ir"],  width=1),
                   name="IR")
            if has_contact:
                shade_regions(p, t, 1 - df["contact"].to_numpy(),
                              pg.mkBrush(140, 140, 140, 60))

        # ── PPG Filtré + marqueurs battements ──────────────────────────────
        elif panel == "ppg_filt":
            p = new_plot(glw, row, "PPG — filtré 0.5–8 Hz", "ADC counts",
                         ref, last=is_last)
            p.addLegend(offset=(10, 10))
            p.plot(t, df["red_filt"].to_numpy(),
                   pen=pg.mkPen(COLORS["red"], width=1.5), name="Red filtré")
            p.plot(t, df["ir_filt"].to_numpy(),
                   pen=pg.mkPen(COLORS["ir"],  width=1.5), name="IR filtré")
            if has_beats:
                for bt in beats_df["beat_time"].dropna().to_numpy():
                    p.addItem(pg.InfiniteLine(
                        pos=float(bt), angle=90,
                        pen=pg.mkPen(COLORS["beat"], width=1,
                                     style=QtCore.Qt.PenStyle.DashLine)
                    ))

        # ── BPM + RMSSD ────────────────────────────────────────────────────
        elif panel == "bpm":
            p = new_plot(glw, row, "BPM  +  RMSSD HRV (ms)", "valeur",
                         ref, last=is_last)
            p.addLegend(offset=(10, 10))
            if has_spectral:
                # Sliding-window FFT estimate — motion-robust and far less
                # jittery than per-beat peak picking; the stable reference.
                p.plot(spectral_df["time_s"].to_numpy(),
                       spectral_df["bpm"].to_numpy(),
                       pen=pg.mkPen(COLORS["spectral"], width=2),
                       name="BPM spectral")
            if has_beats:
                bt       = beats_df["beat_time"].dropna().to_numpy().astype(float)
                bpm_vals = beats_df["bpm"].to_numpy().astype(float)
                p.plot(bt, bpm_vals, pen=None,
                       symbol="o", symbolSize=7,
                       symbolBrush=COLORS["bpm"], symbolPen=None,
                       name="BPM (battement)")
                if "rmssd_ms" in beats_df.columns:
                    rmssd = beats_df["rmssd_ms"].to_numpy().astype(float)
                    mask  = ~np.isnan(rmssd)
                    if mask.any():
                        p.plot(bt[mask], rmssd[mask],
                               pen=pg.mkPen(COLORS["rmssd"], width=2),
                               name="RMSSD (ms)")

        # ── Gyroscope ──────────────────────────────────────────────────────
        elif panel == "gyro":
            p = new_plot(glw, row, "Gyroscope (rad/s)", "rad/s",
                         ref, last=is_last)
            p.addLegend(offset=(10, 10))
            p.plot(t, df["gx"].to_numpy(), pen=pg.mkPen(COLORS["gx"], width=1), name="GX")
            p.plot(t, df["gy"].to_numpy(), pen=pg.mkPen(COLORS["gy"], width=1), name="GY")
            p.plot(t, df["gz"].to_numpy(), pen=pg.mkPen(COLORS["gz"], width=1), name="GZ")

        # ── Orientation ────────────────────────────────────────────────────
        elif panel == "orientation":
            p = new_plot(glw, row, "Orientation Madgwick (°)", "degrés",
                         ref, last=is_last)
            p.addLegend(offset=(10, 10))
            p.plot(t, df["roll_deg"].to_numpy(),
                   pen=pg.mkPen(COLORS["roll"],  width=1.5), name="Roll")
            p.plot(t, df["pitch_deg"].to_numpy(),
                   pen=pg.mkPen(COLORS["pitch"], width=1.5), name="Pitch")
            p.plot(t, df["yaw_deg"].to_numpy(),
                   pen=pg.mkPen(COLORS["yaw"],   width=1.5), name="Yaw")

        # ── Motion / activité ──────────────────────────────────────────────
        elif panel == "motion":
            p = new_plot(glw, row, "Activité — |accel dynamique|", "g",
                         ref, last=is_last)
            p.addLegend(offset=(10, 10))
            p.plot(t, df["accel_mag_g"].to_numpy(),
                   pen=pg.mkPen(COLORS["mag"], width=1.5), name="|accel dyn| (g)")
            shade_regions(p, t, df["motion"].to_numpy(), pg.mkBrush(255, 80, 80, 45))

        row += 1

    win.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
