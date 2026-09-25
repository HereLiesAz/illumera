import { afterEach, describe, expect, it, vi } from 'vitest'
import { AddonStore, acceptsId, encodeId, transportUrl, upgradeUrl } from '../src/core/addons'
import type { Addon } from '../src/core/types'

const addon = (manifest: Partial<Addon['manifest']>, base = 'https://a.test'): Addon =>
  ({ transportUrl: base, enabled: true, manifest: { id: 'x', name: 'X', ...manifest } })

describe('addon urls', () => {
  it('upgrades public http, keeps local hosts', () => {
    expect(upgradeUrl('http://addon.example/x')).toBe('https://addon.example/x')
    expect(upgradeUrl('http://addon.example:80/x')).toBe('https://addon.example/x')
    expect(upgradeUrl('http://addon.example:7000/x')).toBe('https://addon.example:7000/x')
    expect(upgradeUrl('http://192.168.1.5:11470/x')).toBe('http://192.168.1.5:11470/x')
    expect(upgradeUrl('http://localhost:11470')).toBe('http://localhost:11470')
  })

  it('normalizes what users paste', () => {
    expect(transportUrl('stremio://torrentio.strem.fun/manifest.json')).toBe('https://torrentio.strem.fun')
    expect(transportUrl(' https://x.test/abc/manifest.json?y=1 ')).toBe('https://x.test/abc')
  })

  it('encodes ids but keeps colons', () => {
    expect(encodeId('tt1:2:3')).toBe('tt1:2:3')
    expect(encodeId('a b/c')).toBe('a%20b%2Fc')
  })

  it('matches idPrefixes case-insensitively; none means all', () => {
    expect(acceptsId(addon({ idPrefixes: ['TT'] }), 'tt123')).toBe(true)
    expect(acceptsId(addon({ idPrefixes: ['kitsu:'] }), 'tt123')).toBe(false)
    expect(acceptsId(addon({}), 'anything')).toBe(true)
  })
})

describe('AddonStore', () => {
  afterEach(() => vi.unstubAllGlobals())

  const respond = (routes: Record<string, unknown>) => {
    const seen: string[] = []
    vi.stubGlobal('fetch', async (url: string) => {
      seen.push(url)
      const body = routes[url]
      return body === undefined ? { ok: false, status: 404 } : { ok: true, json: async () => body }
    })
    return seen
  }

  it('asks only addons whose idPrefixes accept the id, and tags streams', async () => {
    const store = new AddonStore()
    store.addons.set([
      addon({ resources: ['stream'], idPrefixes: ['tt'] }, 'https://a.test'),
      addon({ resources: ['stream'], idPrefixes: ['kitsu'] }, 'https://b.test'),
    ])
    const seen = respond({ 'https://a.test/stream/series/tt1:1:2.json': { streams: [{ url: 'https://v/1.mp4' }] } })
    const streams = await store.streams('series', 'tt1:1:2')
    expect(seen).toEqual(['https://a.test/stream/series/tt1:1:2.json'])
    expect(streams[0].addonBase).toBe('https://a.test')
  })

  it('sends file hints as subtitle extras and resolves relative urls', async () => {
    const store = new AddonStore()
    store.addons.set([addon({ resources: [{ name: 'subtitles', types: ['movie'], idPrefixes: ['tt'] }] }, 'https://s.test')])
    const seen = respond({ 'https://s.test/subtitles/movie/tt1/videoSize=10&filename=a%20b.mkv.json': { subtitles: [{ url: 'x.srt', lang: 'eng' }] } })
    const subs = await store.subtitles('movie', 'tt1', { videoSize: 10, filename: 'a b.mkv' })
    expect(seen.length).toBe(1)
    expect(subs[0].url).toBe('https://s.test/x.srt')
  })

  it('only accepts a meta with the requested id from non-origin addons', async () => {
    const store = new AddonStore()
    store.addons.set([addon({ resources: ['meta'], types: ['movie'] }, 'https://m.test')])
    respond({
      'https://m.test/meta/movie/tt1.json': { meta: { id: 'tmdb:9', type: 'movie', name: 'Wrong' } },
      'https://v3-cinemeta.strem.io/meta/movie/tt1.json': { meta: { id: 'tt1', type: 'movie', name: 'Right' } },
    })
    expect((await store.meta('movie', 'tt1'))?.name).toBe('Right')
  })
})
