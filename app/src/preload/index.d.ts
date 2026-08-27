import { ElectronAPI } from '@electron-toolkit/preload'

/** One row from `bridge.py --list-ports`. */
export interface SerialPortInfo {
  port: string
  desc: string
  hwid: string
}

/** What class of PhysDAQ device this is. A node has one PPG sensor; a hub has
 * two plus a microSD card. */
export type DeviceType = 'node' | 'hub'

/** One row from `bridge.py --scan`. `rssi` is null on backends that do not
 * report it.
 *
 * `deviceType`/`ppgCount`/`hasSd` come from the manufacturer-specific field in
 * the advertisement and are a *hint for the scan list only* — discovery still
 * filters on the NUS service UUID, never on these or on the name. Firmware
 * older than AD protocol 2 omits the field, in which case these report a
 * single-PPG node; the ID line corrects that on connect. */
export interface BleDeviceInfo {
  address: string
  name: string
  rssi: number | null
  deviceType?: DeviceType
  ppgCount?: number
  hasSd?: boolean
}

/** The firmware image the app would flash, if it has one. */
export interface FirmwareInfo {
  available: boolean
  path?: string
  source: 'dev' | 'bundled'
  error?: string
}

export interface FlashProgress {
  stage: 'waiting' | 'copying' | 'done' | 'error'
  message: string
}

export interface SidecarAPI {
  onSensorData: (callback: (data: any) => void) => () => void
  onSidecarLog: (callback: (log: any) => void) => () => void
  onSensorStatus: (callback: (status: any) => void) => () => void
  connectSensor: (config: {
    id: string
    mode: 'serial' | 'ble'
    target: string
    position: string
    deviceType?: DeviceType
    /** Body-position slots this device feeds, one per PPG channel. Omit for a
     * node; a hub passes two, and channelSlots[0] must equal `id`. */
    channelSlots?: string[]
  }) => void
  disconnectSensor: (id: string) => void
  /** Send one command to a connected device. `id` is the slot the device was
   * linked on — for a hub, its first channel's slot. See docs/protocol.md for
   * the command set; replies arrive asynchronously through onSensorData. */
  sendDeviceCommand: (
    id: string,
    cmd: Record<string, unknown>
  ) => Promise<{ success: boolean; dest?: string; error?: string }>
  getSerialPorts: () => Promise<SerialPortInfo[]>
  scanBle: (all?: boolean) => Promise<BleDeviceInfo[]>
  getNodeAliases: () => Promise<Record<string, string>>
  setNodeAlias: (target: string, alias: string) => Promise<Record<string, string>>
  /** Both images. Node and hub run on the same board, so the app cannot
   * infer which one is wanted — the operator picks. */
  getFirmwareInfo: () => Promise<Record<'node' | 'hub', FirmwareInfo>>
  getBootloaderStatus: () => Promise<{ present: boolean; drive?: string }>
  flashNode: (image?: DeviceType) => Promise<{ success: boolean; error?: string }>
  onFlashProgress: (callback: (progress: FlashProgress) => void) => () => void
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
