#!/usr/bin/env python3
"""Freeze scripts/bridge.py into a standalone binary for the desktop app.

The Electron app spawns the bridge as a child process. In development it runs
`.venv/…/python scripts/bridge.py`, but an installed copy on a teammate's
machine has no Python and no venv — so we ship a PyInstaller bundle instead.

Output (staged for electron-builder's `extraResources`):

    app/sidecar/bridge/bridge.exe      Windows
    app/sidecar/bridge/bridge          Linux / macOS
    app/sidecar/bridge/_internal/…     interpreter + numpy/scipy/bleak/…

Usage:
    python scripts/build-sidecar.py           # build, then smoke-test
    python scripts/build-sidecar.py --clean   # discard previous output first

Note: PyInstaller does not cross-compile. Run this on each OS you ship for.
"""

import argparse
import os
import shutil
import subprocess
import sys

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SPEC = os.path.join(REPO_ROOT, "scripts", "bridge.spec")
DIST = os.path.join(REPO_ROOT, "app", "sidecar")
WORK = os.path.join(REPO_ROOT, "build", "pyinstaller")
BIN_NAME = "bridge.exe" if sys.platform == "win32" else "bridge"
FOREIGN_BIN_NAME = "bridge" if sys.platform == "win32" else "bridge.exe"
BIN = os.path.join(DIST, "bridge", BIN_NAME)


def check_pyinstaller() -> None:
    try:
        import PyInstaller  # noqa: F401
    except ImportError:
        print("ERROR: PyInstaller is not installed in this interpreter.")
        print(f"       Interpreter: {sys.executable}")
        print("       Install it:  python -m pip install -r requirements.txt")
        sys.exit(1)


def discard_foreign_bundle() -> None:
    """Wipe a bundle left behind by a build on a different OS.

    `app/sidecar/bridge/` is a single slot with no platform in its path, and
    electron-builder copies whatever is in it. Without this, building for Linux
    on a machine that last built for Windows ships bridge.exe inside the
    AppImage and the installed app dies with "Python sidecar not found".
    PyInstaller would not overwrite the foreign binary — the names differ.
    """
    foreign = os.path.join(DIST, "bridge", FOREIGN_BIN_NAME)
    if os.path.exists(foreign):
        print(f"[build-sidecar] Discarding bundle from another platform: {foreign}")
        shutil.rmtree(os.path.join(DIST, "bridge"))


def build(clean: bool) -> None:
    if clean:
        for path in (DIST, WORK):
            if os.path.isdir(path):
                print(f"[build-sidecar] Removing {path}")
                shutil.rmtree(path)
    else:
        discard_foreign_bundle()

    cmd = [
        sys.executable,
        "-m",
        "PyInstaller",
        "--noconfirm",
        "--distpath",
        DIST,
        "--workpath",
        WORK,
        SPEC,
    ]
    print(f"[build-sidecar] Running: {' '.join(cmd)}")

    # setup-env.ps1 exports a PYTHONPATH for the Zephyr toolchain; leaking it
    # into the build makes PyInstaller analyse the wrong site-packages.
    env = {k: v for k, v in os.environ.items() if k not in ("PYTHONPATH", "PYTHONHOME")}
    subprocess.run(cmd, check=True, cwd=REPO_ROOT, env=env)


def smoke_test() -> None:
    """Run the frozen binary once — a bundle missing a hidden import fails here
    rather than silently inside the packaged app."""
    if not os.path.exists(BIN):
        print(f"ERROR: expected binary not produced: {BIN}")
        sys.exit(1)

    print(f"[build-sidecar] Smoke test: {BIN} --list-ports")
    result = subprocess.run(
        [BIN, "--list-ports"], capture_output=True, text=True, timeout=120
    )
    if result.returncode != 0:
        print("ERROR: frozen bridge failed to run.")
        print(result.stdout)
        print(result.stderr)
        sys.exit(1)
    print(f"[build-sidecar] --list-ports -> {result.stdout.strip()[:200]}")

    print(f"[build-sidecar] Smoke test: {BIN} --scan")
    result = subprocess.run([BIN, "--scan"], capture_output=True, text=True, timeout=120)
    if result.returncode != 0 or '"error"' in result.stdout:
        print("WARNING: BLE scan did not return devices.")
        print(result.stdout.strip())
        print(result.stderr.strip())
        print("         If this reports a missing module, the bundle is broken.")
        print("         If it reports a Bluetooth/adapter error, the bundle is fine.")
    else:
        print(f"[build-sidecar] --scan -> {result.stdout.strip()[:200]}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--clean", action="store_true", help="remove previous output first")
    parser.add_argument("--skip-test", action="store_true", help="skip the smoke test")
    args = parser.parse_args()

    check_pyinstaller()
    build(args.clean)
    if not args.skip_test:
        smoke_test()

    target = {"win32": "build:win", "darwin": "build:mac"}.get(sys.platform, "build:linux")
    print()
    print(f"[build-sidecar] OK: {BIN}")
    print(f"[build-sidecar] Now run (from app/): npm run {target}")


if __name__ == "__main__":
    main()
