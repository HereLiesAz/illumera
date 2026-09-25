import { useEffect, useState } from 'preact/hooks'
import { AddonStore, addonStore } from '../core/addons'
import { library } from '../core/library'
import type { Addon, CatalogManifest, Meta } from '../core/types'
import { Card, Center } from './components'
import { useStored } from './hooks'

/** One catalog row; loads the next page (when the catalog allows skip) as the end nears. */
function CatalogRow({ addon, catalog, first }: { addon: Addon; catalog: CatalogManifest; first: boolean }) {
  const [items, setItems] = useState<Meta[] | undefined>()
  const [done, setDone] = useState(false)
  const [busy, setBusy] = useState(false)

  const more = async () => {
    if (busy || done) return
    setBusy(true)
    const page = await addonStore.catalog(addon, catalog, items?.length ?? 0)
    setBusy(false)
    const seen = new Set((items ?? []).map((m) => m.type + ':' + m.id))
    const fresh = page.filter((m) => !seen.has(m.type + ':' + m.id))
    setItems((items ?? []).concat(fresh))
    if (!fresh.length || !AddonStore.supportsSkip(catalog)) setDone(true)
  }

  useEffect(() => { more() }, [])
  if (items && !items.length) return null

  const title = `${catalog.name ?? catalog.id} · ${catalog.type}`
  return (
    <section>
      <h2>{title}</h2>
      <div class="row" onFocusIn={(e) => {
        const cards = (e.currentTarget as HTMLElement).children
        if (Array.prototype.indexOf.call(cards, e.target) >= cards.length - 6) more()
      }}>
        {(items ?? []).map((m, i) => <Card key={m.type + m.id} meta={m} autoFocus={first && i === 0} />)}
        {!items && <div class="card"><div class="poster" /></div>}
      </div>
    </section>
  )
}

export function Home() {
  const addons = useStored(addonStore.addons)
  useStored(library.progress) // re-render when progress changes
  const watching = library.continueWatching()
  const catalogs = addonStore.homeCatalogs()

  if (!addons.length) return <Center>Setting up… installing Cinemeta.</Center>
  return (
    <div>
      {watching.length > 0 && (
        <section>
          <h2>Continue watching</h2>
          <div class="row">
            {watching.map((p, i) => (
              <Card key={p.id} meta={{ id: p.id, type: p.type, name: p.name, poster: p.poster }} progress={p.time / p.duration} autoFocus={i === 0} />
            ))}
          </div>
        </section>
      )}
      {catalogs.map(({ addon, catalog }, i) => (
        <CatalogRow key={`${addon.transportUrl}|${catalog.type}|${catalog.id}`} addon={addon} catalog={catalog} first={!watching.length && i === 0} />
      ))}
      {!catalogs.length && <Center>No catalogs. Install an addon that provides some.</Center>}
    </div>
  )
}
