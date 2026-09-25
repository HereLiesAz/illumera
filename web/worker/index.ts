/**
 * The web app's Worker: serves the built app (static assets) and a small /api for what a
 * browser can't do itself.
 *
 *   GET  /api/introdb/segments?imdb_id=&season=&episode=   IntroDB, which only allows its own site (CORS)
 *
 * Everything else is a static asset (index.html for app routes).
 */

interface Env {
  ASSETS: { fetch(request: Request): Promise<Response> }
}

const CORS = { 'access-control-allow-origin': '*', 'access-control-allow-headers': 'content-type' }

function json(body: unknown, status = 200, extra: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json', ...CORS, ...extra } })
}

async function introSegments(url: URL): Promise<Response> {
  const imdb = url.searchParams.get('imdb_id') ?? ''
  const season = url.searchParams.get('season') ?? ''
  const episode = url.searchParams.get('episode') ?? ''
  if (!/^tt\d+$/.test(imdb) || !/^\d+$/.test(season) || !/^\d+$/.test(episode)) return json({ error: 'imdb_id, season and episode are required' }, 400)
  const upstream = await fetch(`https://api.introdb.app/segments?imdb_id=${imdb}&season=${season}&episode=${episode}`)
  if (!upstream.ok) return json({ error: `IntroDB returned ${upstream.status}` }, upstream.status === 404 ? 404 : 502)
  // Segments rarely change; a day of caching spares IntroDB.
  return json(await upstream.json(), 200, { 'cache-control': 'public, max-age=86400' })
}

export async function handle(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url)
  if (url.pathname.startsWith('/api/')) {
    if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: CORS })
    try {
      if (url.pathname === '/api/introdb/segments' && request.method === 'GET') return await introSegments(url)
    } catch {
      return json({ error: 'Upstream unreachable' }, 502)
    }
    return json({ error: 'Not found' }, 404)
  }
  return env.ASSETS.fetch(request)
}

export default { fetch: handle }
