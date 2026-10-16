// Cloudflare Worker entry: Stremio addon protocol routes over the handlers in addon.js.

import { MANIFEST, catalogHandler, metaHandler, streamHandler } from './addon.js'

const CORS = { 'access-control-allow-origin': '*', 'access-control-allow-headers': '*' }
const UA = { 'user-agent': 'OpenArchiveStremioAddon/0.1 (+https://github.com/HereLiesAz/illumera)' }

function json(body, maxAge) {
  return new Response(JSON.stringify(body), {
    headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': `public, max-age=${maxAge}`, ...CORS },
  })
}

/** fetch with the Worker edge cache in front, so the Archive sees one request per TTL. */
function makeFetchers(ttl) {
  const cached = async (url) => {
    const res = await fetch(url, { headers: UA, cf: { cacheTtl: ttl, cacheEverything: true } })
    if (!res.ok) throw new Error(`HTTP ${res.status} for ${url}`)
    return res
  }
  return {
    fetchJson: async (url) => (await cached(url)).json(),
    fetchBytes: async (url) => new Uint8Array(await (await cached(url)).arrayBuffer()),
  }
}

/** /resource/type/id[/extra].json → parts. Ids may contain ':' and are URL-encoded. */
export function parsePath(pathname) {
  const m = /^\/(stream|catalog|meta)\/(movie|series)\/([^/]+?)(?:\/([^/]+))?\.json$/.exec(pathname)
  if (!m) return null
  try {
    const extra = {}
    if (m[4]) for (const [k, v] of new URLSearchParams(decodeURIComponent(m[4]))) extra[k] = v
    return { resource: m[1], type: m[2], id: decodeURIComponent(m[3]), extra }
  } catch {
    return null // malformed percent-encoding is a 404, not a Worker error
  }
}

export default {
  async fetch(request) {
    const url = new URL(request.url)
    if (request.method === 'OPTIONS') return new Response(null, { headers: CORS })
    if (url.pathname === '/' || url.pathname === '/manifest.json') return json(MANIFEST, 3600)

    const req = parsePath(url.pathname)
    if (!req) return new Response('Not found', { status: 404, headers: CORS })
    const fetchers = makeFetchers(6 * 3600)
    try {
      if (req.resource === 'stream') return json(await streamHandler(req, fetchers), 6 * 3600)
      if (req.resource === 'catalog') return json(await catalogHandler(req, fetchers), 3600)
      return json(await metaHandler(req, fetchers), 24 * 3600)
    } catch (e) {
      // An unreachable Archive is "no results", never an addon error the client reports.
      const empty = req.resource === 'stream' ? { streams: [] } : req.resource === 'catalog' ? { metas: [] } : { meta: null }
      return json(empty, 60)
    }
  },
}
