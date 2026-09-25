import type Hls from 'hls.js'
import { useEffect, useRef, useState } from 'preact/hooks'
import { addonStore } from '../core/addons'
import { library } from '../core/library'
import { stremio } from '../core/stremio'
import { settings } from '../core/settings'
import { sortStreams, toIso2 } from '../core/sorting'
import type { Stream, Subtitle } from '../core/types'
import { episodeId, getSession, isPlayable, nextEpisode, setSession, type PlaybackSession } from '../player/session'
import { loadVttUrl } from '../player/subtitles'
import { Center, Icon } from './components'
import { focusFirst, onBack } from './spatial'

const HIDE_AFTER_MS = 4000
const SAVE_EVERY_MS = 5000

function clock(s: number): string {
  if (!isFinite(s)) return '--:--'
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = Math.floor(s % 60)
  return (h ? `${h}:${String(m).padStart(2, '0')}` : String(m)) + ':' + String(sec).padStart(2, '0')
}

/** Full screen, with the webkit prefix Chromium used before version 71. */
function toggleFullscreen(el: HTMLElement | null): void {
  const d = document as Document & { webkitFullscreenElement?: Element; webkitExitFullscreen?: () => void }
  if (d.fullscreenElement || d.webkitFullscreenElement) {
    (d.exitFullscreen?.bind(d) ?? d.webkitExitFullscreen?.bind(d))?.()
    return
  }
  const e = el as (HTMLElement & { webkitRequestFullscreen?: () => void }) | null
  if (e?.requestFullscreen) e.requestFullscreen().catch(() => undefined)
  else e?.webkitRequestFullscreen?.()
}

function isHls(url: string): boolean { return /\.m3u8(\?|$)|\/hls|\/playlist/i.test(url) }

/** Shorter than this, a "movie" or "episode" is a placeholder clip, not the real thing. */
function tooShort(type: string, seconds: number): boolean {
  return isFinite(seconds) && seconds > 0 && seconds < (type === 'movie' ? 270 : 90)
}

/** Next index at or after [from] that the browser can open. */
function nextPlayable(streams: Stream[], from: number): number {
  for (let i = from; i < streams.length; i++) if (isPlayable(streams[i])) return i
  return -1
}

