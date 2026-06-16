import { contextBridge, ipcRenderer } from 'electron'
import { electronAPI } from '@electron-toolkit/preload'

// Custom APIs for renderer
const api = {
  onSensorData: (callback: (data: any) => void) => {
    const subscription = (_event: any, data: any) => callback(data)
    ipcRenderer.on('sensor-data', subscription)
    return () => {
      ipcRenderer.removeListener('sensor-data', subscription)
    }
  },
  onSidecarLog: (callback: (log: string) => void) => {
    const subscription = (_event: any, log: string) => callback(log)
    ipcRenderer.on('sidecar-log', subscription)
    return () => {
      ipcRenderer.removeListener('sidecar-log', subscription)
    }
  },
  onSidecarStatus: (callback: (status: any) => void) => {
    const subscription = (_event: any, status: any) => callback(status)
    ipcRenderer.on('sidecar-status', subscription)
    return () => {
      ipcRenderer.removeListener('sidecar-status', subscription)
    }
  },
  restartSidecar: (config: { mode: 'serial' | 'ble'; portOrAddr?: string }) => {
    ipcRenderer.send('restart-sidecar', config)
  },
  getSerialPorts: () => ipcRenderer.invoke('get-serial-ports'),
  scanBle: () => ipcRenderer.invoke('scan-ble')
}

// Use `contextBridge` APIs to expose Electron APIs to
// renderer only if context isolation is enabled, otherwise
// just add to the DOM global.
if (process.contextIsolated) {
  try {
    contextBridge.exposeInMainWorld('electron', electronAPI)
    contextBridge.exposeInMainWorld('api', api)
  } catch (error) {
    console.error(error)
  }
} else {
  // @ts-ignore (define in dts)
  window.electron = electronAPI
  // @ts-ignore (define in dts)
  window.api = api
}
