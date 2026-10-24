import { Secret } from './secrets'

/**
 * Debrid providers' API keys, several per profile (primary first). The primary's key is filled into the install URL of addons whose URL format is
 * known (Torrentio), as Android's DebridAddonUrlHelper does. Encrypted at rest (secrets.ts).
 */

export const PROVIDERS = {
  realdebrid: 'Real-Debrid',
  alldebrid: 'AllDebrid',
  premiumize: 'Premiumize',
  debridlink: 'Debrid-Link',
  easydebrid: 'EasyDebrid',
  offcloud: 'Offcloud',
  torbox: 'TorBox',
} as const

export type Provider = keyof typeof PROVIDERS

export type DebridKey = { provider: Provider; apiKey: string }

/** Stored as a list; a single object (or null) is the pre-multi-service format. */
export const debridKey = new Secret<DebridKey[] | DebridKey | null>('debrid', [])

/** Connected keys, primary first, whichever format was stored. */
export function debridKeys(value = debridKey.get()): DebridKey[] {
  if (!value) return []
  return Array.isArray(value) ? value : [value]
}

/** Adds [entry], or replaces the key of a provider already connected, keeping its position. */
export function addDebridKey(entry: DebridKey): void {
  const keys = debridKeys()
  const i = keys.findIndex((k) => k.provider === entry.provider)
  debridKey.set(i >= 0 ? keys.map((k, j) => (j === i ? entry : k)) : keys.concat(entry))
}

export function removeDebridKey(provider: Provider): void {
  debridKey.set(debridKeys().filter((k) => k.provider !== provider))
}

/** Moves [provider] to the front: the key Torrentio installs get. */
export function setPrimaryDebrid(provider: Provider): void {
  const keys = debridKeys()
  debridKey.set(keys.filter((k) => k.provider === provider).concat(keys.filter((k) => k.provider !== provider)))
}

/** Torrentio's own debrid keys, to spot a URL that is already configured. */
const TORRENTIO_KEYS = ['realdebrid', 'alldebrid', 'premiumize', 'debridlink', 'easydebrid', 'offcloud', 'torbox', 'putio']

/** [url] with the key inserted when it is a Torrentio manifest without one; otherwise unchanged. */
export function withDebridKey(url: string, debrid: DebridKey | null = debridKeys()[0] ?? null): string {
  if (!debrid || !debrid.apiKey.trim()) return url
  let u: URL
  try { u = new URL(url.trim().replace(/^stremio:\/\//i, 'https://')) } catch { return url }
  if (u.hostname.toLowerCase() !== 'torrentio.strem.fun') return url
  const segments = u.pathname.split('/').filter(Boolean)
  if (segments[segments.length - 1] !== 'manifest.json') return url
  const config = segments.slice(0, -1)
  const parts = config.length ? config[config.length - 1].split('|') : []
  if (parts.some((p) => TORRENTIO_KEYS.indexOf(p.split('=')[0].toLowerCase()) >= 0)) return url
  parts.push(`${debrid.provider}=${debrid.apiKey.trim()}`)
  const rebuilt = config.length ? config.slice(0, -1).concat(parts.join('|')) : [parts.join('|')]
  return `${u.protocol}//${u.host}/${rebuilt.concat('manifest.json').join('/')}`
}
