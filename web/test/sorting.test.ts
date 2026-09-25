import { describe, expect, it } from 'vitest'
import { DEFAULT_SORT_PREFS, sortStreams, toIso2 } from '../src/core/sorting'
import type { Stream } from '../src/core/types'

const s = (title: string, addonBase = 'a'): Stream => ({ title, addonBase })

describe('sortStreams (mirrors Android StreamSortingService)', () => {
  it('hides SD and CAM by default', () => {
    const out = sortStreams([s('480p 1 GB'), s('CAM 1 GB'), s('1080p 1 GB')], 'movie', ['a'])
    expect(out.map((x) => x.title)).toEqual(['1080p 1 GB'])
  })

  it('ranks closeness to the target size before quality, unsized last', () => {
    const out = sortStreams([s('2160p 20 GB'), s('720p 3 GB'), s('1080p')], 'movie', ['a'])
    expect(out.map((x) => x.title)).toEqual(['720p 3 GB', '2160p 20 GB', '1080p'])
  })

  it('uses the episode target for series', () => {
    const out = sortStreams([s('1080p 3 GB'), s('1080p 700 MB')], 'series', ['a'])
    expect(out[0].title).toBe('1080p 700 MB')
  })

  it('then addon order, then quality', () => {
    const prefs = { ...DEFAULT_SORT_PREFS, movieTargetSizeMb: 0, minimumSeeds: 0 }
    const out = sortStreams([s('720p', 'b'), s('1080p', 'b'), s('720p', 'a')], 'movie', ['a', 'b'], prefs)
    expect(out.map((x) => `${x.addonBase}${x.title}`)).toEqual(['a720p', 'b1080p', 'b720p'])
  })

  it('filters by phrase, max size and format', () => {
    const prefs = { ...DEFAULT_SORT_PREFS, excludePhrases: ['hindi'], maxSizeGb: 5, excludedFormats: ['hevc'] }
    const out = sortStreams([s('1080p Hindi'), s('1080p 10 GB'), s('1080p x265'), s('1080p ok')], 'movie', ['a'], prefs)
    expect(out.map((x) => x.title)).toEqual(['1080p ok'])
  })

  it('audio language needs a cue in titles but not in the name', () => {
    const prefs = { ...DEFAULT_SORT_PREFS, audioRequirement: 'primary' as const, audioLanguages: ['es', ''] as [string, string] }
    const out = sortStreams([
      { title: '1080p Spanish subs', addonBase: 'a' },
      { title: '1080p Dual audio Spanish', addonBase: 'a' },
      { name: 'Spanish 1080p', addonBase: 'a' },
    ], 'movie', ['a'], prefs)
    expect(out.map((x) => x.title ?? x.name)).toEqual(['1080p Dual audio Spanish', 'Spanish 1080p'])
  })

  it('maps language codes', () => {
    expect(toIso2('eng')).toBe('en')
    expect(toIso2('pt-BR')).toBe('pt')
  })
})
