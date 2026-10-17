// Stremio addon handlers. Pure functions of (request, fetchers) so they test without a network.

import { imdbIdOf, metadata, normalizeTitle, search, streamsForItem } from './ia.js'

export const MANIFEST = {
  id: 'com.hereliesaz.openarchive',
  version: '0.1.0',
  name: 'Open Archive',
  description:
    'Public-domain and openly licensed film and TV from the Internet Archive, as direct and torrent streams, plus Archive search results.',
  logo: 'https://archive.org/images/glogo.png',
  resources: ['stream', 'catalog', 'meta'],
  types: ['movie', 'series'],
  idPrefixes: ['tt', 'ia:'],
  catalogs: [
    { type: 'movie', id: 'openarchive-films', name: 'Open Archive', extra: [{ name: 'search', isRequired: true }] },
    { type: 'series', id: 'openarchive-tv', name: 'Open Archive TV', extra: [{ name: 'search', isRequired: true }] },
  ],
  behaviorHints: { configurable: false },
}

const CINEMETA = 'https://v3-cinemeta.strem.io'
const MAX_ITEMS = 3

/** Title, year and (for an episode) the episode's own title, from Cinemeta. */
async function describe(fetchJson, type, imdbId, season, episode) {
  const data = await fetchJson(`${CINEMETA}/meta/${type}/${imdbId}.json`)
  const meta = data?.meta
  if (!meta) return null
  const year = Number(String(meta.releaseInfo ?? meta.year ?? '').slice(0, 4)) || null
  const ep = season != null
    ? (meta.videos ?? []).find((v) => Number(v.season) === season && Number(v.episode) === episode)
    : null
  return { title: meta.name, year, episodeTitle: ep?.name || ep?.title || null }
}

/** Items that are this title: declared IMDb id first, then a normalized exact-title match. */
function pickMatches(docs, { imdbId, title, year }) {
  const want = normalizeTitle(title)
  const scored = []
  for (const d of docs) {
    const declared = imdbIdOf(d)
    if (declared && imdbId && declared !== imdbId) continue // explicitly another film
    const t = normalizeTitle([].concat(d.title)[0])
    const titleOk = t === want || t.startsWith(want) && /^(\d{4}|restored|hd|colou?r(ized)?|remastered)?$/.test(t.slice(want.length))
    if (!(declared === imdbId || titleOk)) continue
    const y = Number(d.year) || null
    if (year && y && Math.abs(y - year) > 1) continue
    scored.push({ d, score: (declared === imdbId ? 1000 : 0) + Math.log10(1 + (Number(d.downloads) || 0)) })
  }
  return scored.sort((a, b) => b.score - a.score).slice(0, MAX_ITEMS).map((s) => s.d)
}

export async function streamHandler({ type, id }, { fetchJson, fetchBytes }) {
  if (id.startsWith('ia:')) {
    return { streams: await streamsForItem(fetchJson, fetchBytes, id.slice(3)) }
  }
  const [imdbId, s, e] = id.split(':')
  if (!/^tt\d+$/.test(imdbId)) return { streams: [] } // never interpolate arbitrary ids into the Cinemeta path
  const season = s != null ? Number(s) : null
  const episode = e != null ? Number(e) : null
  const info = await describe(fetchJson, type, imdbId, season, episode)
  if (!info?.title) return { streams: [] }

  let docs
  if (type === 'series') {
    // Archive TV items are usually single episodes titled "<Show> - <Episode>" or similar.
    if (!info.episodeTitle) return { streams: [] }
    docs = await search(fetchJson, { query: `${info.title} ${info.episodeTitle}`, collections: ['classic_tv', 'animationandcartoons', 'classic_cartoons'], rows: 30 })
    const show = normalizeTitle(info.title)
    const ep = normalizeTitle(info.episodeTitle)
    docs = docs.filter((d) => {
      const t = normalizeTitle([].concat(d.title)[0])
      return t.includes(show) && t.includes(ep)
    }).slice(0, MAX_ITEMS)
  } else {
    docs = pickMatches(await search(fetchJson, { title: info.title, year: info.year, rows: 25 }), {
      imdbId, title: info.title, year: info.year,
    })
  }

  const perItem = await Promise.all(
    docs.map((d) => streamsForItem(fetchJson, fetchBytes, d.identifier, { label: [].concat(d.title)[0] }).catch(() => [])),
  )
  return { streams: perItem.flat() }
}

// Encode untrusted archive descriptions rather than deleting apparent HTML tags:
 // incomplete tag removal can leave a dangerous string when rendered downstream.
const escapeHtml = (value) => value
  .replace(/&/g, '&amp;')
  .replace(/</g, '&lt;')
  .replace(/>/g, '&gt;')
  .replace(/"/g, '&quot;')
  .replace(/'/g, '&#39;')

const posterOf = (identifier) => `https://archive.org/services/img/${encodeURIComponent(identifier)}`

function toMetaPreview(doc, type) {
  const title = [].concat(doc.title)[0]
  return {
    id: imdbIdOf(doc) ?? `ia:${doc.identifier}`,
    type,
    name: title,
    poster: posterOf(doc.identifier),
    releaseInfo: doc.year ? String(doc.year) : undefined,
    description: typeof doc.description === 'string' ? escapeHtml(doc.description.slice(0, 300)) : undefined,
  }
}

export async function catalogHandler({ type, id, extra }, { fetchJson }) {
  const query = extra?.search?.trim()
  if (!query) return { metas: [] }
  const collections = id === 'openarchive-tv' ? ['classic_tv', 'animationandcartoons', 'classic_cartoons'] : undefined
  const docs = await search(fetchJson, { query, collections, rows: 40 })
  const seen = new Set()
  const metas = []
  for (const d of docs) {
    const m = toMetaPreview(d, type)
    if (seen.has(m.id)) continue // several uploads of one film collapse to one result
    seen.add(m.id)
    metas.push(m)
  }
  return { metas }
}

export async function metaHandler({ type, id }, { fetchJson }) {
  if (!id.startsWith('ia:')) return { meta: null } // tt ids are Cinemeta's to describe
  const identifier = id.slice(3)
  const data = await metadata(fetchJson, identifier)
  const m = data?.metadata
  if (!m) return { meta: null }
  const desc = [].concat(m.description ?? [])[0]
  return {
    meta: {
      id,
      type,
      name: [].concat(m.title)[0] ?? identifier,
      poster: posterOf(identifier),
      background: posterOf(identifier),
      releaseInfo: m.year ? String(m.year) : undefined,
      description: typeof desc === 'string' ? escapeHtml(desc) : undefined,
      runtime: m.runtime,
      links: [{ name: 'Internet Archive', category: 'Source', url: `https://archive.org/details/${identifier}` }],
    },
  }
}
