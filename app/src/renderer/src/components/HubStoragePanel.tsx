import { useState } from 'react'
import { Card } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle
} from '@/components/ui/dialog'
import { RefreshCw, HardDrive, Download, Trash2, Square, Circle } from 'lucide-react'

export interface SdFile {
  name: string
  size: number
}

export interface SdStat {
  mounted: boolean
  used?: number
  total?: number
  sess?: number
  open?: number
  w?: number
  drop?: number
  err?: number
  /** errno from storage_init(), only present when mounted is false. */
  error?: number
}

export interface DownloadState {
  file: string
  bytes: number
  /** Set once the transfer finishes, successfully or not. */
  done?: boolean
  ok?: boolean
  path?: string | null
  error?: string | null
}

interface Props {
  stat: SdStat | null
  files: SdFile[]
  listing: boolean
  download: DownloadState | null
  /** Transport the device is on — downloads over BLE are ~2.5x slower. */
  mode: 'serial' | 'ble'
  onCommand: (cmd: Record<string, unknown>) => void
}

function formatBytes(n?: number): string {
  if (n === undefined) return '\u2014'
  if (n < 1024) return `${n} B`
  const units = ['kB', 'MB', 'GB', 'TB']
  let v = n / 1024
  let i = 0
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024
    i++
  }
  return `${v.toFixed(v < 10 ? 1 : 0)} ${units[i]}`
}

/** Rough wall-clock estimate for a download, so the operator sees the cost
 * before committing rather than after ten minutes of watching a bar.
 *
 * base64 inflates the payload by a third, and the firmware sends it as ASCII
 * lines over the same link that carries the sample stream. ~4 kB/s over BLE
 * and ~11 kB/s over USB CDC are the link budgets those numbers come from. */
function estimateDuration(bytes: number, mode: 'serial' | 'ble'): string {
  const linkBytesPerSec = mode === 'ble' ? 4000 : 11000
  const seconds = (bytes * 1.34) / linkBytesPerSec
  if (seconds < 90) return `~${Math.ceil(seconds)} s`
  return `~${Math.ceil(seconds / 60)} min`
}

