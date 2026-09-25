import { useState } from 'preact/hooks'
import { addonStore, supports } from '../core/addons'
import { Icon } from './components'
import { useStored } from './hooks'

export function Addons() {
  const addons = useStored(addonStore.addons)
  const [url, setUrl] = useState('')
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<{ text: string; error?: boolean }>()

  const install = async () => {
    if (!url.trim() || busy) return
    setBusy(true)
    try {
      const addon = await addonStore.install(url)
      setMessage({ text: `Installed ${addon.manifest.name}.` })
      setUrl('')
    } catch (e) {
      setMessage({ text: `Couldn’t install: ${e instanceof Error ? e.message : e}`, error: true })
    }
    setBusy(false)
  }

  return (
    <div>
      <h1>Addons</h1>
      <form onSubmit={(e) => { e.preventDefault(); install() }} class="hstack">
        <input class="field" placeholder="Addon URL (…/manifest.json or stremio://…)" value={url}
          onInput={(e) => setUrl((e.target as HTMLInputElement).value)} />
        <button class="btn primary" type="submit" disabled={busy}>{busy ? 'Installing…' : 'Install'}</button>
      </form>
      {message && <p class={message.error ? 'error' : 'muted'}>{message.text}</p>}
      <h2>Installed · the order here is the order sources rank in</h2>
      <div class="list">
        {addons.map((a, i) => (
          <div key={a.transportUrl} class="item" style={{ opacity: a.enabled ? 1 : 0.5 }}>
            {a.manifest.logo ? <img src={a.manifest.logo} alt="" style={{ width: '3rem', height: '3rem', objectFit: 'contain' }} /> : <div style={{ width: '3rem' }} />}
            <div class="main">
              <div>{a.manifest.name} <span class="muted">{a.manifest.version}</span></div>
              <div class="sub">{['catalog', 'meta', 'stream', 'subtitles'].filter((r) => supports(a, r)).join(' · ')}</div>
            </div>
            <button class="btn small" disabled={i === 0} onClick={() => addonStore.move(a.transportUrl, -1)}><Icon name="up" /></button>
            <button class="btn small" disabled={i === addons.length - 1} onClick={() => addonStore.move(a.transportUrl, 1)}><Icon name="down" /></button>
            <button class="btn small" onClick={() => addonStore.toggle(a.transportUrl)}>{a.enabled ? 'Disable' : 'Enable'}</button>
            <button class="btn small" onClick={() => addonStore.remove(a.transportUrl)}>Remove</button>
          </div>
        ))}
      </div>
    </div>
  )
}
