import { spawn, execFile, ChildProcess } from 'child_process'
import { join } from 'path'
import { existsSync, mkdirSync, createWriteStream, WriteStream, readdirSync, statSync, readFileSync, rmSync } from 'fs'
import { app, BrowserWindow, ipcMain } from 'electron'
import { is } from '@electron-toolkit/utils'

// Sessions are written to Documents/PhysDAQ_Sessions. LEGACY_SESSIONS_DIR is the
// pre-rename name (the project was called MAID); it is never written to, only
// listed, so recordings made before the rename stay visible in the app.
const SESSIONS_DIR = 'PhysDAQ_Sessions'
const LEGACY_SESSIONS_DIR = 'MAID_Sessions'
// Files pulled off a hub's card. A sibling of the session folders rather than
// one of them: they are raw .BIN, nothing in the app reads them yet, and
// get-recordings would otherwise have to learn to skip them.
const HUB_DOWNLOAD_DIR = 'hub_downloads'

export function getRepoRoot(): string {
  try {
    const appPath = app.getAppPath()
    const repoRoot = join(appPath, '..')
    if (existsSync(join(repoRoot, 'scripts/bridge.py'))) {
      return repoRoot
    }
  } catch (e) {
    console.error('[Sidecar] getAppPath failed, falling back:', e)
  }
  return join(__dirname, '../../..')
}

function getPythonCommand(): string {
  if (is.dev) {
    const repoRoot = getRepoRoot()
    const winVenvPython = join(repoRoot, '.venv/Scripts/python.exe')
    const unixVenvPython = join(repoRoot, '.venv/bin/python')

    if (process.platform === 'win32' && existsSync(winVenvPython)) {
      return winVenvPython
    } else if (process.platform !== 'win32' && existsSync(unixVenvPython)) {
      return unixVenvPython
    }
  }
  return 'python'
}

// Resolve how to invoke the bridge for the current build.
//
//   dev         -> <venv python> scripts/bridge.py
//   packaged    -> <resourcesPath>/bridge/bridge[.exe]  (PyInstaller bundle,
//                  produced by `npm run build:sidecar`, placed there by the
//                  extraResources rule in electron-builder.yml)
//
// Throws with an actionable message when the packaged bundle is missing —
// otherwise the failure surfaces as an opaque shell error like
// "'…\bridge.exe' is not recognized as an internal or external command".
function getBridgeInvocation(): { command: string; baseArgs: string[] } {
  if (is.dev) {
    return {
      command: getPythonCommand(),
      baseArgs: [join(getRepoRoot(), 'scripts/bridge.py')]
    }
  }

  const binName = process.platform === 'win32' ? 'bridge.exe' : 'bridge'
  const command = join(process.resourcesPath, 'bridge', binName)

  if (!existsSync(command)) {
    throw new Error(
      `Python sidecar not found at ${command}. ` +
        'This build was packaged without it — rebuild with "npm run build:sidecar" ' +
        'before "npm run build:win".'
    )
  }

  return { command, baseArgs: [] }
}

// Build a clean environment for the spawned Python process.
// We invoke our own venv/bundled interpreter explicitly, so any inherited
// PYTHONPATH/PYTHONHOME (e.g. set by scripts/setup-env.ps1 for the Zephyr
// toolchain) must be stripped — otherwise the toolchain's site-packages
// shadow the venv's and numpy fails to load its C-extensions.
function getPythonEnv(): NodeJS.ProcessEnv {
  const env = { ...process.env }
  delete env.PYTHONPATH
  delete env.PYTHONHOME
  return env
}

function parsePythonJsonOutput(stdout: string): any {
  const lines = stdout.split(/\r?\n/)
  // Iterate backwards since the JSON result is typically the last line printed
  for (let i = lines.length - 1; i >= 0; i--) {
    const trimmed = lines[i].trim()
    if (!trimmed) continue
    if ((trimmed.startsWith('[') && trimmed.endsWith(']')) || (trimmed.startsWith('{') && trimmed.endsWith('}'))) {
      try {
        return JSON.parse(trimmed)
      } catch (e) {
        // Continue searching
      }
    }
  }
  try {
    return JSON.parse(stdout.trim())
  } catch (e) {
    return null
  }
}

type DeviceType = 'node' | 'hub'

