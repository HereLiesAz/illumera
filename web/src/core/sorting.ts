import { combinedText, parseStream, QUALITY_ORDER, type ParsedStream, type Quality } from './parser'
import type { Stream } from './types'

/** Port of Android's StreamSortingService. Defaults match ProfileEntity. */

export type SortKey = 'quality' | 'size' | 'seeds'
export type LanguageRequirement = 'off' | 'primary' | 'primary_or_secondary'

export interface SortPrefs {
  enabledQualities: Quality[]
  excludePhrases: string[]
  excludedFormats: string[]
  maxSizeGb: number
  skipSeedless: boolean
  primarySort: SortKey
  secondarySort: SortKey
  movieTargetSizeMb: number
  episodeTargetSizeMb: number
  minimumSeeds: number
  audioRequirement: LanguageRequirement
  subtitleRequirement: LanguageRequirement
  audioLanguages: [string, string]
  subtitleLanguages: [string, string]
}

export const DEFAULT_SORT_PREFS: SortPrefs = {
  enabledQualities: ['4k', '1080p', '720p', 'unknown'],
  excludePhrases: [],
  excludedFormats: [],
  maxSizeGb: 0,
  skipSeedless: false,
  primarySort: 'quality',
  secondarySort: 'size',
  movieTargetSizeMb: 3000,
  episodeTargetSizeMb: 750,
  minimumSeeds: 5,
  audioRequirement: 'off',
  subtitleRequirement: 'off',
  audioLanguages: ['', ''],
  subtitleLanguages: ['', ''],
}

/** English names and ISO 639-2 codes per ISO 639-1 code. */
export const LANGUAGE_ALIASES: Record<string, string[]> = {
  en: ['english', 'eng'], es: ['spanish', 'espanol', 'español', 'spa'], fr: ['french', 'fra', 'fre'],
  de: ['german', 'deu', 'ger'], it: ['italian', 'ita'], pt: ['portuguese', 'por'], ru: ['russian', 'rus'],
  ja: ['japanese', 'jpn'], ko: ['korean', 'kor'], zh: ['chinese', 'mandarin', 'zho', 'chi'],
  ar: ['arabic', 'ara'], hi: ['hindi', 'hin'], tr: ['turkish', 'tur'], pl: ['polish', 'pol'],
  nl: ['dutch', 'nld', 'dut'], sv: ['swedish', 'swe'], no: ['norwegian', 'nor'], da: ['danish', 'dan'],
  fi: ['finnish', 'fin'], cs: ['czech', 'ces', 'cze'], hu: ['hungarian', 'hun'], ro: ['romanian', 'ron', 'rum'],
  th: ['thai', 'tha'], vi: ['vietnamese', 'vie'], id: ['indonesian', 'ind'], uk: ['ukrainian', 'ukr'],
  el: ['greek', 'ell', 'gre'], he: ['hebrew', 'heb'], ms: ['malay', 'msa', 'may'], hr: ['croatian', 'hrv'],
  bg: ['bulgarian', 'bul'], sk: ['slovak', 'slk', 'slo'], sr: ['serbian', 'srp'], tl: ['filipino', 'tagalog'],
  fa: ['persian', 'farsi', 'fas', 'per'], bn: ['bengali', 'ben'], ta: ['tamil', 'tam'], te: ['telugu', 'tel'],
}

const AUDIO_CUE = /\b(audio|dub(?:bed)?|dual)\b/gi
const SUBTITLE_CUE = /\b(sub(?:title)?s?|subbed|cc|captions?)\b/gi
const CUE_RADIUS = 32
const patterns = new Map<string, RegExp>()

/** "eng", "English", "en-US" → "en"; unknown → the lowercased input. */
export function toIso2(code: string | undefined): string {
  const c = (code ?? '').trim().toLowerCase().replace('_', '-')
  const base = c.split('-')[0]
  if (base.length === 2) return base
  for (const [iso2, aliases] of Object.entries(LANGUAGE_ALIASES)) if (aliases.indexOf(base) >= 0) return iso2
  return base
}

