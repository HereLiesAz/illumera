import { useState } from 'preact/hooks'
import { stremio } from '../core/stremio'
import { useStored } from './hooks'

/** Settings section: sign in to Stremio, move addons either way, sync, reset. */
export function StremioAccountSection() {
  const account = useStored(stremio.account)
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState<string>()
  const [message, setMessage] = useState<{ text: string; error?: boolean }>()
  const [confirmReset, setConfirmReset] = useState(false)

  const run = async (label: string, action: () => Promise<string>) => {
    if (busy) return
    setBusy(label)
    setMessage(undefined)
    try {
      setMessage({ text: await action() })
    } catch (e) {
      setMessage({ text: e instanceof Error ? e.message : String(e), error: true })
    }
    setBusy(undefined)
  }

  const reset = (
    <div class="hstack" style={{ marginTop: '0.8rem' }}>
      {confirmReset ? (
        <>
          <span class="muted" style={{ maxWidth: '36rem' }}>
            Signs out, returns addons to the defaults and forgets the sync history. Your Stremio account isn’t changed, and local progress is kept.
          </span>
          <button class="btn small" onClick={() => { setConfirmReset(false); run('Resetting…', async () => { await stremio.resetSync(); return 'Stremio sync reset. Sign in to set it up again.' }) }}>Reset</button>
          <button class="btn small" onClick={() => setConfirmReset(false)}>Cancel</button>
        </>
      ) : (
        <button class="btn small" onClick={() => setConfirmReset(true)}>Reset Stremio sync</button>
      )}
    </div>
  )

  return (
    <div>
      <h2>Stremio account</h2>
      {account ? (
        <div>
          <p>Signed in as {account.email}</p>
          <div class="actions">
            <button class="btn" disabled={!!busy} onClick={() => run('Syncing…', async () => { await stremio.syncLibrary(true); return 'Continue Watching synced.' })}>Sync Continue Watching</button>
            <button class="btn" disabled={!!busy} onClick={() => run('Importing…', async () => `Imported ${await stremio.importAddons()} addons from your account.`)}>Get addons from account</button>
            <button class="btn" disabled={!!busy} onClick={() => run('Sending…', async () => `Sent ${await stremio.pushAddons()} addons to your account.`)}>Send addons to account</button>
            <button class="btn" disabled={!!busy} onClick={() => run('Signing out…', async () => { await stremio.signOut(); return 'Signed out of Stremio.' })}>Sign out</button>
          </div>
        </div>
      ) : (
        <form class="hstack" onSubmit={(e) => {
          e.preventDefault()
          run('Signing in…', async () => { await stremio.signIn(email, password); setPassword(''); return 'Signed in. Addons and Continue Watching are synced from your account.' })
        }}>
          <input class="field" style={{ maxWidth: '18rem' }} type="email" autoComplete="username" placeholder="Email" value={email}
            onInput={(e) => setEmail((e.target as HTMLInputElement).value)} />
          <input class="field" style={{ maxWidth: '14rem' }} type="password" autoComplete="current-password" placeholder="Password" value={password}
            onInput={(e) => setPassword((e.target as HTMLInputElement).value)} />
          <button class="btn primary" type="submit" disabled={!!busy || !email || !password}>Sign in</button>
        </form>
      )}
      {reset}
      {busy && <p class="muted">{busy}</p>}
      {message && <p class={message.error ? 'error' : 'muted'}>{message.text}</p>}
    </div>
  )
}
