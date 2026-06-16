import { ElectronAPI } from '@electron-toolkit/preload'

export interface SidecarAPI {
  onSensorData: (callback: (data: any) => void) => () => void
  onSidecarLog: (callback: (log: string) => void) => () => void
  onSidecarStatus: (callback: (status: any) => void) => () => void
  restartSidecar: (config: { mode: 'serial' | 'ble'; portOrAddr?: string }) => void
  getSerialPorts: () => Promise<any[]>
  scanBle: () => Promise<any[]>
}

declare global {
  interface Window {
    electron: ElectronAPI
    api: SidecarAPI
  }
}
