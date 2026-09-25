import { useEffect, useRef, useState } from 'preact/hooks'
import { pollDeviceAuth, signOutTrakt, startDeviceAuth, traktTokens, type DeviceCode } from '../core/trakt'
import { useStored } from './hooks'

/** Settings section: Trakt sign-in by device code, which also works from a TV. */
export function TraktSection() {
  const tokens = useStored(traktTokens)
  const [code, setCode] = useState<DeviceCode>()
  const [message, setMessage] = useState<{ text: string; error?: boolean }>()
  const timer = useRef<number>()

  useEffect(() => () => clearTimeout(timer.current), [])

  const poll = (c: DeviceCode, interval: number) => {
    timer.current = window.setTimeout(async () => {
      const result = await pollDeviceAuth(c).catch(() => 'pending' as const)
      if (result === 'done') { setCode(undefined); setMessage({ text: 'Connected to Trakt. What you watch is scrobbled.' }); return }
      if (result === 'failed') { setCode(undefined); setMessage({ text: 'The code expired or was declined. Try again.', error: true }); return }
      poll(c, result === 'slow_down' ? interval + 1 : interval)
    }, interval * 1000)
  }

  const connect = async () => {
    setMessage(undefined)
    try {
      const c = await startDeviceAuth()
      setCode(c)
      poll(c, c.interval)
    } catch (e) {
      setMessage({ text: e instanceof Error ? e.message : String(e), error: true })
    }
  }

  return (
    <div>
      <h2>Trakt</h2>
      {tokens ? (
        <div class="hstack">
          <span>Connected. Playback is scrobbled to Trakt.</span>
          <button class="btn small" onClick={() => { signOutTrakt(); setMessage(undefined) }}>Disconnect</button>
        </div>
      ) : code ? (
        <div class="hstack">
          <span>Go to <strong>{code.verificationUrl}</strong> and enter <strong style={{ letterSpacing: '0.15em' }}>{code.userCode}</strong></span>
          <button class="btn small" onClick={() => { clearTimeout(timer.current); setCode(undefined) }}>Cancel</button>
        </div>
      ) : (
        <button class="btn" onClick={connect}>Connect Trakt</button>
      )}
      {message && <p class={message.error ? 'error' : 'muted'}>{message.text}</p>}
    </div>
  )
}
