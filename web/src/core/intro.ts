import { API_BASE } from './api'
import { fetchJson } from './addons'

/** Intro/outro times from IntroDB (through the app's /api), as on Android. Seconds. */
export interface Segments { introStart?: number; introEnd?: number; outroStart?: number }

interface Raw { intro?: { start_ms?: number; end_ms?: number } | null; outro?: { start_ms?: number } | null }

export function parseSegments(raw: Raw): Segments {
  return {
    introStart: raw.intro?.start_ms != null ? raw.intro.start_ms / 1000 : undefined,
    introEnd: raw.intro?.end_ms != null ? raw.intro.end_ms / 1000 : undefined,
    outroStart: raw.outro?.start_ms != null ? raw.outro.start_ms / 1000 : undefined,
  }
}

/** Series episodes only; the id before ":season:episode" must be an IMDb id. */
export async function loadSegments(seriesId: string, season?: number, episode?: number): Promise<Segments | null> {
  if (!/^tt\d+$/.test(seriesId) || !season || !episode) return null
  try {
    return parseSegments(await fetchJson<Raw>(`${API_BASE}/api/introdb/segments?imdb_id=${seriesId}&season=${season}&episode=${episode}`, 5000))
  } catch {
    return null
  }
}
