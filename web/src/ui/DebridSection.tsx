import { useState } from 'preact/hooks'
import { debridKey, PROVIDERS, type Provider } from '../core/debrid'
import { useStored } from './hooks'

/** Settings section: the debrid key that Torrentio installs pick up. */
export function DebridSection() {
  const current = useStored(debridKey)
  const [provider, setProvider] = useState<Provider>(current?.provider ?? 'realdebrid')
  const [key, setKey] = useState('')

  return (
    <div>
      <h2>Debrid</h2>
      <p class="muted" style={{ maxWidth: '45rem' }}>
        Installing Torrentio fills this key into its address, so you don’t paste it into each addon. The key stays in this browser.
      </p>
      {current ? (
        <div class="hstack">
          <span>{PROVIDERS[current.provider]} key saved.</span>
          <button class="btn small" onClick={() => debridKey.set(null)}>Remove</button>
        </div>
      ) : (
        <form class="hstack" onSubmit={(e) => { e.preventDefault(); if (key.trim()) { debridKey.set({ provider, apiKey: key.trim() }); setKey('') } }}>
          <select class="field" style={{ maxWidth: '14rem' }} value={provider} onChange={(e) => setProvider((e.target as HTMLSelectElement).value as Provider)}>
            {(Object.keys(PROVIDERS) as Provider[]).map((p) => <option key={p} value={p}>{PROVIDERS[p]}</option>)}
          </select>
          <input class="field" style={{ maxWidth: '22rem' }} type="password" placeholder="API key" value={key}
            onInput={(e) => setKey((e.target as HTMLInputElement).value)} />
          <button class="btn" type="submit" disabled={!key.trim()}>Save</button>
        </form>
      )}
    </div>
  )
}
