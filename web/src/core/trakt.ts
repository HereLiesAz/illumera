import { API_BASE } from './api'
import { Secret } from './secrets'

/**
 * Trakt: device-code sign-in (token exchange through the app's /api, which holds the
 * client secret) and scrobbling straight to api.trakt.tv, as Android's TraktScrobbleManager.
 */

const TRAKT = 'https://api.trakt.tv'
const REFRESH_MARGIN_MS = 24 * 60 * 60 * 1000
const PAUSE_THROTTLE_MS = 15_000

export interface Tokens { accessToken: string; refreshToken: string; expiresAt: number }
export interface DeviceCode { deviceCode: string; userCode: string; verificationUrl: string; interval: number; expiresAt: number }

export const traktTokens = new Secret<Tokens | null>('trakt', null)
let clientId: string | undefined
let lastPauseAt = 0

async function api<T>(path: string, init?: RequestInit): Promise<{ status: number; body: T }> {
  const res = await fetch(`${API_BASE}/api/trakt/${path}`, init)
  const body = await res.json().catch(() => ({})) as T
  return { status: res.status, body }
}

async function getClientId(): Promise<string> {
  if (clientId) return clientId
  const { status, body } = await api<{ clientId?: string; error?: string }>('config')
  if (status !== 200 || !body.clientId) throw new Error(body.error ?? 'Trakt isn’t available right now.')
  clientId = body.clientId
  return clientId
}

function saveTokens(t: { access_token: string; refresh_token: string; created_at: number; expires_in: number }): void {
  traktTokens.set({ accessToken: t.access_token, refreshToken: t.refresh_token, expiresAt: (t.created_at + t.expires_in) * 1000 })
}

export async function startDeviceAuth(): Promise<DeviceCode> {
  const { status, body } = await api<{ device_code: string; user_code: string; verification_url: string; interval: number; expires_in: number; error?: string }>(
    'device/code', { method: 'POST' })
  if (status !== 200) throw new Error(body.error ?? `Trakt returned ${status}.`)
  return { deviceCode: body.device_code, userCode: body.user_code, verificationUrl: body.verification_url, interval: body.interval, expiresAt: Date.now() + body.expires_in * 1000 }
}

/**
 * One poll of the device code. 'pending' until the user approves; Trakt's status codes:
 * 400 pending, 429 too fast, 404/409/410/418 invalid, used, expired, denied.
 * 'retry' is a transient failure (network error or a 5xx from Trakt or the Worker): the
 * code is still good, so the caller keeps polling rather than giving up.
 */
export async function pollDeviceAuth(code: DeviceCode): Promise<'done' | 'pending' | 'slow_down' | 'retry' | 'failed'> {
  if (Date.now() > code.expiresAt) return 'failed'
  let res: { status: number; body: { access_token: string; refresh_token: string; created_at: number; expires_in: number } }
  try {
    res = await api('device/token', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ code: code.deviceCode }) })
  } catch {
    return 'retry'
  }
  const { status, body } = res
  if (status === 200) { saveTokens(body); return 'done' }
  if (status === 400) return 'pending'
  if (status === 429) return 'slow_down'
  if (status >= 500) return 'retry'
  return 'failed'
}

export function signOutTrakt(): void { traktTokens.set(null) }

type TokenResponse = { access_token: string; refresh_token: string; created_at: number; expires_in: number }

/** The refresh in flight, shared so concurrent callers don't spend the (single-use) refresh token twice. */
let refreshing: Promise<string | null> | undefined

/**
 * Exchanges the refresh token. Only a 400/401 from the refresh itself (invalid_grant) clears
 * the tokens; a network error or 5xx keeps them, and the current access token is still
 * returned while it is valid.
 */
async function refresh(t: Tokens): Promise<string | null> {
  const stillValid = () => (t.expiresAt > Date.now() ? t.accessToken : null)
  let res: { status: number; body: TokenResponse }
  try {
    res = await api<TokenResponse>('refresh', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ refresh_token: t.refreshToken }) })
  } catch {
    return stillValid()
  }
  if (res.status === 200) {
    saveTokens(res.body)
    return traktTokens.get()?.accessToken ?? null
  }
  if (res.status === 400 || res.status === 401) { traktTokens.set(null); return null }
  return stillValid()
}

/** A valid access token, refreshed a day before it expires; null when signed out or refresh fails. */
export async function accessToken(): Promise<string | null> {
  const t = traktTokens.get()
  if (!t) return null
  if (t.expiresAt - Date.now() > REFRESH_MARGIN_MS) return t.accessToken
  refreshing ??= refresh(t).finally(() => { refreshing = undefined })
  return refreshing
}

export type ScrobbleAction = 'start' | 'pause' | 'stop'

/** The scrobble body for a movie ("tt1") or an episode ("tt1:2:3"); null for non-IMDb ids. */
export function scrobbleBody(type: string, videoId: string, progress: number): Record<string, unknown> | null {
  const pct = Math.max(0, Math.min(100, progress))
  const parts = videoId.split(':')
  if (type === 'series') {
    const [imdb, season, episode] = [parts.slice(0, -2).join(':'), parseInt(parts[parts.length - 2], 10), parseInt(parts[parts.length - 1], 10)]
    if (!/^tt\d+$/.test(imdb) || isNaN(season) || isNaN(episode)) return null
    return { show: { ids: { imdb } }, episode: { season, number: episode }, progress: pct }
  }
  if (!/^tt\d+$/.test(videoId)) return null
  return { movie: { ids: { imdb: videoId } }, progress: pct }
}

/** Tells Trakt about playback. Silent when signed out; pauses are throttled. */
export async function scrobble(action: ScrobbleAction, type: string, videoId: string, progress: number): Promise<void> {
  if (!traktTokens.get()) return
  if (action === 'pause') {
    if (Date.now() - lastPauseAt < PAUSE_THROTTLE_MS) return
    lastPauseAt = Date.now()
  }
  const body = scrobbleBody(type, videoId, progress)
  if (!body) return
  try {
    const [token, id] = await Promise.all([accessToken(), getClientId().catch(() => undefined)])
    if (!token || !id) return
    await fetch(`${TRAKT}/scrobble/${action}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}`, 'trakt-api-version': '2', 'trakt-api-key': id },
      body: JSON.stringify(body),
    })
  } catch { /* scrobbling is best effort; playback must never fail on it */ }
}
