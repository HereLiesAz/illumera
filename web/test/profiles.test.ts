import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

function fakeStorage() {
  const m = new Map<string, string>()
  return {
    getItem: (k: string) => (m.has(k) ? m.get(k)! : null),
    setItem: (k: string, v: string) => { m.set(k, v) },
    removeItem: (k: string) => { m.delete(k) },
    key: (i: number) => Array.from(m.keys())[i] ?? null,
    get length() { return m.size },
    dump: () => Object.fromEntries(m),
  }
}

describe('profiles', () => {
  let storage: ReturnType<typeof fakeStorage>
  beforeEach(() => { storage = fakeStorage(); vi.stubGlobal('localStorage', storage); vi.resetModules() })
  afterEach(() => vi.unstubAllGlobals())

  it('keeps each profile’s values apart; the default keeps un-prefixed keys', async () => {
    const { Stored, setActiveProfileId } = await import('../src/core/storage')
    new Stored('addons', [] as string[]).set(['a'])
    setActiveProfileId('kid')
    const kid = new Stored('addons', [] as string[])
    expect(kid.get()).toEqual([])
    kid.set(['k'])
    new Stored('streaming-server', '', true).set('http://x')
    expect(storage.dump()).toEqual({
      'illumera:addons': '["a"]',
      'illumera:active-profile': 'kid',
      'illumera:p:kid:addons': '["k"]',
      'illumera:global:streaming-server': '"http://x"',
    })
  })

  it('adds, switches and deletes profiles with their data', async () => {
    const p = await import('../src/core/profiles')
    const { Stored, activeProfileId } = await import('../src/core/storage')
    const kid = p.addProfile(' Kid ')
    expect(p.profiles.get().map((x) => x.name)).toEqual(['Me', 'Kid'])
    let reloaded = false
    p.switchProfile(kid.id, () => { reloaded = true })
    expect(reloaded).toBe(true)
    expect(activeProfileId()).toBe(kid.id)
    new Stored('progress', {}).set({ x: 1 })
    p.deleteProfile(kid.id) // active: refused
    expect(p.profiles.get()).toHaveLength(2)
    p.switchProfile('default', () => undefined)
    p.deleteProfile(kid.id)
    expect(p.profiles.get().map((x) => x.id)).toEqual(['default'])
    expect(Object.keys(storage.dump()).some((k) => k.indexOf(`p:${kid.id}:`) >= 0)).toBe(false)
  })
})
