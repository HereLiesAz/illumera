// Minimal bencode decoder for .torrent files. Byte strings stay Uint8Array; the raw byte
// span of the top-level "info" dictionary is recorded so its SHA-1 (the infohash) can be
// taken over the exact bytes the torrent was published with.

export function decodeTorrent(bytes) {
  let pos = 0
  let infoSpan = null

  function readInt(end) {
    const s = new TextDecoder().decode(bytes.subarray(pos, end))
    pos = end + 1
    return Number(s)
  }

  function parse(depth, key) {
    const c = bytes[pos]
    if (c === 0x69) { // i<n>e
      pos++
      return readInt(bytes.indexOf(0x65, pos))
    }
    if (c === 0x6c) { // l...e
      pos++
      const list = []
      while (bytes[pos] !== 0x65) list.push(parse(depth + 1))
      pos++
      return list
    }
    if (c === 0x64) { // d...e
      const start = pos
      pos++
      const dict = {}
      while (bytes[pos] !== 0x65) {
        const k = new TextDecoder().decode(parse(depth + 1))
        dict[k] = parse(depth + 1, k)
      }
      pos++
      if (depth === 1 && key === 'info') infoSpan = [start, pos]
      return dict
    }
    if (c >= 0x30 && c <= 0x39) { // <len>:<bytes>
      const colon = bytes.indexOf(0x3a, pos)
      const len = Number(new TextDecoder().decode(bytes.subarray(pos, colon)))
      const out = bytes.subarray(colon + 1, colon + 1 + len)
      pos = colon + 1 + len
      return out
    }
    throw new Error(`bad bencode at ${pos}`)
  }

  const root = parse(0)
  if (!infoSpan) throw new Error('torrent has no info dictionary')
  return { root, infoBytes: bytes.subarray(infoSpan[0], infoSpan[1]) }
}

const text = (b) => (b instanceof Uint8Array ? new TextDecoder().decode(b) : String(b ?? ''))

/** infoHash (hex), trackers, and the file list (index order = Stremio fileIdx). */
export async function torrentInfo(bytes) {
  const { root, infoBytes } = decodeTorrent(bytes)
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-1', infoBytes))
  const infoHash = [...digest].map((b) => b.toString(16).padStart(2, '0')).join('')
  const info = root.info
  const files = Array.isArray(info.files)
    ? info.files.map((f) => ({ path: f.path.map(text).join('/'), length: f.length }))
    : [{ path: text(info.name), length: info.length }]
  const trackers = new Set()
  if (root.announce) trackers.add(text(root.announce))
  for (const tier of root['announce-list'] || []) for (const t of tier) trackers.add(text(t))
  return { infoHash, files, trackers: [...trackers] }
}
