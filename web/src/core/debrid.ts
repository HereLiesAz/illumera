import { Secret } from './secrets'

/**
 * A debrid provider's API key, filled into the install URL of addons whose URL format is
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

export const debridKey = new Secret<{ provider: Provider; apiKey: string } | null>('debrid', null)

/** Torrentio's own debrid keys, to spot a URL that is already configured. */
const TORRENTIO_KEYS = ['realdebrid', 'alldebrid', 'premiumize', 'debridlink', 'easydebrid', 'offcloud', 'torbox', 'putio']

/** [url] with the key inserted when it is a Torrentio manifest without one; otherwise unchanged. */
export function withDebridKey(url: string, debrid = debridKey.get()): string {
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
