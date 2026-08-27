import { useState } from 'react'
import { Card } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Combobox, type ComboboxOption } from '@/components/ui/combobox'
import { RefreshCw, Radio, Trash2, AlertTriangle } from 'lucide-react'

export interface Satellite {
  slot: number
  addr: string
  sourceId: number
  label: string
}

interface Props {
  satellites: Satellite[]
  max: number
  /** Devices from the last BLE scan, offered as candidates. */
  candidates: { address: string; name: string; deviceType?: 'node' | 'hub' }[]
  scanning: boolean
  onScan: () => void
  onCommand: (cmd: Record<string, unknown>) => void
}

export function HubSatellitesPanel({
  satellites,
  max,
  candidates,
  scanning,
  onScan,
  onCommand
}: Props): React.JSX.Element {
  const [addr, setAddr] = useState('')
  const [label, setLabel] = useState('')

  const known = new Set(satellites.map((s) => s.addr.toUpperCase()))

  // A hub cannot be its own satellite, and a node already in the roster is not
  // a candidate — re-adding it would only relabel it, which the list does
  // better.
  const options: ComboboxOption[] = candidates
    .filter((d) => d.deviceType !== 'hub' && !known.has(d.address.toUpperCase()))
    .map((d) => ({
      value: d.address,
      label: `${d.name} (${d.address})`,
      keywords: [d.name, d.address]
    }))

  return (
    <Card className="p-4 bg-card/60 backdrop-blur-md border border-border flex flex-col gap-3">
      <div className="flex items-center justify-between">
        <span className="text-[10px] font-bold uppercase tracking-wider text-muted-foreground flex items-center gap-1.5">
          <Radio className="w-3 h-3" />
          Satellites ({satellites.length}/{max})
        </span>
        <button
          onClick={() => onCommand({ cmd: 'sat.list' })}
          className="text-[10px] text-primary hover:underline font-semibold focus:outline-none"
        >
          Refresh
        </button>
      </div>

      {/* This banner is not decoration. The roster is configuration only: the
          hub stores it and reports it, and that is all it does today. Without
          saying so, a panel that lists satellites next to a storage panel
          reads as "these are being recorded", which is false. */}
      <div className="text-[11px] text-amber-500 bg-amber-500/10 border border-amber-500/20 rounded-md p-2 leading-normal flex gap-2">
        <AlertTriangle className="w-3.5 h-3.5 shrink-0 mt-px" />
        <span>
          <span className="font-bold">Configuration only.</span> The hub remembers this roster
          across reboots, but it does not connect to these nodes and records nothing from them.
          Radio ingestion is unimplemented &mdash; it needs a time-synchronisation scheme that does
          not exist yet. To capture a satellite today, link it to its own body position like any
          other node.
        </span>
      </div>

      {satellites.length === 0 ? (
        <p className="text-[11px] text-muted-foreground italic">No satellites configured.</p>
      ) : (
        <div className="flex flex-col gap-1">
          {satellites.map((s) => (
            <div
              key={s.addr}
              className="flex items-center justify-between gap-2 text-[11px] font-mono px-2 py-1 rounded border border-border/60 bg-background/30"
            >
              <div className="flex flex-col min-w-0">
                <span className="truncate text-foreground">{s.label || '(unlabelled)'}</span>
                <span className="text-[10px] text-muted-foreground truncate">{s.addr}</span>
              </div>
              <span className="text-[10px] text-muted-foreground shrink-0">
                src 0x{s.sourceId.toString(16).toUpperCase().padStart(2, '0')}
              </span>
              <button
                title="Remove from the roster"
                onClick={() => onCommand({ cmd: 'sat.del', addr: s.addr })}
                className="text-destructive hover:opacity-70 shrink-0"
              >
                <Trash2 className="w-3 h-3" />
              </button>
            </div>
          ))}
        </div>
      )}

      <div className="flex flex-col gap-1.5 pt-1 border-t border-border/50">
        <div className="flex items-center justify-between">
          <span className="text-[10px] font-bold uppercase tracking-wider text-muted-foreground">
            Add a satellite
          </span>
          <button
            onClick={onScan}
            className="text-[10px] text-primary hover:underline flex items-center gap-1 font-semibold focus:outline-none"
          >
            <RefreshCw className={`w-2.5 h-2.5 ${scanning ? 'animate-spin' : ''}`} />
            Scan
          </button>
        </div>

        <Combobox
          value={addr}
          onValueChange={setAddr}
          options={options}
          placeholder="-- Pick a discovered node --"
          searchPlaceholder="Filter by name or address\u2026"
          emptyMessage="No unassigned node found. Scan again?"
          className="font-mono bg-background/50"
        />

        <input
          value={label}
          onChange={(e) => setLabel(e.target.value)}
          placeholder="Label (e.g. wrist_L)"
          maxLength={15}
          className="w-full bg-background/50 border border-border rounded-md px-2 py-1 text-xs"
        />

        <div className="flex gap-2">
          <Button
            size="xs"
            disabled={!addr || satellites.length >= max}
            onClick={() => {
              onCommand({ cmd: 'sat.add', addr, label })
              setAddr('')
              setLabel('')
            }}
            className="flex-1"
          >
            Add
          </Button>
          <Button
            variant="outline"
            size="xs"
            disabled={satellites.length === 0}
            onClick={() => onCommand({ cmd: 'sat.clear' })}
          >
            Clear all
          </Button>
        </div>
      </div>
    </Card>
  )
}
