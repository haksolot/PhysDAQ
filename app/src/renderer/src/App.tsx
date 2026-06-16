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
  Card
} from '@/components/ui/card'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue
} from '@/components/ui/select'
import { Input } from '@/components/ui/input'
import { 
  Heart, 
  Bluetooth, 
  Usb, 
  Activity, 
  Battery as BatteryIcon, 
  Fingerprint, 
  WifiOff, 
  Cpu, 
  RefreshCw,
  FileText,
  Trash2,
  ArrowLeft,
  ChevronLeft,
  ChevronRight,
  ZoomIn,
  ZoomOut,
  RotateCcw
} from 'lucide-react'

interface SensorNode {
  id: string
  position: string
  label: string
  mode: 'serial' | 'ble'
  target: string
  status: 'disconnected' | 'connecting' | 'connected' | 'error'
  battery: { pct: number; mv: number } | null
  bpm: number | null
  contact: boolean
  quat: [number, number, number, number]
  ax: number
  ay: number
  az: number
  gx: number
  gy: number
  gz: number
  logs: string[]
}

const SENSOR_POSITIONS = [
  { id: 'head', label: 'Ear / Head', x: 50, y: 14 },
  { id: 'chest', label: 'Chest (Torso)', x: 50, y: 34 },
  { id: 'wrist_left', label: 'Left Wrist', x: 24, y: 44 },
  { id: 'wrist_right', label: 'Right Wrist', x: 76, y: 44 },
  { id: 'finger_left', label: 'Left Finger', x: 16, y: 48 },
  { id: 'finger_right', label: 'Right Finger', x: 84, y: 48 },
  { id: 'ankle_left', label: 'Left Ankle', x: 42, y: 84 },
  { id: 'ankle_right', label: 'Right Ankle', x: 58, y: 84 }
] as const

