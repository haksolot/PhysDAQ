# -*- mode: python ; coding: utf-8 -*-
"""PyInstaller spec for the PhysDAQ desktop-app sidecar.

Freezes scripts/bridge.py — together with numpy, scipy, bleak, imufusion and
pyserial — into a standalone `bridge` binary so the Electron app can run on a
machine with no Python installed.

Build it through scripts/build-sidecar.py (or `make sidecar`) rather than
calling pyinstaller directly: that wrapper sets the output paths the
electron-builder config expects.
"""

import os
import sys

from PyInstaller.utils.hooks import collect_all, collect_submodules

REPO_ROOT = os.path.abspath(os.path.join(SPECPATH, os.pardir))
ENTRY = os.path.join(REPO_ROOT, 'scripts', 'bridge.py')

hiddenimports = ['serial.tools.list_ports']
binaries = []
datas = []


def _collect(package):
    """collect_all() that tolerates a package not being installed."""
    global binaries, datas, hiddenimports
    try:
        pkg_datas, pkg_binaries, pkg_hidden = collect_all(package)
    except Exception as exc:  # noqa: BLE001 - best-effort, keep building
        print(f'[bridge.spec] skipping {package}: {exc}')
        return
    datas += pkg_datas
    binaries += pkg_binaries
    hiddenimports += pkg_hidden


# bleak picks its platform backend through imports nested inside functions, and
# the Windows backend sits on top of the WinRT projection packages, which are
# namespace packages carrying native .pyd payloads. Neither survives
# PyInstaller's static analysis on its own, so name them explicitly.
if sys.platform == 'win32':
    hiddenimports += collect_submodules('bleak.backends.winrt')
    _collect('winrt')
    for _ns in (
        'winrt.windows.devices.bluetooth',
        'winrt.windows.devices.bluetooth.advertisement',
        'winrt.windows.devices.bluetooth.genericattributeprofile',
        'winrt.windows.devices.enumeration',
        'winrt.windows.devices.radios',
        'winrt.windows.foundation',
        'winrt.windows.foundation.collections',
        'winrt.windows.storage.streams',
    ):
        _collect(_ns)
elif sys.platform == 'darwin':
    hiddenimports += collect_submodules('bleak.backends.corebluetooth')
    _collect('CoreBluetooth')
    _collect('objc')
else:
    hiddenimports += collect_submodules('bleak.backends.bluezdbus')
    _collect('dbus_fast')

# The bridge never draws anything — keep the GUI/dataframe stacks out of the
# bundle even if something pulls them in transitively.
excludes = [
    'PyQt5',
    'PyQt6',
    'PySide2',
    'PySide6',
    'pyqtgraph',
    'matplotlib',
    'pandas',
    'tkinter',
    'IPython',
    'pytest',
]

a = Analysis(
    [ENTRY],
    pathex=[REPO_ROOT],
    binaries=binaries,
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=excludes,
    noarchive=False,
    optimize=0,
)

pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name='bridge',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    # Console app: the sidecar talks to Electron over stdout/stderr pipes.
    # Electron spawns it with windowsHide so no console window ever appears.
    console=True,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)

coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=False,
    upx_exclude=[],
    name='bridge',
)
