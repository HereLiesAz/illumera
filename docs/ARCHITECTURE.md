# Architecture

## Unified Movies and Series catalogs

The Android `AddonRepository.getAggregatedCatalogPage` reads catalog manifests from enabled addon records, requesting small batches of catalogs for either `movie` or `series`. No Stremio source/category dropdown excludes other enabled providers. Sources supporting `skip` can contribute subsequent pages. Genre catalog extras are passed through when advertised; other providers are filtered by the returned metadata's declared genres. Results are deduplicated by IMDb ID, or by normalized title and release year when no IMDb ID exists. `HomeViewModel` appends only unseen titles as the user approaches the end of a row.

Movies and Series generate only one row per enabled Stremio genre; they no longer render an All Movies / All Series row or additional per-addon category rows. The official Cinemeta genre lists (19 Movie and 22 Series genres) are always available; only recognized additional film/TV genres advertised by enabled addons are added. Manifest genre fields also contain languages, countries, video resolutions and release-age buckets; these are not genres and are excluded by the recognized-genre vocabulary. Real-genre aliases are normalized. Every genre is enabled by default. `ScreenGenreRows` stores **disabled** genres per profile and screen in `illumera_screen_genres`; the Screen Editor provides a scrollable checklist and enable/disable-all controls. The Movies/Series screens hide genre rows until they contain titles, and three bounded background lanes probe every enabled genre so an empty early genre cannot block access to later populated ones. Each genre probes its initial addon catalog source layer and one additional skip layer, when supported, before being treated as empty. Each item is assigned to a single primary genre using public Cinemeta metadata (IMDb ID or conservative title/year match), independent of installed addons; addon-supplied genre lists are only a deterministic fallback when that lookup fails. Items surfaced under a provider's secondary genre are transferred into the correct primary-genre row instead of being dropped; destination rows deduplicate both redirected and independently fetched results. Source order must not determine cross-row placement. Individual populated rows preserve per-row horizontal pagination. Changes invalidate cached screen data on exiting the editor. Catalog timeouts are isolated to their source.

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
(`LumeraDatabase`, currently at version 54) with one DAO,
`AddonDao`, that — despite the name — covers addons, catalog configs, hub
rows/items, profiles, themes, watch history, watchlist, series
"next up" tracking, and recent searches. Entities: `AddonEntity`,
`CatalogConfigEntity`, `HubRowEntity`/`HubRowItemEntity`, `ProfileEntity`,
`ThemeEntity`, `WatchHistoryEntity`, `WatchlistEntity`,
`SeriesNextUpEntity`, `RecentSearchEntity`, `HistoryTombstoneEntity`,
`RuntimeOwnerEntity`. Model sub-packages
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
Offcloud, Debrid-Link, EasyDebrid; several can be connected per profile, the first being primary, and the library merges all of them), `tmdb` (metadata enrichment), `trakt`
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

Navigation uses **Navigation 3** (`androidx.navigation3`): `MainActivity.kt`
renders a `NavDisplay` over a `NavBackStack<NavKey>` that is saved across
process death (`ui/navigation/Routes.kt`). The keys are `@Serializable` data
classes holding only strings and ints. Exactly one `MainRootKey` (`HomeKey`,
`MoviesKey`, `SeriesKey`, `SearchKey`, `WatchlistKey`, `AddonsKey`, …) sits at
the bottom of the stack for the screen chosen in the menu, and detail,
player and other screens are pushed above it. A `NavDestination` enum drives
which of `NavDrawer`/`TopNavigationBar` is shown (position is a per-profile
preference, cross-faded) in `MainShell`. D-pad focus is managed by hand via
`FocusRequester`s per destination.

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
terminating when a page comes back empty or entirely duplicate; there is
no hard page-count ceiling),
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
latest release's version against `BuildConfig.VERSION_NAME` (a four-component
`major.minor.patch.build` name: the release tag carries the first three and the
APK filename `illumera-<version>-release.apk` the fourth), validates the
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

The main Android Watchlist is a vertically scrolling viewing surface: Suggested Next with an adjacent refresh control is first, the manually managed Queue is beneath it, and the mixed Movies & Series Library is a responsive portrait-poster grid. `WatchlistEntity` bookmarks and active `WatchHistoryEntity` entries are merged and deduplicated by media type, canonical IMDb ID, or title/year when one side has a non-IMDb ID. Episode history collapses to the parent series ID, while different movie/series adaptations remain separate. Compact Library sorting chips are directly above the grid without dropdown menus; sort selection remains per-profile in `illumera_watchlist_ui`. All queue configuration toggles (enabled, movies, episodes, whole shows, suggestion sources, unseen-only) are under Settings → Watchlist; the refresh action stays beside Suggested Next.

