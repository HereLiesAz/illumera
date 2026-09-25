import { useState } from 'preact/hooks'
import { DEFAULT_SERVER, probeServer, serverOnline, serverUrl } from '../core/streamingServer'
import { useStored } from './hooks'

/** Settings section: where the streaming server is, and whether it answers. */
export function StreamingServerSection() {
  const url = useStored(serverUrl)
  const online = useStored(serverOnline)
  const [draft, setDraft] = useState(url)
  const [checking, setChecking] = useState(false)

  const check = async (value: string) => {
    serverUrl.set(value.trim() || DEFAULT_SERVER)
    setChecking(true)
    await probeServer()
    setChecking(false)
  }

  return (
    <div>
      <h2>Torrents</h2>
      <p class="muted" style={{ maxWidth: '45rem' }}>
        Browsers can’t download torrents. Stremio Service (or the Stremio desktop app) runs a streaming server that can;
        with it running on this computer, torrent sources play here.
      </p>
      <form class="hstack" onSubmit={(e) => { e.preventDefault(); check(draft) }}>
        <input class="field" style={{ maxWidth: '22rem' }} value={draft} placeholder={DEFAULT_SERVER}
          onInput={(e) => setDraft((e.target as HTMLInputElement).value)} />
        <button class="btn" type="submit" disabled={checking}>{checking ? 'Checking…' : 'Check'}</button>
        <span class={online ? 'muted' : 'error'}>
          {online === null ? '' : online ? 'Streaming server found.' : 'No streaming server answered.'}
        </span>
      </form>
    </div>
  )
}