function aliases(language: string): string[] {
  const tag = language.trim().toLowerCase().replace('_', '-')
  if (!tag || tag === '#off') return []
  const base = tag.split('-')[0]
  const out = new Set<string>()
  // Two-letter codes ("no", "it", "id") are ordinary words in titles; never match them as text.
  if (base.length > 2) out.add(base)
  if (tag.length > 2) { out.add(tag); out.add(tag.replace('-', ' ')) }
  for (const a of LANGUAGE_ALIASES[toIso2(base)] ?? []) out.add(a)
  if (tag === 'es-419') ['latin american spanish', 'latino', 'latam'].forEach((a) => out.add(a))
  if (tag === 'pt-br') ['brazilian portuguese', 'brazilian', 'pt br'].forEach((a) => out.add(a))
  return Array.from(out)
}

function matches(text: string, alias: string): Array<[number, number]> {
  let re = patterns.get(alias)
  if (!re) {
    const escaped = alias.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    re = new RegExp(`(?<![\\p{L}\\p{N}])${escaped}(?![\\p{L}\\p{N}])`, 'giu')
    patterns.set(alias, re)
  }
  const out: Array<[number, number]> = []
  re.lastIndex = 0
  let m: RegExpExecArray | null
  while ((m = re.exec(text))) out.push([m.index, m.index + m[0].length - 1])
  return out
}

function nearestCue(text: string, [first, last]: [number, number], cue: RegExp): number | undefined {
  const start = Math.max(0, first - CUE_RADIUS)
  const end = Math.min(text.length, last + CUE_RADIUS + 1)
  const center = (first + last) / 2
  const window = text.slice(start, end)
  let best: number | undefined
  cue.lastIndex = 0
  let m: RegExpExecArray | null
  while ((m = cue.exec(window))) {
    const d = Math.abs(start + m.index + (m[0].length - 1) / 2 - center)
    if (best === undefined || d < best) best = d
  }
  return best
}

function required(mode: LanguageRequirement, [primary, secondary]: [string, string]): string[] {
  const list = mode === 'primary' ? [primary] : mode === 'primary_or_secondary' ? [primary, secondary] : []
  return Array.from(new Set(list.map((l) => l.trim().toLowerCase()).filter((l) => l && l !== '#off')))
}

function hasAudioLanguage(stream: Stream, languages: string[]): boolean {
  if (!languages.length) return true
  const authoritative = [stream.behaviorHints?.filename, stream.name].filter((f): f is string => !!f)
  const contextual = [stream.title, stream.description].filter(Boolean).join(' ')
  return languages.some((lang) => {
    const list = aliases(lang)
    if (authoritative.some((f) => list.some((a) => a.length > 2 && matches(f, a).length > 0))) return true
    return list.some((a) => matches(contextual, a).some((range) => {
      const audio = nearestCue(contextual, range, AUDIO_CUE)
      const sub = nearestCue(contextual, range, SUBTITLE_CUE)
      return audio !== undefined && (sub === undefined || audio <= sub)
    }))
  })
}

function hasSubtitleLanguage(stream: Stream, languages: string[]): boolean {
  if (!languages.length) return true
  const advertised = stream.subtitles ?? []
  if (advertised.some((s) => languages.some((l) =>
    (s.lang && toIso2(s.lang) === toIso2(l)) || aliases(l).some((a) => matches(s.name ?? '', a).length > 0)))) return true
  const text = combinedText(stream)
  return languages.some((l) => aliases(l).some((a) => matches(text, a).some((r) => nearestCue(text, r, SUBTITLE_CUE) !== undefined)))
}

type Ranked = { stream: Stream; info: ParsedStream }

