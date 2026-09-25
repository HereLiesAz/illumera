/** Stremio addon protocol shapes, as far as illumera reads them. See docs/ADDONS.md. */

export type Resource = string | { name: string; types?: string[]; idPrefixes?: string[] }

export interface CatalogExtra { name: string; isRequired?: boolean; options?: string[] }

export interface CatalogManifest { type: string; id: string; name?: string; extra?: CatalogExtra[] }

export interface Manifest {
  id: string
  name: string
  version?: string
  description?: string
  logo?: string
  resources?: Resource[]
  types?: string[]
  idPrefixes?: string[]
  catalogs?: CatalogManifest[]
  behaviorHints?: { adult?: boolean; p2p?: boolean; configurable?: boolean; configurationRequired?: boolean }
}

/** An installed addon: its manifest plus where it lives. */
export interface Addon {
  transportUrl: string
  manifest: Manifest
  enabled: boolean
}

export interface MetaVideo {
  id: string
  title?: string
  name?: string
  released?: string
  thumbnail?: string
  overview?: string
  season?: number
  episode?: number
}

export interface Meta {
  id: string
  type: string
  name: string
  poster?: string
  background?: string
  logo?: string
  description?: string
  releaseInfo?: string
  imdbRating?: string
  runtime?: string
  genres?: string[]
  videos?: MetaVideo[]
  /** Not protocol: the addon this meta came from. */
  addonBase?: string
}

export interface Subtitle { id?: string; url: string; lang?: string; name?: string; transportUrl?: string }

export interface StreamBehaviorHints {
  bingeGroup?: string
  group?: string
  notWebReady?: boolean
  proxyHeaders?: { request?: Record<string, string>; response?: Record<string, string> }
  filename?: string
  videoSize?: number
  videoHash?: string
}

export interface Stream {
  name?: string
  title?: string
  description?: string
  url?: string
  ytId?: string
  externalUrl?: string
  infoHash?: string
  fileIdx?: number
  sources?: string[]
  subtitles?: Subtitle[]
  behaviorHints?: StreamBehaviorHints
  /** Not protocol: which addon served it. */
  addonBase?: string
  addonName?: string
}