interface ActiveSensor {
  process: ChildProcess
  mode: 'serial' | 'ble'
  target: string
  position: string
  deviceType: DeviceType
  /** Body-position slots this one device feeds, indexed by PPG channel.
   *
   * A node has exactly one and it equals its own id. A hub has two sensors
   * worn at two different sites, so one bridge process drives two slots. Every
   * layer downstream — charts, CSV writer, session schema — then sees a slot
   * carrying a single PPG stream, which is the shape it already understood. */
  channelSlots: string[]
}

interface ActiveRecording {
  writeStream: WriteStream
  startTime: number
  filename: string
  position: string
}

const activeSensors = new Map<string, ActiveSensor>()
const activeRecordings = new Map<string, ActiveRecording>()
let mainWindowRef: BrowserWindow | null = null
let currentSessionPath: string | null = null
let isRecording = false
let recordingStartTime = 0

export function startSidecar(mainWindow: BrowserWindow): void {
  mainWindowRef = mainWindow

  // Listen to UI commands to control the sidecars
  ipcMain.on(
    'connect-sensor',
    (
      _,
      config: {
        id: string
        mode: 'serial' | 'ble'
        target: string
        position: string
        deviceType?: DeviceType
        /** Slots per PPG channel. Defaults to just this slot, i.e. a node. */
        channelSlots?: string[]
      }
    ) => {
    if (activeSensors.has(config.id)) {
      const existing = activeSensors.get(config.id)
      existing?.process.kill()
      activeSensors.delete(config.id)
    }

    const deviceType: DeviceType = config.deviceType === 'hub' ? 'hub' : 'node'
    // channelSlots[0] is always this sensor's own id: the slot the user
    // configured is the one the first PPG channel lands on.
    const channelSlots =
      config.channelSlots && config.channelSlots.length > 0
        ? config.channelSlots
        : [config.id]

    let command: string
    let args: string[]

    try {
      const invocation = getBridgeInvocation()
      command = invocation.command
      args = [...invocation.baseArgs]
    } catch (err) {
      console.error(`[Sidecar] Cannot locate bridge for ${config.id}:`, err)
      mainWindowRef?.webContents.send('sensor-status', {
        sensorId: config.id,
        status: 'error',
        error: err instanceof Error ? err.message : String(err)
      })
      return
    }

    if (config.mode === 'ble') {
      if (config.target) {
        args.push(`--ble-addr=${config.target}`)
      } else {
        args.push('--ble')
      }
    } else if (config.target) {
      args.push(config.target)
    }

    // Lets the bridge lay out two channels before the first sample arrives.
    // The device's own ID line supersedes it either way.
    if (deviceType === 'hub') {
      args.push('--device-type=hub')
    }

    console.log(`[Sidecar] Spawning sensor ${config.id} (${config.position}): ${command} ${args.join(' ')}`)

    try {
      const p = spawn(command, args, {
        stdio: ['pipe', 'pipe', 'pipe'],
        env: getPythonEnv(),
        // The frozen bridge is a console app; without this Windows flashes a
        // console window for every sensor that connects.
        windowsHide: true
      })

      activeSensors.set(config.id, {
        process: p,
        mode: config.mode,
        target: config.target,
        position: config.position,
        deviceType,
        channelSlots
      })

      // Deliver one message to one body-position slot, in the exact shape a
      // single-PPG node would have produced. `deviceId`/`deviceType` ride along
      // so the renderer can tell which slots are two halves of one hub — they
      // share an IMU, a battery and a radio link.
      const emitToSlot = (slot: string, payload: any): void => {
        mainWindowRef?.webContents.send('sensor-data', {
          ...payload,
          sensorId: slot,
          position: slot,
          deviceId: config.id,
          deviceType
        })

        if (isRecording && payload.type === 'sample') {
          const rec = activeRecordings.get(slot)
          if (rec) {
            const elapsed = (Date.now() - rec.startTime) / 1000
            const q = payload.quat || [1, 0, 0, 0]
            const ppgFiltVal =
              payload.ppg_filt !== undefined && payload.ppg_filt !== null ? payload.ppg_filt : 0.0
            const row = `${payload.timestamp || new Date().toISOString()},${elapsed},${payload.ax},${payload.ay},${payload.az},${payload.gx},${payload.gy},${payload.gz},${q[0]},${q[1]},${q[2]},${q[3]},${payload.red},${payload.ir},${ppgFiltVal},${payload.bpm !== null ? payload.bpm : ''},${payload.contact ? 1 : 0}\n`
            rec.writeStream.write(row)
          }
        }
      }

      let buffer = ''
      p.stdout?.on('data', (data) => {
        buffer += data.toString()
        const lines = buffer.split('\n')
        buffer = lines.pop() || ''

        for (const line of lines) {
          const trimmed = line.trim()
          if (!trimmed) continue
          try {
            const json = JSON.parse(trimmed)

            if (json.type === 'sample' && Array.isArray(json.ch)) {
              // One PPG channel per slot. The bridge always emits `ch`, with
              // one entry for a node and two for a hub, so this single path
              // covers both — a node's slot simply receives channel 0.
              // Channel fields are flattened to the top level because that is
              // where every existing consumer already looks for them.
              json.ch.forEach((c: any, i: number) => {
                const slot = channelSlots[i]
                if (!slot) return
                emitToSlot(slot, {
                  ...json,
                  red: c.red,
                  ir: c.ir,
                  ppg_filt: c.ppg_filt,
                  bpm: c.bpm,
                  contact: c.contact,
                  channelIndex: i
                })
              })
            } else if (json.type === 'sample') {
              // Defensive: a bridge older than the `ch` field. Channel 0 only.
              emitToSlot(channelSlots[0], json)
            } else {
              // Device-level message — battery, identity, link status, SD and
              // hub replies. Both halves of a hub sit on the same battery and
              // the same link, so both slots need it.
              for (const slot of channelSlots) {
                emitToSlot(slot, json)
              }
            }

            // Intercept status messages from the python bridge
            if (json.status && (json.status === 'connected' || json.status === 'disconnected' || json.status === 'connecting')) {
              for (const slot of channelSlots) {
                mainWindowRef?.webContents.send('sensor-status', {
                  sensorId: slot,
                  status: json.status
                })
              }
            }
          } catch (err) {
            console.error(`[Sidecar ${config.id}] Failed to parse JSON:`, trimmed, err)
          }
        }
      })

      // One bridge process, one stderr — but a hub's two slots each have their
      // own System Logs pane. Addressed to the device id alone, the second one
      // stayed empty.
      p.stderr?.on('data', (data) => {
        const log = data.toString()
        for (const slot of channelSlots) {
          mainWindowRef?.webContents.send('sidecar-log', {
            sensorId: slot,
            log
          })
        }
      })

      p.on('close', (code) => {
        console.log(`[Sidecar ${config.id}] Process exited with code ${code}`)
        activeSensors.delete(config.id)

        for (const slot of channelSlots) {
          mainWindowRef?.webContents.send('sensor-status', {
            sensorId: slot,
            status: 'disconnected',
            code
          })

          // Close recording stream if active
          const rec = activeRecordings.get(slot)
          if (rec) {
            rec.writeStream.end()
            activeRecordings.delete(slot)
          }
        }
      })

      for (const slot of channelSlots) {
        mainWindowRef?.webContents.send('sensor-status', {
          sensorId: slot,
          status: 'connecting'
        })
      }

    } catch (err) {
      console.error(`[Sidecar] Failed to spawn child for ${config.id}:`, err)
      for (const slot of channelSlots) {
        mainWindowRef?.webContents.send('sensor-status', {
          sensorId: slot,
          status: 'error',
          error: String(err)
        })
      }
    }
  })

  // Downlink commands. The bridge takes one JSON object per line on stdin and
  // turns it into the firmware's "CMD ..." grammar; its replies come back
  // through the normal stdout path, so nothing else here needs to change.
  //
  // The stdin pipe has existed since the first version — it was only ever used
  // as a liveness signal, closed to make the bridge exit.
  ipcMain.handle('send-device-command', (_, id: string, cmd: Record<string, unknown>) => {
    const sensor = activeSensors.get(id)

    if (!sensor) {
      return { success: false, error: 'Device not connected' }
    }

    // Downloads are written by the bridge straight to disk. Choosing the path
    // here rather than in the renderer keeps it out of reach of the page. The
    // folder sits alongside the session folders, so `scanSessionBase` skips it
    // by name rather than listing it as a session with no recordings.
    let payload = cmd

    if (cmd.cmd === 'sd.get') {
      const dir = join(app.getPath('documents'), SESSIONS_DIR, HUB_DOWNLOAD_DIR)

      mkdirSync(dir, { recursive: true })
      payload = { ...cmd, dest: join(dir, String(cmd.file ?? 'session.bin')) }
    }

    try {
      sensor.process.stdin?.write(`${JSON.stringify(payload)}\n`)
      return { success: true, dest: (payload as { dest?: string }).dest }
    } catch (err) {
      console.error(`[Sidecar ${id}] Failed to send command:`, err)
      return { success: false, error: String(err) }
    }
  })

  ipcMain.on('disconnect-sensor', (_, id: string) => {
    if (activeSensors.has(id)) {
      const existing = activeSensors.get(id)
      existing?.process.kill()
      activeSensors.delete(id)
    }
  })

  ipcMain.on('disconnect-all', () => {
    stopSidecar()
  })

  // Recording IPC Handlers
  ipcMain.handle('start-recording', (_, sessionName: string) => {
    try {
      const baseDir = join(app.getPath('documents'), SESSIONS_DIR)
      if (!existsSync(baseDir)) {
        mkdirSync(baseDir, { recursive: true })
      }
      
      const safeSessionName = sessionName.replace(/[^a-zA-Z0-9_-]/g, '_')
      const dateStr = new Date().toISOString().replace(/[:.]/g, '-')
      const folderName = `session_${dateStr}_${safeSessionName || 'untitled'}`
      currentSessionPath = join(baseDir, folderName)
      mkdirSync(currentSessionPath, { recursive: true })

      recordingStartTime = Date.now()
      activeRecordings.clear()

      // One file per body-position slot, so a hub writes two — each with the
      // unchanged 17-column schema, indistinguishable from a node's file. The
      // key is the slot, not the device: that is what lets the stdout handler
      // route each PPG channel to its own stream.
      for (const sensor of activeSensors.values()) {
        for (const slot of sensor.channelSlots) {
          const filename = `${slot}.csv`
          const filePath = join(currentSessionPath, filename)
          const writeStream = createWriteStream(filePath)

          // Write header
          writeStream.write("timestamp,elapsed_s,ax,ay,az,gx,gy,gz,qw,qx,qy,qz,ppg_red,ppg_ir,ppg_filt,bpm,contact\n")

          activeRecordings.set(slot, {
            writeStream,
            startTime: recordingStartTime,
            filename,
            position: slot
          })
        }
      }

      isRecording = true
      return { success: true, sessionPath: currentSessionPath }
    } catch (err) {
      console.error('[Recording] Failed to start recording:', err)
      return { success: false, error: String(err) }
    }
  })

  ipcMain.handle('stop-recording', () => {
    try {
      isRecording = false
      for (const [, rec] of activeRecordings.entries()) {
        rec.writeStream.end()
      }
      activeRecordings.clear()
      currentSessionPath = null
      return { success: true }
    } catch (err) {
      console.error('[Recording] Failed to stop recording:', err)
      return { success: false, error: String(err) }
    }
  })

  // Enumerate the session folders under one base directory. Every entry carries
  // an absolute `path`, so get-recording-data and delete-recording work the same
  // whether the session came from the current or the legacy directory.
  const scanSessionBase = (baseDir: string, legacy: boolean): any[] => {
    if (!existsSync(baseDir)) return []

    const dirs = readdirSync(baseDir)
    const list: any[] = []

    for (const dir of dirs) {
      // The hub download folder lives inside the session base but is not a
      // session: it holds raw .BIN files pulled off a card, no CSVs. Without
      // this it was listed as an empty session.
      if (!legacy && dir === HUB_DOWNLOAD_DIR) continue

      const fullPath = join(baseDir, dir)
      const stat = statSync(fullPath)
      if (!stat.isDirectory()) continue

      const parts = dir.split('_')
      const datePart = parts[1] || ''
      const namePart = parts.slice(2).join('_') || 'Untitled'

      const files = readdirSync(fullPath)
      const sensorFiles: any[] = []
      const positions: string[] = []
      let estimatedDuration = 0

      for (const file of files) {
        if (!file.endsWith('.csv')) continue
        const filePath = join(fullPath, file)
        const fstat = statSync(filePath)
        const pos = file.replace('.csv', '')
        positions.push(pos)
        sensorFiles.push({
          filename: file,
          size: fstat.size,
          position: pos
        })

        try {
          const dataContent = readFileSync(filePath, 'utf-8')
          const lines = dataContent.trim().split('\n')
          if (lines.length > 2) {
            const lastLine = lines[lines.length - 1]
            const elapsed = parseFloat(lastLine.split(',')[1])
            if (!isNaN(elapsed) && elapsed > estimatedDuration) {
              estimatedDuration = elapsed
            }
          }
        } catch (e) {
          // fallback
        }
      }

      list.push({
        name: namePart,
        date: datePart,
        path: fullPath,
        sensors: positions,
        duration_s: Math.round(estimatedDuration),
        files: sensorFiles,
        legacy
      })
    }

    return list
  }

  ipcMain.handle('get-recordings', () => {
    try {
      const documents = app.getPath('documents')
      const list = [
        ...scanSessionBase(join(documents, SESSIONS_DIR), false),
        ...scanSessionBase(join(documents, LEGACY_SESSIONS_DIR), true)
      ]

      return list.sort((a, b) => b.date.localeCompare(a.date))
    } catch (err) {
      console.error('[Recording] Failed to list recordings:', err)
      return []
    }
  })

  ipcMain.handle('get-recording-data', (_, sessionPath: string, filename: string) => {
    try {
      const filePath = join(sessionPath, filename)
      if (!existsSync(filePath)) return { success: false, error: 'File not found' }
      
      const content = readFileSync(filePath, 'utf-8')
      const lines = content.split('\n')
      if (lines.length < 2) return { success: true, data: [] }
      
      const headers = lines[0].split(',')
      const data: any[] = []

      const idx = {
        timestamp: headers.indexOf('timestamp'),
        elapsed: headers.indexOf('elapsed_s'),
        ax: headers.indexOf('ax'),
        ay: headers.indexOf('ay'),
        az: headers.indexOf('az'),
        gx: headers.indexOf('gx'),
        gy: headers.indexOf('gy'),
        gz: headers.indexOf('gz'),
        qw: headers.indexOf('qw'),
        qx: headers.indexOf('qx'),
        qy: headers.indexOf('qy'),
        qz: headers.indexOf('qz'),
        red: headers.indexOf('ppg_red'),
        ir: headers.indexOf('ppg_ir'),
        ppg_filt: headers.indexOf('ppg_filt'),
        bpm: headers.indexOf('bpm'),
        contact: headers.indexOf('contact')
      }

      let dc = 0
      let lp = 0
      let isFirst = true

      for (let i = 1; i < lines.length; i++) {
        const line = lines[i].trim()
        if (!line) continue
        const cols = line.split(',')
        if (cols.length < 10) continue

        const irVal = idx.ir !== -1 ? parseFloat(cols[idx.ir]) : 0
        let ppgFiltVal = idx.ppg_filt !== -1 ? parseFloat(cols[idx.ppg_filt]) : 0

        if (idx.ppg_filt === -1 && idx.ir !== -1) {
          if (isFirst) {
            dc = irVal
            lp = 0
            isFirst = false
          } else {
            dc = dc * 0.985 + irVal * 0.015
            const ac = irVal - dc
            lp = lp * 0.75 + ac * 0.25
          }
          ppgFiltVal = lp
        }

        data.push({
          timestamp: idx.timestamp !== -1 ? cols[idx.timestamp] : '',
          elapsed: idx.elapsed !== -1 ? parseFloat(cols[idx.elapsed]) : 0,
          ax: idx.ax !== -1 ? parseFloat(cols[idx.ax]) : 0,
          ay: idx.ay !== -1 ? parseFloat(cols[idx.ay]) : 0,
          az: idx.az !== -1 ? parseFloat(cols[idx.az]) : 0,
          gx: idx.gx !== -1 ? parseFloat(cols[idx.gx]) : 0,
          gy: idx.gy !== -1 ? parseFloat(cols[idx.gy]) : 0,
          gz: idx.gz !== -1 ? parseFloat(cols[idx.gz]) : 0,
          qw: idx.qw !== -1 ? parseFloat(cols[idx.qw]) : 1,
          qx: idx.qx !== -1 ? parseFloat(cols[idx.qx]) : 0,
          qy: idx.qy !== -1 ? parseFloat(cols[idx.qy]) : 0,
          qz: idx.qz !== -1 ? parseFloat(cols[idx.qz]) : 0,
          red: idx.red !== -1 ? parseFloat(cols[idx.red]) : 0,
          ir: irVal,
          ppg_filt: ppgFiltVal,
          bpm: (idx.bpm !== -1 && cols[idx.bpm]) ? parseFloat(cols[idx.bpm]) : null,
          contact: idx.contact !== -1 ? cols[idx.contact] === '1' : false
        })
      }

      return { success: true, data }
    } catch (err) {
      console.error('[Recording] Failed to read recording data:', err)
      return { success: false, error: String(err) }
    }
  })

  ipcMain.handle('delete-recording', (_, sessionPath: string) => {
    try {
      if (existsSync(sessionPath)) {
        rmSync(sessionPath, { recursive: true, force: true })
        return { success: true }
      }
      return { success: false, error: 'Path not found' }
    } catch (err) {
      console.error('[Recording] Failed to delete recording:', err)
      return { success: false, error: String(err) }
    }
  })
}

