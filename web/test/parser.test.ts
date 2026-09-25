import { describe, expect, it } from 'vitest'
import { parseStream, qualityOf, seeds, sizeBytes } from '../src/core/parser'

describe('parser (mirrors Android StreamParser)', () => {
  it('prefers explicit resolutions over words', () => {
    expect(qualityOf('Movie 1080p HD')).toBe('1080p')
    expect(qualityOf('UHD remux')).toBe('4k')
    expect(qualityOf('HDCAM')).toBe('cam')
    expect(qualityOf('nothing')).toBe('unknown')
  })

  it('takes quality from the first field that has one', () => {
    const s = parseStream({ name: 'Torrentio\n4k', title: 'Movie.1080p.WEB', behaviorHints: { filename: 'movie.mkv' } })
    expect(s.quality).toBe('1080p')
  })

  it('reads sizes in 1024s and ignores GiB', () => {
    expect(sizeBytes('💾 1.5 GB')).toBe(Math.floor(1.5 * 1024 ** 3))
    expect(sizeBytes('2 GiB')).toBeUndefined()
    expect(parseStream({ title: '9 GB', behaviorHints: { videoSize: 42 } }).sizeBytes).toBe(42)
  })

  it('reads seeders but not S02E05', () => {
    expect(seeds('👤 1,234')).toBe(1234)
    expect(seeds('Seeds: 12')).toBe(12)
    expect(seeds('Show S02E05')).toBeUndefined()
  })

  it('detects formats', () => {
    expect(Array.from(parseStream({ title: 'x265 HDR10+ DDP5.1 Atmos' }).formats).sort()).toEqual(['dolby', 'hdr', 'hevc'])
  })
})
