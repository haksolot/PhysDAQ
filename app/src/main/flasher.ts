import { BrowserWindow } from 'electron'
import { join } from 'path'
import { existsSync, readFileSync, readdirSync, copyFileSync, statSync } from 'fs'
import { is } from '@electron-toolkit/utils'
import { getRepoRoot } from './sidecar'

/** Flashing a node over its UF2 mass-storage bootloader.
 *
 * Deliberately not a wrapper around scripts/uf2-flash.py: that script blocks on
 * input(), so it cannot be driven from Electron at all, and it is not in the
 * frozen sidecar bundle (bridge.spec has a single entry point), so a packaged
 * build could not run it either. */

/** The file every UF2 bootloader exposes on its mass-storage volume. Detecting
 * on this rather than on the volume label is deliberate — labels are localised
 * and user-renamable, and matching 'XIAO|SENSE' also matches unrelated drives. */
const UF2_MARKER = 'INFO_UF2.TXT'

/** Substring expected in INFO_UF2.TXT's Board-ID / Model line. */
const BOARD_HINT = 'XIAO'

export interface FirmwareInfo {
  available: boolean
  path?: string
  /** 'dev' = whatever `make build` last produced; 'bundled' = shipped in the installer. */
  source: 'dev' | 'bundled'
  error?: string
}

/** Where the image to flash comes from — the same dev-vs-packaged split
 * getBridgeInvocation() uses for the sidecar. In development you flash whatever
 * you just built; an installed copy flashes the image shipped alongside it. */
export function getFirmwareInfo(): FirmwareInfo {
  const source: 'dev' | 'bundled' = is.dev ? 'dev' : 'bundled'
  const path = is.dev
    ? join(getRepoRoot(), 'build', 'zephyr', 'zephyr.uf2')
    : join(process.resourcesPath, 'firmware', 'physdaq.uf2')

  if (!existsSync(path)) {
    return {
      available: false,
      source,
      error: is.dev
        ? `No firmware image at ${path}. Run "make build" first.`
        : 'This build was packaged without a firmware image, so flashing is unavailable.'
    }
  }

  return { available: true, path, source }
}

/** Mount points that could plausibly hold a UF2 volume, per platform. */
function candidateMounts(): string[] {
  if (process.platform === 'win32') {
    // Drive letters are cheap to probe with existsSync — far cheaper than
    // spawning PowerShell once per poll tick.
    const letters: string[] = []
    for (let c = 'A'.charCodeAt(0); c <= 'Z'.charCodeAt(0); c++) {
      letters.push(`${String.fromCharCode(c)}:\\`)
    }
    return letters
  }

  const roots =
    process.platform === 'darwin'
      ? ['/Volumes']
      : [`/media/${process.env.USER ?? ''}`, `/run/media/${process.env.USER ?? ''}`, '/media']

  const mounts: string[] = []
  for (const root of roots) {
    try {
      if (!existsSync(root)) continue
      for (const entry of readdirSync(root)) {
        mounts.push(join(root, entry))
      }
    } catch {
      // An unreadable mount root is not an error worth surfacing.
    }
  }
  return mounts
}

/** The bootloader volume, or null if the board is not in bootloader mode. */
export function findBootloaderDrive(): string | null {
  for (const mount of candidateMounts()) {
    const marker = join(mount, UF2_MARKER)
    try {
      if (!existsSync(marker)) continue
      const info = readFileSync(marker, 'utf-8')
      // Accept an unrecognised board rather than refusing: a UF2 volume that is
      // not ours is far less likely than an INFO file we failed to parse.
      if (!info.toUpperCase().includes(BOARD_HINT)) {
        console.warn(`[Flasher] UF2 volume at ${mount} is not a XIAO:`, info.split('\n')[0])
        continue
      }
      return mount
    } catch {
      // Drive letter exists but is not readable (empty card reader, etc.).
    }
  }
  return null
}

type Stage = 'waiting' | 'copying' | 'done' | 'error'

function report(window: BrowserWindow | null, stage: Stage, message: string): void {
  window?.webContents.send('flash-progress', { stage, message })
}

const POLL_INTERVAL_MS = 1000

export interface FlashResult {
  success: boolean
  error?: string
}

/** Wait for the bootloader volume, copy the image onto it, and confirm.
 *
 * `timeoutMs` bounds only the wait for the double-tap, not the copy. */
export async function flashNode(
  window: BrowserWindow | null,
  timeoutMs = 60_000
): Promise<FlashResult> {
  const firmware = getFirmwareInfo()
  if (!firmware.available || !firmware.path) {
    const error = firmware.error ?? 'No firmware image available.'
    report(window, 'error', error)
    return { success: false, error }
  }

  report(window, 'waiting', 'Double-tap the RST button on the node…')

  const deadline = Date.now() + timeoutMs
  let drive: string | null = null
  while (Date.now() < deadline) {
    drive = findBootloaderDrive()
    if (drive) break
    await new Promise((resolve) => setTimeout(resolve, POLL_INTERVAL_MS))
  }

  if (!drive) {
    const error =
      'No bootloader drive appeared. Connect the node over USB and double-tap its RST button.'
    report(window, 'error', error)
    return { success: false, error }
  }

  report(window, 'copying', `Copying firmware to ${drive}…`)

  const destination = join(drive, 'zephyr.uf2')
  try {
    copyFileSync(firmware.path, destination)
  } catch (err) {
    // The board resets and unmounts itself the moment it has taken the whole
    // image, so a write error *after the volume has gone* is what success looks
    // like. Only a failure with the drive still mounted is a real failure.
    if (findBootloaderDrive() === null) {
      report(window, 'done', 'Firmware written. The node is rebooting.')
      return { success: true }
    }
    const error = `Could not write to ${destination}: ${err instanceof Error ? err.message : String(err)}`
    report(window, 'error', error)
    return { success: false, error }
  }

  // Copy returned cleanly. Confirm the board took it: the volume disappearing is
  // the bootloader's only completion signal.
  const size = statSync(firmware.path).size
  for (let i = 0; i < 10; i++) {
    if (findBootloaderDrive() === null) {
      report(window, 'done', 'Firmware written. The node is rebooting.')
      return { success: true }
    }
    await new Promise((resolve) => setTimeout(resolve, 500))
  }

  // Still mounted well after the copy: the image landed but the board never
  // rebooted, which usually means it was rejected as invalid.
  const error = `Copied ${size} bytes but the node did not reboot. The image may not be valid for this board.`
  report(window, 'error', error)
  return { success: false, error }
}

/** Whether a node is sitting in bootloader mode right now — lets the UI show
 * the state before the user commits to flashing. */
export function getBootloaderStatus(): { present: boolean; drive?: string } {
  const drive = findBootloaderDrive()
  return drive ? { present: true, drive } : { present: false }
}
