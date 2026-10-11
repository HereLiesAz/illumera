// Internet Archive access, limited to curated collections of openly licensed or public-domain
// film and television. Items outside these collections (user uploads of anything) are never
// searched, so every source this addon returns is one the Archive distributes as free to share.

import { torrentInfo } from './bencode.js'

export const COLLECTIONS = [
  'feature_films', 'classic_tv', 'silent_films', 'Film_Noir', 'SciFi_Horror', 'Comedy_Films',
  'animationandcartoons', 'classic_cartoons', 'educationalfilms', 'prelinger',
]

const BASE = 'https://archive.org'
const collectionClause = `collection:(${COLLECTIONS.join(' OR ')})`

/** Strips everything but letters and digits, lowercased, for title comparison. */
export function normalizeTitle(s) {
  return String(s ?? '')
    .normalize('NFKD').replace(/[\u0300-\u036f]/g, '')
    .toLowerCase().replace(/&/g, 'and').replace(/^the\s+/, '')
    .replace(/[^a-z0-9]+/g, '')
}

/** Lucene-safe quoted phrase. */
const phrase = (s) => `"${String(s).replace(/["\\]/g, ' ').trim()}"`

export async function search(fetchJson, { query, title, year, rows = 20, collections }) {
  const parts = ['mediatype:movies', collections ? `collection:(${collections.join(' OR ')})` : collectionClause]
  if (title) parts.push(`title:(${phrase(title)})`)
  if (query) parts.push(`(${String(query).replace(/[():"\\]/g, ' ')})`)
  if (year) parts.push(`year:[${year - 1} TO ${year + 1}]`)
  const params = new URLSearchParams({ q: parts.join(' AND '), rows: String(rows), output: 'json' })
  for (const f of ['identifier', 'title', 'year', 'downloads', 'description', 'external-identifier']) params.append('fl[]', f)
  params.append('sort[]', 'downloads desc')
  const data = await fetchJson(`${BASE}/advancedsearch.php?${params}`)
  return data?.response?.docs ?? []
}

export const metadata = (fetchJson, id) => fetchJson(`${BASE}/metadata/${encodeURIComponent(id)}`)

/** The IMDb id an item declares, if any ("urn:imdb:tt0063350"). */
export function imdbIdOf(doc) {
  const ext = [].concat(doc?.['external-identifier'] ?? [])
  for (const e of ext) {
    const m = /urn:imdb:(tt\d+)/.exec(e)
    if (m) return m[1]
  }
  return null
}

const VIDEO_EXT = /\.(mkv|mp4|m4v|mov|webm|avi|mpe?g|m2ts|ts|ogv|wmv|flv)$/i
// Archive re-encodes, best first. 512Kb is a low-bitrate fallback only.
const DERIVATIVE_RANK = ['h.264', 'h.264 IA', 'MPEG4', 'Matroska', 'Ogg Video', '512Kb MPEG4']

/**
 * The playable video files of an item, best first. For each upload, the original file (full
 * quality, e.g. a 1080p .mkv) and the Archive's best re-encode of it (an .mp4 every player
 * handles) are kept; lesser re-encodes of the same upload are dropped. Short extras (DVD menus,
 * trailers) are dropped when a full-length file exists.
 */
export function videoFiles(meta) {
  const groups = new Map()
  for (const f of meta?.files ?? []) {
    if (!VIDEO_EXT.test(f.name)) continue
    const isOriginal = f.source === 'original'
    if (!isOriginal && !DERIVATIVE_RANK.includes(f.format)) continue
    const key = isOriginal ? f.name : (f.original || f.name)
    const g = groups.get(key) ?? { original: null, derivative: null }
    if (isOriginal) g.original = f
    else if (!g.derivative || DERIVATIVE_RANK.indexOf(f.format) < DERIVATIVE_RANK.indexOf(g.derivative.format)) g.derivative = f
    groups.set(key, g)
  }
  const picked = []
  for (const g of groups.values()) {
    if (g.original) picked.push(g.original)
    // The re-encode adds a choice only when it differs from the original (an .mkv/.mpg
    // original gets an .mp4 alongside); an .mp4 original already plays everywhere.
    if (g.derivative && !(g.original && /\.mp4$/i.test(g.original.name))) picked.push(g.derivative)
  }
  const files = picked.map((f) => ({ ...f, size: Number(f.size) || 0, height: Number(f.height) || 0 }))
  const longest = Math.max(0, ...files.map((f) => f.size))
  return files
    .filter((f) => f.size >= longest * 0.03 || files.length === 1)
    .sort((a, b) => b.height - a.height || b.size - a.size)
}

const fmtSize = (n) => (n >= 1 << 30 ? `${(n / (1 << 30)).toFixed(2)} GB` : `${Math.round(n / (1 << 20))} MB`)

function quality(f) {
  const h = f.height
  if (h >= 2000) return '4K'
  if (h >= 1000) return '1080p'
  if (h >= 700) return '720p'
  if (h >= 400) return '480p'
  if (h > 0) return `${h}p`
  return 'SD'
}

/**
 * Streams for one item: a direct HTTP stream for every picked file and, when the item's
 * torrent can be read, a torrent stream for the same file (infoHash + fileIdx).
 */
export async function streamsForItem(fetchJson, fetchBytes, identifier, { label } = {}) {
  const meta = await metadata(fetchJson, identifier)
  if (!meta?.metadata) return [] // dark/withdrawn item
  const files = videoFiles(meta)
  if (!files.length) return []

  let torrent = null
  try {
    const bytes = await fetchBytes(`${BASE}/download/${encodeURIComponent(identifier)}/${encodeURIComponent(identifier)}_archive.torrent`)
    if (bytes) torrent = await torrentInfo(bytes)
  } catch (_) {
    torrent = null // HTTP streams still work without it
  }

  const itemTitle = label || [].concat(meta.metadata.title)[0] || identifier
  const license = meta.metadata.licenseurl ? ' · licensed' : ''
  const streams = []
  for (const f of files) {
    const desc = `${itemTitle}\n${quality(f)} · ${fmtSize(f.size)} · ${f.format}${license}`
    const bingeGroup = `ia-${identifier}`
    streams.push({
      name: `Archive\n${quality(f)}`,
      description: desc,
      url: `${BASE}/download/${encodeURIComponent(identifier)}/${f.name.split('/').map(encodeURIComponent).join('/')}`,
      behaviorHints: { bingeGroup, filename: f.name.split('/').pop(), videoSize: f.size },
    })
    if (torrent) {
      const idx = torrent.files.findIndex((tf) => tf.path === f.name || tf.path.endsWith(`/${f.name}`))
      if (idx >= 0) {
        streams.push({
          name: `Archive\nTorrent ${quality(f)}`,
          description: desc,
          infoHash: torrent.infoHash,
          fileIdx: idx,
          sources: [...torrent.trackers.map((t) => `tracker:${t}`), `dht:${torrent.infoHash}`],
          behaviorHints: { bingeGroup: `${bingeGroup}-bt`, filename: f.name.split('/').pop(), videoSize: f.size },
        })
      }
    }
  }
  return streams
}
