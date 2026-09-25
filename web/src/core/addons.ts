import { Secret } from './secrets'
import type { Addon, CatalogManifest, Manifest, Meta, Resource, Stream, Subtitle } from './types'

/** Addon client and installed-addon store. Behavior mirrors Android's AddonRepository. */

export const CINEMETA = 'https://v3-cinemeta.strem.io'
export const OPENSUBTITLES = 'https://opensubtitles-v3.strem.io'

const TIMEOUT = { catalog: 10_000, stream: 20_000, originMeta: 5_000, meta: 10_000, subtitles: 8_000, manifest: 30_000 }

/** Local-network hosts stay on http; everything else is upgraded to https (docs/ADDONS.md). */
export function upgradeUrl(url: string): string {
  const m = /^http:\/\/([^/:?#]+)(:\d+)?/i.exec(url)
  if (!m) return url
  const host = m[1].toLowerCase()
  const local = host === 'localhost' || /\.(local|lan|home\.arpa)$/.test(host) ||
    /^(127\.|10\.|192\.168\.|169\.254\.)/.test(host) || /^172\.(1[6-9]|2\d|3[01])\./.test(host) || host === '[::1]'
  if (local) return url
  const port = m[2] === ':80' ? '' : m[2] ?? ''
  return 'https://' + m[1] + port + url.slice(m[0].length)
}

/** Base URL of an addon from anything the user pastes: stremio://, manifest URL or base. */
export function transportUrl(input: string): string {
  let url = input.trim().replace(/^stremio:\/\//i, 'https://')
  url = url.replace(/\/manifest\.json(\?.*)?$/i, '').replace(/\/+$/, '')
  return upgradeUrl(url)
}

/** Path-safe id: encoded, but ':' kept as addons expect ("tt1:2:3"). */
export function encodeId(id: string): string {
  return encodeURIComponent(id).replace(/%3A/gi, ':')
}

export async function fetchJson<T>(url: string, timeoutMs: number): Promise<T> {
  const ctrl = new AbortController()
  const timer = setTimeout(() => ctrl.abort(), timeoutMs)
  try {
    const res = await fetch(upgradeUrl(url), { signal: ctrl.signal })
    if (!res.ok) throw new Error(`HTTP ${res.status} for ${url}`)
    return (await res.json()) as T
  } finally {
    clearTimeout(timer)
  }
}

function resourceName(r: Resource): string { return typeof r === 'string' ? r : r.name }

export function supports(addon: Addon, resource: string): boolean {
  return (addon.manifest.resources ?? []).some((r) => {
    const name = resourceName(r)
    return name === resource || (resource === 'subtitles' && name === 'subtitle')
  })
}

/** Top-level idPrefixes; empty or missing matches everything. */
export function acceptsId(addon: Addon, id: string): boolean {
  const prefixes = addon.manifest.idPrefixes ?? []
  if (!prefixes.length) return true
  const lower = id.toLowerCase()
  return prefixes.some((p) => p.trim() && lower.indexOf(p.trim().toLowerCase()) === 0)
}

/** Subtitles honor object-form resource types/idPrefixes too. */
function acceptsSubtitles(addon: Addon, type: string, id: string): boolean {
  const res = (addon.manifest.resources ?? []).find((r) => ['subtitles', 'subtitle'].indexOf(resourceName(r)) >= 0)
  if (!res) return false
  const types = (typeof res === 'object' && res.types) || addon.manifest.types || []
  const prefixes = (typeof res === 'object' && res.idPrefixes) || addon.manifest.idPrefixes || []
  if (types.length && types.indexOf(type) < 0) return false
  const lower = id.toLowerCase()
  return !prefixes.length || prefixes.some((p) => lower.indexOf(p.toLowerCase()) === 0)
}

function validMeta(m: Partial<Meta> | undefined): m is Meta {
  return !!m && !!m.id && !!m.name && !!m.type
}

export class AddonStore {
  // Encrypted like credentials: addon URLs often carry keys (a debrid key in Torrentio's).
  readonly addons = new Secret<Addon[]>('addons', [])

  list(): Addon[] { return this.addons.get() }
  enabled(): Addon[] { return this.list().filter((a) => a.enabled) }

  /** Installs, or refreshes the manifest of, an addon. Throws with a readable message. */
  async install(input: string): Promise<Addon> {
    const base = transportUrl(input)
    const manifest = await fetchJson<Manifest>(`${base}/manifest.json`, TIMEOUT.manifest)
    if (!manifest || !manifest.id || !manifest.name) throw new Error("This addon's manifest is missing its id or name.")
    const existing = this.list()
    const addon: Addon = { transportUrl: base, manifest, enabled: true }
    const i = existing.findIndex((a) => a.transportUrl === base)
    this.addons.set(i >= 0 ? existing.map((a, j) => (j === i ? { ...addon, enabled: a.enabled } : a)) : [...existing, addon])
    return addon
  }

  /** First run: Cinemeta for catalogs and details, OpenSubtitles for subtitles. */
  async ensureDefaults(): Promise<void> {
    if (this.list().length) return
    for (const url of [CINEMETA, OPENSUBTITLES]) {
      try { await this.install(url) } catch { /* offline: try next launch */ }
    }
  }

  remove(base: string): void { this.addons.set(this.list().filter((a) => a.transportUrl !== base)) }

  toggle(base: string): void {
    this.addons.set(this.list().map((a) => (a.transportUrl === base ? { ...a, enabled: !a.enabled } : a)))
  }

  move(base: string, delta: number): void {
    const list = this.list().slice()
    const i = list.findIndex((a) => a.transportUrl === base)
    const j = i + delta
    if (i < 0 || j < 0 || j >= list.length) return
    const [a] = list.splice(i, 1)
    list.splice(j, 0, a)
    this.addons.set(list)
  }

  /** Catalogs that can be shown without user input (no required extras). */
  homeCatalogs(): Array<{ addon: Addon; catalog: CatalogManifest }> {
    const out: Array<{ addon: Addon; catalog: CatalogManifest }> = []
    for (const addon of this.enabled()) {
      if (!supports(addon, 'catalog')) continue
      for (const catalog of addon.manifest.catalogs ?? []) {
        if (!(catalog.extra ?? []).some((e) => e.isRequired)) out.push({ addon, catalog })
      }
    }
    return out
  }

  async catalog(addon: Addon, catalog: CatalogManifest, skip = 0): Promise<Meta[]> {
    const extra = skip > 0 ? `/skip=${skip}` : ''
    const url = `${addon.transportUrl}/catalog/${encodeId(catalog.type)}/${encodeId(catalog.id)}${extra}.json`
    try {
      const res = await fetchJson<{ metas?: Meta[] }>(url, TIMEOUT.catalog)
      return (res.metas ?? []).filter(validMeta).map((m) => ({ ...m, addonBase: addon.transportUrl }))
    } catch {
      return []
    }
  }

  static supportsSkip(catalog: CatalogManifest): boolean {
    return (catalog.extra ?? []).some((e) => e.name === 'skip')
  }

  /** Cinemeta search for movies and series. */
  async search(query: string): Promise<Meta[]> {
    const q = query.trim()
    if (!q) return []
    const run = (type: string) =>
      fetchJson<{ metas?: Meta[] }>(`${CINEMETA}/catalog/${type}/top/search=${encodeURIComponent(q)}.json`, TIMEOUT.catalog)
        .then((r): Meta[] => (r.metas ?? []).filter(validMeta).map((m) => ({ ...m, addonBase: CINEMETA })))
        .catch(() => [] as Meta[])
    const [movies, series] = await Promise.all([run('movie'), run('series')])
    return movies.concat(series)
  }

  /**
   * Details: the origin addon first, then meta addons by type, then Cinemeta. Only the
   * origin addon may answer with a different id.
   */
  async meta(type: string, id: string, originBase?: string): Promise<Meta | undefined> {
    const get = (base: string, t: string, ms: number) =>
      fetchJson<{ meta?: Meta }>(`${base}/meta/${encodeId(t)}/${encodeId(id)}.json`, ms)
        .then((r) => (validMeta(r.meta) ? { ...r.meta, addonBase: base } : undefined))
        .catch(() => undefined)

    if (originBase) {
      const meta = await get(originBase, type, TIMEOUT.originMeta)
      if (meta) return meta
    }
    const metaAddons = this.enabled().filter((a) => supports(a, 'meta') && a.transportUrl !== originBase)
    const typed = metaAddons.filter((a) => !(a.manifest.types ?? []).length || (a.manifest.types ?? []).indexOf(type) >= 0)
    for (const addon of typed.length ? typed : metaAddons.slice(0, 1)) {
      const meta = await get(addon.transportUrl, type, TIMEOUT.meta)
      if (meta && meta.id === id) return meta
    }
    return get(CINEMETA, type, TIMEOUT.meta)
  }

  /** Streams from every addon whose idPrefixes accept the id, tagged with their source. */
  async streams(type: string, id: string, onPartial?: (streams: Stream[]) => void): Promise<Stream[]> {
    const all: Stream[] = []
    const addons = this.enabled().filter((a) => supports(a, 'stream') && acceptsId(a, id))
    await Promise.all(addons.map(async (addon) => {
      try {
        const res = await fetchJson<{ streams?: Stream[] }>(`${addon.transportUrl}/stream/${encodeId(type)}/${encodeId(id)}.json`, TIMEOUT.stream)
        const tagged = (res.streams ?? []).map((s) => ({ ...s, addonBase: addon.transportUrl, addonName: addon.manifest.name }))
        all.push(...tagged)
        onPartial?.(all.slice())
      } catch { /* one slow or broken addon must not hide the rest */ }
    }))
    return all
  }

  /**
   * Subtitles for an id; with the chosen file's hints, addons get them as extras
   * (videoHash, videoSize, filename) and those results come first.
   */
  async subtitles(type: string, id: string, hints?: { videoHash?: string; videoSize?: number; filename?: string }): Promise<Subtitle[]> {
    const seriesId = id.split(':')[0]
    const extras = hints ? [
      hints.videoHash && `videoHash=${encodeURIComponent(hints.videoHash)}`,
      hints.videoSize && `videoSize=${hints.videoSize}`,
      hints.filename && `filename=${encodeURIComponent(hints.filename)}`,
    ].filter(Boolean).join('&') : ''
    const addons = this.enabled().filter((a) => acceptsSubtitles(a, type, seriesId))
    const results = await Promise.all(addons.map(async (addon) => {
      const path = `${addon.transportUrl}/subtitles/${encodeId(type)}/${encodeId(id)}`
      try {
        const res = await fetchJson<{ subtitles?: Subtitle[] }>(`${path}${extras ? '/' + extras : ''}.json`, TIMEOUT.subtitles)
        return (res.subtitles ?? [])
          .filter((s) => !!s.url)
          .map((s) => ({ ...s, url: new URL(s.url, addon.transportUrl + '/').toString(), name: addon.manifest.name }))
          .filter((s) => /^https?:/.test(s.url))
      } catch {
        return []
      }
    }))
    return ([] as Subtitle[]).concat(...results)
  }
}

export const addonStore = new AddonStore()