export function stopSidecar(): void {
  for (const [id, sensor] of activeSensors.entries()) {
    console.log(`[Sidecar] Stopping sensor process for ${id}...`)
    sensor.process.kill()
  }
  activeSensors.clear()
}

app.on('will-quit', () => {
  stopSidecar()
})

// Run the bridge as a one-shot query (--list-ports / --scan) and return the
// JSON array it prints. execFile (not exec) so the binary is invoked directly:
// no shell involved, so paths containing spaces need no quoting and a missing
// binary reports ENOENT instead of a cmd.exe parse error.
function runBridgeQuery(queryArg: string, label: string): Promise<any[]> {
  return new Promise((resolve, reject) => {
    let command: string
    let args: string[]

    try {
      const invocation = getBridgeInvocation()
      command = invocation.command
      args = [...invocation.baseArgs, queryArg]
    } catch (err) {
      reject(err instanceof Error ? err : new Error(String(err)))
      return
    }

    execFile(command, args, { env: getPythonEnv(), windowsHide: true }, (err, stdout, stderr) => {
      const parsed = parsePythonJsonOutput(stdout)
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed) && parsed.error) {
        reject(new Error(parsed.error))
        return
      }

      if (err) {
        console.error(`[Sidecar] Failed to ${label}:`, err, stderr)
        const detail =
          (err as NodeJS.ErrnoException).code === 'ENOENT'
            ? `Sidecar executable not found: ${command}`
            : stderr.trim() || err.message || 'Process exited with error'
        reject(new Error(detail))
        return
      }

      if (Array.isArray(parsed)) {
        resolve(parsed)
      } else if (parsed) {
        reject(new Error('Invalid response format received from sidecar script'))
      } else {
        reject(new Error('Failed to parse response from sidecar script'))
      }
    })
  })
}

export function getSerialPorts(): Promise<any[]> {
  return runBridgeQuery('--list-ports', 'get serial ports')
}

/** The bridge speaks snake_case (it is Python); the renderer's `BleDeviceInfo`
 * is camelCase. Nothing else in the stdout path renames anything, so this is
 * the one place the two conventions meet. Without it `deviceType` is silently
 * `undefined` on every scan result and the HUB badge never renders.
 *
 * The device-class fields are absent for firmware older than AD protocol 2, so
 * they stay optional — the `ID` line corrects the guess on connect either way. */
function mapScanResult(raw: any): any {
  return {
    address: raw.address,
    name: raw.name,
    rssi: raw.rssi,
    deviceType: raw.device_type,
    ppgCount: raw.ppg_count,
    hasSd: raw.has_sd
  }
}

/** Scan for nodes. `all` drops the NUS service-UUID filter and returns every
 * BLE advertiser in range — the escape hatch for a node whose advertisement
 * arrives without the UUID. */
export function scanBle(all = false): Promise<any[]> {
  const query = all
    ? runBridgeQuery('--scan-all', 'scan all BLE devices')
    : runBridgeQuery('--scan', 'scan BLE devices')
  return query.then((devices) => devices.map(mapScanResult))
}

