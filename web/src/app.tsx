import { useEffect } from 'preact/hooks'
import { addonStore } from './core/addons'
import { stremio } from './core/stremio'
import { probeServer } from './core/streamingServer'
import { Addons } from './ui/Addons'
import { Icon } from './ui/components'
import { Details } from './ui/Details'
import { Home } from './ui/Home'
import { Player } from './ui/Player'
import { href, useRoute, type Route } from './ui/router'
import { Search } from './ui/Search'
import { Settings } from './ui/Settings'

const NAV: Array<{ route: Route; icon: 'home' | 'search' | 'addons' | 'settings'; label: string }> = [
  { route: { name: 'home' }, icon: 'home', label: 'Home' },
  { route: { name: 'search', query: '' }, icon: 'search', label: 'Search' },
  { route: { name: 'addons' }, icon: 'addons', label: 'Addons' },
  { route: { name: 'settings' }, icon: 'settings', label: 'Settings' },
]

export function App() {
  const route = useRoute()
  useEffect(() => {
    addonStore.ensureDefaults()
    stremio.syncLibrary(true).catch(() => undefined)
    probeServer()
  }, [])

  if (route.name === 'player') return <Player />
  return (
    <div class="shell">
      <nav class="nav">
        <img class="logo" src="./logo.svg" alt="illumera" />
        {NAV.map((n) => (
          <a key={n.label} href={href(n.route)} class={route.name === n.route.name ? 'active' : ''} aria-label={n.label} title={n.label}>
            <Icon name={n.icon} />
          </a>
        ))}
      </nav>
      <main class="content">
        {route.name === 'home' && <Home />}
        {route.name === 'search' && <Search query={route.query} />}
        {route.name === 'addons' && <Addons />}
        {route.name === 'settings' && <Settings />}
        {route.name === 'details' && <Details key={route.type + route.id} type={route.type} id={route.id} addon={route.addon} />}
      </main>
    </div>
  )
}
