/**
 * Browsers only render WebVTT in <track>. SRT converts cheaply; ASS/SSA lose styling and
 * keep their dialogue text.
 */

export function srtToVtt(srt: string): string {
  const body = srt
    .replace(/^﻿/, '')
    .replace(/\r\n?/g, '\n')
    // 00:00:01,000 --> 00:00:02,000  →  00:00:01.000 --> 00:00:02.000
    .replace(/(\d{1,2}:\d{2}:\d{2}),(\d{3})/g, '$1.$2')
    // Drop cue numbers: a bare integer line right before a timing line.
    .replace(/(^|\n)\d+\n(?=\d{1,2}:\d{2}:\d{2}\.\d{3} -->)/g, '$1')
  return 'WEBVTT\n\n' + body.trim() + '\n'
}

function assTime(t: string): string {
  const [h, m, s] = t.trim().split(':')
  const [sec, cs = '0'] = s.split('.')
  return `${h.padStart(2, '0')}:${m.padStart(2, '0')}:${sec.padStart(2, '0')}.${(cs + '00').slice(0, 3)}`
}

export function assToVtt(ass: string): string {
  const lines = ass.replace(/\r\n?/g, '\n').split('\n')
  let format: string[] = []
  const cues: string[] = []
  for (const line of lines) {
    if (/^\[Events\]/i.test(line)) { format = []; continue }
    if (/^Format:/i.test(line)) { format = line.slice(7).split(',').map((f) => f.trim().toLowerCase()); continue }
    if (!/^Dialogue:/i.test(line) || !format.length) continue
    const parts = line.slice(9).split(',')
    const text = parts.slice(format.length - 1).join(',')
    const start = parts[format.indexOf('start')]
    const end = parts[format.indexOf('end')]
    if (!start || !end) continue
    const clean = text.replace(/\{[^}]*\}/g, '').replace(/\\N/gi, '\n').replace(/\\h/g, ' ').trim()
    if (clean) cues.push(`${assTime(start)} --> ${assTime(end)}\n${clean}`)
  }
  return 'WEBVTT\n\n' + cues.join('\n\n') + '\n'
}

/** Any supported subtitle text → WebVTT, judged by content rather than extension. */
export function toVtt(text: string): string {
  const t = text.replace(/^﻿/, '')
  if (/^WEBVTT/.test(t)) return t
  if (/^\s*\[Script Info\]/i.test(t) || /\nDialogue:/i.test(t)) return assToVtt(t)
  return srtToVtt(t)
}

/** Fetches a subtitle and returns an object URL of it as WebVTT. */
export async function loadVttUrl(url: string): Promise<string> {
  const res = await fetch(url)
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  const buf = await res.arrayBuffer()
  let text = new TextDecoder('utf-8').decode(buf)
  // Many SRTs are Windows-1252; replacement characters mean UTF-8 was wrong.
  if (text.indexOf('�') >= 0) text = new TextDecoder('windows-1252').decode(buf)
  return URL.createObjectURL(new Blob([toVtt(text)], { type: 'text/vtt' }))
}