Stremio library synchronization now imports unwatched/unplayed remote bookmarks into the local watchlist, including series with no current `videoId`. Imports are profile-scoped and tagged as remote-owned; remote deletion removes only bookmarks previously imported by synchronization, preserving local additions. Watch counts from Stremio are recorded for sorting, with local watch completion used as a fallback when a remote count is absent. The previous cloud-storage management list and its play/delete actions live in Settings → Watchlist.

The existing Stremio Library reconciliation behavior and account requirements remain in place. An unavailable/disconnected Stremio account does not block local watchlist content.


## Shared cinematic screens and aggregated catalog

Home, Movies and Series use one vertically scrolling list: the Hero Carousel is the first **ordinary item**, never a sticky or persistent header, followed by genre/category rows. The active row swaps its compact landscape cards for portrait posters; its focused poster becomes an inline cinematic preview. No separate preview is inserted above a row, and all three discovery screens have one fixed cinematic layout (no Simple toggle). The carousel defaults to the first populated Home or genre row, and a sentinel distinguishes an explicit Disable from an unset automatic source. Backdrops remain visible while trailer extraction is pending. The configured trailer addon is only consulted when installed and enabled; non-IMDb catalog IDs are matched conservatively via title/year search.

Movies and Series aggregate the enabled catalog variants across addon manifests (including required-genre catalogs). Each page requests only a bounded set of sources, and paging continues when an addon returns no items. Duplicate identities use canonical IMDb keys plus a type/name/release-year fallback across catalog pages. All enabled genre rows are the sole movie/series browsing content: there is no combined catch-all row, Discover screen, or dropdown catalog selector.

Default profile snapshots now carry Cinemeta, YouTube Trailers, Streaming Catalogs, TMDB, SeedSphere and Stremio Stats. Existing profiles are upgraded additively once, preserving disabled, removed and customized entries thereafter. Provider manifests are fetched with isolated timeouts; a missing manifest does not imply imaginary catalogs or stream support. Stremio Stats requires an individually configured, authenticated manifest before personal statistics can work.

The Stremio Library sync baseline now includes saved-only `WatchlistEntity` titles as well as watch history. The latest watched episode is collapsed under its parent series for Stremio synchronization. Disabling/deleting stock addons remains under user control.


## Home category-pair aggregation

Home retains **separate category/provider groupings**: Latest, Popular, Trending, Netflix, HBO Max and every other configured Home catalog title. For each matching category, the movie and series variants (and enabled addon sources with the same normalized category label) are combined into one `catalog-home:<category>` row. Each pair is deduplicated by media type, canonical IMDb ID, or title/year when one side has a provider-specific ID, and its source batches are fetched lazily. Home does **not** aggregate all categories into a single catch-all row. A category without a paired media type still appears on its own.

The Home Hero Carousel uses the first populated merged category by default; Continue Watching and custom Hub rows remain separate. Existing user-selected Hero category previews continue to work. Movies and Series remain genre-first and do not receive catch-all rows.

The Watchlist Library keeps one deduplicated sorted collection containing both media types, preserving type-specific details navigation and per-profile sorting. The old two-row focus key migrates into row zero; the WatchlistViewModel retains the parent LazyColumn's vertical scroll state across navigation rather than a horizontal Library rail cursor.


### Cinematic rail geometry

The cinematic Home, Movies and Series use a **single active row**: only its items are portrait posters (140×210dp), with the focused poster widening inline up to 340dp and showing a silent trailer/backdrop and metadata. **Every inactive media row**, including Continue Watching, is a compact 190dp-wide 16:9 landscape-card rail, 116dp high. The active rail reserves 220dp to allow focus scaling; title and rail spacing is positive (20dp between LazyColumn rows), never overlapping. The vertical focus pivot keeps 175dp from the viewport top to reveal most of the preceding inactive landscape rail. The Hero remains the first ordinary scrollable item and disappears when scrolled past. Row mode (linear, finite, looping) still controls horizontal pagination and View More behavior; custom Hub rows keep their existing landscape cards. Focus is handed to the same media identity when its row changes from landscape to portrait, preserving D-pad and menu navigation. Vertical Up/Down between adjacent composed media rows uses each focused card's **measured screen-space left edge**, not its center; `CinematicVerticalFocusCoordinator` selects the card whose left side most closely aligns. This avoids the expanded 16:9 preview skipping to the second landscape thumbnail. Hub and Hero transitions preserve default focus navigation, as do offscreen/uncomposed rows. The coordinator registers each finite, looping, and linear card when laid out and unregisters it on disposal. The settings UI does not expose Home Simple/Cinematic or Continue Watching poster/landscape switches; inactive profiles' saved values are retained but no longer select cinematic geometry.

