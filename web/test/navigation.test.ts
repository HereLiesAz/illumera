import { describe, expect, it } from 'vitest'
import { nextEpisode } from '../src/player/session'
import { parseRoute, href } from '../src/ui/router'
import { nearest } from '../src/ui/spatial'
import type { Meta } from '../src/core/types'

const rect = (left: number, top: number, w = 10, h = 10) =>
  ({ left, top, right: left + w, bottom: top + h, width: w, height: h, x: left, y: top, toJSON() {} }) as DOMRect

describe('spatial navigation', () => {
  it('prefers the element straight ahead over a diagonal', () => {
    const from = rect(0, 0)
    const rects = [rect(30, 40), rect(40, 0)].map((r, index) => ({ rect: r, index }))
    expect(nearest(from, 'right', rects)).toBe(1)
    expect(nearest(from, 'left', rects)).toBe(-1)
    expect(nearest(from, 'down', rects)).toBe(0)
  })
})

describe('routes', () => {
  it('round-trips details with an origin addon', () => {
    const route = { name: 'details' as const, type: 'series', id: 'tt1', addon: 'https://a.test/x' }
    expect(parseRoute(href(route))).toEqual(route)
    expect(parseRoute('#/nowhere')).toEqual({ name: 'home' })
  })
})

describe('nextEpisode', () => {
  const meta: Meta = { id: 'tt1', type: 'series', name: 'S', videos: [
    { id: 'a', season: 1, episode: 2, released: '2020-01-02' },
    { id: 'b', season: 0, episode: 1 },
    { id: 'c', season: 1, episode: 1, released: '2020-01-01' },
    { id: 'd', season: 2, episode: 1, released: '2999-01-01' },
  ] }
  it('goes in order, skips specials and unaired', () => {
    expect(nextEpisode(meta, { id: 'c', season: 1, episode: 1 })?.id).toBe('a')
    expect(nextEpisode(meta, { id: 'a', season: 1, episode: 2 }, new Date('2021-01-01'))).toBeUndefined()
  })
})
