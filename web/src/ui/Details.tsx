import { useEffect, useMemo, useRef, useState } from 'preact/hooks'
import { addonStore } from '../core/addons'
import { library } from '../core/library'
import { parseStream } from '../core/parser'
import { sortStreams } from '../core/sorting'
import { settings } from '../core/settings'
import type { Meta, MetaVideo, Stream } from '../core/types'
import { episodeId, isPlayable, setSession } from '../player/session'
import { Center, Icon } from './components'
import { useAsync } from './hooks'
import { navigate } from './router'
import { focusFirst, onBack } from './spatial'

function formatSize(bytes?: number): string {
  if (!bytes) return ''
  return bytes >= 1024 ** 3 ? `${(bytes / 1024 ** 3).toFixed(1)} GB` : `${Math.round(bytes / 1024 ** 2)} MB`
}

/** What happens when a source is picked, by kind. */
export function openStream(meta: Meta, video: MetaVideo | undefined, streams: Stream[], index: number, resumeAt?: number): string | undefined {
  const stream = streams[index]
  if (isPlayable(stream)) {
    setSession({ meta, video, videoId: video ? episodeId(meta, video) : meta.id, streams, index, resumeAt })
    navigate({ name: 'player' })
    return undefined
  }
  if (stream.externalUrl) { window.open(stream.externalUrl, '_blank', 'noopener'); return undefined }
  if (stream.infoHash) return 'Torrent sources need a streaming server, which this version of the web app doesn’t support yet.'
  if (stream.ytId) return 'YouTube sources can’t be played here.'
  return 'This source can’t be played.'
}

function Sources({ meta, video, onClose }: { meta: Meta; video?: MetaVideo; onClose: () => void }) {
  const [raw, setRaw] = useState<Stream[]>([])
  const [loading, setLoading] = useState(true)
  const [message, setMessage] = useState<string>()
  const ref = useRef<HTMLDivElement>(null)
  const id = video ? episodeId(meta, video) : meta.id
  const type = video ? 'series' : meta.type

  useEffect(() => onBack(() => { onClose(); return true }), [onClose])
  useEffect(() => {
    let live = true
    addonStore.streams(type, id, (partial) => live && setRaw(partial)).then(() => live && setLoading(false))
    return () => { live = false }
  }, [type, id])

  const order = addonStore.enabled().map((a) => a.transportUrl)
  const streams = useMemo(() => sortStreams(raw, type, order, settings.sortPrefs()), [raw])
  const progress = library.get(meta.id)
  const resumeAt = progress && progress.videoId === id ? progress.time : undefined
  const focused = useRef(false)
  useEffect(() => { if (streams.length && !focused.current) { focused.current = true; focusFirst(ref.current?.querySelector('.list') ?? null) } }, [streams.length])

  return (
    <div class="panel" data-nav-scope="sources" ref={ref}>
      <div class="actions" style={{ marginTop: 0 }}>
        <button class="btn small" onClick={onClose}><Icon name="close" /> Close</button>
      </div>
      <h1 style={{ fontSize: '1.6rem' }}>{video ? `S${video.season} · E${video.episode}  ${video.title ?? video.name ?? ''}` : meta.name}</h1>
      {message && <p class="error">{message}</p>}
      <div class="list">
        {streams.map((s, i) => {
          const info = parseStream(s)
          return (
            <button key={i} class="item" onClick={() => setMessage(openStream(meta, video, streams, i, resumeAt))}>
              <div class="main">
                <div>{s.description ?? s.title ?? s.name}</div>
                <div class="sub">[{s.addonName}] {s.name}</div>
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: '0.3rem', alignItems: 'flex-end' }}>
                {info.quality !== 'unknown' && <span class="tag">{info.quality}</span>}
                {info.sizeBytes && <span class="tag">{formatSize(info.sizeBytes)}</span>}
              </div>
            </button>
          )
        })}
      </div>
      {loading && <p class="muted">Asking addons… {raw.length} sources so far.</p>}
      {!loading && !streams.length && <p class="muted">{raw.length ? `${raw.length} sources, all hidden by your source filters.` : 'No sources found.'}</p>}
    </div>
  )
}

export function Details({ type, id, addon }: { type: string; id: string; addon?: string }) {
  const { value: meta, loading } = useAsync(() => addonStore.meta(type, id, addon), [type, id, addon])
  const [season, setSeason] = useState<number | undefined>()
  const [picked, setPicked] = useState<{ video?: MetaVideo } | undefined>()
  const root = useRef<HTMLDivElement>(null)

  useEffect(() => { if (meta) focusFirst(root.current) }, [meta])

  if (loading) return <Center>Loading…</Center>
  if (!meta) return <Center>Couldn’t load this title.</Center>

  const videos = (meta.videos ?? []).filter((v) => v.season !== undefined)
  const isSeries = videos.length > 0
  const seasons = Array.from(new Set(videos.map((v) => v.season!))).sort((a, b) => (a === 0 ? 1 : b === 0 ? -1 : a - b))
  const current = season ?? seasons[0]
  const progress = library.get(meta.id)
  const resumeVideo = progress && isSeries ? videos.find((v) => episodeId(meta, v) === progress.videoId) : undefined

  return (
    <div ref={root}>
      {meta.background && <div class="backdrop" style={{ backgroundImage: `url("${meta.background}")` }} />}
      <div class="details">
        {meta.logo ? <img class="title-logo" src={meta.logo} alt={meta.name} /> : <h1>{meta.name}</h1>}
        <div class="facts">
          {[meta.releaseInfo, meta.runtime, meta.imdbRating && `IMDb ${meta.imdbRating}`, (meta.genres ?? []).slice(0, 3).join(', ')]
            .filter(Boolean).map((f) => <span key={String(f)}>{f}</span>)}
        </div>
        {meta.description && <p>{meta.description}</p>}
        <div class="actions">
          {!isSeries && <button class="btn primary" onClick={() => setPicked({})}><Icon name="play" /> {progress ? 'Resume' : 'Play'}</button>}
          {resumeVideo && <button class="btn primary" onClick={() => setPicked({ video: resumeVideo })}><Icon name="play" /> Resume S{resumeVideo.season} · E{resumeVideo.episode}</button>}
        </div>
        {isSeries && (
          <div>
            <div class="row">
              {seasons.map((s) => (
                <button key={s} class={`btn small${s === current ? ' primary' : ''}`} onClick={() => setSeason(s)}>
                  {s === 0 ? 'Specials' : `Season ${s}`}
                </button>
              ))}
            </div>
            <div class="list">
              {videos.filter((v) => v.season === current).sort((a, b) => (a.episode ?? 0) - (b.episode ?? 0)).map((v) => (
                <button key={v.id} class="item" onClick={() => setPicked({ video: v })}>
                  {v.thumbnail ? <img class="thumb" src={v.thumbnail} alt="" loading="lazy" /> : <div class="thumb" />}
                  <div class="main">
                    <div>{v.episode}. {v.title ?? v.name}</div>
                    <div class="sub">{[v.released?.slice(0, 10), v.overview].filter(Boolean).join('\n')}</div>
                  </div>
                </button>
              ))}
            </div>
          </div>
        )}
      </div>
      {picked && <Sources meta={meta} video={picked.video} onClose={() => { setPicked(undefined); setTimeout(() => focusFirst(root.current), 0) }} />}
    </div>
  )
}
