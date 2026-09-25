import { Stored } from './storage'
import type { Stream } from './types'

/**
 * Torrents through a local streaming server. Browsers can't speak BitTorrent; these can,
 * and serve each file over plain HTTP:
 *
 * - TorrServer (port 8090): the engine Android uses, bundled with the desktop app.
 * - Stremio's streaming server (port 11470): run by Stremio Service or Stremio desktop.
 *
 * With no address set, both default ports are tried, TorrServer first. An https page may
 * call http://127.0.0.1 (loopback counts as secure) but not a server elsewhere on the
 * network over http, so a LAN address works only from the TV and desktop packages.
 */

export type ServerKind = 'torrserver' | 'stremio'
export interface ServerInfo { url: string; kind: ServerKind }

export const TORRSERVER_DEFAULT = 'http://127.0.0.1:8090'
export const STREMIO_DEFAULT = 'http://127.0.0.1:11470'
const VIDEO = /\.(mkv|mp4|m4v|avi|mov|webm|wmv|ts|m2ts|mpg|mpeg|flv|ogv)$/i
const PROBE_TIMEOUT_MS = 2500
const CREATE_TIMEOUT_MS = 30_000
const FILE_LIST_TIMEOUT_MS = 15_000

export interface TorrentFile { name?: string; path?: string; length?: number; id?: number }

// The server belongs to the device, not a profile. '' means find one automatically.
export const serverUrl = new Stored<string>('streaming-server', '', true)
/** The server the last probe found; null when none answered, undefined before the first probe. */
export const server = new Stored<ServerInfo | null | undefined>('streaming-server-found', undefined, true)
/** Whether the last probe found a server; null until the first probe. */
export const serverOnline = {
  get: (): boolean | null => (server.get() === undefined ? null : !!server.get()),
}

function trim(url: string): string { return url.trim().replace(/\/+$/, '') }

async function withTimeout(url: string, ms: number, init?: RequestInit): Promise<Response> {
  const ctrl = new AbortController()
  const timer = setTimeout(() => ctrl.abort(), ms)
  try {
    return await fetch(url, { ...init, signal: ctrl.signal })
  } finally {
    clearTimeout(timer)
  }
}

/** What answers at [url]: TorrServer replies to /echo with its version, Stremio's server to /settings. */
async function identify(url: string): Promise<ServerKind | null> {
  try {
    const echo = await withTimeout(`${url}/echo`, PROBE_TIMEOUT_MS)
    if (echo.ok && /matrix|torrserver|^\d/i.test((await echo.text()).trim())) return 'torrserver'
  } catch { /* not TorrServer */ }
  try {
    const settings = await withTimeout(`${url}/settings`, PROBE_TIMEOUT_MS)
    if (settings.ok) return 'stremio'
  } catch { /* nothing there */ }
  return null
}

/** Finds a server at the set address, or at the default ports; remembers it for isPlayable. */
export async function probeServer(): Promise<ServerInfo | null> {
  const set = trim(serverUrl.get())
  let found: ServerInfo | null = null
  for (const url of set ? [set] : [TORRSERVER_DEFAULT, STREMIO_DEFAULT]) {
    const kind = await identify(url)
    if (kind) { found = { url, kind }; break }
  }
  server.set(found)
  return found
}

/** Tracker and DHT sources, from the stream's `sources` or just the hash. */
export function peerSources(stream: Stream): string[] {
  const own = (stream.sources ?? []).filter((s) => /^(tracker|dht):/.test(s))
  return own.length ? own : [`dht:${stream.infoHash}`]
}

/** A magnet link with the stream's trackers, for TorrServer. */
export function magnet(stream: Stream): string {
  const trackers = (stream.sources ?? []).filter((s) => s.indexOf('tracker:') === 0).map((s) => `&tr=${encodeURIComponent(s.slice(8))}`)
  return `magnet:?xt=urn:btih:${(stream.infoHash ?? '').toLowerCase()}${trackers.join('')}`
}

