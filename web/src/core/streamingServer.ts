import { Stored } from './storage'
import type { Stream } from './types'

/**
 * Torrents through a Stremio streaming server (the one Stremio Service or the Stremio
 * desktop app runs on port 11470). Browsers can't speak BitTorrent; the server can, and
 * serves each file over plain HTTP.
 *
 * An https page may call http://127.0.0.1 (loopback counts as secure) but not a server
 * elsewhere on the network over http, so a LAN address works only from the TV and
 * desktop packages, not from the hosted web app.
 */

export const DEFAULT_SERVER = 'http://127.0.0.1:11470'
const VIDEO = /\.(mkv|mp4|m4v|avi|mov|webm|wmv|ts|m2ts|mpg|mpeg|flv|ogv)$/i
const PROBE_TIMEOUT_MS = 2500
const CREATE_TIMEOUT_MS = 30_000

export interface TorrentFile { name?: string; path?: string; length?: number }

// The server belongs to the device, not a profile.
export const serverUrl = new Stored<string>('streaming-server', DEFAULT_SERVER, true)
/** Whether the last probe reached a server; null until the first probe. */
export const serverOnline = new Stored<boolean | null>('streaming-server-online', null, true)

function base(): string { return serverUrl.get().trim().replace(/\/+$/, '') }

async function withTimeout(url: string, ms: number, init?: RequestInit): Promise<Response> {
  const ctrl = new AbortController()
  const timer = setTimeout(() => ctrl.abort(), ms)
  try {
    return await fetch(url, { ...init, signal: ctrl.signal })
  } finally {
    clearTimeout(timer)
  }
}

/** Checks the server answers; remembers the result for isPlayable. */
export async function probeServer(): Promise<boolean> {
  let ok = false
  try {
    const res = await withTimeout(`${base()}/settings`, PROBE_TIMEOUT_MS)
    ok = res.ok
  } catch { /* offline or not installed */ }
  serverOnline.set(ok)
  return ok
}

/** Tracker and DHT sources for the server, from the stream's `sources` and the hash. */
export function peerSources(stream: Stream): string[] {
  const own = (stream.sources ?? []).filter((s) => /^(tracker|dht):/.test(s))
  return own.length ? own : [`dht:${stream.infoHash}`]
}

/**
 * The file to play, in Android's order: the file named in behaviorHints.filename, then
 * fileIdx, then the server's guess, then the largest video file.
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

/** Adds the torrent to the server and returns the HTTP URL of the file to play. */
export async function torrentUrl(stream: Stream): Promise<string> {
  const hash = (stream.infoHash ?? '').toLowerCase()
  if (!/^[0-9a-f]{40}$/.test(hash)) throw new Error('This source has no valid torrent hash.')
  const res = await withTimeout(`${base()}/${hash}/create`, CREATE_TIMEOUT_MS, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      torrent: { infoHash: hash },
      peerSearch: { sources: peerSources(stream), min: 40, max: 200 },
      guessFileIdx: stream.fileIdx === undefined ? {} : undefined,
    }),
  }).catch(() => { throw new Error('The streaming server isn’t reachable. Is Stremio Service running?') })
  if (!res.ok) throw new Error(`The streaming server couldn’t add this torrent (HTTP ${res.status}).`)
  const info = await res.json().catch(() => ({})) as { files?: TorrentFile[]; guessedFileIdx?: number }
  const index = pickFileIndex(info.files ?? [], stream, info.guessedFileIdx)
  return `${base()}/${hash}/${index}`
}
