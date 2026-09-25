import { settings } from '../core/settings'
import type { Quality } from '../core/parser'
import type { SortKey } from '../core/sorting'
import { LANGUAGE_ALIASES } from '../core/sorting'
import { useStored } from './hooks'

const QUALITIES: Quality[] = ['4k', '1080p', '720p', 'sd', 'cam', 'unknown']

function Toggle({ label, on, onChange }: { label: string; on: boolean; onChange: (on: boolean) => void }) {
  return <button class={`btn small${on ? ' primary' : ''}`} onClick={() => onChange(!on)}>{label}</button>
}

function NumberField({ label, value, onChange }: { label: string; value: number; onChange: (n: number) => void }) {
  return (
    <label class="hstack" style={{ margin: '0.4rem 0' }}>
      <span style={{ minWidth: '14rem' }}>{label}</span>
      <input class="field" style={{ maxWidth: '8rem' }} type="number" min={0} value={value}
        onChange={(e) => onChange(Math.max(0, parseInt((e.target as HTMLInputElement).value, 10) || 0))} />
    </label>
  )
}

export function Settings() {
  useStored(settings.value)
  const s = settings.get()
  const sort = s.sort
  const setQuality = (q: Quality, on: boolean) =>
    settings.updateSort({ enabledQualities: on ? sort.enabledQualities.concat(q) : sort.enabledQualities.filter((x) => x !== q) })

  return (
    <div>
      <h1>Settings</h1>
      <h2>Playback</h2>
      <div class="actions">
        <Toggle label="Autoplay next episode" on={s.autoplayNext} onChange={(v) => settings.update({ autoplayNext: v })} />
        <Toggle label="Try the next source on failure" on={s.autoFallback} onChange={(v) => settings.update({ autoFallback: v })} />
      </div>
      <label class="hstack">
        <span style={{ minWidth: '14rem' }}>Subtitles on by default</span>
        <select class="field" style={{ maxWidth: '14rem' }} value={s.subtitleLanguage} onChange={(e) => settings.update({ subtitleLanguage: (e.target as HTMLSelectElement).value })}>
          <option value="">Off</option>
          {Object.keys(LANGUAGE_ALIASES).map((code) => <option key={code} value={code}>{LANGUAGE_ALIASES[code][0]}</option>)}
        </select>
      </label>

      <h2>Sources shown</h2>
      <div class="actions">
        {QUALITIES.map((q) => <Toggle key={q} label={q} on={sort.enabledQualities.indexOf(q) >= 0} onChange={(v) => setQuality(q, v)} />)}
      </div>
      <NumberField label="Largest file, GB (0 = any)" value={sort.maxSizeGb} onChange={(n) => settings.updateSort({ maxSizeGb: n })} />
      <label class="hstack" style={{ margin: '0.4rem 0' }}>
        <span style={{ minWidth: '14rem' }}>Hide sources containing</span>
        <input class="field" placeholder="comma-separated words" value={sort.excludePhrases.join(', ')}
          onChange={(e) => settings.updateSort({ excludePhrases: (e.target as HTMLInputElement).value.split(',').map((x) => x.trim()).filter(Boolean) })} />
      </label>

      <h2>Ranking</h2>
      <div class="actions">
        {(['quality', 'size', 'seeds'] as SortKey[]).map((k) => (
          <Toggle key={k} label={`Sort by ${k}`} on={sort.primarySort === k} onChange={() => settings.updateSort({ primarySort: k })} />
        ))}
      </div>
      <NumberField label="Preferred movie size, MB" value={sort.movieTargetSizeMb} onChange={(n) => settings.updateSort({ movieTargetSizeMb: n })} />
      <NumberField label="Preferred episode size, MB" value={sort.episodeTargetSizeMb} onChange={(n) => settings.updateSort({ episodeTargetSizeMb: n })} />
      <NumberField label="Minimum seeders" value={sort.minimumSeeds} onChange={(n) => settings.updateSort({ minimumSeeds: n })} />
    </div>
  )
}
