# Architecture

## Unified Movies and Series catalogs

The Android `AddonRepository.getAggregatedCatalogPage` reads catalog manifests from enabled addon records, requesting small batches of catalogs for either `movie` or `series`. No Stremio source/category dropdown excludes other enabled providers. Sources supporting `skip` can contribute subsequent pages. Genre catalog extras are passed through when advertised; other providers are filtered by the returned metadata's declared genres. Results are deduplicated by IMDb ID, or by normalized title and release year when no IMDb ID exists. `HomeViewModel` appends only unseen titles as the user approaches the end of a row.

Movies and Series generate only one row per enabled Stremio genre; they no longer render an All Movies / All Series row or additional per-addon category rows. The official Cinemeta genre lists (19 Movie and 22 Series genres) are always available; real non-year genre values advertised by enabled addons are added and aliases normalized. Every genre is enabled by default. `ScreenGenreRows` stores **disabled** genres per profile and screen in `illumera_screen_genres`; the Screen Editor provides a scrollable checklist and enable/disable-all controls. Only the first visible genre rows are warmed on screen open, and later rows load on entry to the LazyColumn viewport. Individual rows independently browse enabled addon catalog source batches and preserve per-row horizontal pagination. Changes invalidate cached screen data on exiting the editor. Catalog timeouts are isolated to their source.

illumera is a single-Activity Android TV app: Kotlin, Jetpack Compose for
TV, MVVM with Hilt dependency injection, Room for local persistence.

## Modules

