import { afterEach, describe, expect, it, vi } from 'vitest'
import { addonStore } from '../src/core/addons'
import { library, type Progress } from '../src/core/library'
import { fromLibraryItem, planLibrarySync, StremioAccount, toLibraryItem } from '../src/core/stremio'

const progress = (id: string, updatedAt: number, extra: Partial<Progress> = {}): Progress =>
  ({ type: 'movie', id, name: id, videoId: id, time: 60, duration: 600, updatedAt, ...extra })

describe('library item mapping', () => {
  it('round-trips a series episode', () => {
    const p = progress('tt1', Date.parse('2026-01-01T00:00:00Z'), { type: 'series', videoId: 'tt1:2:3', season: 2, episode: 3 })
    const item = toLibraryItem(p)
    expect(item.state.video_id).toBe('tt1:2:3')
    expect(item.state.timeOffset).toBe(60_000)
    expect(fromLibraryItem(item)).toEqual({ ...p, poster: undefined })
  })

  it('marks 92% as watched', () => {
    expect(toLibraryItem(progress('a', 0, { time: 560 })).state.timesWatched).toBe(1)
  })
})

describe('planLibrarySync (mirrors Android)', () => {
  it('pushes newer local, pulls newer remote and remote-only', () => {
    const plan = planLibrarySync(
      { a: progress('a', 200), b: progress('b', 100), c: progress('c', 100) },
      { a: 100, b: 200, d: 50 },
      {},
    )
    expect(plan.push.map((i) => i._id).sort()).toEqual(['a', 'c'])
    expect(plan.pull.sort()).toEqual(['b', 'd'])
  })

  it('sends a tombstone for a title removed locally since the last sync', () => {
    const baseline = { gone: toLibraryItem(progress('gone', 100)) }
    const plan = planLibrarySync({}, { gone: 100 }, baseline, Date.parse('2026-02-01T00:00:00Z'))
    expect(plan.push).toHaveLength(1)
    expect(plan.push[0].removed).toBe(true)
    expect(plan.push[0]._mtime).toBe('2026-02-01T00:00:00.000Z')
    expect(plan.pull).toEqual([])
  })

  it('without a baseline, a missing title is pulled, not deleted', () => {
    expect(planLibrarySync({}, { x: 100 }, {})).toEqual({ push: [], pull: ['x'] })
  })
})

describe('StremioAccount', () => {
  afterEach(() => { vi.unstubAllGlobals(); addonStore.addons.set([]); library.progress.set({}) })

  it('signs in, takes the account’s addons and merges Continue Watching', async () => {
    const calls: Array<{ method: string; body: any }> = []
    vi.stubGlobal('fetch', async (url: string, init: { body: string }) => {
      const method = url.split('/').pop()!
      const body = JSON.parse(init.body)
      calls.push({ method, body })
      const result = {
        login: { authKey: 'k' },
        addonCollectionGet: { addons: [{ transportUrl: 'https://a.test/manifest.json', manifest: { id: 'a', name: 'A' } }] },
        datastoreMeta: [['tt9', Date.parse('2026-03-01T00:00:00Z')]],
        datastoreGet: [toLibraryItem(progress('tt9', Date.parse('2026-03-01T00:00:00Z')))],
        datastorePut: { success: true },
      }[method]
      return { ok: true, json: async () => ({ result }) }
    })
    library.progress.set({ tt1: progress('tt1', 5) })
    const account = new StremioAccount()
    await account.signIn(' me@x.test ', 'pw')

    expect(account.get()).toEqual({ authKey: 'k', email: 'me@x.test' })
    expect(addonStore.list().map((a) => a.transportUrl)).toEqual(['https://a.test'])
    expect(Object.keys(library.progress.get()).sort()).toEqual(['tt1', 'tt9'])
    expect(calls.find((c) => c.method === 'datastorePut')?.body.changes.map((c: any) => c._id)).toEqual(['tt1'])
    expect(Object.keys(account.baseline.get()).sort()).toEqual(['tt1', 'tt9'])
  })

  it('reset signs out, forgets the baseline and returns to default addons', async () => {
    vi.stubGlobal('fetch', async (url: string) => ({
      ok: true,
      json: async () => (url.endsWith('/manifest.json') ? { id: url, name: 'D' } : { result: {} }),
    }))
    const account = new StremioAccount()
    account.account.set({ authKey: 'k', email: 'e' })
    account.baseline.set({ a: toLibraryItem(progress('a', 1)) })
    addonStore.addons.set([{ transportUrl: 'https://x.test', manifest: { id: 'x', name: 'X' }, enabled: true }])
    library.progress.set({ a: progress('a', 1) })

    await account.resetSync()

    expect(account.get()).toBeNull()
    expect(account.baseline.get()).toEqual({})
    expect(addonStore.list().map((a) => a.transportUrl)).toEqual(['https://v3-cinemeta.strem.io', 'https://opensubtitles-v3.strem.io'])
    expect(Object.keys(library.progress.get())).toEqual(['a'])
  })
})
