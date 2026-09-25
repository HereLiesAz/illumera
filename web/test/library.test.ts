import { describe, expect, it } from 'vitest'
import { Library, type Progress } from '../src/core/library'

function progress(id: string, type: string, updatedAt: number): Progress {
  return { type, id, name: id, videoId: id, time: 100, duration: 1000, updatedAt }
}

describe('continueWatching', () => {
  it('shows only movies and series, newest first', () => {
    const lib = new Library()
    lib.progress.set({
      m: progress('m', 'movie', 1),
      s: progress('s', 'series', 2),
      c: progress('c', 'channel', 3),
      t: progress('t', 'tv', 4),
    })
    expect(lib.continueWatching().map((p) => p.id)).toEqual(['s', 'm'])
  })
})
