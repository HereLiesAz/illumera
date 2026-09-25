/**
 * Small typed wrapper over localStorage. Storage can be missing or throw (private mode,
 * some TV browsers), so every read falls back and every write is best-effort.
 */

const PREFIX = 'illumera:'

export function load<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(PREFIX + key)
    return raw == null ? fallback : (JSON.parse(raw) as T)
  } catch {
    return fallback
  }
}

export function save<T>(key: string, value: T): void {
  try {
    localStorage.setItem(PREFIX + key, JSON.stringify(value))
  } catch {
    /* storage full or unavailable */
  }
}

/** A value kept in storage, with change listeners for the UI. */
export class Stored<T> {
  private value: T
  private listeners = new Set<(value: T) => void>()

  constructor(private key: string, fallback: T) {
    this.value = load(key, fallback)
  }

  get(): T { return this.value }

  set(value: T): void {
    this.value = value
    save(this.key, value)
    this.listeners.forEach((l) => l(value))
  }

  subscribe(listener: (value: T) => void): () => void {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }
}
