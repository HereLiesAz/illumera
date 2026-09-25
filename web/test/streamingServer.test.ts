import { afterEach, describe, expect, it, vi } from 'vitest'
import { peerSources, pickFileIndex, probeServer, serverOnline, torrentUrl } from '../src/core/streamingServer'
import { isPlayable } from '../src/player/session'

const hash = 'a'.repeat(40)
const files = [{ path: 'Show/sample.mkv', length: 5 }, { path: 'Show/Show.S01E02.mkv', length: 900 }, { path: 'Show/readme.txt', length: 1 }]

describe('file choice (Android order)', () => {
  it('prefers the named file, then fileIdx, then the guess, then the largest video', () => {
    expect(pickFileIndex(files, { infoHash: hash, fileIdx: 0, behaviorHints: { filename: 'Show.S01E02.mkv' } })).toBe(1)
    expect(pickFileIndex(files, { infoHash: hash, fileIdx: 2 })).toBe(2)
    expect(pickFileIndex(files, { infoHash: hash }, 0)).toBe(0)
    expect(pickFileIndex(files, { infoHash: hash })).toBe(1)
  })
})

describe('streaming server', () => {
  afterEach(() => { vi.unstubAllGlobals(); serverOnline.set(null) })

  it('passes the stream’s trackers, or DHT for the hash', () => {
    expect(peerSources({ infoHash: hash, sources: ['tracker:udp://t:1', 'http://x'] })).toEqual(['tracker:udp://t:1'])
    expect(peerSources({ infoHash: hash })).toEqual([`dht:${hash}`])
  })

  it('adds the torrent and returns the chosen file’s URL', async () => {
    let body: any
    vi.stubGlobal('fetch', async (url: string, init: { body: string }) => {
      expect(url).toBe(`http://127.0.0.1:11470/${hash}/create`)
      body = JSON.parse(init.body)
      return { ok: true, json: async () => ({ files, guessedFileIdx: 0 }) }
    })
    expect(await torrentUrl({ infoHash: hash.toUpperCase(), behaviorHints: { filename: 'Show.S01E02.mkv' } })).toBe(`http://127.0.0.1:11470/${hash}/1`)
    expect(body.torrent).toEqual({ infoHash: hash })
  })

  it('rejects a bad hash and reports an unreachable server', async () => {
    await expect(torrentUrl({ infoHash: 'nope' })).rejects.toThrow('no valid torrent hash')
    vi.stubGlobal('fetch', async () => { throw new TypeError('Failed to fetch') })
    await expect(torrentUrl({ infoHash: hash })).rejects.toThrow('Stremio Service')
  })

  it('torrents count as playable only while a server answers', async () => {
    vi.stubGlobal('fetch', async () => ({ ok: true }))
    expect(isPlayable({ infoHash: hash })).toBe(false)
    expect(await probeServer()).toBe(true)
    expect(isPlayable({ infoHash: hash })).toBe(true)
    expect(isPlayable({ url: 'magnet:?xt=1' })).toBe(false)
  })
})