export default function App() {
  const [currentPage, setCurrentPage] = useState<'global' | 'detail' | 'recordings'>('global')
  const [selectedSensorId, setSelectedSensorId] = useState<string | null>(null)

  // Configure slot state
  const [configureOpen, setConfigureOpen] = useState(false)
  const [configureSlot, setConfigureSlot] = useState<string | null>(null)
  const [mode, setMode] = useState<'serial' | 'ble'>('ble')
  const [target, setTarget] = useState<string>('')

  // Device discovery lists
  const [serialPorts, setSerialPorts] = useState<{ port: string; desc: string; hwid: string }[]>([])
  const [bleDevices, setBleDevices] = useState<{ address: string; name: string }[]>([])
  const [isScanning, setIsScanning] = useState<boolean>(false)
  const [isFetchingPorts, setIsFetchingPorts] = useState<boolean>(false)
  const [scanError, setScanError] = useState<string | null>(null)

  // Disconnect alert popup states
  const [showDisconnectDialog, setShowDisconnectDialog] = useState<boolean>(false)
  const [disconnectAlertSensor, setDisconnectAlertSensor] = useState<string>('')

  // Session Recording states
  const [isRecording, setIsRecording] = useState(false)
  const [sessionName, setSessionName] = useState('MAID_Session')
  const [recordingDuration, setRecordingDuration] = useState(0)

  // Recordings database
  const [recordingsList, setRecordingsList] = useState<any[]>([])
  const [selectedSession, setSelectedSession] = useState<any | null>(null)
  const [selectedFile, setSelectedFile] = useState<any | null>(null)
  const [recordingData, setRecordingData] = useState<any[]>([])

  // Nodes state
  const [sensors, setSensors] = useState<Record<string, SensorNode>>({
    head: { id: 'head', position: 'head', label: 'Ear / Head', mode: 'ble', target: '', status: 'disconnected', battery: null, bpm: null, contact: false, quat: [1, 0, 0, 0], ax: 0, ay: 0, az: 0, gx: 0, gy: 0, gz: 0, logs: [] },
    chest: { id: 'chest', position: 'chest', label: 'Chest (Torso)', mode: 'serial', target: '', status: 'disconnected', battery: null, bpm: null, contact: false, quat: [1, 0, 0, 0], ax: 0, ay: 0, az: 0, gx: 0, gy: 0, gz: 0, logs: [] },
    wrist_left: { id: 'wrist_left', position: 'wrist_left', label: 'Left Wrist', mode: 'ble', target: '', status: 'disconnected', battery: null, bpm: null, contact: false, quat: [1, 0, 0, 0], ax: 0, ay: 0, az: 0, gx: 0, gy: 0, gz: 0, logs: [] },
    wrist_right: { id: 'wrist_right', position: 'wrist_right', label: 'Right Wrist', mode: 'ble', target: '', status: 'disconnected', battery: null, bpm: null, contact: false, quat: [1, 0, 0, 0], ax: 0, ay: 0, az: 0, gx: 0, gy: 0, gz: 0, logs: [] },
    finger_left: { id: 'finger_left', position: 'finger_left', label: 'Left Finger', mode: 'ble', target: '', status: 'disconnected', battery: null, bpm: null, contact: false, quat: [1, 0, 0, 0], ax: 0, ay: 0, az: 0, gx: 0, gy: 0, gz: 0, logs: [] },
    finger_right: { id: 'finger_right', position: 'finger_right', label: 'Right Finger', mode: 'ble', target: '', status: 'disconnected', battery: null, bpm: null, contact: false, quat: [1, 0, 0, 0], ax: 0, ay: 0, az: 0, gx: 0, gy: 0, gz: 0, logs: [] },
    ankle_left: { id: 'ankle_left', position: 'ankle_left', label: 'Left Ankle', mode: 'ble', target: '', status: 'disconnected', battery: null, bpm: null, contact: false, quat: [1, 0, 0, 0], ax: 0, ay: 0, az: 0, gx: 0, gy: 0, gz: 0, logs: [] },
    ankle_right: { id: 'ankle_right', position: 'ankle_right', label: 'Right Ankle', mode: 'ble', target: '', status: 'disconnected', battery: null, bpm: null, contact: false, quat: [1, 0, 0, 0], ax: 0, ay: 0, az: 0, gx: 0, gy: 0, gz: 0, logs: [] }
  })

  // Refs for real-time/static charts
  const dataRef = useRef<any[]>([])
  const staticPpgFiltRef = useRef<any[]>([])
  const staticPpgRef = useRef<any[]>([])
  const staticImuRef = useRef<any[]>([])

  // Session Zoom Range state
  const [sessionRange, setSessionRange] = useState<[number, number]>([0, 100])

  const fetchPorts = async () => {
    setIsFetchingPorts(true)
    setScanError(null)
    try {
      const ports = await window.api.getSerialPorts()
      setSerialPorts(ports)
      if (ports && ports.length > 0) {
        setTarget(ports[0].port)
      }
    } catch (err: any) {
      console.error('Error fetching ports:', err)
      setSerialPorts([])
      setScanError(err.message || String(err))
    } finally {
      setIsFetchingPorts(false)
    }
  }

  const startBleScan = async () => {
    setIsScanning(true)
    setScanError(null)
    try {
      const devices = await window.api.scanBle()
      setBleDevices(devices)
      if (devices && devices.length > 0) {
        setTarget(devices[0].address)
      }
    } catch (err: any) {
      console.error('Error scanning BLE:', err)
      setBleDevices([])
      setScanError(err.message || String(err))
    } finally {
      setIsScanning(false)
    }
  }

  // Clear discovery state, error, and automatically start scan on configure dialog open/mode change
  useEffect(() => {
    if (configureOpen) {
      setScanError(null)
      setSerialPorts([])
      setBleDevices([])

      if (mode === 'ble') {
        startBleScan()
      } else {
        fetchPorts()
      }
    }
  }, [configureOpen, mode])

  // Load recordings list
  const loadRecordings = async () => {
    const list = await window.api.getRecordings()
    setRecordingsList(list)
  }

  // Handle active selected sensor change - reset real-time chart buffer
  useEffect(() => {
    dataRef.current = []
  }, [selectedSensorId])

  // Handle recording elapsed timer
  useEffect(() => {
    let timer: NodeJS.Timeout
    if (isRecording) {
      timer = setInterval(() => {
        setRecordingDuration((prev) => prev + 1)
      }, 1000)
    } else {
      setRecordingDuration(0)
    }
    return () => clearInterval(timer)
  }, [isRecording])

  // IPC listeners
  useEffect(() => {
    const unsubscribeSensor = window.api.onSensorData((data) => {
      const { sensorId } = data
      if (!sensorId) return

      setSensors((prev) => {
        const node = prev[sensorId]
        if (!node) return prev

        if (data.type === 'battery') {
          return {
            ...prev,
            [sensorId]: { ...node, battery: { pct: data.pct, mv: data.mv } }
          }
        }

        if (data.type === 'sample') {
          // If we are currently displaying detail page for this sensor, append to chart ref
          if (currentPage === 'detail' && selectedSensorId === sensorId) {
            dataRef.current.push(data)
            if (dataRef.current.length > 300) dataRef.current.shift()
          }

          return {
            ...prev,
            [sensorId]: {
              ...node,
              bpm: data.bpm,
              contact: data.contact,
              quat: data.quat,
              ax: data.ax, ay: data.ay, az: data.az,
              gx: data.gx, gy: data.gy, gz: data.gz
            }
          }
        }
        return prev
      })
    })

    const unsubscribeLogs = window.api.onSidecarLog(({ sensorId, log }) => {
      if (!sensorId) return
      setSensors((prev) => {
        const node = prev[sensorId]
        if (!node) return prev
        return {
          ...prev,
          [sensorId]: { ...node, logs: [log.trim(), ...node.logs.slice(0, 49)] }
        }
      })
    })

    const unsubscribeStatus = window.api.onSensorStatus(({ sensorId, status: newStatus }) => {
      if (!sensorId) return
      setSensors((prev) => {
        const node = prev[sensorId]
        if (!node) return prev

        if (newStatus === 'disconnected' && node.status === 'connected') {
          setDisconnectAlertSensor(node.label)
          setShowDisconnectDialog(true)
        }

        return {
          ...prev,
          [sensorId]: {
            ...node,
            status: newStatus,
            bpm: newStatus === 'disconnected' ? null : node.bpm,
            contact: newStatus === 'disconnected' ? false : node.contact,
            battery: newStatus === 'disconnected' ? null : node.battery
          }
        }
      })
    })

    // Initial load
    fetchPorts()
    loadRecordings()

    return () => {
      unsubscribeSensor()
      unsubscribeLogs()
      unsubscribeStatus()
    }
  }, [currentPage, selectedSensorId])

  const handleConnect = () => {
    if (!configureSlot) return
    
    const finalTarget = target === 'auto-detect' ? '' : target
    window.api.connectSensor({
      id: configureSlot,
      mode,
      target: finalTarget,
      position: configureSlot
    })

    setSensors((prev) => ({
      ...prev,
      [configureSlot]: {
        ...prev[configureSlot],
        status: 'connecting',
        mode,
        target: finalTarget,
        logs: [`[UI] Initiating link to ${finalTarget || 'Auto'} via ${mode}...`]
      }
    }))
    setConfigureOpen(false)
  }

  const handleDisconnect = (slotId: string) => {
    window.api.disconnectSensor(slotId)
    setSensors((prev) => ({
      ...prev,
      [slotId]: {
        ...prev[slotId],
        status: 'disconnected',
        battery: null,
        bpm: null,
        contact: false
      }
    }))
  }

  const handleStartRecording = async () => {
    const res = await window.api.startRecording(sessionName)
    if (res.success) {
      setIsRecording(true)
    }
  }

  const handleStopRecording = async () => {
    const res = await window.api.stopRecording()
    if (res.success) {
      setIsRecording(false)
      loadRecordings()
    }
  }

  const handleDeleteRecording = async (path: string, e: React.MouseEvent) => {
    e.stopPropagation()
    const res = await window.api.deleteRecording(path)
    if (res.success) {
      setSelectedSession(null)
      setSelectedFile(null)
      setRecordingData([])
      loadRecordings()
    }
  }

  const handleSelectFile = async (file: any) => {
    setSelectedFile(file)
    setRecordingData([])
    setSessionRange([0, 100])
    if (selectedSession) {
      const res = await window.api.getRecordingData(selectedSession.path, file.filename)
      if (res.success && res.data) {
        setRecordingData(res.data)
      }
    }
  }

  // Downsample helper for static history chart to keep rendering fast
  const downsample = (arr: any[], max = 1000) => {
    if (arr.length <= max) return arr
    const step = arr.length / max
    const result: any[] = []
    for (let i = 0; i < max; i++) {
      result.push(arr[Math.floor(i * step)])
    }
    return result
  }

  // Get active subset of recording data based on interactive range selection
  const getSelectedDataSubset = () => {
    if (recordingData.length === 0) return []
    const startIdx = Math.floor((sessionRange[0] / 100) * (recordingData.length - 1))
    const endIdx = Math.floor((sessionRange[1] / 100) * (recordingData.length - 1))
    return recordingData.slice(startIdx, Math.max(startIdx + 2, endIdx + 1))
  }

  const currentSubset = getSelectedDataSubset()

  // Timeline Zoom and Pan functions
  const handleZoomIn = () => {
    const start = sessionRange[0]
    const end = sessionRange[1]
    const center = (start + end) / 2
    const newWidth = Math.max(5, (end - start) * 0.7)
    const newStart = Math.max(0, Math.round(center - newWidth / 2))
    const newEnd = Math.min(100, Math.round(center + newWidth / 2))
    setSessionRange([newStart, newEnd])
  }

  const handleZoomOut = () => {
    const start = sessionRange[0]
    const end = sessionRange[1]
    const center = (start + end) / 2
    const newWidth = Math.min(100, (end - start) * 1.3)
    const newStart = Math.max(0, Math.round(center - newWidth / 2))
    const newEnd = Math.min(100, Math.round(center + newWidth / 2))
    let finalStart = newStart
    let finalEnd = newEnd
    if (finalStart === 0) {
      finalEnd = Math.min(100, Math.round(newWidth))
    } else if (finalEnd === 100) {
      finalStart = Math.max(0, 100 - Math.round(newWidth))
    }
    setSessionRange([finalStart, finalEnd])
  }

  const handlePanLeft = () => {
    const start = sessionRange[0]
    const end = sessionRange[1]
    const width = end - start
    const shift = Math.max(5, Math.round(width * 0.2))
    const newStart = Math.max(0, start - shift)
    const newEnd = newStart + width
    setSessionRange([newStart, newEnd])
  }

  const handlePanRight = () => {
    const start = sessionRange[0]
    const end = sessionRange[1]
    const width = end - start
    const shift = Math.max(5, Math.round(width * 0.2))
    const newEnd = Math.min(100, end + shift)
    const newStart = newEnd - width
    setSessionRange([newStart, newEnd])
  }

  const timelineRef = useRef<HTMLDivElement | null>(null)
  const isDraggingTimeline = useRef<'pan' | 'none'>('none')
  const dragStartPct = useRef<number>(0)
  const dragStartRange = useRef<[number, number]>([0, 100])

  const handleTimelineMouseDown = (e: React.MouseEvent<HTMLDivElement>) => {
    if (!timelineRef.current) return
    const rect = timelineRef.current.getBoundingClientRect()
    const clickX = e.clientX - rect.left
    const clickPct = (clickX / rect.width) * 100

    const start = sessionRange[0]
    const end = sessionRange[1]

    if (clickPct >= start && clickPct <= end) {
      isDraggingTimeline.current = 'pan'
      dragStartPct.current = clickPct
      dragStartRange.current = [...sessionRange]
    } else {
      const width = end - start
      let newStart = Math.max(0, Math.round(clickPct - width / 2))
      let newEnd = newStart + width
      if (newEnd > 100) {
        newEnd = 100
        newStart = 100 - width
      }
      setSessionRange([newStart, newEnd])
      
      isDraggingTimeline.current = 'pan'
      dragStartPct.current = clickPct
      dragStartRange.current = [newStart, newEnd]
    }
  }

  useEffect(() => {
    const handleMouseMove = (e: MouseEvent) => {
      if (isDraggingTimeline.current === 'pan' && timelineRef.current) {
        const rect = timelineRef.current.getBoundingClientRect()
        const currentX = e.clientX - rect.left
        const currentPct = (currentX / rect.width) * 100
        const diff = currentPct - dragStartPct.current

        const startRange = dragStartRange.current
        const width = startRange[1] - startRange[0]

        let newStart = Math.max(0, Math.round(startRange[0] + diff))
        let newEnd = newStart + width

        if (newEnd > 100) {
          newEnd = 100
          newStart = 100 - width
        }

        setSessionRange([newStart, newEnd])
      }
    }

    const handleMouseUp = () => {
      isDraggingTimeline.current = 'none'
    }

    window.addEventListener('mousemove', handleMouseMove)
    window.addEventListener('mouseup', handleMouseUp)
    return () => {
      window.removeEventListener('mousemove', handleMouseMove)
      window.removeEventListener('mouseup', handleMouseUp)
    }
  }, [])

  const formatTimeOfDay = (isoString: string) => {
    if (!isoString) return ''
    try {
      const date = new Date(isoString)
      if (isNaN(date.getTime())) return ''
      return date.toLocaleTimeString(undefined, { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' })
    } catch (e) {
      return ''
    }
  }

  const getTimeAtPercent = (pct: number) => {
    if (recordingData.length === 0) return ''
    const idx = Math.max(0, Math.min(recordingData.length - 1, Math.floor((pct / 100) * (recordingData.length - 1))))
    const sample = recordingData[idx]
    if (!sample) return ''
    return formatTimeOfDay(sample.timestamp) || `${Math.floor(sample.elapsed)}s`
  }

  const getTimelineSparklinePoints = () => {
    if (recordingData.length === 0) return ''
    const sparkData = downsample(recordingData, 150)
    let minVal = sparkData[0]?.ppg_filt ?? sparkData[0]?.ir ?? 0
    let maxVal = minVal
    sparkData.forEach((d) => {
      const v = d.ppg_filt ?? d.ir ?? 0
      if (v < minVal) minVal = v
      if (v > maxVal) maxVal = v
    })
    const range = maxVal - minVal || 1
    
    return sparkData.map((d, i) => {
      const v = d.ppg_filt ?? d.ir ?? 0
      const x = (i / (sparkData.length - 1)) * 100
      const y = 20 - ((v - minVal) / range) * 16
      return `${x},${y}`
    }).join(' ')
  }
  staticPpgFiltRef.current = downsample(currentSubset)
  staticPpgRef.current = downsample(currentSubset)
  staticImuRef.current = downsample(currentSubset)

  const activeSensor = selectedSensorId ? sensors[selectedSensorId] : null
  const heartAnimDuration = activeSensor?.bpm ? `${60 / activeSensor.bpm}s` : '1.2s'

  return (
    <div className="w-screen h-screen flex flex-col bg-background text-foreground font-sans overflow-hidden">
      
      {/* ── HEADER ── */}
      <header className="flex items-center justify-between px-6 py-4 bg-card/80 border-b border-border backdrop-blur-md z-10">
        <div className="flex items-center gap-3">
          <Activity className="w-7 h-7 text-destructive animate-pulse" />
          <div>
            <h1 className="text-lg font-bold tracking-tight text-foreground">
              MAID Multi-Node Network
            </h1>
            <p className="text-[10px] font-mono text-muted-foreground">BODY SURFACE SENSOR ARRAY INTERFACE</p>
          </div>
        </div>

        {/* Global Nav Tabs */}
        <div className="flex bg-muted/40 p-1 rounded-lg border border-border h-9 items-center">
          <Button
            variant={currentPage === 'global' || currentPage === 'detail' ? 'secondary' : 'ghost'}
            size="sm"
            onClick={() => setCurrentPage('global')}
            className="h-7"
          >
            Body Network
          </Button>
          <Button
            variant={currentPage === 'recordings' ? 'secondary' : 'ghost'}
            size="sm"
            onClick={() => {
              setCurrentPage('recordings')
              loadRecordings()
            }}
            className="h-7"
          >
            Session Database
          </Button>
        </div>
      </header>

      {/* ── MAIN DASHBOARD VIEW ── */}
      {currentPage === 'global' && (
        <main className="flex-1 flex flex-col md:flex-row overflow-y-auto md:overflow-hidden p-4 gap-4">
          
          {/* LEFT PANEL: Nodes Configuration & Live Status */}
          <div className="w-full md:w-[380px] flex flex-col gap-4 md:overflow-y-auto pr-1">
            
            {/* Recorder controls */}
            <Card className="p-4 bg-card/60 backdrop-blur-md border border-border">
              <span className="text-[10px] font-bold uppercase tracking-wider text-muted-foreground block mb-2">Session Recording</span>
              
              <div className="flex gap-2 mb-3">
                <Input
                  type="text"
                  value={sessionName}
                  onChange={(e) => setSessionName(e.target.value)}
                  placeholder="Session Name"
                  disabled={isRecording}
                  className="bg-background/50 h-9 font-mono"
                />
                
                {isRecording ? (
                  <Button variant="destructive" onClick={handleStopRecording} className="h-9 font-bold">
                    Stop ({Math.floor(recordingDuration / 60)}:{(recordingDuration % 60).toString().padStart(2, '0')})
                  </Button>
                ) : (
                  <Button onClick={handleStartRecording} className="h-9 font-bold bg-emerald-600 hover:bg-emerald-500 text-white">
                    Record
                  </Button>
                )}
              </div>
              
              <div className="text-[10px] font-mono text-muted-foreground">
                {isRecording ? (
                  <span className="text-emerald-500 font-bold animate-pulse flex items-center gap-1.5">
                    <span className="w-1.5 h-1.5 rounded-full bg-emerald-500" />
                    RECORDING ONGOING ACROSS CONNECTED NODES
                  </span>
                ) : (
                  <span>Ready. Recorded CSV files save to Documents/MAID_Sessions</span>
                )}
              </div>
            </Card>

            {/* Configured nodes list */}
            <Card className="flex-1 p-4 bg-card/60 backdrop-blur-md border border-border flex flex-col overflow-hidden min-h-[350px] md:min-h-0">
              <span className="text-[10px] font-bold uppercase tracking-wider text-muted-foreground block mb-3">Sensor Nodes Inventory</span>
              
              <div className="flex-1 overflow-y-auto flex flex-col gap-3.5 pr-1">
                {SENSOR_POSITIONS.map((pos) => {
                  const sensor = sensors[pos.id]
                  const isConnected = sensor.status === 'connected'
                  const isConnecting = sensor.status === 'connecting'
                  
                  return (
                    <div key={pos.id} className="p-3 rounded-lg border border-border/80 bg-background/30 flex items-center justify-between transition-all hover:bg-background/50">
                      <div className="flex flex-col gap-0.5">
                        <span className="text-xs font-bold text-foreground">{pos.label}</span>
                        <div className="flex items-center gap-1.5 text-[9px] font-mono text-muted-foreground">
                          {isConnected ? (
                            <>
                              <span className="text-emerald-500 font-bold">LIVE</span>
                              <span>•</span>
                              <span>{sensor.mode.toUpperCase()}</span>
                              {sensor.battery && (
                                <>
                                  <span>•</span>
                                  <span className={sensor.battery.pct < 20 ? 'text-destructive font-bold' : ''}>
                                    {sensor.battery.pct}%
                                  </span>
                                </>
                              )}
                              {sensor.bpm && (
                                <>
                                  <span>•</span>
                                  <span className="text-destructive font-bold">{Math.round(sensor.bpm)} BPM</span>
                                </>
                              )}
                            </>
                          ) : isConnecting ? (
                            <span className="text-primary font-bold animate-pulse">CONNECTING...</span>
                          ) : (
                            <span className="text-muted-foreground/60 font-semibold">DISCONNECTED</span>
                          )}
                        </div>
                      </div>

                      <div className="flex gap-1.5">
                        {isConnected ? (
                          <>
                            <Button
                              variant="outline"
                              size="xs"
                              onClick={() => {
                                setSelectedSensorId(pos.id)
                                setCurrentPage('detail')
                              }}
                            >
                              Detail
                            </Button>
                            <Button
                              variant="destructive"
                              size="xs"
                              onClick={() => handleDisconnect(pos.id)}
                            >
                              Kill
                            </Button>
                          </>
                        ) : (
                          <Button
                            variant="secondary"
                            size="xs"
                            disabled={isConnecting}
                            onClick={() => {
                              setConfigureSlot(pos.id)
                              setMode(sensor.mode)
                              setTarget(sensor.target)
                              setConfigureOpen(true)
                            }}
                          >
                            Link
                          </Button>
                        )}
                      </div>
                    </div>
                  )
                })}
              </div>
            </Card>
          </div>

          {/* RIGHT PANEL: Interactive Body Outline Visualizer */}
          <Card className="flex-1 flex flex-col p-4 bg-card/60 backdrop-blur-md border border-border relative overflow-hidden min-h-[500px] md:min-h-0">
            <div className="absolute top-4 left-4 z-10">
              <span className="text-[10px] font-bold uppercase tracking-wider text-muted-foreground block mb-1">Body Node Placement Map</span>
              <span className="text-[9px] font-mono text-muted-foreground/60">CLICK NODE TO ACCESS INDIVIDUAL CALIBRATION & LIVE SIGNAL GRAPHS</span>
            </div>

            {/* SVG silhouette container */}
            <div className="flex-1 relative w-full h-full flex items-center justify-center pt-8">
              <svg viewBox="0 0 100 100" className="h-full max-h-[520px] text-muted-foreground/30 stroke-current" fill="none" strokeWidth="1" strokeLinecap="round" strokeLinejoin="round">
                <defs>
                  <pattern id="grid" width="10" height="10" patternUnits="userSpaceOnUse">
                    <path d="M 10 0 L 0 0 0 10" fill="none" stroke="currentColor" strokeWidth="0.08" opacity="0.15" />
                  </pattern>
                </defs>
                <rect width="100" height="100" fill="url(#grid)" opacity="0.3" />

                {/* Tech circles */}
                <circle cx="50" cy="50" r="45" strokeDasharray="1 3" strokeWidth="0.4" opacity="0.25" />
                <circle cx="50" cy="50" r="32" strokeDasharray="2 4" strokeWidth="0.4" opacity="0.15" />

                {/* Human Silhouette Wireframe */}
                {/* Head */}
                <circle cx="50" cy="14" r="5" fill="currentColor" fillOpacity="0.03" strokeWidth="1.2" />
                
                {/* Neck & Shoulders */}
                <path d="M 47 18.5 C 47 21, 40 22, 40 24" strokeWidth="1.2" />
                <path d="M 53 18.5 C 53 21, 60 22, 60 24" strokeWidth="1.2" />

                {/* Left Arm & Finger */}
                <path d="M 40 24 L 32 34 L 24 44 L 16 48" strokeWidth="1.2" />
                
                {/* Right Arm & Finger */}
                <path d="M 60 24 L 68 34 L 76 44 L 84 48" strokeWidth="1.2" />

                {/* Torso & Hips */}
                <path d="M 40 24 L 42 54 L 58 54 L 60 24 Z" fill="currentColor" fillOpacity="0.02" strokeWidth="1.2" />
                <line x1="50" y1="19" x2="50" y2="54" strokeWidth="0.8" strokeDasharray="2 2" opacity="0.5" />
                
                {/* Rib lines (sci-fi detail) */}
                <line x1="43" y1="30" x2="57" y2="30" strokeWidth="0.6" opacity="0.3" />
                <line x1="44" y1="36" x2="56" y2="36" strokeWidth="0.6" opacity="0.3" />
                <line x1="45" y1="42" x2="55" y2="42" strokeWidth="0.6" opacity="0.3" />

                {/* Left Leg */}
                <path d="M 43 54 L 42 84 L 38 87" strokeWidth="1.2" />

                {/* Right Leg */}
                <path d="M 57 54 L 58 84 L 62 87" strokeWidth="1.2" />
              </svg>
              
              {/* Interactive positioned markers */}
              {SENSOR_POSITIONS.map((pos) => {
                const sensor = sensors[pos.id]
                const isConnected = sensor.status === 'connected'
                const isConnecting = sensor.status === 'connecting'
                const isWorn = sensor.contact
                
                let badgeColor = 'bg-muted border-muted-foreground/30 text-muted-foreground'
                let glowColor = ''
                if (isConnected) {
                  if (isWorn) {
                    badgeColor = 'bg-emerald-500/10 border-emerald-500/30 text-emerald-500'
                    glowColor = 'animate-ping bg-emerald-500'
                  } else {
                    badgeColor = 'bg-amber-500/10 border-amber-500/30 text-amber-500'
                    glowColor = 'animate-pulse bg-amber-500'
                  }
                } else if (isConnecting) {
                  badgeColor = 'bg-primary/10 border-primary/30 text-primary animate-pulse'
                  glowColor = 'animate-pulse bg-primary'
                }
                
                return (
                  <div
                    key={pos.id}
                    className="absolute -translate-x-1/2 -translate-y-1/2 flex flex-col items-center gap-1 z-20"
                    style={{ top: `${pos.y}%`, left: `${pos.x}%` }}
                  >
                    {/* Glowing status circle */}
                    <div className="relative flex h-3.5 w-3.5 items-center justify-center">
                      {glowColor && (
                        <span className={`absolute inline-flex h-full w-full rounded-full opacity-75 ${glowColor}`}></span>
                      )}
                      <span className={`relative inline-flex rounded-full h-3 w-3 border ${
                        isConnected 
                          ? (isWorn ? 'bg-emerald-500 border-emerald-600' : 'bg-amber-500 border-amber-600') 
                          : (isConnecting ? 'bg-primary border-primary' : 'bg-muted-foreground/60 border-border')
                      }`}></span>
                    </div>
                    
                    {/* Interactive label */}
                    <button
                      onClick={() => {
                        if (isConnected) {
                          setSelectedSensorId(pos.id)
                          setCurrentPage('detail')
                        } else {
                          setConfigureSlot(pos.id)
                          setMode(sensor.mode)
                          setTarget(sensor.target)
                          setConfigureOpen(true)
                        }
                      }}
                      className={`px-2 py-0.5 rounded-md text-[9px] font-mono font-bold tracking-tight uppercase border transition-all cursor-pointer hover:scale-105 active:scale-95 shadow-sm bg-card/90 backdrop-blur-xs ${badgeColor}`}
                    >
                      {sensor.position.replace('_', ' ')}
                    </button>
                  </div>
                )
              })}
            </div>
          </Card>
        </main>
      )}

      {/* ── INDIVIDUAL SENSOR DETAIL VIEW ── */}
      {currentPage === 'detail' && activeSensor && (
        <main className="flex-1 flex flex-col lg:flex-row overflow-y-auto lg:overflow-hidden p-4 gap-4">
          
          {/* LEFT COLUMN: Node Controls & Raw Logs */}
          <div className="flex-1 flex flex-col gap-4 lg:overflow-y-auto pr-1">
            
            {/* Back panel */}
            <div className="flex justify-between items-center bg-card/60 border border-border p-3 rounded-xl backdrop-blur-md">
              <Button variant="ghost" size="sm" onClick={() => setCurrentPage('global')} className="h-8 gap-1 font-semibold">
                <ArrowLeft className="w-4 h-4" />
                Back to Body Network
              </Button>
              <div className="flex items-center gap-3">
                <span className="text-[10px] font-mono text-primary bg-primary/10 px-2 py-0.5 rounded border border-primary/20">
                  NODE: {activeSensor.label.toUpperCase()}
                </span>
                <span className="text-[10px] font-mono text-muted-foreground">
                  {activeSensor.mode.toUpperCase()} • {activeSensor.target || 'AUTO'}
                </span>
              </div>
            </div>

            {/* Real-time graphs */}
            <div className="flex-1 flex flex-col gap-4 min-h-[680px]">
              <RealTimeChart
                title="PPG — Filtered Signal (BPM AC Waveform)"
                channels={[
                  { key: 'ppg_filt', color: '#a6e3a1', name: 'Filtered Signal' }
                ]}
                dataRef={dataRef}
                yLabel="AC Amplitude"
                autoScale={true}
              />

              <RealTimeChart
                title="PPG — Raw Signals (18-bit ADC)"
                channels={[
                  { key: 'red', color: '#f38ba8', name: 'Red LED' },
                  { key: 'ir', color: '#cba6f7', name: 'Infrared LED' }
                ]}
                dataRef={dataRef}
                yLabel="ADC Counts"
                autoScale={true}
              />

              <RealTimeChart
                title="IMU — Gyroscope Angular Velocity"
                channels={[
                  { key: 'gx', color: '#f38ba8', name: 'Axis X' },
                  { key: 'gy', color: '#a6e3a1', name: 'Axis Y' },
                  { key: 'gz', color: '#89b4fa', name: 'Axis Z' }
                ]}
                dataRef={dataRef}
                yLabel="rad/s"
                autoScale={true}
              />
            </div>
          </div>

          {/* RIGHT COLUMN: 3D Board & Node Details */}
          <div className="w-full lg:w-[380px] flex flex-col gap-4 lg:overflow-y-auto pr-1">
            
            {/* 3D Board Orientation Mesh */}
            <div className="h-[340px] flex-shrink-0">
              <BoardVisualizer quat={activeSensor.quat} />
            </div>

            {/* Heart Rate / Wear status */}
            <div className="grid grid-cols-2 gap-4">
              
              {/* Heart Rate Display */}
              <Card className="flex flex-col items-center justify-center p-4 bg-card/60 backdrop-blur-md text-center border-border">
                <span className="text-[10px] font-bold uppercase tracking-wider text-muted-foreground mb-2">Heart Rate</span>
                <div className="relative mb-2">
                  <Heart 
                    className={`w-12 h-12 text-destructive ${activeSensor.bpm ? 'animate-heartbeat' : ''}`}
                    style={{
                      animationDuration: heartAnimDuration,
                      animationIterationCount: 'infinite',
                      animationTimingFunction: 'ease-in-out'
                    }}
                  />
                </div>
                <span className="text-3xl font-black text-destructive font-mono">
                  {activeSensor.bpm ? Math.round(activeSensor.bpm) : '—'}
                </span>
                <span className="text-[10px] text-muted-foreground font-semibold mt-1">BPM (spectral)</span>
              </Card>

              {/* Skin Contact Indicator */}
              <Card className="flex flex-col items-center justify-center p-4 bg-card/60 backdrop-blur-md text-center border-border">
                <span className="text-[10px] font-bold uppercase tracking-wider text-muted-foreground mb-2">Wear Status</span>
                <div className={`w-12 h-12 rounded-full flex items-center justify-center mb-2 border ${
                  activeSensor.contact 
                    ? 'bg-emerald-500/10 border-emerald-500/30 text-emerald-500' 
                    : 'bg-destructive/10 border-destructive/30 text-destructive'
                }`}>
                  <Fingerprint className={`w-6 h-6 ${activeSensor.contact ? 'animate-pulse' : ''}`} />
                </div>
                <span className={`text-xl font-bold uppercase tracking-wide ${activeSensor.contact ? 'text-emerald-500' : 'text-destructive'}`}>
                  {activeSensor.contact ? 'WORN' : 'UNWORN'}
                </span>
                <span className="text-[10px] text-muted-foreground font-semibold mt-2">Skin Contact</span>
              </Card>
            </div>

            {/* Battery state */}
            {activeSensor.battery && (
              <Card className="flex items-center justify-between p-3 bg-card/60 backdrop-blur-md border border-border">
                <span className="text-[10px] font-bold uppercase tracking-wider text-muted-foreground">Battery Level</span>
                <div className="flex items-center gap-2">
                  {activeSensor.battery.pct > 20 ? (
                    <BatteryIcon className="w-5 h-5 text-emerald-500" />
                  ) : (
                    <BatteryIcon className="w-5 h-5 text-destructive animate-bounce" />
                  )}
                  <span className="text-xs font-mono font-bold">
                    {activeSensor.battery.pct}% <span className="text-[9px] text-muted-foreground font-normal">({activeSensor.battery.mv} mV)</span>
                  </span>
                </div>
              </Card>
            )}

            {/* Telemetry info */}
            <Card className="p-4 bg-card/60 backdrop-blur-md border border-border">
              <h3 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground mb-3 flex items-center gap-1.5">
                <Cpu className="w-4 h-4 text-primary" />
                Node Telemetry
              </h3>
              
              <div className="space-y-2 text-xs font-mono">
                <div className="flex justify-between border-b border-border pb-1.5">
                  <span className="text-muted-foreground">Accel X:</span>
                  <span className="text-foreground">{activeSensor.ax.toFixed(3)} m/s²</span>
                </div>
                <div className="flex justify-between border-b border-border pb-1.5">
                  <span className="text-muted-foreground">Accel Y:</span>
                  <span className="text-foreground">{activeSensor.ay.toFixed(3)} m/s²</span>
                </div>
                <div className="flex justify-between border-b border-border pb-1.5">
                  <span className="text-muted-foreground">Accel Z:</span>
                  <span className="text-foreground">{activeSensor.az.toFixed(3)} m/s²</span>
                </div>
                <div className="flex justify-between border-b border-border pb-1.5">
                  <span className="text-muted-foreground">Gyro X:</span>
                  <span className="text-foreground">{activeSensor.gx.toFixed(3)} rad/s</span>
                </div>
                <div className="flex justify-between border-b border-border pb-1.5">
                  <span className="text-muted-foreground">Gyro Y:</span>
                  <span className="text-foreground">{activeSensor.gy.toFixed(3)} rad/s</span>
                </div>
                <div className="flex justify-between pb-0.5">
                  <span className="text-muted-foreground">Gyro Z:</span>
                  <span className="text-foreground">{activeSensor.gz.toFixed(3)} rad/s</span>
                </div>
              </div>
            </Card>

            {/* System Logs */}
            <Card className="flex-1 flex flex-col p-4 bg-card/60 backdrop-blur-md overflow-hidden min-h-[180px] border border-border">
              <span className="text-[10px] font-bold uppercase tracking-wider text-muted-foreground mb-2">System Logs</span>
              <div className="flex-1 bg-background/50 p-3 rounded-lg border border-border font-mono text-[10px] overflow-y-auto flex flex-col-reverse gap-1 text-muted-foreground">
                {activeSensor.logs.map((log, i) => (
                  <div key={i} className="whitespace-pre-wrap leading-normal border-b border-border/30 pb-0.5 last:border-b-0">
                    {log}
                  </div>
                ))}
                {activeSensor.logs.length === 0 && <div className="text-muted-foreground/50 italic">No logs yet. Link active.</div>}
              </div>
            </Card>
          </div>
        </main>
      )}

      {/* ── RECORDINGS DATABASE EXPLORER VIEW ── */}
      {currentPage === 'recordings' && (
        <main className="flex-1 flex flex-col md:flex-row overflow-y-auto md:overflow-hidden p-4 gap-4">
          
          {/* LEFT PANEL: Sessions folder list */}
          <Card className="w-full md:w-[380px] p-4 bg-card/60 backdrop-blur-md border border-border flex flex-col overflow-hidden min-h-[350px] md:min-h-0">
            <span className="text-[10px] font-bold uppercase tracking-wider text-muted-foreground block mb-3">Saved Session Folders</span>
            
            <div className="flex-1 overflow-y-auto flex flex-col gap-3.5 pr-1">
              {recordingsList.map((rec) => (
                <div
                  key={rec.path}
                  onClick={() => {
                    setSelectedSession(rec)
                    setSelectedFile(null)
                    setRecordingData([])
                  }}
                  className={`p-3 rounded-lg border text-left cursor-pointer transition-all hover:bg-background/40 ${
                    selectedSession?.path === rec.path ? 'bg-background/60 border-primary' : 'bg-background/25 border-border/70'
                  }`}
                >
                  <div className="flex justify-between items-start">
                    <span className="text-xs font-bold text-foreground truncate max-w-[200px]">{rec.name.replace(/_/g, ' ')}</span>
                    <button
                      onClick={(e) => handleDeleteRecording(rec.path, e)}
                      className="text-muted-foreground hover:text-destructive p-1 rounded transition-all cursor-pointer"
                    >
                      <Trash2 className="w-3.5 h-3.5" />
                    </button>
                  </div>
                  
                  <div className="text-[9px] font-mono text-muted-foreground/80 mt-1 space-y-0.5">
                    <div>DATE: {new Date(rec.date.replace(/(\d{4}-\d{2}-\d{2})T(\d{2})-(\d{2})-(\d{2})-(\d{3}Z)/, '$1T$2:$3:$4.$5')).toLocaleString()}</div>
                    <div>DURATION: {Math.floor(rec.duration_s / 60)}m {rec.duration_s % 60}s</div>
                    <div className="text-[8px] text-primary/75 font-semibold mt-1">NODES: {rec.sensors.map((s: string) => s.replace('_', ' ')).join(', ')}</div>
                  </div>
                </div>
              ))}
              {recordingsList.length === 0 && (
                <div className="text-xs text-muted-foreground/60 italic text-center pt-8">No recorded sessions found.</div>
              )}
            </div>
          </Card>

          {/* RIGHT PANEL: Session data plotter and viewer */}
          <Card className="flex-1 p-4 bg-card/60 backdrop-blur-md border border-border flex flex-col overflow-hidden min-h-[500px] md:min-h-0">
            {selectedSession ? (
              <div className="flex-1 flex flex-col overflow-hidden">
                
                {/* Session details */}
                <div className="border-b border-border pb-3 mb-4 flex flex-col sm:flex-row justify-between items-start sm:items-center gap-3 flex-shrink-0">
                  <div>
                    <h2 className="text-sm font-bold text-foreground">{selectedSession.name.replace(/_/g, ' ')}</h2>
                    <span className="text-[10px] font-mono text-muted-foreground">
                      {new Date(selectedSession.date.replace(/(\d{4}-\d{2}-\d{2})T(\d{2})-(\d{2})-(\d{2})-(\d{3}Z)/, '$1T$2:$3:$4.$5')).toLocaleString()} • {selectedSession.sensors.length} nodes
                    </span>
                  </div>

                  {/* CSV File selector */}
                  <div className="flex flex-wrap gap-2">
                    {selectedSession.files.map((file: any) => (
                      <Button
                        key={file.filename}
                        variant={selectedFile?.filename === file.filename ? 'default' : 'secondary'}
                        size="sm"
                        onClick={() => handleSelectFile(file)}
                        className="h-8 text-xs font-semibold gap-1.5"
                      >
                        <FileText className="w-3.5 h-3.5" />
                        {file.position.replace('_', ' ').toUpperCase()}
                      </Button>
                    ))}
                  </div>
                </div>

                {/* Plotter area */}
                {selectedFile ? (
                  recordingData.length > 0 ? (
                    <div className="flex-1 flex flex-col gap-4 overflow-y-auto pr-1">
                      {/* Timeline range selector */}
                      <Card className="p-3 bg-muted/20 border border-border/60 flex flex-col gap-3 rounded-xl flex-shrink-0">
                        <div className="flex justify-between items-center text-xs">
                          <span className="font-bold text-muted-foreground uppercase tracking-wider text-[9px]">Timeline Range Zoom</span>
                          <span className="font-mono text-primary font-bold">
                            {getTimeAtPercent(sessionRange[0]) || `${sessionRange[0]}%`} - {getTimeAtPercent(sessionRange[1]) || `${sessionRange[1]}%`}
                            {` (${Math.round((sessionRange[0] / 100) * recordingData.length)} to ${Math.round((sessionRange[1] / 100) * recordingData.length)} of ${recordingData.length} samples)`}
                          </span>
                        </div>
                        
                        {/* Interactive Drag Scrubber Track */}
                        <div className="relative">
                          <div
                            ref={timelineRef}
                            onMouseDown={handleTimelineMouseDown}
                            className="relative w-full h-8 bg-background/60 border border-border rounded-lg overflow-hidden cursor-ew-resize select-none flex items-center"
                          >
                            {/* Tiny Waveform Preview behind track */}
                            <svg className="absolute inset-0 w-full h-full text-primary/10 fill-none" viewBox="0 0 100 20" preserveAspectRatio="none">
                              <polyline
                                fill="none"
                                stroke="currentColor"
                                strokeWidth="0.8"
                                points={getTimelineSparklinePoints()}
                              />
                            </svg>

                            {/* Viewport box */}
                            <div
                              className="absolute h-full bg-primary/15 border-l-2 border-r-2 border-primary backdrop-blur-[0.5px] transition-shadow flex items-center justify-between px-1"
                              style={{
                                left: `${sessionRange[0]}%`,
                                width: `${sessionRange[1] - sessionRange[0]}%`
                              }}
                            >
                              <div className="w-0.5 h-3 bg-primary/50 rounded-sm" />
                              <div className="w-0.5 h-3 bg-primary/50 rounded-sm" />
                            </div>

                            {/* Time markings inside track */}
                            <div className="absolute inset-x-0 bottom-0.5 flex justify-between px-2 text-[7px] font-mono text-muted-foreground/50 pointer-events-none select-none">
                              <span>{getTimeAtPercent(0)}</span>
                              <span>{getTimeAtPercent(25)}</span>
                              <span>{getTimeAtPercent(50)}</span>
                              <span>{getTimeAtPercent(75)}</span>
                              <span>{getTimeAtPercent(100)}</span>
                            </div>
                          </div>
                          <span className="text-[8px] text-muted-foreground/60 mt-1 block text-center font-mono">
                            CLICK OR DRAG VIEWPORT TO PAN/SCROLL THROUGH TIMELINE
                          </span>
                        </div>

                        {/* Timeline controls bar */}
                        <div className="flex flex-wrap items-center justify-between gap-3 pt-1 border-t border-border/40">
                          {/* Zoom and Scroll Buttons */}
                          <div className="flex gap-1.5">
                            <Button
                              variant="outline"
                              size="xs"
                              onClick={handlePanLeft}
                              disabled={sessionRange[0] === 0}
                              className="text-[10px] h-7 px-2 font-bold font-mono gap-1"
                              title="Pan Left"
                            >
                              <ChevronLeft className="w-3.5 h-3.5" />
                              Scroll Left
                            </Button>
                            <Button
                              variant="outline"
                              size="xs"
                              onClick={handlePanRight}
                              disabled={sessionRange[1] === 100}
                              className="text-[10px] h-7 px-2 font-bold font-mono gap-1"
                              title="Pan Right"
                            >
                              Scroll Right
                              <ChevronRight className="w-3.5 h-3.5" />
                            </Button>
                            <span className="h-7 w-px bg-border/60 mx-1" />
                            <Button
                              variant="outline"
                              size="xs"
                              onClick={handleZoomIn}
                              disabled={sessionRange[1] - sessionRange[0] <= 5}
                              className="text-[10px] h-7 px-2 font-bold font-mono gap-1"
                              title="Zoom In"
                            >
                              <ZoomIn className="w-3.5 h-3.5" />
                              Zoom In (+)
                            </Button>
                            <Button
                              variant="outline"
                              size="xs"
                              onClick={handleZoomOut}
                              disabled={sessionRange[0] === 0 && sessionRange[1] === 100}
                              className="text-[10px] h-7 px-2 font-bold font-mono gap-1"
                              title="Zoom Out"
                            >
                              <ZoomOut className="w-3.5 h-3.5" />
                              Zoom Out (-)
                            </Button>
                          </div>

                          {/* Fallback fine-grained control sliders */}
                          <div className="flex gap-4 items-center flex-1 md:flex-initial">
                            <div className="flex gap-2 items-center text-[10px] font-mono">
                              <span className="text-muted-foreground">Start</span>
                              <input
                                type="number"
                                min="0"
                                max={Math.min(99, sessionRange[1] - 1)}
                                value={sessionRange[0]}
                                onChange={(e) => {
                                  const val = Math.max(0, Math.min(sessionRange[1] - 1, parseInt(e.target.value) || 0))
                                  setSessionRange([val, sessionRange[1]])
                                }}
                                className="w-12 h-6 bg-background border border-border rounded text-center focus:outline-none"
                              />
                              <span className="text-muted-foreground">%</span>
                            </div>

                            <div className="flex gap-2 items-center text-[10px] font-mono">
                              <span className="text-muted-foreground">End</span>
                              <input
                                type="number"
                                min={Math.max(1, sessionRange[0] + 1)}
                                max="100"
                                value={sessionRange[1]}
                                onChange={(e) => {
                                  const val = Math.max(sessionRange[0] + 1, Math.min(100, parseInt(e.target.value) || 100))
                                  setSessionRange([sessionRange[0], val])
                                }}
                                className="w-12 h-6 bg-background border border-border rounded text-center focus:outline-none"
                              />
                              <span className="text-muted-foreground">%</span>
                            </div>

                            <Button
                              variant="ghost"
                              size="xs"
                              onClick={() => setSessionRange([0, 100])}
                              className="text-[10px] h-7 px-2 font-bold text-muted-foreground hover:text-foreground gap-1"
                            >
                              <RotateCcw className="w-3.5 h-3.5" />
                              Reset View
                            </Button>
                          </div>
                        </div>
                      </Card>

                      <div className="flex-1 flex flex-col gap-4 min-h-[500px]">
                        {/* Filtered PPG Static Chart */}
                        <RealTimeChart
                          title={`PPG Filtered Signal Database Plot - ${selectedFile.position.replace('_', ' ').toUpperCase()}`}
                          channels={[
                            { key: 'ppg_filt', color: '#a6e3a1', name: 'Filtered Signal' }
                          ]}
                          dataRef={staticPpgFiltRef}
                          maxSamples={staticPpgFiltRef.current.length}
                          yLabel="AC Amplitude"
                          autoScale={true}
                        />

                        {/* PPG Static Chart */}
                        <RealTimeChart
                          title={`PPG Raw Waveform Database Plot - ${selectedFile.position.replace('_', ' ').toUpperCase()}`}
                          channels={[
                            { key: 'red', color: '#f38ba8', name: 'Red LED' },
                            { key: 'ir', color: '#cba6f7', name: 'Infrared LED' }
                          ]}
                          dataRef={staticPpgRef}
                          maxSamples={staticPpgRef.current.length}
                          yLabel="Counts"
                          autoScale={true}
                        />

                        {/* IMU Static Chart */}
                        <RealTimeChart
                          title={`IMU Angular Velocity Database Plot - ${selectedFile.position.replace('_', ' ').toUpperCase()}`}
                          channels={[
                            { key: 'gx', color: '#f38ba8', name: 'Axis X' },
                            { key: 'gy', color: '#a6e3a1', name: 'Axis Y' },
                            { key: 'gz', color: '#89b4fa', name: 'Axis Z' }
                          ]}
                          dataRef={staticImuRef}
                          maxSamples={staticImuRef.current.length}
                          yLabel="rad/s"
                          autoScale={true}
                        />
                      </div>
                    </div>
                  ) : (
                    <div className="flex-1 flex items-center justify-center text-xs text-muted-foreground/60 italic">
                      <RefreshCw className="w-4 h-4 animate-spin mr-2" />
                      Parsing scientific CSV telemetry data...
                    </div>
                  )
                ) : (
                  <div className="flex-1 flex items-center justify-center text-xs text-muted-foreground/60 italic">
                    Select a node data file from the top right to plot and study metrics.
                  </div>
                )}
              </div>
            ) : (
              <div className="flex-1 flex items-center justify-center text-xs text-muted-foreground/60 italic">
                Select a recorded session folder from the left pane to consult signals.
              </div>
            )}
          </Card>
        </main>
      )}

      {/* ── CONFIGURE SENSOR MODAL DIALOG ── */}
      <Dialog open={configureOpen} onOpenChange={setConfigureOpen}>
        <DialogContent className="sm:max-w-[425px] bg-popover text-popover-foreground border border-border">
          <DialogHeader>
            <DialogTitle className="text-sm font-bold tracking-tight">Configure Sensor Node</DialogTitle>
            <DialogDescription className="text-xs text-muted-foreground">
              Map a physical MAID wear sensor module to the selected body surface position.
            </DialogDescription>
          </DialogHeader>
          
          <div className="space-y-4 py-3">
            {/* Slot indicator */}
            <div className="flex justify-between items-center text-xs pb-1 border-b border-border/50">
              <span className="font-semibold text-muted-foreground">Position Assignment:</span>
              <span className="font-mono font-bold text-primary bg-primary/10 px-2 py-0.5 rounded border border-primary/20">
                {configureSlot ? sensors[configureSlot].label.toUpperCase() : ''}
              </span>
            </div>

            {/* Transport mode */}
            <div className="flex flex-col gap-1.5">
              <span className="text-[10px] font-bold uppercase tracking-wider text-muted-foreground block">Transport Mode</span>
              <div className="flex bg-muted/40 p-1 rounded-lg border border-border h-9 items-center">
                <Button
                  variant={mode === 'serial' ? 'secondary' : 'ghost'}
                  size="sm"
                  onClick={() => { setMode('serial'); setTarget('') }}
                  className="flex-1 h-7"
                >
                  <Usb className="w-3.5 h-3.5" />
                  USB Serial
                </Button>
                <Button
                  variant={mode === 'ble' ? 'secondary' : 'ghost'}
                  size="sm"
                  onClick={() => { setMode('ble'); setTarget('') }}
                  className="flex-1 h-7"
                >
                  <Bluetooth className="w-3.5 h-3.5" />
                  BLE NUS
                </Button>
              </div>
            </div>

            {/* Target Select */}
            <div className="flex flex-col gap-1.5">
              <div className="flex justify-between items-center h-4">
                <label className="text-[10px] font-bold uppercase tracking-wider text-muted-foreground">
                  {mode === 'serial' ? 'Select Port' : 'Select Device'}
                </label>
                {mode === 'serial' ? (
                  <button 
                    onClick={fetchPorts} 
                    disabled={isFetchingPorts}
                    className="text-[10px] text-primary hover:underline flex items-center gap-1 font-semibold focus:outline-none"
                  >
                    <RefreshCw className={`w-2.5 h-2.5 ${isFetchingPorts ? 'animate-spin' : ''}`} />
                    Refresh
                  </button>
                ) : (
                  <button 
                    onClick={startBleScan} 
                    disabled={isScanning}
                    className="text-[10px] text-primary hover:underline flex items-center gap-1 font-semibold focus:outline-none"
                  >
                    <RefreshCw className={`w-2.5 h-2.5 ${isScanning ? 'animate-spin' : ''}`} />
                    Scan Devices
                  </button>
                )}
              </div>

              {scanError && (
                <div className="text-[11px] text-destructive bg-destructive/10 border border-destructive/20 rounded-md p-2 font-mono leading-normal">
                  {scanError}
                </div>
              )}

              <Select value={target} onValueChange={setTarget}>
                <SelectTrigger className="w-full font-mono bg-background/50 h-9">
                  <SelectValue placeholder={mode === 'serial' ? '-- Auto-detect --' : '-- Auto-detect / Scan --'} />
                </SelectTrigger>
                <SelectContent position="popper" className="max-h-60 overflow-y-auto">
                  <SelectItem value="auto-detect">-- Auto-detect --</SelectItem>
                  {mode === 'serial' ? (
                    Array.isArray(serialPorts) && serialPorts.map((p) => (
                      <SelectItem key={p.port} value={p.port}>
                        {p.port} {p.desc ? ` - ${p.desc}` : ''}
                      </SelectItem>
                    ))
                  ) : (
                    Array.isArray(bleDevices) && bleDevices.map((d) => (
                      <SelectItem key={d.address} value={d.address}>
                        {d.name} ({d.address})
                      </SelectItem>
                    ))
                  )}
                </SelectContent>
              </Select>
            </div>

            {/* Manual Override */}
            <div className="flex flex-col gap-1.5">
              <label className="text-[10px] font-bold uppercase tracking-wider text-muted-foreground block">Manual Override</label>
              <Input
                type="text"
                value={target}
                onChange={(e) => setTarget(e.target.value)}
                placeholder="Manual Override"
                className="font-mono w-full h-9 bg-background/50"
              />
            </div>
          </div>

          <div className="flex justify-end gap-2 pt-2 border-t border-border/50">
            <Button variant="ghost" onClick={() => setConfigureOpen(false)}>
              Cancel
            </Button>
            <Button onClick={handleConnect} disabled={!target} className="bg-primary text-primary-foreground font-bold">
              Link Sensor
            </Button>
          </div>
        </DialogContent>
      </Dialog>

      {/* Disconnect Alert Dialog (Native shadcn components only) */}
      <Dialog open={showDisconnectDialog} onOpenChange={setShowDisconnectDialog}>
        <DialogContent className="bg-popover text-popover-foreground border border-border">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2 text-sm font-bold">
              <WifiOff className="w-5 h-5 text-destructive" />
              Node Connection Lost
            </DialogTitle>
            <DialogDescription className="text-xs text-muted-foreground">
              The link with the <strong>{disconnectAlertSensor}</strong> node has dropped. Please check:
              <ul className="list-disc pl-5 pt-2 space-y-1 text-xs text-muted-foreground">
                <li>The physical wearable node is turned ON.</li>
                <li>The battery is charged.</li>
                <li>Host PC Bluetooth is enabled (for BLE links) or USB is connected (for Serial links).</li>
              </ul>
            </DialogDescription>
          </DialogHeader>
          <div className="flex justify-end pt-2">
            <Button onClick={() => setShowDisconnectDialog(false)}>
              Acknowledge
            </Button>
          </div>
        </DialogContent>
      </Dialog>
    </div>
  )
}