function byKey(key: SortKey): (a: Ranked, b: Ranked) => number {
  switch (key) {
    case 'size': return (a, b) => (b.info.sizeBytes ?? 0) - (a.info.sizeBytes ?? 0)
    case 'seeds': return (a, b) => {
      const tier = (s?: number) => (s === undefined ? 1 : s > 0 ? 2 : 0)
      return tier(b.info.seeds) - tier(a.info.seeds) || cmp(b.info.seeds ?? -1, a.info.seeds ?? -1)
    }
    default: return (a, b) => QUALITY_ORDER[b.info.quality] - QUALITY_ORDER[a.info.quality]
  }
}

/**
 * Filters and ranks streams from every addon: closeness to the target size, then seeders
 * below the minimum, then addon order, then the chosen sort keys.
 */
export function sortStreams(streams: Stream[], type: string, addonOrder: string[], prefs: SortPrefs = DEFAULT_SORT_PREFS): Stream[] {
  const phrases = prefs.excludePhrases.map((p) => p.trim().toLowerCase()).filter(Boolean)
  const maxBytes = prefs.maxSizeGb > 0 ? prefs.maxSizeGb * 1024 ** 3 : Infinity
  const targetMb = type === 'movie' ? prefs.movieTargetSizeMb : prefs.episodeTargetSizeMb
  const target = targetMb > 0 ? targetMb * 1024 ** 2 : 0
  const audio = required(prefs.audioRequirement, prefs.audioLanguages)
  const subs = required(prefs.subtitleRequirement, prefs.subtitleLanguages)

  const ranked: Ranked[] = streams
    .map((stream) => ({ stream, info: parseStream(stream) }))
    .filter(({ info }) => prefs.enabledQualities.indexOf(info.quality) >= 0)
    .filter(({ stream }) => !phrases.length || !phrases.some((p) => combinedText(stream).toLowerCase().indexOf(p) >= 0))
    .filter(({ info }) => !prefs.skipSeedless || info.seeds !== 0)
    .filter(({ stream }) => hasAudioLanguage(stream, audio) && hasSubtitleLanguage(stream, subs))
    .filter(({ info }) => info.sizeBytes === undefined || info.sizeBytes <= maxBytes)
    .filter(({ info }) => !prefs.excludedFormats.some((f) => info.formats.has(f)))

  const primary = prefs.primarySort
  const secondary = prefs.secondarySort !== primary ? prefs.secondarySort : primary === 'quality' ? 'size' : 'quality'
  const keys: SortKey[] = [primary, secondary]
  for (const k of ['quality', 'size', 'seeds'] as SortKey[]) if (keys.indexOf(k) < 0) keys.push(k)

  const order = (s: Stream) => { const i = addonOrder.indexOf(s.addonBase ?? ''); return i < 0 ? Infinity : i }
  const sizeGap = (r: Ranked) => (target <= 0 ? 0 : r.info.sizeBytes === undefined ? Number.MAX_SAFE_INTEGER : Math.abs(r.info.sizeBytes - target))
  const seedGap = (r: Ranked) => (prefs.minimumSeeds <= 0 || r.info.seeds === undefined || r.info.seeds >= prefs.minimumSeeds ? 0 : prefs.minimumSeeds - r.info.seeds)

  // Array.prototype.sort is stable from Chromium 70; index as a final tiebreak keeps
  // addon-given order on Chromium 68.
  const indexed = ranked.map((r, i) => ({ r, i }))
  indexed.sort((x, y) => {
    const a = x.r, b = y.r
    const base = sizeGap(a) - sizeGap(b) || seedGap(a) - seedGap(b) || cmp(order(a.stream), order(b.stream))
    if (base) return base
    for (const k of keys) { const c = byKey(k)(a, b); if (c) return c }
    return x.i - y.i
  })
  return indexed.map(({ r }) => r.stream)
}

function cmp(a: number, b: number): number { return a === b ? 0 : a < b ? -1 : 1 }
