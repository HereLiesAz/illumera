import { serverOnline } from '../core/streamingServer'
import type { Meta, MetaVideo, Stream } from '../core/types'

/** What the player needs, handed over from the details screen. Lost on reload by design. */
export interface PlaybackSession {
  meta: Meta
  /** The episode, for series. */
  video?: MetaVideo
  /** The stream request id: the meta id, or the episode's video id. */
  videoId: string
  /** Ranked sources; the player starts at [index] and falls back down the list. */
  streams: Stream[]
  index: number
  resumeAt?: number
}

let current: PlaybackSession | undefined

export function setSession(session: PlaybackSession): void { current = session }
export function getSession(): PlaybackSession | undefined { return current }

/** Streams the web player can open: http(s) URLs, and torrents while a streaming server answers. */
export function isPlayable(stream: Stream): boolean {
  if (stream.url) return /^https?:/i.test(stream.url)
  return !!stream.infoHash && serverOnline.get() === true
}

/** Episode id per the protocol: the video's id, else series:season:episode. */
export function episodeId(meta: Meta, video: MetaVideo): string {
  return video.id || `${meta.id}:${video.season}:${video.episode}`
}

/** Next aired episode after [video], in season/episode order; specials (season 0) skipped. */
export function nextEpisode(meta: Meta, video: MetaVideo, now = new Date()): MetaVideo | undefined {
  const today = now.toISOString().slice(0, 10)
  const list = (meta.videos ?? [])
    .filter((v) => (v.season ?? 0) > 0 && (v.episode ?? 0) > 0)
    .filter((v) => !v.released || v.released.slice(0, 10) <= today)
    .sort((a, b) => (a.season! - b.season!) || (a.episode! - b.episode!))
  const i = list.findIndex((v) => v.season === video.season && v.episode === video.episode)
  return i >= 0 ? list[i + 1] : undefined
}
