import { fetchJson } from './addons'

/** Songs from the Soundtrack addon (HereLiesAz/stremio-soundtrack), as in Android's SoundtrackRepository. */

export const SOUNDTRACK_ADDON = 'https://stremio-soundtrack.hereliesaz.workers.dev'

export interface Song { title: string; artist?: string }
export interface SongGroup { season?: number | null; episode?: number | null; title?: string; songs: Song[] }
export interface Soundtrack { title?: string; groups: SongGroup[] }

const cache = new Map<string, Soundtrack | null>()

/** IMDb ids only; for an episode pass the series id with season and episode. Null when none are listed. */
export async function loadSoundtrack(type: string, imdbId: string, season?: number, episode?: number): Promise<Soundtrack | null> {
  if (!/^tt\d+$/.test(imdbId)) return null
  const kind = type === 'movie' ? 'movie' : 'series'
  const id = season !== undefined && episode !== undefined ? `${imdbId}:${season}:${episode}` : imdbId
  const key = `${kind}/${id}`
  if (cache.has(key)) return cache.get(key)!
  let result: Soundtrack | null = null
  try {
    const data = await fetchJson<Soundtrack>(`${SOUNDTRACK_ADDON}/soundtrack/${kind}/${id}.json`, 15_000)
    const groups = (data.groups ?? []).filter((g) => g.songs && g.songs.length)
    result = groups.length ? { title: data.title, groups } : null
  } catch { /* not found or unreachable */ }
  cache.set(key, result)
  return result
}
