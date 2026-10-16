import { afterEach, describe, expect, it, vi } from 'vitest'
import { handle, resetRateLimit } from '../worker/index'
import { parseSegments } from '../src/core/intro'
import { loadSoundtrack } from '../src/core/soundtrack'

const env = { ASSETS: { fetch: async () => new Response('asset') } }

describe('web worker', () => {
  afterEach(() => { vi.unstubAllGlobals(); resetRateLimit() })

  it('proxies IntroDB with CORS and caching', async () => {
    vi.stubGlobal('fetch', async (url: string) => {
      expect(url).toBe('https://api.introdb.app/segments?imdb_id=tt1&season=2&episode=3')
      return new Response(JSON.stringify({ intro: { start_ms: 1000, end_ms: 5000 } }))
    })
    const res = await handle(new Request('https://w.test/api/introdb/segments?imdb_id=tt1&season=2&episode=3', { headers: { origin: 'https://w.test' } }), env)
    expect(res.status).toBe(200)
    expect(res.headers.get('access-control-allow-origin')).toBe('https://w.test')
    expect(res.headers.get('cache-control')).toContain('max-age')
    expect(parseSegments(await res.json())).toEqual({ introStart: 1, introEnd: 5, outroStart: undefined })
  })

  it('allows the app’s origins (TV packages send null) and refuses others', async () => {
    const call = (origin: string, extraEnv = {}) => handle(new Request('https://w.test/api/nope', { headers: { origin } }), { ...env, ...extraEnv })
    expect((await call('null')).headers.get('access-control-allow-origin')).toBe('null')
    expect((await call('tauri://localhost')).status).toBe(404)
    expect((await call('https://evil.test')).status).toBe(403)
    expect((await call('https://evil.test')).headers.get('access-control-allow-origin')).toBeNull()
    expect((await call('https://mine.test', { ALLOWED_ORIGINS: 'https://other.test, https://mine.test' })).status).toBe(404)
    const pre = await handle(new Request('https://w.test/api/trakt/refresh', { method: 'OPTIONS', headers: { origin: 'null' } }), env)
    expect(pre.status).toBe(204)
    expect(pre.headers.get('access-control-allow-methods')).toContain('POST')
  })

  it('rate limits per client IP', async () => {
    const call = (ip: string) => handle(new Request('https://w.test/api/nope', { headers: { 'cf-connecting-ip': ip } }), env)
    for (let i = 0; i < 120; i++) expect((await call('1.1.1.1')).status).toBe(404)
    const limited = await call('1.1.1.1')
    expect(limited.status).toBe(429)
    expect(limited.headers.get('retry-after')).toBe('60')
    expect((await call('2.2.2.2')).status).toBe(404)
  })

  it('passes the upstream content type through', async () => {
    vi.stubGlobal('fetch', async () => new Response('<html>blocked</html>', { status: 403, headers: { 'content-type': 'text/html' } }))
    const res = await handle(new Request('https://w.test/api/trakt/device/code', { method: 'POST' }), { ...env, TRAKT_CLIENT_ID: 'id', TRAKT_CLIENT_SECRET: 's' })
    expect(res.status).toBe(403)
    expect(res.headers.get('content-type')).toBe('text/html')
  })

  it('validates input and 404s unknown api routes; everything else is an asset', async () => {
    expect((await handle(new Request('https://w.test/api/introdb/segments?imdb_id=x'), env)).status).toBe(400)
    expect((await handle(new Request('https://w.test/api/nope'), env)).status).toBe(404)
    expect(await (await handle(new Request('https://w.test/#/home'), env)).text()).toBe('asset')
  })

  it('sends Trakt a User-Agent, which its Cloudflare front requires', async () => {
    let sent: RequestInit | undefined
    vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
      expect(url).toBe('https://api.trakt.tv/oauth/device/code')
      sent = init
      return new Response(JSON.stringify({ user_code: 'ABCD' }))
    })
    const res = await handle(new Request('https://w.test/api/trakt/device/code', { method: 'POST' }),
      { ...env, TRAKT_CLIENT_ID: 'id', TRAKT_CLIENT_SECRET: 'secret' })
    expect(res.status).toBe(200)
    expect((sent?.headers as Record<string, string>)['user-agent']).toMatch(/^illumera-web\//)
    expect(JSON.parse(String(sent?.body))).toEqual({ client_id: 'id' })
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

  it('does not cache failures, so a later visit retries', async () => {
    let calls = 0
    vi.stubGlobal('fetch', async () => {
      calls++
      if (calls === 1) throw new TypeError('offline')
      return { ok: true, json: async () => ({ groups: [{ season: 1, episode: 1, songs: [{ title: 'B' }] }] }) }
    })
    expect(await loadSoundtrack('series', 'tt77', 1, 1)).toBeNull()
    expect((await loadSoundtrack('series', 'tt77', 1, 1))?.groups).toHaveLength(1)
    expect(calls).toBe(2)
  })
})