export function HubStoragePanel({
  stat,
  files,
  listing,
  download,
  mode,
  onCommand
}: Props): React.JSX.Element {
  const [confirmErase, setConfirmErase] = useState(false)
  const [eraseTyped, setEraseTyped] = useState('')
  const [confirmDelete, setConfirmDelete] = useState<SdFile | null>(null)

  const recording = stat?.open === 1
  const mounted = stat?.mounted === true

  return (
    <Card className="p-4 bg-card/60 backdrop-blur-md border border-border flex flex-col gap-3">
      <div className="flex items-center justify-between">
        <span className="text-[10px] font-bold uppercase tracking-wider text-muted-foreground flex items-center gap-1.5">
          <HardDrive className="w-3 h-3" />
          Hub Storage
        </span>
        <button
          onClick={() => {
            onCommand({ cmd: 'sd.stat' })
            onCommand({ cmd: 'sd.list' })
          }}
          className="text-[10px] text-primary hover:underline flex items-center gap-1 font-semibold focus:outline-none"
        >
          <RefreshCw className={`w-2.5 h-2.5 ${listing ? 'animate-spin' : ''}`} />
          Refresh
        </button>
      </div>

      {!stat ? (
        <p className="text-[11px] text-muted-foreground italic">
          No card status yet &mdash; hit Refresh.
        </p>
      ) : !mounted ? (
        <div className="text-[11px] text-amber-500 bg-amber-500/10 border border-amber-500/20 rounded-md p-2 leading-normal">
          <span className="font-bold">No card mounted.</span> The hub is acquiring and streaming
          normally; nothing is being recorded to the card.
          {stat.error !== undefined && (
            <span className="font-mono block mt-1 text-[10px]">errno {stat.error}</span>
          )}
        </div>
      ) : (
        <div className="grid grid-cols-2 gap-x-3 gap-y-1 text-[11px] font-mono">
          <span className="text-muted-foreground">Used</span>
          <span className="text-right">
            {formatBytes(stat.used)} / {formatBytes(stat.total)}
          </span>
          <span className="text-muted-foreground">Session</span>
          <span className="text-right">
            {recording ? (
              <span className="text-emerald-500 font-bold">
                SESS{String(stat.sess ?? 0).padStart(4, '0')} &middot; REC
              </span>
            ) : (
              <span className="text-muted-foreground/70">stopped</span>
            )}
          </span>
          <span className="text-muted-foreground">Written</span>
          <span className="text-right">{stat.w ?? 0} rec</span>
          <span className="text-muted-foreground">Dropped / errors</span>
          <span
            className={`text-right ${stat.drop || stat.err ? 'text-destructive font-bold' : ''}`}
          >
            {stat.drop ?? 0} / {stat.err ?? 0}
          </span>
        </div>
      )}

      {mounted && (
        <div className="flex gap-2">
          {recording ? (
            <Button
              variant="outline"
              size="xs"
              onClick={() => onCommand({ cmd: 'rec.stop' })}
              className="flex-1"
            >
              <Square className="w-3 h-3 mr-1" />
              Stop session
            </Button>
          ) : (
            <Button
              variant="outline"
              size="xs"
              onClick={() => onCommand({ cmd: 'rec.start' })}
              className="flex-1"
            >
              <Circle className="w-3 h-3 mr-1" />
              New session
            </Button>
          )}
          <Button
            variant="destructive"
            size="xs"
            disabled={recording}
            title={recording ? 'Stop the session first' : 'Delete every session file'}
            onClick={() => {
              setEraseTyped('')
              setConfirmErase(true)
            }}
          >
            <Trash2 className="w-3 h-3 mr-1" />
            Erase card
          </Button>
        </div>
      )}

      {mounted && (
        <div className="flex flex-col gap-1">
          <span className="text-[10px] font-bold uppercase tracking-wider text-muted-foreground">
            Sessions on card ({files.length})
          </span>
          {files.length === 0 ? (
            <p className="text-[11px] text-muted-foreground italic">
              {listing ? 'Reading\u2026' : 'No session files.'}
            </p>
          ) : (
            <div className="flex flex-col gap-1 max-h-48 overflow-y-auto pr-1">
              {files.map((f) => (
                <div
                  key={f.name}
                  className="flex items-center justify-between gap-2 text-[11px] font-mono px-2 py-1 rounded border border-border/60 bg-background/30"
                >
                  <span className="truncate">{f.name}</span>
                  <span className="text-muted-foreground shrink-0">{formatBytes(f.size)}</span>
                  <div className="flex gap-1 shrink-0">
                    <button
                      title={`Download \u2014 ${estimateDuration(f.size, mode)} over ${mode === 'ble' ? 'BLE' : 'USB'}`}
                      onClick={() => onCommand({ cmd: 'sd.get', file: f.name })}
                      disabled={!!download && !download.done}
                      className="text-primary hover:opacity-70 disabled:opacity-30"
                    >
                      <Download className="w-3 h-3" />
                    </button>
                    <button
                      title="Delete this session file"
                      onClick={() => setConfirmDelete(f)}
                      className="text-destructive hover:opacity-70"
                    >
                      <Trash2 className="w-3 h-3" />
                    </button>
                  </div>
                </div>
              ))}
            </div>
          )}
          {mode === 'ble' && files.length > 0 && (
            <p className="text-[10px] text-muted-foreground leading-snug">
              Downloads share the link with the live stream. USB is roughly 2.5x faster &mdash;
              worth switching for anything over a few megabytes.
            </p>
          )}
        </div>
      )}

      {download && (
        <div className="text-[11px] font-mono border border-border/60 rounded p-2 bg-background/30">
          {download.done ? (
            download.ok ? (
              <span className="text-emerald-500 break-all">
                {download.file} &rarr; {download.path}
              </span>
            ) : (
              <span className="text-destructive">
                {download.file}: {download.error ?? 'failed'}
              </span>
            )
          ) : (
            <div className="flex items-center justify-between gap-2">
              <span className="truncate">
                {download.file} &middot; {formatBytes(download.bytes)}
              </span>
              <button
                onClick={() => onCommand({ cmd: 'sd.abort' })}
                className="text-destructive hover:underline shrink-0"
              >
                Cancel
              </button>
            </div>
          )}
        </div>
      )}

      <Dialog open={!!confirmDelete} onOpenChange={(o) => !o && setConfirmDelete(null)}>
        <DialogContent className="sm:max-w-[380px] bg-popover text-popover-foreground border border-border">
          <DialogHeader>
            <DialogTitle className="text-sm font-bold">Delete session file?</DialogTitle>
            <DialogDescription className="text-xs text-muted-foreground">
              <span className="font-mono text-foreground">{confirmDelete?.name}</span> will be
              removed from the card. If it has not been downloaded, the recording is gone for good.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" size="sm" onClick={() => setConfirmDelete(null)}>
              Cancel
            </Button>
            <Button
              variant="destructive"
              size="sm"
              onClick={() => {
                onCommand({ cmd: 'sd.del', file: confirmDelete?.name })
                setConfirmDelete(null)
              }}
            >
              Delete
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={confirmErase} onOpenChange={setConfirmErase}>
        <DialogContent className="sm:max-w-[380px] bg-popover text-popover-foreground border border-border">
          <DialogHeader>
            <DialogTitle className="text-sm font-bold">Erase every session?</DialogTitle>
            <DialogDescription className="text-xs text-muted-foreground">
              All {files.length} session file{files.length === 1 ? '' : 's'} will be deleted from
              the card. Files this app did not write are left alone. Type{' '}
              <span className="font-mono text-foreground">ERASE</span> to confirm.
            </DialogDescription>
          </DialogHeader>
          <input
            value={eraseTyped}
            onChange={(e) => setEraseTyped(e.target.value)}
            placeholder="ERASE"
            className="w-full bg-background/50 border border-border rounded-md px-2 py-1 text-xs font-mono"
          />
          <DialogFooter>
            <Button variant="outline" size="sm" onClick={() => setConfirmErase(false)}>
              Cancel
            </Button>
            <Button
              variant="destructive"
              size="sm"
              disabled={eraseTyped !== 'ERASE'}
              onClick={() => {
                onCommand({ cmd: 'sd.format' })
                setConfirmErase(false)
              }}
            >
              Erase card
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </Card>
  )
}