| Module | Purpose |
|--------|---------|
| `app` | The application itself — all UI, data, and DI |
| `playbackcore` | Local-AAR wrapper around the custom Stremio Media3 fork (ExoPlayer core + decoder extensions); see [`RELEASING.md`](RELEASING.md) |
| `assrender` (vendored at `assrender/`, originally from [`LumeraD3v/assrender`](https://github.com/LumeraD3v/assrender)) | ASS/SSA subtitle rendering integrated into Media3: `AssRenderersFactory`, `AssSubtitleRenderer`, `AssMatroskaExtractor`, a native `libass` bridge, and the `SubtitleOverlayView` compositing layer. Its Media3 `Renderer` overrides must be kept in sync with whatever Media3 version `gradle/libs.versions.toml` declares — see [`RELEASING.md`](RELEASING.md) |

## Data layer (`app/src/main/java/com/hereliesaz/illumera/data/`)

**Persistence** (`data/local/`, `data/model/`): a single Room database
(`LumeraDatabase`, currently migrated through version 45) with one DAO,
`AddonDao`, that — despite the name — covers addons, catalog configs, hub
rows/items, profiles, themes, watch history, watchlist, series
"next up" tracking, and recent searches. Entities: `AddonEntity`,
`CatalogConfigEntity`, `HubRowEntity`/`HubRowItemEntity`, `ProfileEntity`,
`ThemeEntity`, `WatchHistoryEntity`, `WatchlistEntity`,
`SeriesNextUpEntity`, `RecentSearchEntity`. Model sub-packages
(`model/debrid`, `model/introdb`, `model/stremio`, `model/tmdb`,
`model/trakt`) hold API DTOs.

**Repositories** (`data/repository/`): `AddonRepository` (Stremio catalog
fetch/paginate/search — see below), `IntroRepository` (IntroDB skip-segment
data), `SubtitleRepository` (subtitle fetch/matching).

**Integration services**, each in its own package under `data/`:
`auth`/`remote` (Stremio account API — email/password, TV-to-phone credential handoff, and hosted
Facebook/Apple OAuth, addon-collection get/set, and two-way
Continue Watching sync against the `datastoreMeta`/`Get`/`Put` library API;
see `StremioAuthManager`/`StremioAuthService`/`StremioLibrarySyncManager`),
`debrid` (a `DebridManager` fronting
per-provider implementations: Real-Debrid, AllDebrid, Premiumize, TorBox,
Offcloud, Debrid-Link, EasyDebrid), `tmdb` (metadata enrichment), `trakt`
(auth, library sync, scrobbling), `wutch` (wutch.tv: sign-in with a personal
API key, watchlist and progress import, progress and watchlist write-back), `torrent` (a TorrServer client),
`player` (persisted per-playback audio/subtitle/source selection),
`profile` (active-profile/session state), `stream` (stream parsing and
quality sorting), `trailer` (YouTube trailer extraction), `soundtrack`
(song lists; see below), `update` (see below).

**Soundtracks** (`data/soundtrack/`): `SoundtrackRepository` reads IMDb
credits from the Soundtrack addon (`BuildConfig.SOUNDTRACK_ADDON_URL`,
HereLiesAz/stremio-soundtrack). For a movie or single episode,
`TunefindSource` then reads Tunefind's public pages on the device through
`PageScriptRunner` — an off-screen WebView (`WebViewPageScriptRunner`) that
loads a page and polls a script until the rendered songs appear. Tunefind
has no usable API, so page structure is unverified and every step fails
soft to IMDb alone. `Tunefind.merge` keeps Tunefind's order, fills missing
artists from IMDb and appends IMDb-only songs. `ui/soundtrack/` shows IMDb
first and swaps in the merged list when Tunefind answers.

**Reset Stremio sync** (Integrations → Stremio, signed in or not) undoes sync on the
active profile so it can be set up again from scratch: it clears the profile's Stremio
credentials, forgets the library-sync baseline (`StremioLibrarySyncManager.clearSyncState`,
so the next sign-in merges both sides instead of pushing local gaps as deletions), and
removes every addon except Cinemeta. Local watch progress and the Stremio account itself
are left alone.

A Facebook-created Stremio account's login response carries its FB photo as
`user.avatar`; on a successful `auth` login this is applied to the active
profile's avatar automatically (`ProfileAssets.urlAvatarRef` wraps it as a
`"url:<url>"` `avatarRef`, which the avatar-rendering call sites already
hand straight to Coil), mirroring stremio-web's own
`profile.auth.user.avatar` behavior.

## Dependency injection (`di/`)

Three Hilt modules: `DatabaseModule` (the Room database + `AddonDao`),
`NetworkModule` (a shared `OkHttpClient`, plus qualified Retrofit instances
for the Stremio addon index, TMDB, Trakt, and an authenticated Trakt client
with `TraktAuthInterceptor`), `ImageLoaderModule` (the Coil image loader).
Everything else is constructor injection (`@Inject constructor`) without a
separate binding module.

## UI (`ui/`)

Feature-per-package, each typically a `Screen` composable plus a
`@HiltViewModel`: `home` (dashboard, hero carousel, hub rows, grid view),
`details`, `player` (see below), `profiles`, `search`, `settings`
(general settings, dashboard editor, theme editor, integrations, about),
`addons`, `watchlist`, `cast`, `studio`, `theme` (see
[`THEMING.md`](THEMING.md)), `navigation` (`NavDrawer`/`TopNavigationBar`).

### Navigation

`MainActivity.kt` does **not** use a Jetpack `NavHost` graph — the
`androidx.navigation.compose` imports there are unused. Navigation is a
manual state machine inside one `setContent` block: a `NavDestination` enum
drives which of `NavDrawer`/`TopNavigationBar` is shown (position is a
per-profile preference, cross-faded), a `rememberSaveable var activeView`
string switches between full-screen composables (`"menu"`, `"details"`,
`"grid"`, `"player"`, trailer, …), and selection/player state travels as
plain `mutableStateOf` fields rather than route arguments. D-pad focus is
managed by hand via a map of `FocusRequester`s per destination.

### Player

`ui/player/PlayerScreen`/`PlayerViewModel` sit on top of
`ui/player/base/`: `BasePlayerScaffold`, a `PlayerBackend` interface with
`ExoPlayerBackend` as the concrete Media3 implementation,
`ComposePlayerSurface`, `FrameRateManager`. See [`RELEASING.md`](RELEASING.md)
for how the underlying Media3/decoder binaries are built and why version
skew there is dangerous.

## The Stremio addon system

`data/model/stremio/` holds the manifest/catalog/meta/stream models.
`AddonRepository` fetches catalogs page-by-page (skip-based pagination,
terminating when a page comes back empty or entirely duplicate — there is
currently no hard page-count ceiling despite a `MAX_CATALOG_PAGES = 30`
constant on the class; it's unused dead code, not an enforced cap),
searches Cinemeta directly for movies/series in parallel, sanitizes
malformed catalog entries, and applies per-request timeouts (10s catalog,
20s stream) — all through `StremioApiService`
(Retrofit). `ui/addons/AddonsScreen` manages install/uninstall/reorder;
addons can be added by manifest URL, by QR-paired remote paste from a
phone, or synced from a connected Stremio account (and pushed back to it).
What illumera reads beyond the protocol, for addon authors, is in
[ADDONS.md](ADDONS.md).

## Auto-update

`data/update/AppUpdateManager` polls the GitHub Releases API, compares the
latest release's tag against `BuildConfig.VERSION_NAME`, validates the
release's `.apk` asset URL against an allow-list, and drives a
`StateFlow<UpdateState>` (`Idle → Checking → UpdateAvailable/UpToDate/Error
→ Downloading → ReadyToInstall`) that `MainActivity` renders as dialogs and
uses to trigger the install intent via `FileProvider`. See
[`RELEASING.md`](RELEASING.md) for the release shape this depends on.

## QR / remote input (`remote_input/`)

The TV app embeds small NanoHTTPD servers so a phone on the same LAN can
act as an input device, paired by scanning a QR code (a URL carrying a pin
token, rendered via ZXing): `LinkServer` (paste a URL, e.g. an addon
manifest, from phone to TV), `HubBulkUploadServer` and `AvatarUploadServer`
(image upload portals for hub cards / profile avatars), `IntegrationServer`
(remote input for settings flows). Every request must carry the pairing
token, proving the phone actually scanned the QR code first.


## Watchlist and Stremio Library

The main Android Watchlist is a viewing surface: Queue is always first, then one mixed Movies & Series Library rail. `WatchlistEntity` bookmarks and active `WatchHistoryEntity` entries are merged and deduplicated by media type, canonical IMDb ID, or title/year when one side has a non-IMDb ID. Episode history collapses to the parent series ID, while different movie/series adaptations remain separate. Library sorting controls are directly accessible without dropdown menus. Sort selection is saved per profile in `illumera_watchlist_ui`.

Stremio library synchronization now imports unwatched/unplayed remote bookmarks into the local watchlist, including series with no current `videoId`. Imports are profile-scoped and tagged as remote-owned; remote deletion removes only bookmarks previously imported by synchronization, preserving local additions. Watch counts from Stremio are recorded for sorting, with local watch completion used as a fallback when a remote count is absent. The previous cloud-storage management list and its play/delete actions live in Settings → Watchlist.

The existing Stremio Library reconciliation behavior and account requirements remain in place. An unavailable/disconnected Stremio account does not block local watchlist content.


## Shared cinematic screens and aggregated catalog

All three screens (Home, Movies, Series) use a top Hero Carousel and a per-focused-row cinematic preview; Movies and Series do not offer Simple layout. The carousel defaults to the first populated Home or genre row, and a sentinel distinguishes an explicit Disable from an unset automatic source. Backdrops remain visible while trailer extraction is pending. The configured trailer addon is only consulted when installed and enabled; non-IMDb catalog IDs are matched conservatively via title/year search.

Movies and Series aggregate the enabled catalog variants across addon manifests (including required-genre catalogs). Each page requests only a bounded set of sources, and paging continues when an addon returns no items. Duplicate identities use canonical IMDb keys plus a type/name/release-year fallback across catalog pages. All enabled genre rows are the sole movie/series browsing content: there is no combined catch-all row, Discover screen, or dropdown catalog selector.

Default profile snapshots now carry Cinemeta, YouTube Trailers, Streaming Catalogs, TMDB, SeedSphere and Stremio Stats. Existing profiles are upgraded additively once, preserving disabled, removed and customized entries thereafter. Provider manifests are fetched with isolated timeouts; a missing manifest does not imply imaginary catalogs or stream support. Stremio Stats requires an individually configured, authenticated manifest before personal statistics can work.

The Stremio Library sync baseline now includes saved-only `WatchlistEntity` titles as well as watch history. The latest watched episode is collapsed under its parent series for Stremio synchronization. Disabling/deleting stock addons remains under user control.


## Home mixed-catalog aggregation

Home displays one lazily paginated `catalog-mixed:home` rail containing both movies and series from every enabled eligible addon catalog, interleaved across media types. Each source-batch cursor advances independently through movie and series catalog candidates. Deduplication uses the same IMDb/type/title/year identity as genre browsing, carried across fetched pages. The Home Hero Carousel draws automatically from the mixed row as it populates; Continue Watching and user-created Hub rows remain separate by design. Explicit addon-by-addon Home rows are superseded by this unified rail, while Movies and Series screens retain **only** their individually enabled genre rows.

The Watchlist Library now presents a single sorted collection containing both media types, preserving type-specific details navigation and per-profile sorting. The old two-row focus key migrates into row zero and one remembered horizontal scroll position is used for the mixed rail.
