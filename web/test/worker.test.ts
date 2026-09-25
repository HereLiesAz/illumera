import { afterEach, describe, expect, it, vi } from 'vitest'
import { handle } from '../worker/index'
import { parseSegments } from '../src/core/intro'
import { loadSoundtrack } from '../src/core/soundtrack'

const env = { ASSETS: { fetch: async () => new Response('asset') } }

describe('web worker', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('proxies IntroDB with CORS and caching', async () => {
    vi.stubGlobal('fetch', async (url: string) => {
      expect(url).toBe('https://api.introdb.app/segments?imdb_id=tt1&season=2&episode=3')
      return new Response(JSON.stringify({ intro: { start_ms: 1000, end_ms: 5000 } }))
    })
    const res = await handle(new Request('https://w.test/api/introdb/segments?imdb_id=tt1&season=2&episode=3'), env)
    expect(res.status).toBe(200)
    expect(res.headers.get('access-control-allow-origin')).toBe('*')
    expect(res.headers.get('cache-control')).toContain('max-age')
    expect(parseSegments(await res.json())).toEqual({ introStart: 1, introEnd: 5, outroStart: undefined })
  })

  it('validates input and 404s unknown api routes; everything else is an asset', async () => {
    expect((await handle(new Request('https://w.test/api/introdb/segments?imdb_id=x'), env)).status).toBe(400)
    expect((await handle(new Request('https://w.test/api/nope'), env)).status).toBe(404)
    expect(await (await handle(new Request('https://w.test/#/home'), env)).text()).toBe('asset')
  })
})

describe('soundtrack', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('loads an episode and drops empty groups; non-IMDb ids are skipped', async () => {
    const seen: string[] = []
    vi.stubGlobal('fetch', async (url: string) => {
      seen.push(url)
      return { ok: true, json: async () => ({ title: 'Show', groups: [{ season: 1, episode: 2, songs: [{ title: 'A' }] }, { season: 1, episode: 3, songs: [] }] }) }
    })
    const st = await loadSoundtrack('series', 'tt5', 1, 2)
    expect(seen).toEqual(['https://stremio-soundtrack.hereliesaz.workers.dev/soundtrack/series/tt5:1:2.json'])
    expect(st?.groups.map((g) => g.episode)).toEqual([2])
    expect(await loadSoundtrack('movie', 'kitsu:1')).toBeNull()
  })
})
