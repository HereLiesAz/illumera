/**
 * Small typed wrapper over localStorage. Storage can be missing or throw (private mode,
 * some TV browsers), so every read falls back and every write is best-effort.
 *
 * Values belong to the active profile unless created as global. The default profile keeps
 * the original un-prefixed keys, so data from before profiles existed stays with it.
 */

const ROOT = 'illumera:'
const ACTIVE_PROFILE_KEY = ROOT + 'active-profile'
export const DEFAULT_PROFILE = 'default'

function rawGet(key: string): string | null {
  try { return localStorage.getItem(key) } catch { return null }
}

export function activeProfileId(): string {
  return rawGet(ACTIVE_PROFILE_KEY) || DEFAULT_PROFILE
}

export function setActiveProfileId(id: string): void {
  try { localStorage.setItem(ACTIVE_PROFILE_KEY, id) } catch { /* unavailable */ }
}

/** Storage key prefix for a profile's values. */
export function profilePrefix(id: string): string {
  return id === DEFAULT_PROFILE ? ROOT : `${ROOT}p:${id}:`
}

/** Deletes everything stored for a profile (never the default profile's globals). */
export function clearProfile(id: string): void {
  if (id === DEFAULT_PROFILE) return
  const prefix = profilePrefix(id)
  try {
    const doomed: string[] = []
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i)
      if (k && k.indexOf(prefix) === 0) doomed.push(k)
    }
    doomed.forEach((k) => localStorage.removeItem(k))
  } catch { /* unavailable */ }
}

function fullKey(key: string, global: boolean): string {
  return global ? `${ROOT}global:${key}` : profilePrefix(activeProfileId()) + key
}

export function load<T>(key: string, fallback: T, global = false): T {
  const raw = rawGet(fullKey(key, global))
  if (raw == null) return fallback
  try { return JSON.parse(raw) as T } catch { return fallback }
}

export function save<T>(key: string, value: T, global = false): void {
  try {
    localStorage.setItem(fullKey(key, global), JSON.stringify(value))
  } catch {
    /* storage full or unavailable */
  }
}

/**
 * A value kept in storage, with change listeners for the UI. Profile values are read once,
 * at startup; switching profiles reloads the app.
 */
export class Stored<T> {
  private value: T
  private listeners = new Set<(value: T) => void>()

  constructor(private key: string, fallback: T, private global = false) {
    this.value = load(key, fallback, global)
  }

  get(): T { return this.value }

  set(value: T): void {
    this.value = value
    save(this.key, value, this.global)
    this.listeners.forEach((l) => l(value))
  }

  subscribe(listener: (value: T) => void): () => void {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }
}
