import { ElectronAPI } from '@electron-toolkit/preload'

/** One row from `bridge.py --list-ports`. */
export interface SerialPortInfo {
  port: string
  desc: string
  hwid: string
}

/** One row from `bridge.py --scan`. `rssi` is null on backends that do not
 * report it. */
export interface BleDeviceInfo {
  address: string
  name: string
  rssi: number | null
}

export interface SidecarAPI {
  onSensorData: (callback: (data: any) => void) => () => void
  onSidecarLog: (callback: (log: any) => void) => () => void
  onSensorStatus: (callback: (status: any) => void) => () => void
  connectSensor: (config: { id: string; mode: 'serial' | 'ble'; target: string; position: string }) => void
  disconnectSensor: (id: string) => void
  getSerialPorts: () => Promise<SerialPortInfo[]>
  scanBle: (all?: boolean) => Promise<BleDeviceInfo[]>
  getNodeAliases: () => Promise<Record<string, string>>
  setNodeAlias: (target: string, alias: string) => Promise<Record<string, string>>
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
