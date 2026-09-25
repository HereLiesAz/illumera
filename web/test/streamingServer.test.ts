import { afterEach, describe, expect, it, vi } from 'vitest'
import { internal, magnet, peerSources, pickFileIndex, probeServer, server, serverUrl, torrentUrl } from '../src/core/streamingServer'
import { isPlayable } from '../src/player/session'

const hash = 'a'.repeat(40)
const files = [{ path: 'Show/sample.mkv', length: 5 }, { path: 'Show/Show.S01E02.mkv', length: 900 }, { path: 'Show/readme.txt', length: 1 }]
const res = (status: number, body: unknown) => ({ ok: status < 400, status, json: async () => body, text: async () => String(body) })

describe('file choice (Android order)', () => {
  it('prefers the named file, then fileIdx, then the guess, then the largest video', () => {
    expect(pickFileIndex(files, { infoHash: hash, fileIdx: 0, behaviorHints: { filename: 'Show.S01E02.mkv' } })).toBe(1)
    expect(pickFileIndex(files, { infoHash: hash, fileIdx: 2 })).toBe(2)
    expect(pickFileIndex(files, { infoHash: hash }, 0)).toBe(0)
    expect(pickFileIndex(files, { infoHash: hash })).toBe(1)
  })
})

describe('streaming servers', () => {
  afterEach(() => { vi.unstubAllGlobals(); server.set(undefined); serverUrl.set('') })

  it('passes the stream’s trackers, or DHT for the hash', () => {
    expect(peerSources({ infoHash: hash, sources: ['tracker:udp://t:1', 'http://x'] })).toEqual(['tracker:udp://t:1'])
    expect(peerSources({ infoHash: hash })).toEqual([`dht:${hash}`])
    expect(magnet({ infoHash: hash.toUpperCase(), sources: ['tracker:udp://t:1'] })).toBe(`magnet:?xt=urn:btih:${hash}&tr=udp%3A%2F%2Ft%3A1`)
  })

  it('finds TorrServer first, then Stremio’s server, on the default ports', async () => {
    vi.stubGlobal('fetch', async (url: string) => {
      if (url === 'http://127.0.0.1:8090/echo') throw new TypeError('refused')
      if (url === 'http://127.0.0.1:8090/settings') throw new TypeError('refused')
      if (url === 'http://127.0.0.1:11470/echo') return res(404, '')
      if (url === 'http://127.0.0.1:11470/settings') return res(200, {})
      throw new Error(url)
    })
    expect(isPlayable({ infoHash: hash })).toBe(false)
    expect(await probeServer()).toEqual({ url: 'http://127.0.0.1:11470', kind: 'stremio' })
    expect(isPlayable({ infoHash: hash })).toBe(true)

    vi.stubGlobal('fetch', async (url: string) => (url.endsWith(':8090/echo') ? res(200, 'MatriX.145') : res(404, '')))
    expect(await probeServer()).toEqual({ url: 'http://127.0.0.1:8090', kind: 'torrserver' })
  })

  it('Stremio server: adds the torrent and returns the chosen file’s URL', async () => {
    let body: any
    vi.stubGlobal('fetch', async (url: string, init: { body: string }) => {
      expect(url).toBe(`http://127.0.0.1:11470/${hash}/create`)
      body = JSON.parse(init.body)
      return res(200, { files, guessedFileIdx: 0 })
    })
    const url = await torrentUrl({ infoHash: hash.toUpperCase(), behaviorHints: { filename: 'Show.S01E02.mkv' } }, { url: 'http://127.0.0.1:11470', kind: 'stremio' })
    expect(url).toBe(`http://127.0.0.1:11470/${hash}/1`)
    expect(body.torrent).toEqual({ infoHash: hash })
  })

  it('TorrServer: waits for the file list and streams by 1-based id', async () => {
    const calls: any[] = []
    let gets = 0
    vi.stubGlobal('fetch', async (_url: string, init: { body: string }) => {
      const body = JSON.parse(init.body)
      calls.push(body.action)
      if (body.action === 'add') return res(200, { hash })
      gets++
      return res(200, gets < 2 ? {} : { file_stats: [{ id: 1, path: 'x/sample.mkv', length: 5 }, { id: 2, path: 'x/Movie.mkv', length: 900 }] })
    })
    const url = await internal.torrServerUrl('http://127.0.0.1:8090', { infoHash: hash }, async () => undefined)
    expect(calls).toEqual(['add', 'get', 'get'])
    expect(url).toBe(`http://127.0.0.1:8090/stream?link=${encodeURIComponent(`magnet:?xt=urn:btih:${hash}`)}&index=2&play`)
  })

  it('reports a missing server, a bad hash and an unreachable server', async () => {
    await expect(torrentUrl({ infoHash: hash }, null)).rejects.toThrow('No streaming server')
    await expect(torrentUrl({ infoHash: 'nope' }, { url: 'http://x', kind: 'stremio' })).rejects.toThrow('no valid torrent hash')
    vi.stubGlobal('fetch', async () => { throw new TypeError('Failed to fetch') })
    await expect(torrentUrl({ infoHash: hash }, { url: 'http://x', kind: 'torrserver' })).rejects.toThrow('isn’t reachable')
  })
})
