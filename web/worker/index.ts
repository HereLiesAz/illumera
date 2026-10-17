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
 *
 * CORS is limited to the app's own origins: the Worker's origin, the hosted Worker, the
 * desktop app (tauri://localhost, http(s)://tauri.localhost), the TV packages (file://
 * pages send `Origin: null`) and the Vite dev server. Add more, comma-separated, in an
 * ALLOWED_ORIGINS variable. A request from any other origin gets a 403.
 *
 * /api is also rate limited per client IP (RATE_LIMIT requests a minute, in memory per
 * Worker isolate: best effort, enough to stop a script hammering the upstreams through us).
 */

interface Env {
  ASSETS: { fetch(request: Request): Promise<Response> }
  TRAKT_CLIENT_ID?: string
  TRAKT_CLIENT_SECRET?: string
  /** Extra allowed browser origins, comma-separated (e.g. a custom domain for the web app). */
  ALLOWED_ORIGINS?: string
}

const TRAKT = 'https://api.trakt.tv'
/** Trakt sits behind Cloudflare, which answers requests without a User-Agent with a 403 page. */
const USER_AGENT = 'illumera-web/1.0 (+https://github.com/HereLiesAz/illumera)'
const HOSTED_ORIGIN = 'https://illumera-web.hereliesaz.workers.dev'
const BUILT_IN_ORIGINS = [HOSTED_ORIGIN, 'tauri://localhost', 'http://tauri.localhost', 'https://tauri.localhost', 'null', 'http://localhost:5173', 'http://127.0.0.1:5173']
const RATE_LIMIT = 120
const RATE_WINDOW_MS = 60_000
const MAX_TRACKED_CLIENTS = 5_000
const NOT_CONFIGURED = 'Trakt isn’t configured on this server.'

function json(body: unknown, status = 200, extra: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json', ...extra } })
}

/** Passes an upstream response through with its own content type (JSON when it sent none or nothing), never cached. */
async function relay(upstream: Response): Promise<Response> {
  const text = await upstream.text()
  const type = text ? upstream.headers.get('content-type') ?? 'application/json' : 'application/json'
  return new Response(text || '{}', { status: upstream.status, headers: { 'content-type': type, 'cache-control': 'no-store' } })
}

function allowedOrigins(request: Request, env: Env): string[] {
  const extra = (env.ALLOWED_ORIGINS ?? '').split(',').map((o) => o.trim()).filter(Boolean)
  return [new URL(request.url).origin, ...BUILT_IN_ORIGINS, ...extra]
}

const hits = new Map<string, { count: number; resetAt: number }>()

/** Whether this client may make another /api request in the current window. */
function withinRateLimit(request: Request, now = Date.now()): boolean {
  const ip = request.headers.get('cf-connecting-ip') ?? 'unknown'
  if (hits.size > MAX_TRACKED_CLIENTS) for (const [k, v] of hits) if (v.resetAt <= now) hits.delete(k)
  const entry = hits.get(ip)
  if (!entry || entry.resetAt <= now) { hits.set(ip, { count: 1, resetAt: now + RATE_WINDOW_MS }); return true }
  entry.count++
  return entry.count <= RATE_LIMIT
}

/** Test hook: forget every client's request count. */
export function resetRateLimit(): void { hits.clear() }

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

async function api(url: URL, request: Request, env: Env): Promise<Response> {
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

export async function handle(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url)
  if (!url.pathname.startsWith('/api/')) return env.ASSETS.fetch(request)

  // Same-origin GETs may carry no Origin; a browser always sends one cross-origin.
  const origin = request.headers.get('origin')
  const cors: Record<string, string> = { vary: 'Origin' }
  if (origin !== null) {
    if (allowedOrigins(request, env).indexOf(origin) < 0) return json({ error: 'Origin not allowed' }, 403, cors)
    cors['access-control-allow-origin'] = origin
    cors['access-control-allow-headers'] = 'content-type'
    cors['access-control-allow-methods'] = 'GET, POST, OPTIONS'
  }
  if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: { ...cors, 'access-control-max-age': '86400' } })
  if (!withinRateLimit(request)) return json({ error: 'Too many requests' }, 429, { ...cors, 'retry-after': '60' })

  const res = await api(url, request, env)
  for (const k in cors) res.headers.set(k, cors[k])
  return res
}

export default { fetch: handle }
