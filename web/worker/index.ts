/**
 * The web app's Worker: serves the built app (static assets) and a small /api for what a
 * browser can't do itself.
 *
 *   GET  /api/introdb/segments?imdb_id=&season=&episode=   IntroDB, which only allows its own site (CORS)
 *   GET  /api/trakt/config          the public Trakt client id
 *   POST /api/trakt/device/code     start Trakt device sign-in
 *   POST /api/trakt/device/token    { code }            poll it (Trakt's status codes pass through)
 *   POST /api/trakt/refresh         { refresh_token }   renew tokens
 *
 * The Trakt routes keep the client secret out of the browser. Set on the Worker:
 * a TRAKT_CLIENT_ID variable and `wrangler secret put TRAKT_CLIENT_SECRET`.
 *
 * Everything else is a static asset (index.html for app routes).
 */

interface Env {
  ASSETS: { fetch(request: Request): Promise<Response> }
  TRAKT_CLIENT_ID?: string
  TRAKT_CLIENT_SECRET?: string
}

const TRAKT = 'https://api.trakt.tv'
/** Trakt sits behind Cloudflare, which answers requests without a User-Agent with a 403 page. */
const USER_AGENT = 'illumera-web/1.0 (+https://github.com/HereLiesAz/illumera)'
const CORS = { 'access-control-allow-origin': '*', 'access-control-allow-headers': 'content-type' }
const NOT_CONFIGURED = 'Trakt isn’t configured on this server.'

function json(body: unknown, status = 200, extra: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json', ...CORS, ...extra } })
}

/** Passes an upstream response through with CORS, never cached. */
async function relay(upstream: Response): Promise<Response> {
  const text = await upstream.text()
  return new Response(text || '{}', { status: upstream.status, headers: { 'content-type': 'application/json', 'cache-control': 'no-store', ...CORS } })
}

async function readJson(request: Request): Promise<Record<string, unknown>> {
  try { return (await request.json()) as Record<string, unknown> } catch { return {} }
}

async function introSegments(url: URL): Promise<Response> {
  const imdb = url.searchParams.get('imdb_id') ?? ''
  const season = url.searchParams.get('season') ?? ''
  const episode = url.searchParams.get('episode') ?? ''
  if (!/^tt\d+$/.test(imdb) || !/^\d+$/.test(season) || !/^\d+$/.test(episode)) return json({ error: 'imdb_id, season and episode are required' }, 400)
  const upstream = await fetch(`https://api.introdb.app/segments?imdb_id=${imdb}&season=${season}&episode=${episode}`, {
    headers: { 'user-agent': USER_AGENT },
  })
  if (!upstream.ok) return json({ error: `IntroDB returned ${upstream.status}` }, upstream.status === 404 ? 404 : 502)
  // Segments rarely change; a day of caching spares IntroDB.
  return json(await upstream.json(), 200, { 'cache-control': 'public, max-age=86400' })
}

async function trakt(url: URL, request: Request, env: Env): Promise<Response | null> {
  const post = (path: string, body: Record<string, unknown>) =>
    fetch(`${TRAKT}${path}`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'user-agent': USER_AGENT },
      body: JSON.stringify(body),
    }).then(relay)

  if (url.pathname === '/api/trakt/config' && request.method === 'GET') {
    return env.TRAKT_CLIENT_ID ? json({ clientId: env.TRAKT_CLIENT_ID }) : json({ error: NOT_CONFIGURED }, 503)
  }
  if (request.method !== 'POST') return null
  if (!env.TRAKT_CLIENT_ID || !env.TRAKT_CLIENT_SECRET) return json({ error: NOT_CONFIGURED }, 503)
  const credentials = { client_id: env.TRAKT_CLIENT_ID, client_secret: env.TRAKT_CLIENT_SECRET }

  if (url.pathname === '/api/trakt/device/code') return post('/oauth/device/code', { client_id: env.TRAKT_CLIENT_ID })
  if (url.pathname === '/api/trakt/device/token') {
    const { code } = await readJson(request)
    if (typeof code !== 'string' || !code) return json({ error: 'code is required' }, 400)
    return post('/oauth/device/token', { code, ...credentials })
  }
  if (url.pathname === '/api/trakt/refresh') {
    const { refresh_token } = await readJson(request)
    if (typeof refresh_token !== 'string' || !refresh_token) return json({ error: 'refresh_token is required' }, 400)
    return post('/oauth/token', { refresh_token, grant_type: 'refresh_token', redirect_uri: 'urn:ietf:wg:oauth:2.0:oob', ...credentials })
  }
  return null
}

export async function handle(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url)
  if (url.pathname.startsWith('/api/')) {
    if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: CORS })
    try {
      if (url.pathname === '/api/introdb/segments' && request.method === 'GET') return await introSegments(url)
      if (url.pathname.startsWith('/api/trakt/')) {
        const res = await trakt(url, request, env)
        if (res) return res
      }
    } catch {
      return json({ error: 'Upstream unreachable' }, 502)
    }
    return json({ error: 'Not found' }, 404)
  }
  return env.ASSETS.fetch(request)
}

export default { fetch: handle }
