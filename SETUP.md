# Environment Setup

## Prerequisites

| Tool | How to get it |
|------|---------------|
| nRF Connect SDK v3.3.0 | nrfutil toolchain-manager (see below) |
| west, CMake, Ninja, ARM toolchain | Bundled with nRF Connect SDK |
| GNU Make | Bundled via MinGW in the nRF toolchain, or Git Bash / MSYS2 |
| pyserial | `pip install pyserial` inside the toolchain shell (once) |

> **Python note:** The nRF toolchain ships its own Python 3.12. The project scripts use `python` (not `python3`) to target it. If another Python version is on your system (Windows Store, Chocolatey…), do not mix them — run everything from the toolchain shell.

---

## First-time setup

### 1. Install nrfutil

Download from [nordicsemi.com/nRF-Util](https://www.nordicsemi.com/Products/Development-tools/nRF-Util), add to PATH, then:

```powershell
nrfutil install toolchain-manager
nrfutil toolchain-manager install --ncs-version v3.3.0
```

### 2. Open a toolchain shell

**Option A — recommended:**
```powershell
nrfutil toolchain-manager launch --terminal --ncs-version v3.3.0
```

**Option B — activate in an existing PowerShell:**
```powershell
. .\scripts\setup-env.ps1
```

> This step is required every new terminal session. Without it, `west` and the ARM compiler are not on PATH.

**Linux / macOS:**
```bash
source scripts/setup-env.sh
```

### 3. Initialize the west workspace (once after clone)

```powershell
make setup
```

Downloads Zephyr v3.6.0 and its modules (~2–3 GB).

### 4. Install pyserial (once)

```powershell
pip install pyserial
```

---

## Daily workflow

1. Open a toolchain shell (step 2 above).
2. `cd` to the repo.
3. Run `make build`, `make flash`, or `make term`.

---

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `west: command not found` | Toolchain not activated | Open a toolchain shell |
| `ImportError: python312.dll conflicts` | Wrong Python (`python3` vs `python`) | Use the toolchain shell; Makefile calls `python` |
| `No module named 'serial'` | pyserial not installed | `pip install pyserial` in the toolchain shell |
| `CMake Error: C:/ncs/...` | Stale build cache | `make clean` then `make build` |
| `XIAO-SENSE drive not found` | Bootloader not active | Double-click RST before `make flash` |