/**
 * The file to play, in Android's order: the file named in behaviorHints.filename, then
 * fileIdx, then the server's guess, then the largest video file. Returns a position in [files].
 */
export function pickFileIndex(files: TorrentFile[], stream: Stream, guessed?: number): number {
  const wanted = stream.behaviorHints?.filename?.toLowerCase()
  if (wanted) {
    const byName = files.findIndex((f) => {
      const n = (f.path ?? f.name ?? '').toLowerCase()
      return n === wanted || n.endsWith('/' + wanted)
    })
    if (byName >= 0) return byName
  }
  if (stream.fileIdx !== undefined && stream.fileIdx >= 0 && (!files.length || stream.fileIdx < files.length)) return stream.fileIdx
  if (guessed !== undefined && guessed >= 0) return guessed
  let best = -1
  files.forEach((f, i) => {
    if (VIDEO.test(f.path ?? f.name ?? '') && (best < 0 || (f.length ?? 0) > (files[best].length ?? 0))) best = i
  })
  return best >= 0 ? best : 0
}

function validHash(stream: Stream): string {
  const hash = (stream.infoHash ?? '').toLowerCase()
  if (!/^[0-9a-f]{40}$/.test(hash)) throw new Error('This source has no valid torrent hash.')
  return hash
}

const unreachable = () => new Error('The streaming server isn’t reachable. Is it running?')

async function stremioUrl(base: string, stream: Stream): Promise<string> {
  const hash = validHash(stream)
  const res = await withTimeout(`${base}/${hash}/create`, CREATE_TIMEOUT_MS, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      torrent: { infoHash: hash },
      peerSearch: { sources: peerSources(stream), min: 40, max: 200 },
      guessFileIdx: stream.fileIdx === undefined ? {} : undefined,
    }),
  }).catch(() => { throw unreachable() })
  if (!res.ok) throw new Error(`The streaming server couldn’t add this torrent (HTTP ${res.status}).`)
  const info = await res.json().catch(() => ({})) as { files?: TorrentFile[]; guessedFileIdx?: number }
  return `${base}/${hash}/${pickFileIndex(info.files ?? [], stream, info.guessedFileIdx)}`
}

/**
 * TorrServer, as Android's TorrentService: add the magnet, wait for the file list (it needs
 * metadata from peers), choose the file, stream it by its 1-based id.
 */
async function torrServerUrl(base: string, stream: Stream, sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))): Promise<string> {
  const hash = validHash(stream)
  const link = magnet(stream)
  const post = (body: Record<string, unknown>) => withTimeout(`${base}/torrents`, CREATE_TIMEOUT_MS, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  })
  const added = await post({ action: 'add', link, save_to_db: false }).catch(() => { throw unreachable() })
  if (!added.ok) throw new Error(`The streaming server couldn’t add this torrent (HTTP ${added.status}).`)

  const deadline = Date.now() + FILE_LIST_TIMEOUT_MS
  let files: TorrentFile[] = []
  while (Date.now() < deadline) {
    const res = await post({ action: 'get', hash }).catch(() => null)
    const info = res && res.ok ? await res.json().catch(() => ({})) as { file_stats?: TorrentFile[] } : {}
    files = info.file_stats ?? []
    if (files.length) break
    await sleep(500)
  }
  if (!files.length) throw new Error('No peers sent this torrent’s file list in time.')
  const chosen = files[pickFileIndex(files, stream)]
  const id = chosen.id ?? files.indexOf(chosen) + 1
  return `${base}/stream?link=${encodeURIComponent(link)}&index=${id}&play`
}

/** Adds the torrent to the found server and returns the HTTP URL of the file to play. */
export async function torrentUrl(stream: Stream, found = server.get()): Promise<string> {
  if (!found) throw new Error('No streaming server found. Check Settings → Torrents.')
  return found.kind === 'torrserver' ? torrServerUrl(found.url, stream) : stremioUrl(found.url, stream)
}

/** Exposed for tests. */
export const internal = { torrServerUrl }