export function Player() {
  const [session, setLocal] = useState<PlaybackSession | undefined>(getSession())
  const video = useRef<HTMLVideoElement>(null)
  const hls = useRef<Hls | null>(null)
  const root = useRef<HTMLDivElement>(null)
  const [playing, setPlaying] = useState(false)
  const [time, setTime] = useState(0)
  const [duration, setDuration] = useState(NaN)
  const [shown, setShown] = useState(true)
  const [toast, setToast] = useState<string>()
  const [subs, setSubs] = useState<Subtitle[]>([])
  const [activeSub, setActiveSub] = useState<number>(-1)
  const [menu, setMenu] = useState<'subs' | 'sources' | undefined>()
  const hideTimer = useRef<number>()

  const stream = session?.streams[session.index]
  const meta = session?.meta
  const mediaType = session?.video ? 'series' : meta?.type ?? 'movie'

  const say = (text: string) => { setToast(text); setTimeout(() => setToast((t) => (t === text ? undefined : t)), 3500) }
  const wake = () => {
    setShown(true)
    clearTimeout(hideTimer.current)
    hideTimer.current = window.setTimeout(() => { if (!video.current?.paused) setShown(false) }, HIDE_AFTER_MS)
  }

  const switchTo = (index: number, keepTime = true) => {
    if (!session) return
    const at = keepTime ? video.current?.currentTime : undefined
    const next = { ...session, index, resumeAt: at && at > 5 ? at : session.resumeAt }
    setSession(next)
    setLocal(next)
  }

  const fallback = (reason: string) => {
    if (!session || !settings.get().autoFallback) { say(reason); return }
    const i = nextPlayable(session.streams, session.index + 1)
    if (i < 0) { say(`${reason} No more sources to try.`); return }
    say(`${reason} Trying the next source.`)
    switchTo(i, false)
  }

  const save = () => {
    const v = video.current
    if (!v || !session || !isFinite(v.duration) || tooShort(mediaType, v.duration)) return
    library.update(session.meta, session.videoId, v.currentTime, v.duration, session.video?.season, session.video?.episode)
    // Throttled to once a minute inside.
    stremio.syncLibrary().catch(() => undefined)
  }

  // Load the current stream.
  useEffect(() => {
    const v = video.current
    if (!v || !stream?.url) return
    let cancelled = false
    hls.current?.destroy()
    hls.current = null
    const url = stream.url
    const headers = stream.behaviorHints?.proxyHeaders?.request
    if (isHls(url) && !v.canPlayType('application/vnd.apple.mpegurl')) {
      import('hls.js').then(({ default: HlsJs }) => {
        if (cancelled || !HlsJs.isSupported()) { v.src = url; return }
        const h = new HlsJs({
          // Browsers refuse some headers (User-Agent, Referer); the rest are sent.
          xhrSetup: headers ? (xhr) => { for (const k in headers) { try { xhr.setRequestHeader(k, headers[k]) } catch { /* forbidden */ } } } : undefined,
        })
        h.on(HlsJs.Events.ERROR, (_e, data) => { if (data.fatal) fallback('This source failed to play.') })
        h.loadSource(url)
        h.attachMedia(v)
        hls.current = h
      })
    } else {
      v.src = url
    }
    const resume = session?.resumeAt
    const onMeta = () => {
      setDuration(v.duration)
      if (tooShort(mediaType, v.duration)) { fallback('This source is only a placeholder clip.'); return }
      if (resume && resume < v.duration - 10) v.currentTime = resume
      v.play().catch(() => setPlaying(false))
    }
    v.addEventListener('loadedmetadata', onMeta, { once: true })
    return () => { cancelled = true; v.removeEventListener('loadedmetadata', onMeta) }
  }, [stream?.url])

  // Subtitles: the stream's own, then every subtitle addon's (with this file's hints).
  useEffect(() => {
    if (!session || !stream) return
    let live = true
    const hints = stream.behaviorHints
    addonStore.subtitles(mediaType, session.videoId, hints && { videoHash: hints.videoHash, videoSize: hints.videoSize, filename: hints.filename })
      .then((fromAddons) => {
        if (!live) return
        const own: Subtitle[] = (stream.subtitles ?? []).map((s) => ({ ...s, url: new URL(s.url, (s.transportUrl ?? stream.addonBase ?? location.href) + '/').toString(), name: s.name ?? stream.addonName }))
        const list = own.concat(fromAddons)
        setSubs(list)
        const want = settings.get().subtitleLanguage
        setActiveSub(want ? list.findIndex((s) => toIso2(s.lang) === want) : -1)
      })
    return () => { live = false }
  }, [session?.videoId, stream?.url])

  // Show the chosen subtitle as a <track>.
  useEffect(() => {
    const v = video.current
    if (!v) return
    Array.prototype.slice.call(v.querySelectorAll('track')).forEach((t: HTMLTrackElement) => t.remove())
    const sub = subs[activeSub]
    if (!sub) return
    let url: string | undefined
    loadVttUrl(sub.url).then((u) => {
      url = u
      const track = document.createElement('track')
      track.kind = 'subtitles'
      track.srclang = toIso2(sub.lang)
      track.src = u
      track.default = true
      v.appendChild(track)
      track.track.mode = 'showing'
    }).catch(() => say('That subtitle couldn’t be loaded.'))
    return () => { if (url) URL.revokeObjectURL(url) }
  }, [activeSub, subs])

  // Save progress now and then, and on the way out.
  useEffect(() => {
    const timer = setInterval(save, SAVE_EVERY_MS)
    return () => { clearInterval(timer); save() }
  }, [session?.videoId])

  // Start on the play button, not the seek bar above it.
  useEffect(() => { focusFirst(root.current?.querySelector('.buttons') ?? null); wake() }, [])
  useEffect(() => {
    const unBack = onBack(() => {
      if (menu) { setMenu(undefined); return true }
      if (!shown) { wake(); return true }
      return false
    })
    return unBack
  }, [menu, shown])

  // Media keys: play/pause/rewind/forward on TV remotes.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      wake()
      const v = video.current
      if (!v) return
      switch (e.keyCode) {
        case 415: v.play(); break // Play
        case 19: v.pause(); break // Pause
        case 10252: case 179: v.paused ? v.play() : v.pause(); break // PlayPause
        case 412: v.currentTime -= 10; break // Rewind
        case 417: v.currentTime += 10; break // FastForward
        default: return
      }
      e.preventDefault()
    }
    document.addEventListener('keydown', onKey)
    return () => { document.removeEventListener('keydown', onKey); hls.current?.destroy() }
  }, [])

  const playNext = async () => {
    if (!session?.video || !meta) return
    const next = nextEpisode(meta, session.video)
    if (!next) return
    save()
    say(`Loading S${next.season} · E${next.episode}…`)
    const id = episodeId(meta, next)
    const order = addonStore.enabled().map((a) => a.transportUrl)
    const streams = sortStreams(await addonStore.streams('series', id), 'series', order, settings.sortPrefs())
    const group = stream?.behaviorHints?.bingeGroup ?? stream?.behaviorHints?.group
    // Same bingeGroup from the same addon first, as on Android; else the top playable source.
    let index = group ? streams.findIndex((s) => (s.behaviorHints?.bingeGroup ?? s.behaviorHints?.group) === group && s.addonBase === stream?.addonBase && isPlayable(s)) : -1
    if (index < 0) index = nextPlayable(streams, 0)
    if (index < 0) { say('No playable source for the next episode.'); return }
    const nextSession = { meta, video: next, videoId: id, streams, index }
    setSession(nextSession)
    setLocal(nextSession)
  }

  if (!session || !stream) return <Center>Nothing is playing. <a class="btn" href="#/">Home</a></Center>

  const v = video.current
  const upNext = session.video && nextEpisode(session.meta, session.video)
  const nearEnd = isFinite(duration) && duration - time < 60
  const title = session.video ? `${session.meta.name} · S${session.video.season} · E${session.video.episode}  ${session.video.title ?? session.video.name ?? ''}` : session.meta.name

  return (
    <div class="player" ref={root} data-nav-scope="player" onMouseMove={wake} onClick={wake}>
      <video ref={video} playsInline
        onPlay={() => setPlaying(true)} onPause={() => { setPlaying(false); setShown(true); save() }}
        onTimeUpdate={(e) => setTime((e.target as HTMLVideoElement).currentTime)}
        onDurationChange={(e) => setDuration((e.target as HTMLVideoElement).duration)}
        onEnded={() => { save(); if (settings.get().autoplayNext && upNext) playNext() }}
        onError={() => fallback('This source failed to play.')} />
      {toast && <div class="toast">{toast}</div>}
      <div class={`controls${shown || menu ? '' : ' hidden'}`}>
        <div class="title">{title}</div>
        <div class="muted" style={{ fontSize: '0.85rem' }}>[{stream.addonName}] {stream.name}</div>
        {/* Click or drag to seek; with the remote, focus it and press left/right. */}
        <div class="seek" tabIndex={0} role="slider" aria-label="Seek" aria-valuemin={0}
          aria-valuemax={isFinite(duration) ? Math.round(duration) : 0} aria-valuenow={Math.round(time)}
          onPointerDown={(e) => {
            const bar = e.currentTarget as HTMLDivElement
            const seekTo = (x: number) => {
              const r = bar.getBoundingClientRect()
              if (v && isFinite(v.duration)) v.currentTime = Math.min(1, Math.max(0, (x - r.left) / r.width)) * v.duration
            }
            seekTo(e.clientX)
            bar.setPointerCapture(e.pointerId)
            const move = (m: PointerEvent) => seekTo(m.clientX)
            const up = () => { bar.removeEventListener('pointermove', move); bar.removeEventListener('pointerup', up) }
            bar.addEventListener('pointermove', move)
            bar.addEventListener('pointerup', up)
          }}
          onKeyDown={(e) => {
            if (!v) return
            const step = e.keyCode === 37 ? -10 : e.keyCode === 39 ? 10 : 0
            if (!step) return
            // Handled here so spatial navigation doesn't move focus off the bar.
            e.preventDefault()
            e.stopPropagation()
            wake()
            v.currentTime = Math.max(0, v.currentTime + step)
          }}>
          <i style={{ width: `${isFinite(duration) && duration ? (time / duration) * 100 : 0}%` }} />
        </div>
        <div class="buttons">
          <button class="btn" onClick={() => (v?.paused ? v.play() : v?.pause())}><Icon name={playing ? 'pause' : 'play'} /></button>
          <button class="btn" onClick={() => { if (v) v.currentTime -= 10 }}><Icon name="back10" /></button>
          <button class="btn" onClick={() => { if (v) v.currentTime += 10 }}><Icon name="fwd10" /></button>
          <button class="btn" onClick={() => setMenu(menu === 'subs' ? undefined : 'subs')}><Icon name="subtitles" /> {activeSub >= 0 ? (subs[activeSub]?.lang ?? 'On') : 'Off'}</button>
          <button class="btn" onClick={() => setMenu(menu === 'sources' ? undefined : 'sources')}><Icon name="list" /> Sources</button>
          {upNext && (nearEnd || !playing) && <button class="btn primary" onClick={playNext}><Icon name="next" /> Next episode</button>}
          <button class="btn" onClick={() => toggleFullscreen(root.current)} aria-label="Full screen"><Icon name="fullscreen" /></button>
          <button class="btn" onClick={() => history.back()} aria-label="Close"><Icon name="close" /></button>
          <span class="time">{clock(time)} / {clock(duration)}</span>
        </div>
      </div>
      {menu && (
        <div class="panel" data-nav-scope="player-menu">
          <div class="list">
            {menu === 'subs' && [
              <button key="off" class="item" onClick={() => { setActiveSub(-1); setMenu(undefined) }}>Off</button>,
              ...subs.map((s, i) => (
                <button key={i} class="item" onClick={() => { setActiveSub(i); setMenu(undefined) }}>
                  <div class="main"><div>{s.lang ?? 'Unknown'}{i === activeSub ? ' ✓' : ''}</div><div class="sub">{s.name}</div></div>
                </button>
              )),
            ]}
            {menu === 'sources' && session.streams.map((s, i) => (
              <button key={i} class="item" disabled={!isPlayable(s)} onClick={() => { switchTo(i); setMenu(undefined) }}>
                <div class="main"><div>{s.description ?? s.title ?? s.name}{i === session.index ? ' ✓' : ''}</div><div class="sub">[{s.addonName}] {s.name}</div></div>
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}

