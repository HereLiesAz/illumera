import { afterEach, describe, expect, it, vi } from 'vitest'
import { handle } from '../worker/index'
import { accessToken, pollDeviceAuth, scrobbleBody, traktTokens } from '../src/core/trakt'

const env = { ASSETS: { fetch: async () => new Response('asset') }, TRAKT_CLIENT_ID: 'id', TRAKT_CLIENT_SECRET: 'secret' }

describe('scrobble body (mirrors Android)', () => {
  it('builds movie and episode bodies', () => {
    expect(scrobbleBody('movie', 'tt1', 42)).toEqual({ movie: { ids: { imdb: 'tt1' } }, progress: 42 })
    expect(scrobbleBody('series', 'tt2:3:4', 120)).toEqual({ show: { ids: { imdb: 'tt2' } }, episode: { season: 3, number: 4 }, progress: 100 })
    expect(scrobbleBody('movie', 'kitsu:1', 5)).toBeNull()
    expect(scrobbleBody('series', 'tt2', 5)).toBeNull()
  })
})

describe('Trakt through the worker', () => {
  afterEach(() => { vi.unstubAllGlobals(); traktTokens.set(null) })

  it('adds the secret server-side and passes Trakt’s status through', async () => {
    let sent: any
    vi.stubGlobal('fetch', async (url: string, init: { body: string }) => {
      expect(url).toBe('https://api.trakt.tv/oauth/device/token')
      sent = JSON.parse(init.body)
      return new Response('', { status: 400 })
    })
    const req = new Request('https://w.test/api/trakt/device/token', { method: 'POST', body: JSON.stringify({ code: 'dc' }) })
    const res = await handle(req, env)
    expect(res.status).toBe(400)
    expect(sent).toEqual({ code: 'dc', client_id: 'id', client_secret: 'secret' })
  })

  it('reports an unconfigured server', async () => {
    const res = await handle(new Request('https://w.test/api/trakt/config'), { ASSETS: env.ASSETS })
    expect(res.status).toBe(503)
  })

  it('maps poll results and stores tokens', async () => {
    const code = { deviceCode: 'dc', userCode: 'U', verificationUrl: 'v', interval: 5, expiresAt: Date.now() + 60_000 }
    vi.stubGlobal('fetch', async () => ({ status: 400, json: async () => ({}) }))
    expect(await pollDeviceAuth(code)).toBe('pending')
    vi.stubGlobal('fetch', async () => ({ status: 410, json: async () => ({}) }))
    expect(await pollDeviceAuth(code)).toBe('failed')
    vi.stubGlobal('fetch', async () => ({ status: 200, json: async () => ({ access_token: 'a', refresh_token: 'r', created_at: Date.now() / 1000, expires_in: 7776000 }) }))
    expect(await pollDeviceAuth(code)).toBe('done')
    expect(await accessToken()).toBe('a')
  })

  it('refreshes a token close to expiry', async () => {
    traktTokens.set({ accessToken: 'old', refreshToken: 'r', expiresAt: Date.now() + 1000 })
    vi.stubGlobal('fetch', async (url: string) => {
      expect(url).toBe('https://illumera-web.hereliesaz.workers.dev/api/trakt/refresh')
      return { status: 200, json: async () => ({ access_token: 'new', refresh_token: 'r2', created_at: Date.now() / 1000, expires_in: 7776000 }) }
    })
    expect(await accessToken()).toBe('new')
    expect(traktTokens.get()?.refreshToken).toBe('r2')
  })
})
