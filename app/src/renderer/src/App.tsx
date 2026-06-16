import { useEffect, useState, useRef } from 'react'
import { RealTimeChart } from './components/RealTimeChart'
import { BoardVisualizer } from './components/BoardVisualizer'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle
} from '@/components/ui/dialog'
import { 
  Heart, 
  Bluetooth, 
  Usb, 
  Activity, 
  Battery as BatteryIcon, 
  Wifi, 
  WifiOff, 
  Cpu, 
  RefreshCw 
} from 'lucide-react'

interface SensorSample {
  type: 'sample'
  red: number
  ir: number
  ax: number
  ay: number
  az: number
  gx: number
  gy: number
  gz: number
  quat: [number, number, number, number]
  bpm: number | null
  contact: boolean
}

interface BatteryState {
  pct: number
  mv: number
}

interface SidecarStatus {
  status: 'connected' | 'disconnected'
  port?: string
  ble_addr?: string
}

export default function App() {
  const [mode, setMode] = useState<'serial' | 'ble'>('serial')
  const [target, setTarget] = useState<string>('')
  
  // Status states
  const [status, setStatus] = useState<SidecarStatus>({ status: 'disconnected' })
  const [battery, setBattery] = useState<BatteryState | null>(null)
  const [bpm, setBpm] = useState<number | null>(null)
  const [contact, setContact] = useState<boolean>(false)
  const [latestQuat, setLatestQuat] = useState<[number, number, number, number]>([1, 0, 0, 0])
  
  // Device discovery lists
  const [serialPorts, setSerialPorts] = useState<{ port: string; desc: string; hwid: string }[]>([])
  const [bleDevices, setBleDevices] = useState<{ address: string; name: string }[]>([])
  const [isScanning, setIsScanning] = useState<boolean>(false)
  const [isFetchingPorts, setIsFetchingPorts] = useState<boolean>(false)

  // Disconnect alert popup states
  const [showDisconnectDialog, setShowDisconnectDialog] = useState<boolean>(false)
  const wasConnected = useRef<boolean>(false)

  // High-frequency values throttled for UI text display
  const [textValues, setTextValues] = useState({
    ax: 0, ay: 0, az: 0,
    gx: 0, gy: 0, gz: 0
  })

  // Throttled logging console
  const [logs, setLogs] = useState<string[]>([])

  // Raw data ref for RealTimeCharts
  const dataRef = useRef<any[]>([])
  const sampleCounter = useRef<number>(0)

  const fetchPorts = async () => {
    setIsFetchingPorts(true)
    setLogs((prev) => ["[UI] Fetching available serial ports...", ...prev])
    try {
      const ports = await window.api.getSerialPorts()
      setSerialPorts(ports)
      if (ports.length > 0) {
        // Auto-select candidate if it has Xiao keywords
        const candidate = ports.find((p) =>
          p.desc.toUpperCase().includes('XIAO') ||
          p.desc.toUpperCase().includes('SEEED') ||
          p.desc.toUpperCase().includes('NRF52840') ||
          p.desc.toUpperCase().includes('J-LINK')
        )
        setTarget(candidate ? candidate.port : ports[0].port)
        setLogs((prev) => [`[UI] Found ${ports.length} port(s). Auto-selected: ${candidate ? candidate.port : ports[0].port}`, ...prev])
      } else {
        setLogs((prev) => ["[UI] No serial ports found.", ...prev])
      }
    } catch (err) {
      setLogs((prev) => [`[UI] Error listing ports: ${err}`, ...prev])
    } finally {
      setIsFetchingPorts(false)
    }
  }

  const startBleScan = async () => {
    setIsScanning(true)
    setLogs((prev) => ["[UI] Scanning for BLE wearables (MAID/XIAO) for 3s...", ...prev])
    try {
      const devices = await window.api.scanBle()
      setBleDevices(devices)
      if (devices.length > 0) {
        setTarget(devices[0].address)
        setLogs((prev) => [`[UI] Discovered ${devices.length} BLE devices. Auto-selected: ${devices[0].name} (${devices[0].address})`, ...prev])
      } else {
        setLogs((prev) => ["[UI] No compatible BLE devices found during scan.", ...prev])
      }
    } catch (err) {
      setLogs((prev) => [`[UI] BLE scan failed: ${err}`, ...prev])
    } finally {
      setIsScanning(false)
    }
  }

  // Fetch ports on startup
  useEffect(() => {
    fetchPorts()
  }, [])

  // Listen to Sidecar data
  useEffect(() => {
    // Listen to sensor data
    const unsubscribeSensor = window.api.onSensorData((data) => {
      if (data.type === 'battery') {
        setBattery({ pct: data.pct, mv: data.mv })
        return
      }

      if (data.type === 'sample') {
        const sample = data as SensorSample
        
        // Push to buffer for canvas charts
        dataRef.current.push(sample)
        if (dataRef.current.length > 300) {
          dataRef.current.shift()
        }

        // Throttle UI state updates to keep render cycle fast
        sampleCounter.current += 1
        if (sampleCounter.current % 5 === 0) {
          setBpm(sample.bpm)
          setContact(sample.contact)
          setLatestQuat(sample.quat)
          setTextValues({
            ax: sample.ax, ay: sample.ay, az: sample.az,
            gx: sample.gx, gy: sample.gy, gz: sample.gz
          })
        }
      }
    })

    // Listen to logs
    const unsubscribeLogs = window.api.onSidecarLog((log) => {
      setLogs((prev) => [log.trim(), ...prev.slice(0, 49)])
      
      // Parse connection status from logs if needed
      if (log.includes('connected to Serial')) {
        const portMatch = log.match(/Serial\s+(\S+)/)
        setStatus({ status: 'connected', port: portMatch ? portMatch[1] : 'USB' })
        wasConnected.current = true
      } else if (log.includes('connected to BLE')) {
        const addrMatch = log.match(/BLE\s+(\S+)/)
        setStatus({ status: 'connected', ble_addr: addrMatch ? addrMatch[1] : 'BLE' })
        wasConnected.current = true
      }
    })

    // Listen to sidecar status events
    const unsubscribeStatus = window.api.onSidecarStatus((event) => {
      if (event.status === 'disconnected') {
        setStatus({ status: 'disconnected' })
        setBpm(null)
        setContact(false)
        setLatestQuat([1, 0, 0, 0])
        dataRef.current = []

        if (wasConnected.current) {
          setShowDisconnectDialog(true)
          wasConnected.current = false
        }
      }
    })

    return () => {
      unsubscribeSensor()
      unsubscribeLogs()
      unsubscribeStatus()
    }
  }, [])

  const handleConnect = (): void => {
    wasConnected.current = false
    window.api.restartSidecar({
      mode,
      portOrAddr: target.trim() || undefined
    })
    setLogs((prev) => [`[UI] Connection request: ${mode} ${target}`, ...prev])
  }

  // Heart beat animation speed based on BPM
  const heartAnimDuration = bpm ? `${60 / bpm}s` : '1.2s'

  return (
    <div className="w-screen h-screen flex flex-col bg-[#1e1e2e] text-[#cdd6f4] font-sans overflow-hidden">
      
      {/* ── HEADER ── */}
      <header className="flex items-center justify-between px-6 py-4 bg-[#11111b]/80 border-b border-[#313244] backdrop-blur-md z-10">
        <div className="flex items-center gap-3">
          <Activity className="w-7 h-7 text-[#f38ba8] animate-pulse" />
          <div>
            <h1 className="text-lg font-bold tracking-tight text-[#cdd6f4]">
              MAID Wearable Interface
            </h1>
            <p className="text-[10px] font-mono text-[#a6adc8]">ACQUISITION & ANALYSIS SYSTEM</p>
          </div>
        </div>

        {/* Status bar */}
        <div className="flex items-center gap-6">
          {/* Connection status badge */}
          <div className="flex items-center gap-2 bg-[#181825] px-3 py-1.5 rounded-lg border border-[#313244]">
            {status.status === 'connected' ? (
              <>
                <span className="relative flex h-2 w-2">
                  <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-[#a6e3a1] opacity-75"></span>
                  <span className="relative inline-flex rounded-full h-2 w-2 bg-[#a6e3a1]"></span>
                </span>
                <span className="text-xs font-semibold text-[#a6e3a1]">
                  CONNECTED {status.port ? `(${status.port})` : status.ble_addr ? `(${status.ble_addr})` : ''}
                </span>
              </>
            ) : (
              <>
                <span className="h-2 w-2 rounded-full bg-[#f38ba8]"></span>
                <span className="text-xs font-semibold text-[#f38ba8]">DISCONNECTED</span>
              </>
            )}
          </div>

          {/* Battery Status */}
          {battery && (
            <div className="flex items-center gap-2 bg-[#181825] px-3 py-1.5 rounded-lg border border-[#313244]">
              {battery.pct > 20 ? (
                <BatteryIcon className="w-5 h-5 text-[#a6e3a1]" />
              ) : (
                <BatteryIcon className="w-5 h-5 text-[#f38ba8] animate-bounce" />
              )}
              <span className="text-xs font-mono font-bold text-[#cdd6f4]">
                {battery.pct}% <span className="text-[10px] text-[#a6adc8]">({battery.mv} mV)</span>
              </span>
            </div>
          )}
        </div>
      </header>

      {/* ── MAIN LAYOUT ── */}
      <main className="flex-1 flex overflow-hidden p-4 gap-4">
        
        {/* LEFT COLUMN: Controls & Charts */}
        <div className="flex-1 flex flex-col gap-4 overflow-y-auto pr-1">
          
          {/* Connection configuration card */}
          <div className="grid grid-cols-1 md:grid-cols-4 gap-4 p-4 rounded-xl bg-[#181825]/60 border border-[#313244]/50 backdrop-blur-md">
            
            {/* Mode selection */}
            <div className="flex flex-col gap-1.5">
              <label className="text-[10px] font-bold uppercase tracking-wider text-[#a6adc8]">Transport Mode</label>
              <div className="flex bg-[#11111b] p-1 rounded-lg border border-[#313244]">
                <button
                  onClick={() => { setMode('serial'); setTarget('') }}
                  className={`flex-1 flex items-center justify-center gap-1.5 py-1.5 rounded text-xs font-medium transition-all ${
                    mode === 'serial' ? 'bg-[#313244] text-[#cdd6f4]' : 'text-[#a6adc8] hover:text-[#cdd6f4]'
                  }`}
                >
                  <Usb className="w-3.5 h-3.5" />
                  USB Serial
                </button>
                <button
                  onClick={() => { setMode('ble'); setTarget('') }}
                  className={`flex-1 flex items-center justify-center gap-1.5 py-1.5 rounded text-xs font-medium transition-all ${
                    mode === 'ble' ? 'bg-[#313244] text-[#cdd6f4]' : 'text-[#a6adc8] hover:text-[#cdd6f4]'
                  }`}
                >
                  <Bluetooth className="w-3.5 h-3.5" />
                  BLE NUS
                </button>
              </div>
            </div>

            {/* Target Select & Input */}
            <div className="flex flex-col gap-1.5 md:col-span-2">
              <div className="flex justify-between items-center">
                <label className="text-[10px] font-bold uppercase tracking-wider text-[#a6adc8]">
                  {mode === 'serial' ? 'Select Port' : 'Select Device'}
                </label>
                {mode === 'serial' ? (
                  <button 
                    onClick={fetchPorts} 
                    disabled={isFetchingPorts}
                    className="text-[10px] text-[#cba6f7] hover:underline flex items-center gap-1 font-semibold focus:outline-none"
                  >
                    <RefreshCw className={`w-2.5 h-2.5 ${isFetchingPorts ? 'animate-spin' : ''}`} />
                    Refresh Ports
                  </button>
                ) : (
                  <button 
                    onClick={startBleScan} 
                    disabled={isScanning}
                    className="text-[10px] text-[#cba6f7] hover:underline flex items-center gap-1 font-semibold focus:outline-none"
                  >
                    <RefreshCw className={`w-2.5 h-2.5 ${isScanning ? 'animate-spin' : ''}`} />
                    {isScanning ? 'Scanning...' : 'Scan Devices'}
                  </button>
                )}
              </div>

              <div className="flex gap-2">
                {mode === 'serial' ? (
                  <select
                    value={target}
                    onChange={(e) => setTarget(e.target.value)}
                    className="bg-[#11111b] text-xs px-3 py-2.5 rounded-lg border border-[#313244] focus:outline-none focus:border-[#cba6f7] transition-all font-mono flex-1 text-[#cdd6f4]"
                  >
                    <option value="">-- Auto-detect --</option>
                    {serialPorts.map((p) => (
                      <option key={p.port} value={p.port}>
                        {p.port} {p.desc ? ` - ${p.desc}` : ''}
                      </option>
                    ))}
                  </select>
                ) : (
                  <select
                    value={target}
                    onChange={(e) => setTarget(e.target.value)}
                    className="bg-[#11111b] text-xs px-3 py-2.5 rounded-lg border border-[#313244] focus:outline-none focus:border-[#cba6f7] transition-all font-mono flex-1 text-[#cdd6f4]"
                  >
                    <option value="">-- Auto-detect / Scan --</option>
                    {bleDevices.map((d) => (
                      <option key={d.address} value={d.address}>
                        {d.name} ({d.address})
                      </option>
                    ))}
                  </select>
                )}
                
                {/* Manual override input (optional) */}
                <input
                  type="text"
                  value={target}
                  onChange={(e) => setTarget(e.target.value)}
                  placeholder="Manual Override"
                  className="bg-[#11111b] text-xs px-3 py-2.5 rounded-lg border border-[#313244] focus:outline-none focus:border-[#cba6f7] transition-all font-mono placeholder:text-[#585b70] w-36 text-center"
                />
              </div>
            </div>

            {/* Connect button */}
            <div className="flex items-end">
              <Button
                onClick={handleConnect}
                className="w-full flex items-center justify-center gap-2 font-bold"
              >
                <RefreshCw className="w-4 h-4" />
                Initialize Link
              </Button>
            </div>

          </div>

          {/* Real-time graphs */}
          <div className="flex-1 grid grid-cols-1 gap-4 min-h-[500px]">
            {/* Raw PPG chart */}
            <RealTimeChart
              title="PPG — Raw Signals (18-bit ADC)"
              channels={[
                { key: 'red', color: '#f38ba8', name: 'Red LED' },
                { key: 'ir', color: '#cba6f7', name: 'Infrared LED' }
              ]}
              dataRef={dataRef}
              yLabel="ADC Counts"
            />

            {/* Gyroscope chart */}
            <RealTimeChart
              title="IMU — Gyroscope Angular Velocity"
              channels={[
                { key: 'gx', color: '#f38ba8', name: 'Axis X' },
                { key: 'gy', color: '#a6e3a1', name: 'Axis Y' },
                { key: 'gz', color: '#89b4fa', name: 'Axis Z' }
              ]}
              dataRef={dataRef}
              yLabel="rad/s"
            />
          </div>

        </div>

        {/* RIGHT COLUMN: 3D orientation & Cardiac metrics */}
        <div className="w-[380px] flex flex-col gap-4 overflow-y-auto">
          
          {/* Card: 3D Visualization */}
          <div className="h-[340px] flex-shrink-0">
            <BoardVisualizer quat={latestQuat} />
          </div>

          {/* Card: Heart Rate & Skin Contact */}
          <div className="grid grid-cols-2 gap-4">
            
            {/* Heart Rate Display */}
            <div className="flex flex-col items-center justify-center p-4 rounded-xl bg-[#181825]/60 border border-[#313244]/50 backdrop-blur-md text-center">
              <span className="text-[10px] font-bold uppercase tracking-wider text-[#a6adc8] mb-2">Heart Rate</span>
              <div className="relative mb-2">
                <Heart 
                  className={`w-12 h-12 text-[#f38ba8] ${bpm ? 'animate-heartbeat' : ''}`}
                  style={{
                    animationDuration: heartAnimDuration,
                    animationIterationCount: 'infinite',
                    animationTimingFunction: 'ease-in-out'
                  }}
                />
              </div>
              <span className="text-3xl font-black text-[#f38ba8] font-mono">
                {bpm ? Math.round(bpm) : '—'}
              </span>
              <span className="text-[10px] text-[#a6adc8] font-semibold mt-1">BPM (spectral)</span>
            </div>

            {/* Skin Contact Indicator */}
            <div className="flex flex-col items-center justify-center p-4 rounded-xl bg-[#181825]/60 border border-[#313244]/50 backdrop-blur-md text-center">
              <span className="text-[10px] font-bold uppercase tracking-wider text-[#a6adc8] mb-2">Wear Status</span>
              <div className={`w-12 h-12 rounded-full flex items-center justify-center mb-2 border ${
                contact 
                  ? 'bg-[#a6e3a1]/10 border-[#a6e3a1]/30 text-[#a6e3a1]' 
                  : 'bg-[#f38ba8]/10 border-[#f38ba8]/30 text-[#f38ba8]'
              }`}>
                {contact ? <Wifi className="w-6 h-6 animate-pulse" /> : <WifiOff className="w-6 h-6" />}
              </div>
              <span className={`text-xl font-bold uppercase tracking-wide ${contact ? 'text-[#a6e3a1]' : 'text-[#f38ba8]'}`}>
                {contact ? 'WORN' : 'UNWORN'}
              </span>
              <span className="text-[10px] text-[#a6adc8] font-semibold mt-2">Skin Contact</span>
            </div>

          </div>

          {/* Card: Sensor Details */}
          <div className="p-4 rounded-xl bg-[#181825]/60 border border-[#313244]/50 backdrop-blur-md">
            <h3 className="text-xs font-semibold uppercase tracking-wider text-[#a6adc8] mb-3 flex items-center gap-1.5">
              <Cpu className="w-4 h-4 text-[#cba6f7]" />
              Telemetry Details
            </h3>
            
            <div className="space-y-2.5 text-xs font-mono">
              <div className="flex justify-between border-b border-[#313244]/55 pb-1.5">
                <span className="text-[#a6adc8]">Accel X:</span>
                <span className="text-[#cdd6f4]">{textValues.ax.toFixed(3)} m/s²</span>
              </div>
              <div className="flex justify-between border-b border-[#313244]/55 pb-1.5">
                <span className="text-[#a6adc8]">Accel Y:</span>
                <span className="text-[#cdd6f4]">{textValues.ay.toFixed(3)} m/s²</span>
              </div>
              <div className="flex justify-between border-b border-[#313244]/55 pb-1.5">
                <span className="text-[#a6adc8]">Accel Z:</span>
                <span className="text-[#cdd6f4]">{textValues.az.toFixed(3)} m/s²</span>
              </div>
              <div className="flex justify-between border-b border-[#313244]/55 pb-1.5">
                <span className="text-[#a6adc8]">Gyro X:</span>
                <span className="text-[#cdd6f4]">{textValues.gx.toFixed(3)} rad/s</span>
              </div>
              <div className="flex justify-between border-b border-[#313244]/55 pb-1.5">
                <span className="text-[#a6adc8]">Gyro Y:</span>
                <span className="text-[#cdd6f4]">{textValues.gy.toFixed(3)} rad/s</span>
              </div>
              <div className="flex justify-between pb-0.5">
                <span className="text-[#a6adc8]">Gyro Z:</span>
                <span className="text-[#cdd6f4]">{textValues.gz.toFixed(3)} rad/s</span>
              </div>
            </div>
          </div>

          {/* Console / Logs panel */}
          <div className="flex-1 flex flex-col p-4 rounded-xl bg-[#181825]/60 border border-[#313244]/50 backdrop-blur-md overflow-hidden min-h-[180px]">
            <span className="text-[10px] font-bold uppercase tracking-wider text-[#a6adc8] mb-2">System Logs</span>
            <div className="flex-1 bg-[#11111b] p-3 rounded-lg border border-[#313244] font-mono text-[10px] overflow-y-auto flex flex-col-reverse gap-1 text-[#a6adc8]">
              {logs.map((log, i) => (
                <div key={i} className="whitespace-pre-wrap leading-normal border-b border-[#181825] pb-0.5 last:border-b-0">
                  {log}
                </div>
              ))}
              {logs.length === 0 && <div className="text-[#585b70] italic">No logs yet. Click Initialize Link.</div>}
            </div>
          </div>

        </div>

      </main>

      {/* Disconnect Alert Dialog (Native shadcn components only, no custom colors) */}
      <Dialog open={showDisconnectDialog} onOpenChange={setShowDisconnectDialog}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <WifiOff className="w-5 h-5 text-destructive" />
              Sensor Connection Lost
            </DialogTitle>
            <DialogDescription>
              The connection to the MAID sensor module has been lost. Please verify that:
              <ul className="list-disc pl-5 pt-2 space-y-1 text-xs">
                <li>The sensor module is powered on and within range.</li>
                <li>The USB cable is securely plugged in (for Serial connection).</li>
                <li>The BLE Bluetooth is enabled on your host PC.</li>
              </ul>
            </DialogDescription>
          </DialogHeader>
          <div className="flex justify-end pt-4">
            <Button onClick={() => setShowDisconnectDialog(false)}>
              Acknowledge
            </Button>
          </div>
        </DialogContent>
      </Dialog>
    </div>
  )
}