The Watchlist screen itself presents Suggested Next, Queue, and the Movies/Series Library grid, not genre buckets. The language/country/quality/release-date pseudo-genre rows were originating from Movie and Series addon options, so they are filtered at ScreenGenreRows, without altering Watchlist items.

## Paired Home catalogs and combined Watchlist Library

Home groups enabled catalog configurations **by provider and logical catalog category before the rows are paginated**, pairing each category's movie and series versions in one row (e.g. Latest, Popular, Trending, Netflix and HBO Max remain separate). Paired results retain original item media types and are interleaved and deduplicated. Individual movie/series source pagination counters are tracked separately so loading more of one half never skips items from the other. Unpaired categories remain visible, and user-set Home ordering and visibility are respected. The automatic Hero uses Home rows, not a global mixed catch-all. Movies and Series themselves remain separately browsable, genre-first screens.

Watchlist keeps Suggested Next (with refresh) at the top, then Queue. The Library below is one independently sortable Movies & Series poster grid; saved items and watch history are deduplicated by canonical media identity without merging movies and TV series that share a name. A movie/series filter narrows the same Library row rather than introducing separate category rows.

## Per-profile cinematic audio and video crop (October 15, 2026)

`ui/audio/CinematicAudioPreferences.kt` owns three per-profile SharedPreferences flags (autoplay, preview sound, navigation sounds), not Room fields; this avoids a schema migration and ensures each profile's settings survive updates. Compose registers a preference-change listener and updates existing Home/Hero and Settings consumers immediately. Autoplay defaults on, trailer sound muted, navigation sounds enabled. Disabled autoplay prevents trailer extraction/player construction. Trailer sound adjusts the existing player's volume without restarting it. The Hero stops playback when it leaves the actual scrolling LazyColumn viewport or another row takes focus; an off-screen Hero must never keep its audio playing. PlayerView uses `AspectRatioFrameLayout.RESIZE_MODE_ZOOM` for both Hero and inline previews, cropping excess vertical/horizontal video as necessary instead of letterboxing. Inline preview card targets 210dp × 16/9 (~373dp) and is bounded by the width left after side navigation. `MainShell` plays Android's platform NAVIGATION_* sounds on D-pad direction keydowns without consuming the event, with a 110ms repeat guard; selection and Back remain silent.

## Cinematic preview and trailer fallback (October 15, 2026)

The previous 282→184dp above-row preview was removed entirely: it still took a full extra line of vertical space. The new 210dp-high inline card expands **in the focused poster's slot** (maximum width 340dp, screen-width-constrained), reclaiming that line. This retains the original goal—more browse rows visible at once—without making the hero or artwork persist above the rows. The backdrop remains until a playable trailer renders its first frame. Trailer discovery first consults the enabled Stremio trailer addon, then TMDB if the build has an API key, then Cinemeta's public trailer metadata. Each source failure or timeout is isolated; preview extraction prefers a muxed AVC/MP4 stream to avoid unnecessary adaptive-media merging for silent TV previews. Signed YouTube extraction remains inherently network-dependent and must be tested on a TV device.

## Watchlist grid navigation and control relocation (October 15, 2026)

`QueueSection` now renders Suggested Next and its focusable refresh action before the manual Queue, even if either set of cards is empty. The queue enabled/type/source/unseen controls moved without changing their QueueManager persistence or actions into `SettingsScreen.WatchlistSettings`, using the existing `QueueOption` renderer through `QueuePreferencesSettings`. The QueueManager is Hilt-singleton/profile-scoped.

`WatchlistScreen` renders Library with one `LazyVerticalGrid`: Suggested Next and Library sorting occupy full-span header items, while every poster is its own key-stable `library_<type>_<id>` lazy item, surviving sorting and movement across column and row boundaries without the old row-local composition-key trap. `watchlistGridColumnCount` uses the available width after navigation and screen padding; compact devices get smaller cards, retaining two columns where practical. The grid scroll state is held in `WatchlistViewModel.watchlistGridState`. Existing `0_<id>_<index>` restore keys and media-type identity survive sorting and aggregation, while visible and near-viewport cells trigger lazy metadata enrichment. Sort chips use 11sp labels with 8×5dp padding. Queue suggestions retain their rating/reorder/removal controls, and disabled generation still leaves a focusable refresh target that directs the viewer to Settings.

