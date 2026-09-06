# Architecture

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
`auth`/`remote` (Stremio account API — email/password or Facebook login via
Stremio's own hosted OAuth handoff, addon-collection get/set, and two-way
Continue Watching sync against the `datastoreMeta`/`Get`/`Put` library API;
see `StremioAuthManager`/`StremioAuthService`/`StremioLibrarySyncManager`),
`debrid` (a `DebridManager` fronting
per-provider implementations: Real-Debrid, AllDebrid, Premiumize, TorBox,
Offcloud, Debrid-Link, EasyDebrid), `tmdb` (metadata enrichment), `trakt`
(auth, library sync, scrobbling), `torrent` (a TorrServer client),
`player` (persisted per-playback audio/subtitle/source selection),
`profile` (active-profile/session state), `stream` (stream parsing and
quality sorting), `trailer` (YouTube trailer extraction), `update` (see
below).

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
