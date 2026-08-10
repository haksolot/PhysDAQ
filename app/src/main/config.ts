import { app } from 'electron'
import { join } from 'path'
import { existsSync, readFileSync, writeFileSync, mkdirSync } from 'fs'

/** Friendly names for physical nodes, persisted between runs.
 *
 * Keyed on the transport target — a BLE MAC address or a COM port — not on a
 * body position. The point is to recognise a piece of hardware wherever it ends
 * up being worn, so `wrist_left`'s label and a node's alias are different
 * things and deliberately stored apart. */
interface NodeConfig {
  aliases: Record<string, string>
}

const EMPTY: NodeConfig = { aliases: {} }

function configPath(): string {
  return join(app.getPath('userData'), 'nodes.json')
}

export function readNodeConfig(): NodeConfig {
  const path = configPath()
  if (!existsSync(path)) {
    return { ...EMPTY }
  }
  try {
    const parsed = JSON.parse(readFileSync(path, 'utf-8'))
    // Tolerate a hand-edited or half-written file rather than failing to start.
    if (!parsed || typeof parsed !== 'object' || typeof parsed.aliases !== 'object') {
      return { ...EMPTY }
    }
    return { aliases: parsed.aliases ?? {} }
  } catch (err) {
    console.error('[Config] nodes.json unreadable, ignoring it:', err)
    return { ...EMPTY }
  }
}

export function getNodeAliases(): Record<string, string> {
  return readNodeConfig().aliases
}

/** Set or, with an empty name, clear the alias for one target. */
export function setNodeAlias(target: string, alias: string): Record<string, string> {
  const config = readNodeConfig()
  const trimmed = alias.trim()

  if (trimmed) {
    config.aliases[target] = trimmed
  } else {
    delete config.aliases[target]
  }

  const path = configPath()
  mkdirSync(app.getPath('userData'), { recursive: true })
  writeFileSync(path, JSON.stringify(config, null, 2), 'utf-8')
  return config.aliases
}
