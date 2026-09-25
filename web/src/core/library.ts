import { Stored } from './storage'
import type { Meta } from './types'

/** Watch progress per title, for Continue Watching and resume. */
export interface Progress {
  type: string
  id: string
  name: string
  poster?: string
  /** The video being watched: the meta id for a movie, the episode's video id for a series. */
  videoId: string
  season?: number
  episode?: number
  time: number
  duration: number
  updatedAt: number
}

/** Past this fraction, a video counts as watched and leaves Continue Watching. */
export const WATCHED_FRACTION = 0.92

export class Library {
  readonly progress = new Stored<Record<string, Progress>>('progress', {})

  get(id: string): Progress | undefined { return this.progress.get()[id] }

  update(meta: Pick<Meta, 'type' | 'id' | 'name' | 'poster'>, videoId: string, time: number, duration: number, season?: number, episode?: number): void {
    if (!(duration > 0)) return
    const all = { ...this.progress.get() }
    all[meta.id] = { type: meta.type, id: meta.id, name: meta.name, poster: meta.poster, videoId, season, episode, time, duration, updatedAt: Date.now() }
    this.progress.set(all)
  }

  remove(id: string): void {
    const all = { ...this.progress.get() }
    delete all[id]
    this.progress.set(all)
  }

  /** Unfinished titles, newest first. */
  continueWatching(): Progress[] {
    return Object.keys(this.progress.get()).map((k) => this.progress.get()[k])
      .filter((p) => p.time > 30 && p.time / p.duration < WATCHED_FRACTION)
      .sort((a, b) => b.updatedAt - a.updatedAt)
  }
}

export const library = new Library()
