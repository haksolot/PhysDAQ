import { spawn, exec, ChildProcess } from 'child_process'
import { join } from 'path'
import { existsSync, mkdirSync, createWriteStream, WriteStream, readdirSync, statSync, readFileSync, rmSync } from 'fs'
import { app, BrowserWindow, ipcMain } from 'electron'
import { is } from '@electron-toolkit/utils'

function getPythonCommand(): string {
  if (is.dev) {
    const repoRoot = join(__dirname, '../../..')
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

interface ActiveSensor {
  process: ChildProcess
  mode: 'serial' | 'ble'
  target: string
  position: string
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
  ipcMain.on('connect-sensor', (_, config: { id: string; mode: 'serial' | 'ble'; target: string; position: string }) => {
    if (activeSensors.has(config.id)) {
      const existing = activeSensors.get(config.id)
      existing?.process.kill()
      activeSensors.delete(config.id)
    }

    let command = getPythonCommand()
    let args: string[] = []

    if (is.dev) {
      const bridgePath = join(__dirname, '../../../scripts/bridge.py')
      args = [bridgePath]
    } else {
      command = join(process.resourcesPath, 'bridge.exe')
      args = []
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

    console.log(`[Sidecar] Spawning sensor ${config.id} (${config.position}): ${command} ${args.join(' ')}`)

    try {
      const p = spawn(command, args, {
        stdio: ['pipe', 'pipe', 'pipe']
      })

      activeSensors.set(config.id, {
        process: p,
        mode: config.mode,
        target: config.target,
        position: config.position
      })

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
            const enriched = { ...json, sensorId: config.id, position: config.position }
            mainWindowRef?.webContents.send('sensor-data', enriched)

            // If recording, write to CSV
            if (isRecording) {
              const rec = activeRecordings.get(config.id)
              if (rec && json.type === 'sample') {
                const elapsed = (Date.now() - rec.startTime) / 1000
                const q = json.quat || [1, 0, 0, 0]
                const ppgFiltVal = json.ppg_filt !== undefined && json.ppg_filt !== null ? json.ppg_filt : 0.0
                const row = `${enriched.timestamp || new Date().toISOString()},${elapsed},${json.ax},${json.ay},${json.az},${json.gx},${json.gy},${json.gz},${q[0]},${q[1]},${q[2]},${q[3]},${json.red},${json.ir},${ppgFiltVal},${json.bpm !== null ? json.bpm : ''},${json.contact ? 1 : 0}\n`
                rec.writeStream.write(row)
              }
            }
          } catch (err) {
            console.error(`[Sidecar ${config.id}] Failed to parse JSON:`, trimmed, err)
          }
        }
      })

      p.stderr?.on('data', (data) => {
        mainWindowRef?.webContents.send('sidecar-log', {
          sensorId: config.id,
          log: data.toString()
        })
      })

      p.on('close', (code) => {
        console.log(`[Sidecar ${config.id}] Process exited with code ${code}`)
        mainWindowRef?.webContents.send('sensor-status', {
          sensorId: config.id,
          status: 'disconnected',
          code
        })
        activeSensors.delete(config.id)

        // Close recording stream if active
        const rec = activeRecordings.get(config.id)
        if (rec) {
          rec.writeStream.end()
          activeRecordings.delete(config.id)
        }
      })

      mainWindowRef?.webContents.send('sensor-status', {
        sensorId: config.id,
        status: 'connected'
      })

    } catch (err) {
      console.error(`[Sidecar] Failed to spawn child for ${config.id}:`, err)
      mainWindowRef?.webContents.send('sensor-status', {
        sensorId: config.id,
        status: 'error',
        error: String(err)
      })
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
      const baseDir = join(app.getPath('documents'), 'MAID_Sessions')
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

      for (const [id, sensor] of activeSensors.entries()) {
        const filename = `${sensor.position}.csv`
        const filePath = join(currentSessionPath, filename)
        const writeStream = createWriteStream(filePath)
        
        // Write header
        writeStream.write("timestamp,elapsed_s,ax,ay,az,gx,gy,gz,qw,qx,qy,qz,ppg_red,ppg_ir,ppg_filt,bpm,contact\n")
        
        activeRecordings.set(id, {
          writeStream,
          startTime: recordingStartTime,
          filename,
          position: sensor.position
        })
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

  ipcMain.handle('get-recordings', () => {
    try {
      const baseDir = join(app.getPath('documents'), 'MAID_Sessions')
      if (!existsSync(baseDir)) return []
      
      const dirs = readdirSync(baseDir)
      const list: any[] = []

      for (const dir of dirs) {
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
          files: sensorFiles
        })
      }

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

export function getSerialPorts(): Promise<any[]> {
  return new Promise((resolve, reject) => {
    let command = getPythonCommand()
    let args = [join(__dirname, '../../../scripts/bridge.py'), '--list-ports']

    if (!is.dev) {
      command = join(process.resourcesPath, 'bridge.exe')
      args = ['--list-ports']
    }

    exec(`"${command}" ${args.join(' ')}`, (err, stdout, stderr) => {
      const parsed = parsePythonJsonOutput(stdout)
      if (parsed && typeof parsed === 'object' && parsed.error) {
        reject(new Error(parsed.error))
        return
      }

      if (err) {
        console.error('[Sidecar] Failed to get serial ports:', err, stderr)
        reject(new Error(stderr.trim() || err.message || 'Process exited with error'))
        return
      }

      if (parsed) {
        if (Array.isArray(parsed)) {
          resolve(parsed)
        } else {
          reject(new Error('Invalid response format received from sidecar script'))
        }
      } else {
        reject(new Error('Failed to parse response from sidecar script'))
      }
    })
  })
}

export function scanBle(): Promise<any[]> {
  return new Promise((resolve, reject) => {
    let command = getPythonCommand()
    let args = [join(__dirname, '../../../scripts/bridge.py'), '--scan']

    if (!is.dev) {
      command = join(process.resourcesPath, 'bridge.exe')
      args = ['--scan']
    }

    exec(`"${command}" ${args.join(' ')}`, (err, stdout, stderr) => {
      const parsed = parsePythonJsonOutput(stdout)
      if (parsed && typeof parsed === 'object' && parsed.error) {
        reject(new Error(parsed.error))
        return
      }

      if (err) {
        console.error('[Sidecar] Failed to scan BLE devices:', err, stderr)
        reject(new Error(stderr.trim() || err.message || 'Process exited with error'))
        return
      }

      if (parsed) {
        if (Array.isArray(parsed)) {
          resolve(parsed)
        } else {
          reject(new Error('Invalid response format received from sidecar script'))
        }
      } else {
        reject(new Error('Failed to parse response from sidecar script'))
      }
    })
  })
}

