import { describe, expect, it } from 'vitest'
import { assToVtt, srtToVtt, toVtt } from '../src/player/subtitles'

describe('subtitle conversion', () => {
  it('converts SRT', () => {
    const vtt = srtToVtt('1\r\n00:00:01,000 --> 00:00:02,500\r\nHello\r\n\r\n2\r\n00:00:03,000 --> 00:00:04,000\r\nWorld\r\n')
    expect(vtt).toBe('WEBVTT\n\n00:00:01.000 --> 00:00:02.500\nHello\n\n00:00:03.000 --> 00:00:04.000\nWorld\n')
  })

  it('keeps ASS dialogue text, commas included, without styling', () => {
    const ass = '[Script Info]\n[V4+ Styles]\nFormat: Name, Fontname\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\nDialogue: 0,0:00:01.50,0:00:03.00,Default,,0,0,0,,{\\i1}Hi, there\\Nfriend\n'
    expect(assToVtt(ass)).toBe('WEBVTT\n\n00:00:01.500 --> 00:00:03.000\nHi, there\nfriend\n')
    expect(toVtt(ass)).toBe(assToVtt(ass))
  })

  it('passes WebVTT through', () => {
    expect(toVtt('WEBVTT\n\nx')).toBe('WEBVTT\n\nx')
  })
})
