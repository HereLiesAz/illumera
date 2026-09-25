import { useState } from 'preact/hooks'
import { probeServer, server, serverUrl, STREMIO_DEFAULT, TORRSERVER_DEFAULT } from '../core/streamingServer'
import { useStored } from './hooks'

/** Settings section: where the streaming server is, and which one answered. */
export function StreamingServerSection() {
  const url = useStored(serverUrl)
  const found = useStored(server)
  const [draft, setDraft] = useState(url)
  const [checking, setChecking] = useState(false)

  const check = async (value: string) => {
    serverUrl.set(value.trim())
    setChecking(true)
    await probeServer()
    setChecking(false)
  }

  return (
    <div>
      <h2>Torrents</h2>
      <p class="muted" style={{ maxWidth: '45rem' }}>
        Browsers can’t download torrents; a streaming server on this computer can. The desktop app includes one (TorrServer).
        In a browser, run TorrServer or Stremio Service. Leave the address empty to find either on its usual port.
      </p>
      <form class="hstack" onSubmit={(e) => { e.preventDefault(); check(draft) }}>
        <input class="field" style={{ maxWidth: '22rem' }} value={draft} placeholder={`${TORRSERVER_DEFAULT} or ${STREMIO_DEFAULT}`}
          onInput={(e) => setDraft((e.target as HTMLInputElement).value)} />
        <button class="btn" type="submit" disabled={checking}>{checking ? 'Checking…' : 'Check'}</button>
        <span class={found ? 'muted' : 'error'}>
          {found === undefined ? '' : found ? `${found.kind === 'torrserver' ? 'TorrServer' : 'Stremio streaming server'} found at ${found.url}.` : 'No streaming server answered.'}
        </span>
      </form>
    </div>
  )
}