**Device checks required:** D-pad traversal across Suggested Next, Queue, Library chips and grid; focus on disappearing cards; portrait poster sizing in phone and TV widths; Library sort/progress badges; profile switch, reopen/resume and Settings toggles; refresh when suggestions are disabled. Compile/TV integration has not been verified.

## Center-cropped video and active-row clearance (October 15, 2026)

Cinematic rows use `useLandscapeMediaCard` so **every inactive row** is landscape regardless of stale caller settings. Only the active row becomes 140dp posters and an inline cinematic preview, in linear, finite, and infinite row modes. The active rail reserves 238dp for a focused 210dp poster scaled 1.05x, plus an 8dp trailing spacer before the next heading. Inactive rails stay 116dp. The vertical pivot stays 175dp to reveal the preceding landscape rail.

`cinematicVideoCoverSize` computes a 16:9 covering surface from the **actual viewport** width and height, rather than trusting a player resize flag. `TrailerPreview` centers the oversized video inside a clipped `BoxWithConstraints`. `cinematic_trailer_player.xml` requests TextureView, which supports precise clipping on Android TV, with Media3 zoom for other video ratios. The Hero's AnimatedContent explicitly fills its 420dp viewport. Both Hero and inline video center-crop without letterboxing.

## Standalone Add-ons destination and Hero focus return (October 15, 2026)

`NavDestination.Addons` has a saved, serializable `AddonsKey` and a main navigation entry, not a Settings subsection. `AddonsScreen` displays the existing browse catalog content directly, without a dialog's height constraint. Source tabs scroll horizontally, catalog results vertically, and configure/install/uninstall are accessible, focusable icons. Installed addons and manual URL/phone-paste installation retain their previous persistence and confirmation behavior. The external stremio-addons.net source is labeled **3rd Party** but its endpoint is unchanged.

`HeroCarousel` retains an unconditional return-focus requester in addition to its existing initial/restored-entry requester. Up from the first focused content row (history, Hub, or category) scrolls the nonsticky `LazyColumn` to the Hero, waits for it to compose, and moves focus there. Deeper rows preserve normal vertical navigation.

## Add-ons navigation and canonical Hero focus (October 15, 2026)

The Add-ons root has a screen-level `FocusMemory` and `RestoreFocusOnResume` fallback to its Browse action. This is necessary because attaching `entryRequester` to a button does **not** itself move focus from the navigation drawer. Returning from Installed to Browse explicitly restores that focus after the Browse subtree is composed. At compact widths below the header's 372dp control requirement, Browse and Installed share a flexible row and the phone-link action moves below them, rather than overflowing the viewport.

Addon installations are keyed by their **complete transport URL**, including configuration and credentials. The catalog's Uninstall button first matches the exact URL; where only the manifest ID matches and several configured instances exist, it opens Installed for explicit selection instead of deleting an arbitrary instance. The Installed list still removes by its exact transport URL.

Movies/Series keep previously revealed empty genre shells mounted to avoid detaching the focused LazyColumn subtree. This is a focus-stability decision, **not** evidence that those shells are focusable. The Hero Up/Down return pivot now uses `firstFocusableCinematicRowIndex` to skip empty Category and Hub rows while retaining the original indexed focus IDs. The drawer's Profile/Up regression must prove Search became focused before asserting the boundary.


## Add-ons, adaptive discovery and navigation (October 15, 2026)

Add-ons renders a single **Browse Add-ons** heading followed immediately by horizontally scrolling compact **All**, **Installed**, **Official**, **Community**, **3rd Party**, collection and Install URL controls. The previous above-heading Browse/Installed/URL header is removed. The All feed concurrently reads the available official, community, third-party and saved collection sources; one unavailable provider does not discard successful providers. Each installed configuration is selected for deletion only by a matching normalized transport endpoint. Normalization removes the trailing `/manifest.json` but **retains the configured path and query string**; manifest ID is never authority for removal. Exact uninstalls still require confirmation. The source/header, search, installed delete, and catalog actions are tagged in the existing `FocusMemory`; switching Installed keeps the header composed so back-stack restoration does not discard focus. Ambiguous URL selection never silently deletes a different configuration.

