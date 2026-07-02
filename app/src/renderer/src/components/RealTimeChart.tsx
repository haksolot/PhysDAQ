import { useEffect, useRef } from 'react'

interface Channel {
  key: string
  color: string
  name: string
}

interface RealTimeChartProps {
  title: string
  channels: Channel[]
  dataRef: React.MutableRefObject<any[]>
  maxSamples?: number
  yLabel?: string
  autoScale?: boolean
  fixedRange?: [number, number]
}

export function RealTimeChart({
  title,
  channels,
  dataRef,
  maxSamples = 300,
  yLabel = '',
  autoScale = true,
  fixedRange = [0, 1]
}: RealTimeChartProps) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null)

  // Keep the latest drawing config in a ref so the animation loop can read it
  // without the effect having to re-run. The parent re-renders at the sensor
  // data rate (up to 100 Hz) and passes `channels`/`fixedRange` as inline
  // literals, so depending on them here would tear down and rebuild the canvas
  // (including a canvas.width reallocation) on every sample — the churn that
  // eventually froze the UI. The effect below now runs once per mount.
  const cfgRef = useRef({ channels, maxSamples, yLabel, autoScale, fixedRange })
  cfgRef.current = { channels, maxSamples, yLabel, autoScale, fixedRange }

  useEffect(() => {
    let animationId: number
    const canvas = canvasRef.current
    if (!canvas) return

    const ctx = canvas.getContext('2d')
    if (!ctx) return

    // Size the backing store for the display DPI. Only runs on mount and on
    // real resize events — never per frame — to avoid reallocating the canvas.
    let dpr = window.devicePixelRatio || 1
    const resizeCanvas = (): void => {
      const rect = canvas.getBoundingClientRect()
      dpr = window.devicePixelRatio || 1
      canvas.width = rect.width * dpr
      canvas.height = rect.height * dpr
    }

    resizeCanvas()
    window.addEventListener('resize', resizeCanvas)

    const draw = (): void => {
      const { channels, maxSamples, yLabel, autoScale, fixedRange } = cfgRef.current

      const rect = canvas.getBoundingClientRect()
      const width = rect.width
      const height = rect.height

      // Apply the DPI scale fresh each frame (idempotent) instead of a
      // cumulative ctx.scale(), so we don't need to reset via canvas.width.
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
      ctx.clearRect(0, 0, width, height)

      // Background grid
      ctx.strokeStyle = '#313244'
      ctx.lineWidth = 1
      const gridRows = 4
      const gridCols = 8

      // Horizontal grid lines
      for (let i = 1; i < gridRows; i++) {
        const y = (height / gridRows) * i
        ctx.beginPath()
        ctx.moveTo(0, y)
        ctx.lineTo(width, y)
        ctx.stroke()
      }

      // Vertical grid lines
      for (let i = 1; i < gridCols; i++) {
        const x = (width / gridCols) * i
        ctx.beginPath()
        ctx.moveTo(x, 0)
        ctx.lineTo(x, height)
        ctx.stroke()
      }

      const data = dataRef.current
      if (data.length < 2) {
        // Draw loading/no data text
        ctx.fillStyle = '#a6adc8'
        ctx.font = '12px monospace'
        ctx.textAlign = 'center'
        ctx.fillText('WAITING FOR DATA...', width / 2, height / 2)
        animationId = requestAnimationFrame(draw)
        return
      }

      // Determine Y range (auto-scaling or fixed)
      let yMin = fixedRange[0]
      let yMax = fixedRange[1]

      if (autoScale) {
        let first = true
        for (let i = 0; i < data.length; i++) {
          const sample = data[i]
          for (const ch of channels) {
            const val = sample[ch.key]
            if (val !== undefined && typeof val === 'number') {
              if (first) {
                yMin = val
                yMax = val
                first = false
              } else {
                if (val < yMin) yMin = val
                if (val > yMax) yMax = val
              }
            }
          }
        }
        // Add a small padding (10%) to the auto-scale range
        const padding = (yMax - yMin) * 0.1 || 1.0
        yMin -= padding
        yMax += padding
      }

      const yRange = yMax - yMin

      // Draw each channel
      channels.forEach((ch) => {
        ctx.beginPath()
        ctx.strokeStyle = ch.color
        ctx.lineWidth = 2
        ctx.lineJoin = 'round'

        let started = false

        for (let i = 0; i < data.length; i++) {
          const sample = data[i]
          const val = sample[ch.key]
          if (val === undefined || typeof val !== 'number') continue

          // Map index to X coordinate
          const x = (width / (maxSamples - 1)) * i
          // Map value to Y coordinate (inverted in canvas coordinates)
          const y = height - ((val - yMin) / yRange) * height

          if (!started) {
            ctx.moveTo(x, y)
            started = true
          } else {
            ctx.lineTo(x, y)
          }
        }

        ctx.stroke()
      })

      // Draw axis labels
      ctx.fillStyle = '#cdd6f4'
      ctx.font = '10px monospace'
      ctx.textAlign = 'left'
      ctx.fillText(yMax.toFixed(1), 8, 12)
      ctx.fillText(yMin.toFixed(1), 8, height - 6)

      if (yLabel) {
        ctx.fillStyle = '#a6adc8'
        ctx.fillText(yLabel, 8, height / 2)
      }

      animationId = requestAnimationFrame(draw)
    }

    draw()

    return () => {
      window.removeEventListener('resize', resizeCanvas)
      cancelAnimationFrame(animationId)
    }
    // dataRef is a stable ref object; cfgRef carries the changing props, so
    // this effect intentionally sets up the render loop only once per mount.
  }, [dataRef])

  return (
    <div className="flex flex-col h-full rounded-xl bg-[#181825]/60 border border-[#313244]/50 backdrop-blur-md overflow-hidden">
      <div className="flex items-center justify-between px-4 py-2 bg-[#1e1e2e]/40 border-b border-[#313244]/40">
        <h3 className="text-xs font-semibold uppercase tracking-wider text-[#a6adc8]">{title}</h3>
        <div className="flex gap-3">
          {channels.map((ch) => (
            <div key={ch.key} className="flex items-center gap-1.5">
              <span className="w-2.5 h-2.5 rounded-full" style={{ backgroundColor: ch.color }} />
              <span className="text-xs font-medium text-[#cdd6f4]">{ch.name}</span>
            </div>
          ))}
        </div>
      </div>
      <div className="flex-1 relative p-1 min-h-[140px]">
        <canvas ref={canvasRef} className="w-full h-full block" />
      </div>
    </div>
  )
}
