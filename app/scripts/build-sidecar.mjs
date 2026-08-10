// Builds the Python sidecar (scripts/bridge.py -> app/sidecar/bridge/) before
// electron-builder packages the app. Thin cross-platform shim: it only locates
// an interpreter, the real work lives in scripts/build-sidecar.py at the repo
// root so it can also be driven by `make sidecar`.

import { spawnSync } from 'node:child_process'
import { existsSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const APP_DIR = dirname(dirname(fileURLToPath(import.meta.url)))
const REPO_ROOT = dirname(APP_DIR)
const BUILD_SCRIPT = join(REPO_ROOT, 'scripts', 'build-sidecar.py')

function findPython() {
  const candidates =
    process.platform === 'win32'
      ? [join(REPO_ROOT, '.venv', 'Scripts', 'python.exe'), 'python']
      : [join(REPO_ROOT, '.venv', 'bin', 'python'), 'python3', 'python']

  for (const candidate of candidates) {
    if (candidate.includes('/') || candidate.includes('\\')) {
      if (existsSync(candidate)) return candidate
    } else {
      return candidate
    }
  }
  return 'python'
}

const python = findPython()
console.log(`[build-sidecar] Using interpreter: ${python}`)

// Strip the Zephyr toolchain's PYTHONPATH (set by scripts/setup-env.ps1) so
// PyInstaller analyses the venv's site-packages, not the toolchain's.
const env = { ...process.env }
delete env.PYTHONPATH
delete env.PYTHONHOME

const result = spawnSync(python, [BUILD_SCRIPT, ...process.argv.slice(2)], {
  stdio: 'inherit',
  cwd: REPO_ROOT,
  env
})

if (result.error) {
  console.error('[build-sidecar] Failed to launch Python:', result.error.message)
  console.error('[build-sidecar] Install Python 3 and run: pip install -r requirements.txt')
  process.exit(1)
}

process.exit(result.status ?? 1)
