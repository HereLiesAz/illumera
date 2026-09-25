import { activeProfileId, profilePrefix } from './storage'

/**
 * Credentials (Stremio auth key, Trakt tokens, debrid key) and the addon list, whose URLs
 * often embed keys, are never written to storage in clear text. They are encrypted with AES-GCM under a key that WebCrypto creates as
 * non-extractable and IndexedDB keeps as an opaque CryptoKey: page scripts can use it but
 * never read it, and copying localStorage alone yields nothing usable.
 *
 * Where WebCrypto or IndexedDB is missing, credentials live in memory for the session only.
 * `loadSecrets()` must finish before the app renders.
 */

const DB = 'illumera-keys'
const STORE = 'keys'
const KEY_ID = 'secrets-v1'
const PREFIX = 'secret:'

let cryptoKey: CryptoKey | null = null
const values = new Map<string, unknown>()
const listeners = new Map<string, Set<(v: unknown) => void>>()

function storageKey(name: string): string { return profilePrefix(activeProfileId()) + PREFIX + name }

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB, 1)
    req.onupgradeneeded = () => req.result.createObjectStore(STORE)
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error)
  })
}

function idb<T>(db: IDBDatabase, mode: IDBTransactionMode, run: (s: IDBObjectStore) => IDBRequest): Promise<T> {
  return new Promise((resolve, reject) => {
    const req = run(db.transaction(STORE, mode).objectStore(STORE))
    req.onsuccess = () => resolve(req.result as T)
    req.onerror = () => reject(req.error)
  })
}

async function getKey(): Promise<CryptoKey | null> {
  if (typeof indexedDB === 'undefined' || typeof crypto === 'undefined' || !crypto.subtle) return null
  try {
    const db = await openDb()
    const existing = await idb<CryptoKey | undefined>(db, 'readonly', (s) => s.get(KEY_ID))
    if (existing) return existing
    const key = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt'])
    await idb(db, 'readwrite', (s) => s.put(key, KEY_ID))
    return key
  } catch {
    return null
  }
}

function toBase64(bytes: Uint8Array): string {
  let s = ''
  bytes.forEach((b) => { s += String.fromCharCode(b) })
  return btoa(s)
}

function fromBase64(text: string): Uint8Array<ArrayBuffer> {
  const s = atob(text)
  const out = new Uint8Array(new ArrayBuffer(s.length))
  for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i)
  return out
}

async function encrypt(key: CryptoKey, value: unknown): Promise<string> {
  const iv = crypto.getRandomValues(new Uint8Array(12))
  const data = new TextEncoder().encode(JSON.stringify(value))
  const sealed = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, data))
  return `${toBase64(iv)}.${toBase64(sealed)}`
}

async function decrypt(key: CryptoKey, text: string): Promise<unknown> {
  const [iv, sealed] = text.split('.')
  const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: fromBase64(iv) }, key, fromBase64(sealed))
  return JSON.parse(new TextDecoder().decode(plain))
}

/** Decrypts this profile's saved credentials into memory. Call once, before rendering. */
export async function loadSecrets(names: string[]): Promise<void> {
  cryptoKey = await getKey()
  if (!cryptoKey) return
  for (const name of names) {
    // Credentials saved in clear text by earlier builds move into encrypted storage.
    const legacyKey = profilePrefix(activeProfileId()) + name
    let legacy: string | null = null
    try { legacy = localStorage.getItem(legacyKey) } catch { /* unavailable */ }
    if (legacy) {
      try {
        const value = JSON.parse(legacy)
        if (value !== null) { values.set(name, value); await persist(name, value) }
      } catch { /* unreadable: drop it */ }
      try { localStorage.removeItem(legacyKey) } catch { /* unavailable */ }
      continue
    }
    let stored: string | null = null
    try { stored = localStorage.getItem(storageKey(name)) } catch { /* unavailable */ }
    if (!stored) continue
    try { values.set(name, await decrypt(cryptoKey, stored)) } catch { /* key lost or data corrupt: treat as signed out */ }
  }
}

async function persist(name: string, value: unknown): Promise<void> {
  try {
    if (value === null || value === undefined) { localStorage.removeItem(storageKey(name)); return }
    if (cryptoKey) localStorage.setItem(storageKey(name), await encrypt(cryptoKey, value))
  } catch { /* storage unavailable: memory only */ }
}

/** A credential: same shape as Stored, but encrypted at rest (or memory-only). */
export class Secret<T> {
  constructor(private name: string, private fallback: T) {}

  get(): T { return values.has(this.name) ? (values.get(this.name) as T) : this.fallback }

  set(value: T): void {
    values.set(this.name, value)
    persist(this.name, value)
    listeners.get(this.name)?.forEach((l) => l(value))
  }

  subscribe(listener: (value: T) => void): () => void {
    let set = listeners.get(this.name)
    if (!set) { set = new Set(); listeners.set(this.name, set) }
    set.add(listener as (v: unknown) => void)
    return () => set!.delete(listener as (v: unknown) => void)
  }
}

/** The credential names, for loadSecrets. */
export const SECRET_NAMES = ['stremio-account', 'trakt', 'debrid', 'addons']
