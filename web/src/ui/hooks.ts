import { useEffect, useState } from 'preact/hooks'

/** Current value of a Stored, re-rendering on change. */
export function useStored<T>(stored: { get(): T; subscribe(listener: (value: T) => void): () => void }): T {
  const [value, setValue] = useState(stored.get())
  useEffect(() => stored.subscribe(setValue), [stored])
  return value
}

/** Result of an async load, restarted when deps change; stale results are dropped. */
export function useAsync<T>(load: () => Promise<T>, deps: unknown[]): { value?: T; loading: boolean; error?: string } {
  const [state, setState] = useState<{ value?: T; loading: boolean; error?: string }>({ loading: true })
  useEffect(() => {
    let live = true
    setState({ loading: true })
    load().then(
      (value) => live && setState({ value, loading: false }),
      (e) => live && setState({ loading: false, error: e instanceof Error ? e.message : String(e) }),
    )
    return () => { live = false }
  }, deps)
  return state
}
