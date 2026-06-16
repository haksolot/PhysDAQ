import { ElectronAPI } from '@electron-toolkit/preload'

export interface SidecarAPI {
  onSensorData: (callback: (data: any) => void) => () => void
  onSidecarLog: (callback: (log: any) => void) => () => void
  onSensorStatus: (callback: (status: any) => void) => () => void
  connectSensor: (config: { id: string; mode: 'serial' | 'ble'; target: string; position: string }) => void
  disconnectSensor: (id: string) => void
  getSerialPorts: () => Promise<any[]>
  scanBle: () => Promise<any[]>
  startRecording: (sessionName: string) => Promise<{ success: boolean; sessionPath?: string; error?: string }>
  stopRecording: () => Promise<{ success: boolean; error?: string }>
  getRecordings: () => Promise<any[]>
  getRecordingData: (sessionPath: string, filename: string) => Promise<{ success: boolean; data?: any[]; error?: string }>
  deleteRecording: (sessionPath: string) => Promise<{ success: boolean; error?: string }>
}

declare global {
  interface Window {
    electron: ElectronAPI
    api: SidecarAPI
  }
}
