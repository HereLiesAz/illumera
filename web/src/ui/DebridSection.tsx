import { useState } from 'preact/hooks'
import { addDebridKey, debridKey, debridKeys, PROVIDERS, removeDebridKey, setPrimaryDebrid, type Provider } from '../core/debrid'
import { useStored } from './hooks'

/** Settings section: debrid keys, several per profile; the primary's is what Torrentio installs pick up. */
export function DebridSection() {
  const keys = debridKeys(useStored(debridKey))
  const available = (Object.keys(PROVIDERS) as Provider[]).filter((p) => !keys.some((k) => k.provider === p))
  const [chosen, setProvider] = useState<Provider>(available[0] ?? 'realdebrid')
  const provider = available.indexOf(chosen) >= 0 ? chosen : available[0]
  const [key, setKey] = useState('')

  return (
    <div>
      <h2>Debrid</h2>
      <p class="muted" style={{ maxWidth: '45rem' }}>
        Installing Torrentio fills the primary key into its address, so you don’t paste it into each addon. Keys stay in this browser.
      </p>
      {keys.map((k, i) => (
        <div class="hstack" key={k.provider}>
          <span>{PROVIDERS[k.provider]} key saved{i === 0 ? ' · primary' : ''}.</span>
          {i > 0 && <button class="btn small" onClick={() => setPrimaryDebrid(k.provider)}>Make primary</button>}
          <button class="btn small" onClick={() => removeDebridKey(k.provider)}>Remove</button>
        </div>
      ))}
      {provider && (
        <form class="hstack" onSubmit={(e) => { e.preventDefault(); if (key.trim()) { addDebridKey({ provider, apiKey: key.trim() }); setKey('') } }}>
          <select class="field" style={{ maxWidth: '14rem' }} value={provider} onChange={(e) => setProvider((e.target as HTMLSelectElement).value as Provider)}>
            {available.map((p) => <option key={p} value={p}>{PROVIDERS[p]}</option>)}
          </select>
          <input class="field" style={{ maxWidth: '22rem' }} type="password" placeholder="API key" value={key}
            onInput={(e) => setKey((e.target as HTMLInputElement).value)} />
          <button class="btn" type="submit" disabled={!key.trim()}>{keys.length ? 'Add' : 'Save'}</button>
        </form>
      )}
    </div>
  )
}
