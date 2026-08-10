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
  onSidecarLog: (callback: (log: any) => void) => {
    const subscription = (_event: any, log: any) => callback(log)
    ipcRenderer.on('sidecar-log', subscription)
    return () => {
      ipcRenderer.removeListener('sidecar-log', subscription)
    }
  },
  onSensorStatus: (callback: (status: any) => void) => {
    const subscription = (_event: any, status: any) => callback(status)
    ipcRenderer.on('sensor-status', subscription)
    return () => {
      ipcRenderer.removeListener('sensor-status', subscription)
    }
  },
  connectSensor: (config: { id: string; mode: 'serial' | 'ble'; target: string; position: string }) => {
    ipcRenderer.send('connect-sensor', config)
  },
  disconnectSensor: (id: string) => {
    ipcRenderer.send('disconnect-sensor', id)
  },
  getSerialPorts: () => ipcRenderer.invoke('get-serial-ports'),
  scanBle: (all?: boolean) => ipcRenderer.invoke('scan-ble', all),
  getNodeAliases: () => ipcRenderer.invoke('get-node-aliases'),
  setNodeAlias: (target: string, alias: string) =>
    ipcRenderer.invoke('set-node-alias', target, alias),
  getFirmwareInfo: () => ipcRenderer.invoke('get-firmware-info'),
  getBootloaderStatus: () => ipcRenderer.invoke('get-bootloader-status'),
  flashNode: () => ipcRenderer.invoke('flash-node'),
  onFlashProgress: (callback: (progress: any) => void) => {
    const subscription = (_event: any, progress: any) => callback(progress)
    ipcRenderer.on('flash-progress', subscription)
    return () => {
      ipcRenderer.removeListener('flash-progress', subscription)
    }
  },
  startRecording: (sessionName: string) => ipcRenderer.invoke('start-recording', sessionName),
  stopRecording: () => ipcRenderer.invoke('stop-recording'),
  getRecordings: () => ipcRenderer.invoke('get-recordings'),
  getRecordingData: (sessionPath: string, filename: string) => ipcRenderer.invoke('get-recording-data', sessionPath, filename),
  deleteRecording: (sessionPath: string) => ipcRenderer.invoke('delete-recording', sessionPath)
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
