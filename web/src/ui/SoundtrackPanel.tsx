import { useEffect, useRef } from 'preact/hooks'
import { loadSoundtrack } from '../core/soundtrack'
import { Icon } from './components'
import { useAsync } from './hooks'
import { focusFirst, onBack } from './spatial'

/** The songs of a movie, a whole series (grouped by episode) or one episode, in order of appearance. */
export function SoundtrackPanel({ type, imdbId, season, episode, onClose }: {
  type: string; imdbId: string; season?: number; episode?: number; onClose: () => void
}) {
  const { value, loading } = useAsync(() => loadSoundtrack(type, imdbId, season, episode), [type, imdbId, season, episode])
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => onBack(() => { onClose(); return true }), [onClose])
  useEffect(() => { focusFirst(ref.current) }, [loading])
  const grouped = !!value && (value.groups.length > 1 || value.groups[0]?.season != null)

  return (
    <div class="panel" data-nav-scope="soundtrack" ref={ref}>
      <div class="actions" style={{ marginTop: 0 }}>
        <button class="btn small" onClick={onClose}><Icon name="close" /> Close</button>
      </div>
      <h1 style={{ fontSize: '1.6rem' }}>{value?.title ? `${value.title} · Soundtrack` : 'Soundtrack'}</h1>
      {loading && <p class="muted">Loading…</p>}
      {!loading && !value && <p class="muted">No songs listed for this title.</p>}
      {value && value.groups.map((g, gi) => (
        <div key={gi}>
          {grouped && g.season != null && <h2 tabIndex={0}>S{g.season} · E{g.episode}  {g.title ?? ''}</h2>}
          <div class="list">
            {g.songs.map((s, i) => (
              // Rows are focusable so the remote can scroll the list.
              <div key={i} class="item" tabIndex={0}>
                <span class="muted" style={{ minWidth: '1.5rem' }}>{i + 1}</span>
                <div class="main"><div>{s.title}</div>{s.artist && <div class="sub">{s.artist}</div>}</div>
              </div>
            ))}
          </div>
        </div>
      ))}
      <p class="muted" style={{ fontSize: '0.75rem', marginTop: '1.5rem' }}>Song data from IMDb.</p>
    </div>
  )
}
