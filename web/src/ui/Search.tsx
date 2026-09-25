import { useEffect, useRef, useState } from 'preact/hooks'
import { addonStore } from '../core/addons'
import type { Meta } from '../core/types'
import { Card, Center } from './components'
import { navigate } from './router'

export function Search({ query }: { query: string }) {
  const [text, setText] = useState(query)
  const [results, setResults] = useState<Meta[] | undefined>()
  const input = useRef<HTMLInputElement>(null)

  useEffect(() => { input.current?.focus() }, [])
  useEffect(() => {
    let live = true
    const q = text.trim()
    if (!q) { setResults(undefined); return }
    const timer = setTimeout(() => {
      addonStore.search(q).then((r) => live && setResults(r))
      navigate({ name: 'search', query: q }, true)
    }, 350)
    return () => { live = false; clearTimeout(timer) }
  }, [text])

  return (
    <div>
      <input ref={input} class="field" type="search" placeholder="Search movies and series" value={text}
        onInput={(e) => setText((e.target as HTMLInputElement).value)} />
      <h2>{results ? `${results.length} results` : ' '}</h2>
      {results && !results.length && <Center>Nothing found.</Center>}
      <div class="grid">{(results ?? []).map((m) => <Card key={m.type + m.id} meta={m} />)}</div>
    </div>
  )
}