Movies/Series keep their existing **independently paginated genre-first model**; genre shells stay in the LazyColumn while fetching so rows after Documentary/Drama remain visible even when early sources are empty or late. Genre rows are ordered by the current profile's watched-title and saved-watchlist overlap with their loaded content (watch = 2, saved = 3). Ties retain the original stable order and original per-row focus index. Each genre's title ordering is independently persisted in `illumera_screen_genres` and configurable in Screen Editor as catalog order, rating, newest, oldest or alphabetical direction; this does not change catalog pagination or media identities.

The Hero is still a regular scrollable LazyColumn item. Both Up back to Hero and Down to the first populated media row scroll that target into composition, wait for a focus requester, then move D-pad focus. Empty genre shells cannot be the first focus destination. Selecting a navigation item closes the shell menu and navigates on the same action. Details' Episodes and Sources symbols now match the player. Sound-enabled cinematic trailers and full-screen trailer playback use 60% player volume; other playback remains at 100%.


### Release-channel update notifications (October 15, 2026)

The app uses its **compiled release channel**, not cross-store sideloading. GitHub release builds use `AppUpdateManager`: the stable GitHub Releases tag provides major/minor/patch and the matching signed APK filename supplies the fourth build component (e.g. `0.12.46` and `illumera-0.12.46.1-release.apk` yield `0.12.46.1`). HTTP checks are serialized and throttled to once per 30 minutes on foreground resume, while the first launch requests a check. A newer version triggers the existing update dialog after the splash. Dismissing affects only that specific version for the current session, not later releases; the existing Settings opt-out remains respected. Downloads continue to require SHA-256 when published and the installed signing certificate.

Play builds use the Play Core immediate-update flow when supported. On Android TV, where Play Core may not offer an update, they also consult the repository's published `versionCode` and latest release announcement; an upstream announcement is explicitly identified as such and **only opens the Play listing**, never a GitHub APK. A Play-confirmed update starts Play's managed flow, falling back to the listing when unsupported. Dismissal is per Play version-code/announcement pair. Do not assert that an announced upstream release is already distributed to every Play device or rollout.
## Browse-first screen loading and directional hold navigation (October 15, 2026)

Home, Movies and Series publish their row shells and accept D-pad focus before any optional custom Hero catalog preview is fetched. Each newly created category/genre row starts with **zero visible titles**, rather than synchronously waiting for a fixed poster count. Two Home catalog rows are warmed in parallel with other work. Movies and Series probe all enabled genre rows through a bounded three-worker FIFO queue: early rows are prioritized, but a slow provider releases its worker independently instead of stalling a fixed index lane. Ordinary row pagination reveals up to 50 additional items per buffered batch (`ROW_BATCH_SIZE`); external add-on catalog pages can contain fewer/more items, and genre classification can move their titles between rows. The `initialDashboardBatchSize` constant does not currently govern initial items.

`DpadRepeatGate` still debounces duplicate first presses but passes Android's native long-press key repeats without artificial 150/200ms delays. Home's navigation listener never consumes directional events; it pauses all cinematic trailer resolution and playback while directional events continue, preserving still backdrops/posters and resuming preview requests only 750ms after the final key-down (then the existing 500ms preview startup delay). Cancelling preview fetches never gates row focus or catalog pagination.

## Empty rows, baked addons, personal rows and taste (October 22, 2026)

**Empty rows are not composed.** Home, Movies and Series drop a row from the `LazyColumn` until it has a title (a row that was shown and later emptied stays mounted, invisible, for focus stability). Their first catalog fetch is started by a screen-level effect instead of by the absent row item. Details (cast, studios, recommendations), Studio rails, Cast filmography, Queue rows and the Watchlist cloud-storage section render nothing when empty, and focus fallbacks are only handed out when a populated row will carry them.

**Baked addons** (`data/profile/BakedAddons.kt`): Cinemeta, Streaming Catalogs and Torrentio ship their manifests in `assets/baked_addons/`. A fresh profile gets their catalogs even when the providers are unreachable; an existing profile whose baked addon was stored without catalogs is repaired on load (`withBakedCatalogs`). Stremio account sync, and Reset Stremio sync, never prune them.

