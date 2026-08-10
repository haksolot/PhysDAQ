#!/usr/bin/env python3
"""Generate the PhysDAQ application icons from the project mark.

The mark (assets/physdaq.svg) is a pixel-art pulse trace made entirely of
axis-aligned rectangles, so it is drawn directly with Pillow rather than
rasterised through an SVG renderer -- neither ImageMagick nor a Node toolchain
is required, only Pillow.

Writes, overwriting in place:
    app/build/icon.ico       Windows executable, taskbar, NSIS installer
    app/build/icon.png       Linux AppImage / deb / snap
    app/build/icon.icns      macOS .app / .dmg
    app/resources/icon.png   runtime BrowserWindow icon (Linux)

electron-builder picks all of these up by convention from
`directories.buildResources: build` -- there are no icon paths in
electron-builder.yml to keep in sync.

Usage:
    python scripts/make-icons.py       (or: make icons)
"""

import re
import struct
from io import BytesIO
from pathlib import Path

from PIL import Image, ImageDraw

# The mark, verbatim from assets/physdaq.svg, in a 24x24 viewBox.
PATH = (
    "M13 22h-2v-2h2v2Zm-2-2H9v-2h2v2Zm4 0h-2v-2h2v2Zm-6-2H7v-2h2v2Zm8 0h-2v-2h2v2Z"
    "M7 16H5v-2h2v2Zm12 0h-2v-2h2v2ZM5 14H3v-2h2v2Zm16 0h-2v-2h2v2ZM3 12H1V6h2v6Z"
    "m20 0h-2V6h2v6ZM13 8h-2V6h2v2ZM5 6H3V4h2v2Zm6 0H9V4h2v2Zm4 0h-2V4h2v2Zm6 0h-2V4h2v2Z"
    "M9 4H5V2h4v2Zm10 0h-4V2h4v2Z"
)
VIEWBOX = 24

# Catppuccin Mocha, matching app/src/renderer/src/assets/main.css.
BACKGROUND = (30, 30, 46, 255)  # --color-background  #1e1e2e
GLYPH = (243, 139, 168, 255)  # --color-destructive #f38ba8

MASTER = 1024  # rendered size before downsampling
SUPERSAMPLE = 4
PADDING = 0.14  # fraction of the canvas left clear around the glyph
CORNER_RADIUS = 0.09  # fraction of the canvas
ICO_SIZES = [16, 32, 48, 64, 128, 256]

ROOT = Path(__file__).resolve().parent.parent


def parse_rects(d):
    """Decompose an M/H/h/V/v/Z path into (x, y, w, h) rectangles.

    Only the subset the mark actually uses is supported; anything curved or
    diagonal raises, rather than being silently mis-drawn.
    """
    rects = []
    x = y = 0.0
    points = []

    for cmd, args in re.findall(r"([MmHhVvZz])([-0-9.\s]*)", d):
        nums = [float(v) for v in args.replace("-", " -").split()]
        if cmd in "Mm":
            if points:
                rects.append(points)
            x, y = (nums[0], nums[1]) if cmd == "M" else (x + nums[0], y + nums[1])
            points = [(x, y)]
        elif cmd == "H":
            x = nums[0]
            points.append((x, y))
        elif cmd == "h":
            x += nums[0]
            points.append((x, y))
        elif cmd == "V":
            y = nums[0]
            points.append((x, y))
        elif cmd == "v":
            y += nums[0]
            points.append((x, y))
        else:  # Z / z
            rects.append(points)
            points = []
    if points:
        rects.append(points)

    out = []
    for pts in rects:
        xs = sorted({round(px, 4) for px, _ in pts})
        ys = sorted({round(py, 4) for _, py in pts})
        if len(xs) != 2 or len(ys) != 2:
            raise ValueError(f"subpath is not an axis-aligned rectangle: {pts}")
        out.append((xs[0], ys[0], xs[1] - xs[0], ys[1] - ys[0]))
    return out


def render(size):
    """Draw the icon at `size` px, supersampled then downsampled."""
    rects = parse_rects(PATH)
    canvas = size * SUPERSAMPLE

    img = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw.rounded_rectangle(
        [0, 0, canvas - 1, canvas - 1],
        radius=canvas * CORNER_RADIUS,
        fill=BACKGROUND,
    )

    # The glyph's own bounding box is inset within the 24-unit viewBox, so map
    # that box -- not the viewBox -- onto the padded area, or the mark floats.
    gx0 = min(r[0] for r in rects)
    gy0 = min(r[1] for r in rects)
    gx1 = max(r[0] + r[2] for r in rects)
    gy1 = max(r[1] + r[3] for r in rects)
    span = max(gx1 - gx0, gy1 - gy0)

    scale = canvas * (1 - 2 * PADDING) / span
    ox = (canvas - (gx1 - gx0) * scale) / 2
    oy = (canvas - (gy1 - gy0) * scale) / 2

    for rx, ry, rw, rh in rects:
        x0 = ox + (rx - gx0) * scale
        y0 = oy + (ry - gy0) * scale
        draw.rectangle([x0, y0, x0 + rw * scale - 1, y0 + rh * scale - 1], fill=GLYPH)

    return img.resize((size, size), Image.LANCZOS)


def write_icns(path, master):
    """Write a minimal ICNS container of PNG entries.

    Pillow can only save ICNS on macOS (it shells out to iconutil), and the
    format is trivial: a magic + length header followed by OSType-tagged
    chunks. Sizes below are the standard PNG-capable types.
    """
    types = {
        b"icp4": 16,
        b"icp5": 32,
        b"ic07": 128,
        b"ic08": 256,
        b"ic09": 512,
        b"ic10": 1024,
    }
    chunks = b""
    for ostype, size in types.items():
        buf = BytesIO()
        master.resize((size, size), Image.LANCZOS).save(buf, format="PNG")
        data = buf.getvalue()
        chunks += ostype + struct.pack(">I", len(data) + 8) + data
    path.write_bytes(b"icns" + struct.pack(">I", len(chunks) + 8) + chunks)


def main():
    master = render(MASTER)

    targets = {
        ROOT / "app" / "build" / "icon.png": 512,
        ROOT / "app" / "resources" / "icon.png": 512,
    }
    for path, size in targets.items():
        master.resize((size, size), Image.LANCZOS).save(path, format="PNG")
        print(f"wrote {path.relative_to(ROOT)} ({size}x{size})")

    ico = ROOT / "app" / "build" / "icon.ico"
    master.save(ico, format="ICO", sizes=[(s, s) for s in ICO_SIZES])
    print(f"wrote {ico.relative_to(ROOT)} ({', '.join(str(s) for s in ICO_SIZES)})")

    icns = ROOT / "app" / "build" / "icon.icns"
    write_icns(icns, master)
    print(f"wrote {icns.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
