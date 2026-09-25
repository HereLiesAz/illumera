import { useEffect, useState } from 'preact/hooks'

/** Hash routes, so the same build works from a web host, a TV app package or file://. */
export type Route =
  | { name: 'home' }
  | { name: 'search'; query: string }
  | { name: 'addons' }
  | { name: 'settings' }
  | { name: 'profiles' }
  | { name: 'details'; type: string; id: string; addon?: string }
  | { name: 'player' }

export function parseRoute(hash: string): Route {
  const [path, qs = ''] = hash.replace(/^#/, '').split('?')
  const parts = path.split('/').filter(Boolean).map(decodeURIComponent)
  const params = new URLSearchParams(qs)
  switch (parts[0]) {
    case 'search': return { name: 'search', query: params.get('q') ?? '' }
    case 'addons': return { name: 'addons' }
    case 'settings': return { name: 'settings' }
    case 'profiles': return { name: 'profiles' }
    case 'player': return { name: 'player' }
    case 'details':
      if (parts[1] && parts[2]) return { name: 'details', type: parts[1], id: parts[2], addon: params.get('addon') ?? undefined }
  }
  return { name: 'home' }
}

export function href(route: Route): string {
  switch (route.name) {
    case 'search': return `#/search${route.query ? '?q=' + encodeURIComponent(route.query) : ''}`
    case 'details': return `#/details/${encodeURIComponent(route.type)}/${encodeURIComponent(route.id)}${route.addon ? '?addon=' + encodeURIComponent(route.addon) : ''}`
    case 'home': return '#/'
    default: return `#/${route.name}`
  }
}

export function navigate(route: Route, replace = false): void {
  if (replace) location.replace(href(route))
  else location.hash = href(route)
}

export function useRoute(): Route {
  const [route, setRoute] = useState(() => parseRoute(location.hash))
  useEffect(() => {
    const on = () => setRoute(parseRoute(location.hash))
    window.addEventListener('hashchange', on)
    return () => window.removeEventListener('hashchange', on)
  }, [])
  return route
}
