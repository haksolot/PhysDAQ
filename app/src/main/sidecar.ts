import { spawn, ChildProcess } from 'child_process'
import { join } from 'path'
import { app, BrowserWindow, ipcMain } from 'electron'
import { is } from '@electron-toolkit/utils'

let sidecarProcess: ChildProcess | null = null

export function startSidecar(mainWindow: BrowserWindow): void {
  // Determine command and args depending on dev/prod mode
  let command = 'python'
  let args: string[] = []

  if (is.dev) {
    // In dev mode, run bridge.py using the system python interpreter
    const bridgePath = join(__dirname, '../../../scripts/bridge.py')
    // Check if we want to run with BLE or default Serial
    // For now, let's start with auto-detect Serial.
    // We can allow the UI to restart the sidecar with BLE or other settings.
    args = [bridgePath]
  } else {
    // In production, the sidecar is compiled into resources/bridge.exe
    command = join(process.resourcesPath, 'bridge.exe')
    args = []
  }

  console.log(`[Sidecar] Spawning: ${command} ${args.join(' ')}`)

  try {
    sidecarProcess = spawn(command, args, {
      stdio: ['pipe', 'pipe', 'pipe']
    })
  } catch (err) {
    console.error('[Sidecar] Failed to spawn sidecar process:', err)
    mainWindow.webContents.send('sidecar-error', `Failed to start: ${err}`)
    return
  }

  // Handle stdout (JSON lines from bridge.py)
  let buffer = ''
  sidecarProcess.stdout?.on('data', (data) => {
    buffer += data.toString()
    const lines = buffer.split('\n')
    buffer = lines.pop() || '' // Keep unfinished line in buffer

    for (const line of lines) {
      const trimmed = line.trim()
      if (!trimmed) continue
      try {
        const json = JSON.parse(trimmed)
        mainWindow.webContents.send('sensor-data', json)
      } catch (err) {
        console.error('[Sidecar] Failed to parse JSON line:', trimmed, err)
      }
    }
  })

  // Handle stderr (for debugging logs)
  sidecarProcess.stderr?.on('data', (data) => {
    console.log(`[Sidecar py] ${data.toString().trim()}`)
    mainWindow.webContents.send('sidecar-log', data.toString())
  })

  sidecarProcess.on('close', (code) => {
    console.log(`[Sidecar] Process exited with code ${code}`)
    mainWindow.webContents.send('sidecar-status', { status: 'disconnected', code })
    sidecarProcess = null
  })

  // Listen to UI commands to control the sidecar
  ipcMain.on('restart-sidecar', (_, config: { mode: 'serial' | 'ble'; portOrAddr?: string }) => {
    stopSidecar()
    
    // Reconfigure args
    if (is.dev) {
      const bridgePath = join(__dirname, '../../../scripts/bridge.py')
      args = [bridgePath]
      if (config.mode === 'ble') {
        if (config.portOrAddr) {
          args.push(`--ble-addr=${config.portOrAddr}`)
        } else {
          args.push('--ble')
        }
      } else if (config.portOrAddr) {
        args.push(config.portOrAddr)
      }
    } else {
      command = join(process.resourcesPath, 'bridge.exe')
      args = []
      if (config.mode === 'ble') {
        if (config.portOrAddr) {
          args.push(`--ble-addr=${config.portOrAddr}`)
        } else {
          args.push('--ble')
        }
      } else if (config.portOrAddr) {
        args.push(config.portOrAddr)
      }
    }

    console.log(`[Sidecar] Restarting: ${command} ${args.join(' ')}`)
    try {
      sidecarProcess = spawn(command, args, {
        stdio: ['pipe', 'pipe', 'pipe']
      })
      // Re-bind listeners (same as above)
      let restartBuf = ''
      sidecarProcess.stdout?.on('data', (data) => {
        restartBuf += data.toString()
        const lines = restartBuf.split('\n')
        restartBuf = lines.pop() || ''
        for (const line of lines) {
          const trimmed = line.trim()
          if (!trimmed) continue
          try {
            const json = JSON.parse(trimmed)
            mainWindow.webContents.send('sensor-data', json)
          } catch (err) {
            console.error('[Sidecar] Failed to parse JSON:', trimmed, err)
          }
        }
      })

      sidecarProcess.stderr?.on('data', (data) => {
        console.log(`[Sidecar py] ${data.toString().trim()}`)
        mainWindow.webContents.send('sidecar-log', data.toString())
      })

      sidecarProcess.on('close', (code) => {
        console.log(`[Sidecar] Process exited with code ${code}`)
        mainWindow.webContents.send('sidecar-status', { status: 'disconnected', code })
        sidecarProcess = null
      })
    } catch (err) {
      console.error('[Sidecar] Failed to restart sidecar:', err)
      mainWindow.webContents.send('sidecar-error', `Restart failed: ${err}`)
    }
  })
}

export function stopSidecar(): void {
  if (sidecarProcess) {
    console.log('[Sidecar] Stopping process...')
    sidecarProcess.kill()
    sidecarProcess = null
  }
}

// Clean up on app quit
app.on('will-quit', () => {
  stopSidecar()
})
