import { addonStore, transportUrl } from './addons'
import { library, type Progress } from './library'
import { Secret } from './secrets'
import { Stored } from './storage'
import type { Manifest } from './types'

/**
 * Stremio account: sign-in, addon collection and Continue Watching sync. Mirrors
 * Android's StremioAuthService and StremioLibrarySyncManager (same API, same merge rules).
 */

const API = 'https://api.strem.io/api'
const LIBRARY = 'libraryItem'
const MIN_SYNC_INTERVAL_MS = 60_000

export interface Account { authKey: string; email: string }

export interface RemoteAddon { transportUrl: string; manifest: Manifest; flags?: { official?: boolean; protected?: boolean } }

/** Stremio's library item as sent over the wire (times in ms, dates ISO). */
export interface LibraryItem {
  _id: string
  name: string
  type: string
  poster?: string
  removed: boolean
  temp: boolean
  _ctime?: string
  _mtime: string
  state: {
    lastWatched?: string
    timeOffset: number
    duration: number
    overallTimeWatched: number
    timesWatched: number
    flaggedWatched: number
    video_id?: string
  }
}

export class StremioError extends Error {}

async function call<T>(method: string, body: Record<string, unknown>): Promise<T> {
  let res: Response
  try {
    res = await fetch(`${API}/${method}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
  } catch {
    throw new StremioError('Couldn’t reach Stremio. Check your connection.')
  }
  if (!res.ok) throw new StremioError(`Stremio returned ${res.status}.`)
  const json = await res.json() as { result?: T; error?: { message?: string } }
  if (json.error || json.result === undefined || json.result === null) {
    throw new StremioError(json.error?.message || 'Stremio rejected the request.')
  }
  return json.result
}

/** A progress entry as Stremio's library item. */
export function toLibraryItem(p: Progress): LibraryItem {
  const at = new Date(p.updatedAt).toISOString()
  const watched = p.duration > 0 && p.time / p.duration >= 0.92 ? 1 : 0
  return {
    _id: p.id, name: p.name, type: p.type, poster: p.poster, removed: false, temp: false, _mtime: at,
    state: {
      lastWatched: at,
      timeOffset: Math.round(p.time * 1000),
      duration: Math.round(p.duration * 1000),
      overallTimeWatched: Math.round(p.time * 1000),
      timesWatched: watched,
      flaggedWatched: watched,
      video_id: p.type === 'series' ? p.videoId : undefined,
    },
  }
}

/** Stremio's library item as a progress entry; null when it can't be resumed. */
export function fromLibraryItem(item: LibraryItem): Progress | null {
  const updatedAt = Date.parse(item._mtime)
  if (isNaN(updatedAt)) return null
  const videoId = item.type === 'series' ? item.state.video_id : item._id
  if (!videoId) return null
  const parts = videoId.split(':')
  const season = item.type === 'series' ? parseInt(parts[parts.length - 2], 10) : NaN
  const episode = item.type === 'series' ? parseInt(parts[parts.length - 1], 10) : NaN
  return {
    type: item.type, id: item._id, name: item.name, poster: item.poster, videoId,
    season: isNaN(season) ? undefined : season, episode: isNaN(episode) ? undefined : episode,
    time: item.state.timeOffset / 1000,
    duration: Math.max(item.state.duration, item.state.timeOffset) / 1000,
    updatedAt,
  }
}

export interface SyncPlan { push: LibraryItem[]; pull: string[] }

/**
 * What to send and fetch, as on Android: newer side wins per title; a title in the last
 * synced baseline that is now gone locally is a deletion, sent as a tombstone.
 */
export function planLibrarySync(
  local: Record<string, Progress>,
  remoteMtimes: Record<string, number>,
  baseline: Record<string, LibraryItem>,
  now = Date.now(),
): SyncPlan {
  const push: LibraryItem[] = []
  const pull: string[] = []
  const deleted = new Set(Object.keys(baseline).filter((id) => !local[id] && remoteMtimes[id] !== undefined))
  const deletedAt = new Date(now).toISOString()
  deleted.forEach((id) => push.push({ ...baseline[id], removed: true, _mtime: deletedAt, state: { ...baseline[id].state, lastWatched: deletedAt } }))
  for (const id of Object.keys(local)) {
    const remote = remoteMtimes[id]
    if (remote === undefined || local[id].updatedAt > remote) push.push(toLibraryItem(local[id]))
    else if (remote > local[id].updatedAt) pull.push(id)
  }
  for (const id of Object.keys(remoteMtimes)) if (!local[id] && !deleted.has(id)) pull.push(id)
  return { push, pull }
}

export class StremioAccount {
  readonly account = new Secret<Account | null>('stremio-account', null)
  /** The library as last synced; the baseline for spotting local deletions. */
  readonly baseline = new Stored<Record<string, LibraryItem>>('stremio-library-baseline', {})
  private lastSyncAt = 0
  private syncing: Promise<void> | null = null

  get(): Account | null { return this.account.get() }

  async signIn(email: string, password: string): Promise<void> {
    const result = await call<{ authKey: string }>('login', { type: 'Login', email: email.trim(), password, facebook: false })
    this.account.set({ authKey: result.authKey, email: email.trim() })
    // Start from the account's state: its addons, then a merge of Continue Watching.
    await this.importAddons()
    await this.syncLibrary(true)
  }

  async signOut(): Promise<void> {
    const account = this.get()
    this.account.set(null)
    if (account) await call('logout', { type: 'Logout', authKey: account.authKey }).catch(() => undefined)
  }

  /**
   * Undo sync so it can be set up from scratch: sign out, forget the baseline (so the next
   * sign-in merges instead of sending deletions) and return addons to the defaults.
   * Local progress and the account itself are left alone.
   */
  async resetSync(): Promise<void> {
    await this.signOut()
    this.baseline.set({})
    this.lastSyncAt = 0
    addonStore.addons.set([])
    await addonStore.ensureDefaults()
  }

  private authKey(): string {
    const account = this.get()
    if (!account) throw new StremioError('Sign in to Stremio first.')
    return account.authKey
  }

  /** Replaces local addons with the account's collection, in its order. */
  async importAddons(): Promise<number> {
    const { addons } = await call<{ addons: RemoteAddon[] }>('addonCollectionGet', { type: 'AddonCollectionGet', authKey: this.authKey(), update: true })
    const enabled = new Map(addonStore.list().map((a) => [a.transportUrl, a.enabled]))
    const list = (addons ?? [])
      .filter((a) => a.transportUrl && a.manifest && a.manifest.id && a.manifest.name)
      .map((a) => { const base = transportUrl(a.transportUrl); return { transportUrl: base, manifest: a.manifest, enabled: enabled.get(base) ?? true } })
    if (list.length) addonStore.addons.set(list)
    return list.length
  }

  /** Replaces the account's collection with the local addons, in local order. */
  async pushAddons(): Promise<number> {
    const addons = addonStore.list().map((a) => ({ transportUrl: `${a.transportUrl}/manifest.json`, manifest: a.manifest, flags: { official: false, protected: false } }))
    await call('addonCollectionSet', { type: 'AddonCollectionSet', authKey: this.authKey(), addons })
    return addons.length
  }

  /** Two-way Continue Watching sync; throttled to once a minute unless [force]. */
  syncLibrary(force = false): Promise<void> {
    if (!this.get()) return Promise.resolve()
    if (this.syncing) return this.syncing
    if (!force && Date.now() - this.lastSyncAt < MIN_SYNC_INTERVAL_MS) return Promise.resolve()
    this.lastSyncAt = Date.now()
    this.syncing = this.runSync().finally(() => { this.syncing = null })
    return this.syncing
  }

  private async runSync(): Promise<void> {
    const authKey = this.authKey()
    const meta = await call<Array<[string, number]>>('datastoreMeta', { authKey, collection: LIBRARY })
    const remoteMtimes: Record<string, number> = {}
    for (const [id, mtime] of meta ?? []) remoteMtimes[id] = mtime
    const plan = planLibrarySync(library.progress.get(), remoteMtimes, this.baseline.get())
    if (plan.push.length) await call('datastorePut', { authKey, collection: LIBRARY, changes: plan.push })
    const pulled = plan.pull.length
      ? await call<LibraryItem[]>('datastoreGet', { authKey, collection: LIBRARY, ids: plan.pull, all: false })
      : []
    const all = { ...library.progress.get() }
    for (const item of pulled ?? []) {
      if (item.removed) { delete all[item._id]; continue }
      const p = fromLibraryItem(item)
      if (p && (!all[p.id] || all[p.id].updatedAt < p.updatedAt)) all[p.id] = p
    }
    library.progress.set(all)
    const baseline: Record<string, LibraryItem> = {}
    for (const id of Object.keys(all)) baseline[id] = toLibraryItem(all[id])
    this.baseline.set(baseline)
  }
}

export const stremio = new StremioAccount()