**Personal rows** (`data/recommend/SimilarTitlesEngine.kt`): Home shows up to two rows, *More like ‹most recently watched›* and *Because you watched ‹a different title›* (a Loved one if any, otherwise a random recent one, so it varies). Candidates come from TMDB recommendations, TMDB similar and Trakt related (public endpoint; no Trakt sign-in needed), ranked by source agreement, genre overlap with the seed, rating proximity, overall quality and the genres of Loved (×3) / Liked (×1) titles. Watched, disliked and seed titles are excluded; a row with fewer than six titles is omitted. Rows are rebuilt only when the recent-history head changes. They are appended to `mixedRows` (so their indices never shift catalog focus keys) and sorted to the top.

**Taste** (`data/taste/TasteStore.kt`): the card long-press menu adds **Love**, **Like** and **Don't like** (choosing the active one again clears it). Verdicts are per profile. Love also adds the title to the Trakt library when connected. Don't like removes the title from every Home/Movies/Series row and the Hero, the Details and screen-level *More like this* lists (matched by a stored `tmdb:` id) and Queue suggestions (including refills) immediately; Search is deliberately unaffected. Details shows the same three verdicts as buttons.

**Disabling addons:** Add-ons → Installed has an enable/disable toggle per addon (`AddonDao.setAddonEnabled`), so a baked or any other addon can be switched off without uninstalling.

**Native Torrentio configuration** (`data/debrid/TorrentioConfig.kt`, `ui/addons/TorrentioConfigScreen.kt`): Torrentio's settings live in its install URL (`/<key=value|…>/manifest.json`). The Add-ons Configure action for Torrentio (catalog card or Installed row) opens a native dialog instead of the web page: debrid service + key (pre-filled from the primary connected debrid account; any other connected service's key fills in when picked), debrid link/catalog options, sort, results per quality, excluded qualities, providers and priority languages. Saving installs the new URL first, then removes the previous Torrentio, so a failure keeps the old configuration; the bundled manifest covers an unreachable provider. Torrentio is matched by host, so any configured URL counts as the baked install; existing profiles receive the stock install once (`baked_torrentio_v1_`), and an account's own Torrentio replaces the stock one on Stremio import.

## 3D sources on 2D displays (October 22, 2026)

Android exposes no HDMI 3D capability to apps, so `Stereo3d` (`ui/player/base/Stereo3d.kt`) treats the display as 2D unless **Settings → Playback → Disable 3D** is off. When the current source's title/filename names a frame packing (side-by-side, or top-bottom with `HOU`/`Half-OU`/`TAB`; a bare "3D" names no packing and does not prompt), the player asks once per source whether to play in 2D. "Play in 2D" (`PlayerPlaybackController.setStereoLayout`) clips `exo_content_frame` to the left/top eye, scales it to fit the screen at the eye's real aspect (full packings halve the frame aspect; half packings keep it) and centres it; the transform is recomputed on every layout change and replaces the encoded-zoom resize mode while active. The 3D control button cycles detected packing → the other packing → full frame.

**Blu-ray 3D (MVC).** MVC stores the second eye as extra H.264 NAL units (prefix 14, subset SPS 15, slice extension 20). Some TV-box decoders (Rockchip) decode both views and present them alternately, so the picture jitters between viewpoints. While *Disable 3D* is on (the default), every progressive extractor is wrapped by `MvcStripExtractorsFactory`, whose video `TrackOutput` buffers each H.264 sample and drops those units before any renderer sees it; plain H.264 never contains them, and encrypted or multi-part samples pass through untouched.

### Playback exit and queued navigation (October 29, 2026)

The player deliberately distinguishes **explicit exit** (remote Back, X, source-error exit) from **automatic completion**. Both persist the session, but only automatic completion can call `QueueManager.advanceAfterPlayback` through `PlaybackSessionViewModel.end(advanceQueue = true)`. The explicit X is the final player control and invokes the same direct save-and-exit callback as Back, bypassing the Back-key controls/sources-panel hierarchy. Automatic same-series next-episode switching stays inside Player and does not add Details pages.

When the queue advances to a different item, `BackStackOps.queueAdvance` removes the Player, collapses prior queue-generated Details pages (including historical saved stacks), then opens only the current item's Details. If advancing within the original show, it replaces that Details entry instead of duplicating it. A newly assigned Details instance prevents reuse of consumed one-shot queue autoplay state; earlier user-opened Details navigation remains intact. On exit from the queued item, Back lands on its Details rather than walking past titles that never played.

The optional persistent cinematic Home/Movies/Series layout shows the complete preview stage (maximum 420dp) and reserves only one landscape row beneath it. Remaining rows may be partially visible; row headings use the smaller TV title style. The non-persistent scrollable Hero mode is unchanged.
