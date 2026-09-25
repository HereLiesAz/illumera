import type { Stream } from './types'

/**
 * Port of Android's StreamParser and StreamQuality (app/.../data/stream). Keep the two in
 * step: docs/ADDONS.md documents these patterns for addon authors.
 */

export type Quality = '4k' | '1080p' | '720p' | 'sd' | 'cam' | 'unknown'

/** Higher is better. */
export const QUALITY_ORDER: Record<Quality, number> = { '4k': 5, '1080p': 4, '720p': 3, sd: 2, cam: 1, unknown: 0 }

export interface ParsedStream {
  quality: Quality
  sizeBytes?: number
  seeds?: number
  formats: Set<string>
}

const QUALITY_RULES: Array<[RegExp, Quality]> = [
  // Explicit resolutions win over words.
  [/\b2160[pi]?\b/i, '4k'],
  [/\b1080[pi]?\b/i, '1080p'],
  [/\b720[pi]?\b/i, '720p'],
  [/\b480[pi]?\b/i, 'sd'],
  [/\b(4k|uhd|ultra\s*hd)\b/i, '4k'],
  [/\bfhd\b/i, '1080p'],
  [/\b(cam|camrip|ts|telesync|hdts|hdcam|telecine|tc)\b/i, 'cam'],
  [/\b(sd|dvd|dvdrip)\b/i, 'sd'],
  [/\bHD\b/i, '720p'],
]

const SEED_PATTERNS = [
  /👤\s*(\d[\d,.]*)/,
  /\bseeds?[:\s]+(\d[\d,.]*)/i,
  // Needs a separator, so "S02E05" isn't read as seeds.
  /\bS[:\s]+(\d[\d,.]*)/i,
]

const SIZE_PATTERN = /(\d+(?:\.\d+)?)\s?(KB|MB|GB|TB)/i
const UNITS: Record<string, number> = { KB: 1024, MB: 1024 ** 2, GB: 1024 ** 3, TB: 1024 ** 4 }

const FORMATS: Array<[string, RegExp]> = [
  ['dv', /\b(dolby\s*vision|dovi|dv)\b/i],
  ['hdr', /\b(hdr10\+?|hdr|hlg)\b/i],
  ['dts', /\b(dts[-\s]?(hd|x|ma)?)\b/i],
  ['dolby', /\b(dolby\s*(digital|atmos)?|dd[+\s]?[257]\.?1?|atmos|ac-?3|eac-?3)\b/i],
  ['hevc', /\b(hevc|h\.?265|x\.?265)\b/i],
  ['av1', /\bav1\b/i],
  ['3d', /\b(3d|sbs|half.?sbs|hou)\b/i],
]

export function qualityOf(text: string): Quality {
  for (const [pattern, quality] of QUALITY_RULES) if (pattern.test(text)) return quality
  return 'unknown'
}

/** name, title, description and filename, space-joined. */
export function combinedText(stream: Stream): string {
  return [stream.name, stream.title, stream.description, stream.behaviorHints?.filename].filter(Boolean).join(' ')
}

export function sizeBytes(text: string): number | undefined {
  const m = SIZE_PATTERN.exec(text)
  if (!m) return undefined
  const bytes = Math.floor(parseFloat(m[1]) * UNITS[m[2].toUpperCase()])
  return bytes > 0 ? bytes : undefined
}

export function seeds(text: string): number | undefined {
  for (const pattern of SEED_PATTERNS) {
    const m = pattern.exec(text)
    if (m) {
      const n = parseInt(m[1].replace(/[,.]/g, ''), 10)
      return isNaN(n) ? undefined : n
    }
  }
  return undefined
}

export function formats(text: string): Set<string> {
  return new Set(FORMATS.filter(([, p]) => p.test(text)).map(([f]) => f))
}

export function parseStream(stream: Stream): ParsedStream {
  const text = combinedText(stream)
  // Quality from the first field that has one: filename, title, description, name.
  let quality: Quality = 'unknown'
  for (const field of [stream.behaviorHints?.filename, stream.title, stream.description, stream.name]) {
    if (!field) continue
    quality = qualityOf(field)
    if (quality !== 'unknown') break
  }
  return {
    quality,
    sizeBytes: stream.behaviorHints?.videoSize ?? sizeBytes(text),
    seeds: seeds(text),
    formats: formats(text),
  }
}
