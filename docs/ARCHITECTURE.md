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
Offcloud, Debrid-Link, EasyDebrid, Torrin; several can be connected per profile, the first being primary, and the library merges all of them), `tmdb` (metadata enrichment), `trakt`
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


## Watch-history provenance and unplayed-bookmark protection (October 29, 2026)

The single source of truth for whether a history row represents a real play is `WatchHistoryEntity.hasPlaybackEvidence()`: `watched=true` (explicitly marked watched or completed) or at least 5,000 ms of measured position. **A Stremio Library item is not a playback event.** `StremioLibrarySyncManager` now creates Watchlist bookmarks independently but cannot write watch-history rows for a zero-progress/zero-count state or use a newer bookmark modification time to overwrite legitimate local playback. It honors `timesWatched` and `flaggedWatched` for explicit completion. The Stremio outbound library view still syncs unplayed bookmarks, but takes only playback-confirmed history to prevent snapshot/sync feedback loops.

`TraktSyncManager` no longer manufactures `WatchHistoryEntity(position=0, duration=0)` for percentage-only `/sync/playback` entries; without an actual duration those placeholders cannot support accurate resume or prove a local play. Trakt's explicit watched-history and local scrobble reconciliation remain supported. Wutch Up Next requires measured resume progress for an in-progress row or an actual last-watched timestamp for a Next Up series row. The player retains its independent 5-second persistence threshold.

Continue Watching, `SimilarTitlesEngine` (More Like This / Because You Watched), history-only Watchlist Library entries, and history-based Queue suggestions all apply the same evidence rule. For existing installations, `AddonDao.pruneUnplayedHistory()` removes legacy unplayed rows on the Home load under the active-profile runtime lock; `replaceRuntimeState` filters both incoming snapshots and live same-profile merges, and `captureRuntimeSnapshot` saves only confirmed entries. No bookmark is removed, and explicitly watched entries with unknown duration remain valid. A future imported title can enter history normally once actual playback occurs.

**Validation:** regression tests for unplayed Stremio bookmark import, newer metadata not overwriting measured playback, 0/0 cleanup without losing saved bookmarks or watched markers, snapshot non-resurrection, and Continue Watching rejection. Physical device and connected-account checks remain pending.

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

## Home card draw cost (November 19, 2026)

A Home row holds hundreds of cards, so per-card work is kept small (issue #401: an ANR inside `ViewRootImpl.performDraw` on a low-memory TV, in the Compose draw tree and View clip invalidation).

- **No no-op layers.** `graphicsLayer { clip = false }` is the default and only forced an extra render layer, so it is gone from the row `Column`, the `LazyRow` and each item/card `Box` in `InfiniteLoopRow`, `LumeraCard` and `LumeraLandscapeCard`. A `graphicsLayer` stays only where it does work (the View More fade sets `alpha`).
- **One clip per card.** The TV `Surface` already clips its content to its (focused) shape and paints the surface colour, so the poster/backdrop image no longer adds its own `clip` and `background`. The thin progress bar paints rounded ends with `background(color, shape)` instead of `clip`. The watched corner triangle is built in `drawWithCache`, once per size rather than once per draw.
- **Shared scrim.** The landscape card's 8-stop title gradient is one top-level `Brush`; the logo is a plain `AsyncImage` that falls back to the title `Text` on error, not a `SubcomposeAsyncImage`.
- **The long-press menu is lazy.** Cards use `LazyMediaCardActionMenu`, which composes `MediaCardActionMenu` (and so `hiltViewModel()`, its state and `LaunchedEffect`) only after the menu is first opened, then keeps it composed so the soundtrack dialog and the popup's exit animation still work. The `tt\d+` Regex is a top-level value used by `soundtrackImdbIdOf`. Menu behaviour is unchanged; `GlassSidebar` still uses `MediaCardActionMenu` directly.

Not verified on a device: the visual result should be identical (same shapes, scrim and badges), but the ANR itself needs a low-memory TV to confirm.

## Grid focus and demand-driven loading (October 29, 2026)

Streaming-service and category full-screen grids (`GridViewScreen`) retain a fixed header but deliberately route D-pad Up from the first poster row to the selected sorting chip when the grid is sortable; Up again reaches Back, and Down from sorting returns to the previously focused poster. Non-sortable grids go directly from the first row to Back. These paths use distinct focus requesters and the currently measured LazyGrid column count, rather than treating every grid as six columns. The grid uses `LazyVerticalGrid` virtualization, stable card focus memory, pagination near the end, and only three poster prefetch requests to each side of the focused card instead of twelve.

Watchlist's Library already uses one virtualized `LazyVerticalGrid`, so its entire Library is **not** eagerly composed or painted. The earlier metadata scheduling still launched a first-two-rows lookup on every Library emission and mapped visible raw indexes against poster indexes despite full-span Queue/Cloud/Sort header items. Now a grid-visible-key planner (`watchlistVisibleMetadataKeys`) selects only currently composed `library_<type>_<id>` entries whose artwork/identity needs enrichment, caps each visible batch at four **eligible** candidates and limits remote metadata resolution to two concurrent requests. Cooldown/in-flight/hydrated items are skipped before applying that budget; each completed lookup emits a scheduler signal so the next visible candidates are considered even if the grid has not scrolled. Successful but still-incomplete metadata cannot hot-loop because every attempt has a short retry gate. Queue, Cloud and Sort keys never trigger metadata requests. Focus can still explicitly request enrichment for the selected poster. All stored Library titles remain available in their original order; this is network-demand bounding, not item deletion or an arbitrary hard list cap.

**Device checks outstanding:** watchlist with thousands of saved/history items and slow metadata sources, quick D-pad scroll, Netflix/other movie and series hub sorting/Originals selection, sort-to-grid/down/back traversal, paginated loading and profile restore; no Android build was executed in this authoring session.

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



## Native VPN engine boundaries (removed)

Removed on October 30, 2026. Google Play rejected the app under its VpnService policy, which reserves VpnService for apps whose core function is VPN. The WireGuard, IKEv2, OpenVPN and SoftEther engines, their settings UI, the `native-openvpn`/`native-softether` modules and the `vendor/` VPN submodules are gone from every build. Recover them from git history (before this change) if VPN ever returns as a separate app.

## Torrin debrid and TorrentClaw discovery (October 29, 2026)

Torrin is a distinct `DebridProvider.TORRIN` connected through `DebridManager` and the same encrypted per-profile service key store used for Real-Debrid, TorBox and others. Torrin's `https://api.torrin.app/rest/1.0` implements the documented RealDebrid-compatible `/user`, `/torrents`, `/unrestrict/link`, and torrent deletion endpoints. `TorrinService` reuses the tested RD-shaped response parser while intentionally omitting the separate `/downloads` endpoint (not documented by Torrin). Cloud items retain the Torrin provider identity so library aggregation, stream resolution and deletion dispatch to the correct service. The Torrentio editor and auto-injection helper exclude Torrin because Torrentio has no Torrin config field.

TorrentClaw is **not** a debrid provider. Its `TorrentClawIntegration` is explicitly opt-in under Settings → Integrations and scoped per profile. `DebridHttp` sends URL-encoded GET requests to `https://torrentclaw.com/api/v1/search`; an optional API key is stored in Android encrypted preferences and passed in the Authorization header, never in a URL. Results without canonical IMDb IDs or supported movie/series types are dropped. `SearchViewModel` merges TorrentClaw metadata with installed-addon search results, deduplicating by type + IMDb ID; one provider's failure does not erase the other's results. Successful installed-addon results are published immediately without waiting for the optional TorrentClaw HTTP call, so a slow provider cannot hold the normal Search UI behind a spinner. Playback still resolves through installed Stremio addons; TorrentClaw magnets are not treated as direct streams. Profile deletion clears its enabled flag and key.

API contracts: https://docs.torrin.app/api-reference/introduction ; https://torrentclaw.com/api/docs#/Discovery/searchContent .

## Settings scrolling and repository discovery ownership (October 29, 2026)

Settings owns two independent bounded scroll surfaces: the section sidebar scrolls on both compact and TV layouts, and each content section scrolls as appropriate. Personalization, Playback, Sort & Filter, About, Integrations and the Watchlist's settings list already scroll; Screen Editor's lazy list owns its vertical overflow. Theme selection now has a single full-height lazy grid containing both header controls and theme cards, avoiding clipped fixed headers; compact theme tabs scroll horizontally. The Theme editor's compact layout scrolls its stacked panels, and its wide layout scrolls only the color-controls panel while retaining the preview.

`AddonCatalogRepository` remains the single state owner for built-in and custom catalog sources; `AddonRepositoriesViewModel` exposes that live state in **Settings → Integrations**, where the repository manager follows Debrid Service in the original order (the screen itself scrolls). This location is intentional: installing an addon and configuring where the browser discovers addons are separate operations. The Add-ons root no longer adds or removes catalog repositories.

Saved `SavedAddonCollection` JSON remains unchanged. A separate `disabled_collection_urls` preference stores custom source disablement, so older saved repositories start enabled without rewriting or losing their definitions. `Repositories.enabledCollections` is used for the All feed, and only those sources appear in the Add-ons tab row; a selection of a since-disabled custom source is rejected and redirected to All. Built-in source toggles retain their original preference. New built-in discovery choices are AIOMetadata (a configurable metadata addon linked to its official setup page) and the GitHub stremio-addon topic (repository index queried via GitHub's public search API, with a link to the topic itself when rate-limited). Their `AddonCatalogItem.discoveryUrl` rows offer setup/project links, and deliberately do not claim to be installable manifests. The standard manifest URL install flow is unchanged. Disabling a discovery repository does not disable its installed addons, which are managed separately through Installed.

## Add-ons, adaptive discovery and navigation (October 15, 2026)

Add-ons renders a single **Browse Add-ons** heading followed by horizontally scrolling compact **All**, **Installed**, enabled **Official**, **Community**, **3rd Party**, enabled custom repository tabs (named by their actual catalog), and **Install URL** controls. Management is never shown in Add-ons. The previous above-heading Browse/Installed/URL header is removed. The All feed concurrently reads the available official, community, third-party and saved collection sources; one unavailable provider does not discard successful providers. Each installed configuration is selected for deletion only by a matching normalized transport endpoint. Normalization removes the trailing `/manifest.json` but **retains the configured path and query string**; manifest ID is never authority for removal. Exact uninstalls still require confirmation. The source/header, search, installed delete, and catalog actions are tagged in the existing `FocusMemory`; switching Installed keeps the header composed so back-stack restoration does not discard focus. Ambiguous URL selection never silently deletes a different configuration.

Movies/Series keep their existing **independently paginated genre-first model**; genre shells stay in the LazyColumn while fetching so rows after Documentary/Drama remain visible even when early sources are empty or late. Genre rows are ordered by the current profile's watched-title and saved-watchlist overlap with their loaded content (watch = 2, saved = 3). Ties retain the original stable order and original per-row focus index. Each genre's title ordering is independently persisted in `illumera_screen_genres` and configurable in Screen Editor as catalog order, rating, newest, oldest or alphabetical direction; this does not change catalog pagination or media identities.

The Hero is still a regular scrollable LazyColumn item. Both Up back to Hero and Down to the first populated media row scroll that target into composition, wait for a focus requester, then move D-pad focus. Empty genre shells cannot be the first focus destination. Selecting a navigation item closes the shell menu and navigates on the same action. Details' Episodes and Sources symbols now match the player. Sound-enabled cinematic trailers and full-screen trailer playback use 60% player volume; other playback remains at 100%.


### Release-channel update notifications (October 15, 2026)

The app uses its **compiled release channel**, not cross-store sideloading. GitHub release builds use `AppUpdateManager`: the stable GitHub Releases tag provides major/minor/patch and the matching signed APK filename supplies the fourth build component (e.g. `0.12.46` and `illumera-0.12.46.1-release.apk` yield `0.12.46.1`). HTTP checks are serialized and throttled to once per 30 minutes on foreground resume, while the first launch requests a check. A newer version triggers the existing update dialog after the splash. Dismissing affects only that specific version for the current session, not later releases; the existing Settings opt-out remains respected. Downloads continue to require SHA-256 when published and the installed signing certificate.

Play builds ask **only Google Play** whether an update exists. Play Core's immediate-update flow answers where it is supported. On Android TV, where Play Core can't, the build reads the version Google Play's own store listing shows (`parsePlayListingVersion`, which keys on the `[[["x.y.z"]],[[[sdk]]` entry) and raises an update only when that is newer than the installed `versionName`; pressing Update opens the Play listing. A newer GitHub release or `version.properties` is never consulted by a Play build and never raises a dialog: it says nothing about what Play has published. A listing that can't be read claims nothing. Dismissal is per Play version-code/listing-version pair.
## Browse-first screen loading and directional hold navigation (October 15, 2026)

Home, Movies and Series publish their row shells and accept D-pad focus before any optional custom Hero catalog preview is fetched. Each newly created category/genre row starts with **zero visible titles**, rather than synchronously waiting for a fixed poster count. Two Home catalog rows are warmed in parallel with other work. Movies and Series probe all enabled genre rows through a bounded three-worker FIFO queue: early rows are prioritized, but a slow provider releases its worker independently instead of stalling a fixed index lane. Ordinary row pagination reveals up to 50 additional items per buffered batch (`ROW_BATCH_SIZE`); external add-on catalog pages can contain fewer/more items, and genre classification can move their titles between rows. The `initialDashboardBatchSize` constant does not currently govern initial items.

`DpadRepeatGate` still debounces duplicate first presses but passes Android's native long-press key repeats without artificial 150/200ms delays. Home's navigation listener never consumes directional events; it pauses all cinematic trailer resolution and playback while directional events continue, preserving still backdrops/posters and resuming preview requests only 750ms after the final key-down (then the existing 500ms preview startup delay). Cancelling preview fetches never gates row focus or catalog pagination.

## Home: a focus move does not rebuild the rows (November 19, 2026)

A D-pad move used to publish a new `HomeState` (it held `lastFocusedKey`), and `HomeScreen` rebuilt
everything from it on the main thread: every row filtered for watched and disliked titles, the
Movies/Series genre rows sorted (with a SharedPreferences read per row), the streaming-service sorts
applied, and a new list instance handed to every effect keyed on the rows. Now:

- The remembered card is its own flow, `HomeViewModel.lastFocusedKey` (a `StateFlow<String?>`), not a
  field of `HomeState`. A focus move changes only that flow. Readers: `HomeScreen` (passes it to the
  rows to restore focus onto the remembered card) and `mergeRowHead` (a row the viewer is on keeps
  its cards). Row and vertical scroll memory were already outside the state (`PositionMemory`).
- What a tab shows is derived once per real data change in the ViewModel, not in composition.
  `HomeViewModel.displayState(tab, profileId)` returns a `StateFlow<HomeState>` built by
  `displayStates` (`ui/home/HomeDisplay.kt`): `combine(state, rowSortOrders)`, derived by a
  `HomeDisplayDeriver`, `distinctUntilChanged`, `flowOn(Dispatchers.Default)`. `HomeScreen` only
  collects it. The derivation is the former `displayStateFor` / `withPersonalRows` /
  `withoutDisliked` / `withRowSorts` chain, unchanged in effect: Home hides watched titles, every tab
  hides disliked ones, Movies and Series sort each genre row by its stored order, streaming-service
  rows follow their header chip, Home gets its personal rows.
- The deriver remembers the inputs of its last result (rows, mixed rows, hero, watched and disliked
  ids, personal rows, header sorts, and the stored genre sort of each row title) and returns the
  **same row list instances** while they are equal. Unrelated state changes (Continue Watching
  progress, enrichment of another title) therefore emit a state with the rows untouched. The
  genre sort setting is read once per row title per derivation, off the main thread; the Dashboard
  Editor saves it and calls `HomeViewModel.invalidate()`, which emits a state and so re-reads it.
- One derivation is live at a time (the tab in front): asking again for the same tab restarts it and
  keeps its last value, so returning to Home from a detail page shows the rows at once; a different
  tab or profile starts afresh.
- The two `visibleMixedRows` effects in `CinematicLayout` (which rows are on screen; art
  preload of the first three titles of the visible and next rows) are keyed on `rowOrderKeyOf` /
  `artPreloadKeyOf` (row ids in order; first-title ids and backdrops), and read the current rows
  through `rememberUpdatedState`. A title appended to some row no longer restarts them and re-requests
  up to 15 backdrops.

`HomeScreen` still recomposes on a focus move (the rows need the live key to put `entryRequester` on
the remembered card), but it now only compares unchanged list instances: no allocation, filtering,
sorting or preference read. Tests: `HomeDisplayTest` (filtering, sorting, stable instances, flow),
`HomeViewModelFocusTest` (a focus move emits neither a new state nor new rows).

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

The player deliberately distinguishes **explicit exit** (remote Back, X, source-error exit) from **automatic completion**. Both persist the session, but only automatic completion can call `QueueManager.advanceAfterPlayback` through `PlaybackSessionViewModel.end(advanceQueue = true)`. The explicit X is the final player control and invokes the same direct save-and-exit callback as Back, bypassing the Back-key controls/sources-panel hierarchy. Automatic same-series next-episode switching stays inside Player and does not add Details pages. Both autoplay and manually selected episodes rank fetched stream candidates on `Dispatchers.Default`, never on the UI thread, because language/quality parsing compiles regexes and can stall input on slower Android TV devices.

When the queue advances to a different item, `BackStackOps.queueAdvance` removes the Player, collapses prior queue-generated Details pages (including historical saved stacks), then opens only the current item's Details. If advancing within the original show, it replaces that Details entry instead of duplicating it. A newly assigned Details instance prevents reuse of consumed one-shot queue autoplay state; earlier user-opened Details navigation remains intact. On exit from the queued item, Back lands on its Details rather than walking past titles that never played.

The optional persistent cinematic Home/Movies/Series layout shows the complete preview stage (maximum 420dp) and reserves one landscape row plus the next row's heading and the top of its cards beneath it (250dp), so more content is always visibly below. Further rows may be cut off; row headings use the smaller TV title style. The non-persistent scrollable Hero mode is unchanged.

## Debrid load progress and earlier debrid sources (November 16, 2026)

While a source is loaded into a debrid service, the player's loading overlay shows its live state under the step feed from the first moment: "Debrid · adding…" with an indeterminate bar while the torrent is being sent, then "Debrid · N%" with a progress bar (0% once the service has accepted it, then the library's percent). It sits just under the feed, clear of the control bar. `TorrentProgress.debrid` marks progress that belongs to a debrid download (from `DebridManager.resolveMagnet` and from the still-downloading wait in `onSuspectSource`); the feed's "Preloaded 25/50/75%" milestones stay local-torrent only, and the debrid status line no longer embeds the percent so it stops producing a feed step per tick.

`PlaybackSessionViewModel` remembers every source it sent to debrid that has not delivered (`debridAttempts`, per playback; cleared on a new playback, episode switch or exit). When the viewer moves on (ranked fallback, or picking another source in the player) it first checks all of them once with `DebridManager.readyUrlFor`, a single non-waiting look at the account library. A ready one is only **held**: the next source is tried first, and the held one plays when that source fails (or at once if no source is left to try), before any source further down the list. A source the **viewer** moves on from (a manual pick in the player) is dropped from this memory and never returns on its own: only sources the app gave up on, such as a debrid wait that ran out, stay eligible.

## Not installed filter on Add-ons (November 16, 2026)

The Add-ons tab row has a **Not installed** toggle after **Installed**. It hides catalog entries whose normalized endpoint (`normalizeCatalogTransportUrl`, same rule as removal) matches an installed addon, so a configured variant with a different query string still shows. It applies to whichever catalog is selected (All, a built-in source or a custom repository) and composes with search (`filterCatalogItems`). Choosing it from the Installed list opens the catalog with the filter on; choosing it again turns it off. When it leaves nothing, the list says every addon in the catalog is already installed. State lives in `AddonsScreen`; the control is tagged `addons:not-installed` in the screen's `FocusMemory`.

## Two button highlights only (November 16, 2026)

A button has two highlights: **active** (the cursor: accent border and tinted fill, with the label in the normal text color so it stays readable on the tint) and **selected** (a tab, choice or toggle that is on: a brighter neutral fill and border, deliberately not the accent). Only the cursor uses the accent, so nothing at rest can be mistaken for it, and nothing else is lit at rest. The old "main action" glow (`isPrimary = true` on Update, Save, Confirm, Connect, Retry, Install and the like) is gone, because it read as a selection nobody had made. `VoidButton` takes `isSelected`; `ButtonEmphasis` is `Normal`, `Selected` and `Destructive`. `IntegrationButton` and the Theme editor's `EditorButton` no longer take an emphasis flag. Destructive buttons are ordinary at rest and turn red only under the cursor.

The same rule holds outside buttons: nav drawer and top bar current-page markers, season and episode tabs, the player's audio and subtitle choice chips, settings segment and swatch choices, sort lists, the theme card title, and switch tracks and checkboxes show "on" with a neutral tint (`onBackground` at about 0.2 to 0.6 alpha), never the accent. Status colors (a connected integration, the reorder banner) are not interactive highlights and keep the accent.


## Source controls while a source is still loading (November 16, 2026)

The player's own source list exists only once it has a video, so during a debrid or torrent wait (no URL yet) the **Next source** and **More sources** controls were hidden even though the session already knew every candidate. `BasePlayerScaffold` now takes `availableSources` (the session's `selectedPlayerSources`) and uses it until the player has loaded its own. Before load, Next source calls `PlaybackSessionViewModel.skipSourceBeforeLoad()` and a pick from the list calls `selectSourceBeforeLoad(id)`; both go through the same `openRankedSource` path as ranked fallback, and both count as the viewer leaving the current source, so it is dropped from the earlier-debrid-sources memory and not brought back on its own. Back still exits as before: the back-key source-list step keeps using the player's own list.

## Debrid status in the source list (November 16, 2026)

Source lists (the player's **Select Source** panel and a Details page's source sidebar) mark torrent sources by where they stand in the viewer's debrid services, each with its percentage in the row: **In debrid · 100%** (a plain outline on the row, never the accent), **Adding to debrid · 0%** (indeterminate bar) and **In debrid · N%** (progress bar) while a service is still downloading. `DebridManager.sourceStatuses` is the one source of truth, keyed by lowercase info hash: the accounts' libraries (`refreshSourceStatuses`, one read per open list, repeated every 5 seconds while it is open) overlaid with what `resolveMagnet` is sending right now. When a send ends, what the service holds is kept until the next library read replaces it, so a row does not flicker. `PlaybackSessionViewModel.debridStatuses` / `refreshDebridStatuses` expose the flow, and `MainActivity` hands them to `PlayerScreen` and `DetailsScreen` as parameters (`debridStatuses`, `onRefreshDebridStatuses`), never through `hiltViewModel()` inside the composables, so a screen rendered in a unit test without Hilt still composes. `GlassSidebar` takes `debridStatuses` and `RawSourceItem` draws it. Direct-URL sources have no info hash and are not marked.

## Debrid list in the loading overlay (November 16, 2026)

While a source loads, the overlay lists **every torrent source this playback sent to debrid** immediately under the loading text, in the same type (`bodyMedium`): one row per source, `label · N%` (or `adding`, `downloading`, `100%`, `not added`) with its own progress bar. Rows are kept in `PlaybackSessionViewModel.debridSources` in the order sent and cleared with the playback; unlike the earlier-debrid-sources memory they keep the sources the viewer moved on from, since they are a record, not sources that may be loaded. Each row's state comes from `DebridManager.sourceStatuses` by info hash. Every library read the manager makes (the wait loop polls every 5 seconds) refreshes all sources, and the overlay reads again every 5 seconds while any row is listed. The overlay shows the 8 most recent rows and folds older ones into "+N more". The single "Debrid · N%" readout remains only as a fallback for a magnet with no info hash.


## About → Check for Update (November 16, 2026)

About has one **Check for Update** button for both builds. It runs `ManualUpdateCheck`, which calls the build's own check (`AppUpdateManager.checkForUpdate(force = true)` for a GitHub build; `PlayStoreUpdateManager.checkForUpdate` for a Play build, waiting up to 20 seconds for Play Core, or on TV the store listing, to finish) and emits a `ManualUpdateResult`. `MainActivity` collects it. **Available** clears any earlier "Later" and the popup opt-out for this prompt and shows the existing update dialog (GitHub: Update / Later / Don't show again; Play: Update / Later). **NoUpdate** shows a **No new updates** dialog with **Close** and **Play Store** (opens the Play listing), plus the failure message when the check itself could not finish. A download or install already under way reports nothing, because its own dialog is showing. Launch and foreground checks are unchanged and stay quiet when there is nothing to say.

## About: Change Log, User Guide and README text

About (Settings -> About) shows, top to bottom: Version, Check for Update, **Change Log**, then
**User Guide** and the text under it.

- Content is never duplicated in code. `syncAboutAssets` (app/build.gradle.kts) copies
  `README.md`, `docs/USER_GUIDE.md` and `distribution/whatsnew/whatsnew-en-US` into the app's
  `assets/about/` on every `merge*Assets`; `AboutContent` reads them.
- The text block shows the README intro (title to first image, `AboutContent.introOf`). The User
  Guide button swaps it for `USER_GUIDE.md` and back (Selected highlight while on).
- Change Log opens a dialog with the bundled what's-new text, the same file Play shows.
- Each paragraph is focusable so D-pad Down walks (and scrolls) the page on a TV.
- To change the text, edit the repo documents; there is nothing else to update.

## Hero preview: web videos play from the middle

A Home title that is itself a video on a website (a YouTube video, a video site) previews as
that video, from its halfway point, instead of a trailer.

- Candidate: an addon's own catalog title (`MetaItem.addonBaseUrl` set) whose id is not an IMDb
  id (`isWebVideoCandidate`). Cinemeta-style titles keep their trailers.
- `HomeViewModel.resolveWebVideo` asks only that addon for the title's streams (7 s), and
  `webPreviewSource` takes the first YouTube or plain http(s) stream. Torrents, header-gated and
  not-web-ready streams are skipped. YouTube goes through `YouTubeExtractor` as trailers do.
  No such stream: the trailer path runs as before.
- `startsAtMidpoint` is true for titles resolved this way. `TrailerPreview` then seeks to half the
  duration once it is known (`seekToMidpointWhenReady`; live streams are left alone). Home provides
  it through `LocalPreviewStartsAtMidpoint`, so the hero, the rows' previews and the persistent
  preview all behave the same.

## Home rows keep loading past the first batch

Row loading is demand-driven (`RowDemandPlanner`): visible rows, then the next few below. The
screen reports only rows it has composed, and a row that never had a title is not composed, so the
report ended at the last loaded row and the rest of Home was never asked for. `padLookahead` tops
the rows-below list up with the next unloaded rows (in screen order, skipping exhausted or
repeatedly failed ones) whenever fewer than three loaded rows remain below the viewer. Loading
still follows the viewer: it only reaches further as they near the end of what has loaded.

## Memory: a monitor that dumps the least useful first

`MemoryMonitor` (`data/memory`) samples the Java heap every 3 seconds, and also listens to
Android's `onTrimMemory`. Memory is **High** at 70% of the heap and **Critical** at 85%. High must
show on two samples in a row (uncollected garbage can make one reading look worse than it is);
Critical acts at once. After acting it waits out a cooldown (15 s High, 5 s Critical), because the
heap only shows a trim's effect after the next collection.

Whatever holds a cache registers a trimmer with a rank, and the monitor asks them lowest rank
first:

| Rank | Holder | High | Critical |
|---|---|---|---|
| 5 | Trailer buffered for the next title (`TrailerPreloader`) | released | released |
| 10 | Decoded images (Coil memory cache) | half | all (reload from the disk cache) |
| 20 | Home data (`HomeViewModel.trimForMemory`) | trailer URLs, most of the art cache, what was fetched ahead and enrichment for rows not near the viewer | also the rows far from the viewer cut back to their first 10 titles (farthest first; they restart their catalog when reached and their scroll memory is cleared), and the snapshots of screens already left |

"Near" is what the viewer is on, what is on screen, the rows just below, and the hero's row. The
titles a row shows are otherwise kept (a row never shrinks while the viewer is near it), so
scrolling back is instant. Also bounded all the time: the art-fallback cache (300 titles, least
recently used out) and each preview player (`newPreviewPlayer`: 10 s / 12 MB buffer instead of
ExoPlayer's 50 s default, tens of megabytes of heap per player).

To add a cache: `memoryMonitor.register(rank) { pressure -> ... }`, and close the result when its
owner goes away.

## Screen Editor: move, hold for options, group by addon

- On a row in Settings -> Screen Editor, **OK picks the row up to move it** (▲▼, OK to put it
  down, Back to cancel). The options dialog (rename, layout, hide, hub settings) opens on a **hold**
  of OK or a touch (`Modifier.longPressClick`, `LONG_PRESS_MS` = 1 s, shared with the cards).
- **Moving several rows in a row.** There is no "reorder mode" banner. The row put down last
  (`lastMovedKey`) is where the next picked-up row lands: picking up jumps it right under that row
  (`pickupAnchor`, `DashboardViewModel.moveEditorItemAfter`), so building an order is pick, OK,
  pick, OK. Putting a row down sends focus back to the place it was picked up from
  (`returnFocusKey`: the row that moved up into the gap), ready for the next. The jump is off in
  the grouped view, where the display doesn't follow the order. The memory resets per tab.
- **Group by addon** (button above the Home list) folds each addon that has two or more rows into
  one line (`EditorDisplayItem.Group`) that OK opens or shuts. Hubs and single-row addons stay as
  they are. A group sits where its addon's first row was. Moving a row inside a group trades places
  with the next row of the same addon (`moveNeighbour`, `DashboardViewModel.swapEditorItems`),
  so a move never jumps over a header. Grouping is a view; the real row order is unchanged.

## Configuring addons

- Browse tab: Configure on a catalog item opens its page in the in-app browser (Torrentio keeps
  its own dialog).
- Installed tab: every addon served over http(s) gets Configure (`installedAddonCanBeConfigured`),
  opening `<addon>/configure` in the browser, unless the catalog knows its manifest isn't
  configurable. The page's Install button comes back as an addon link and goes to the install
  confirmation.
- If the device has no WebView, `MainActivity.openBrowser` hands the page to the device's browser.

## Play updates: the `play-release` issues

The Play build asks Play Core whether an update exists. Play Core does not run on Android TV, and
the public store page only shows the production track (while builds go to internal and alpha), so
a TV on an internal-track build used to be told "no update" while Play had one. Now, when Play Core
cannot answer, `PlayStoreUpdateManager.checkPlayListing` reads this repository's issues:

- The central Play release workflow (HereLiesAz/workflows, `android-play-release.yml`, step
  "Announce Play release") files one **closed** issue per build it publishes, labelled
  `play-release`, titled `Play release <versionName> (<versionCode>)`. Re-runs are not announced
  twice; a deferred publish announces nothing.
- The app lists the ten most recent (`GET /repos/<owner>/<repo>/issues?labels=play-release&state=closed`),
  takes the highest versionCode (`latestPlayRelease`; pull requests and other titles are ignored)
  and offers the update when it is above `BuildConfig.VERSION_CODE`. Play owns the install, so the
  dialog sends the user to the Play Store.
- Announcements read, nothing newer: up to date. No announcements yet (or the API unreachable):
  the store-page version is the last resort, then the Play Core error as before.
- GitHub builds are unaffected (they read GitHub releases; `newestApkAsset` picks the newest APK of
  a grouped release).

## Sources that are web pages

Some streams (video sites, some addons) hand over a page, not a file; the player reports
`ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED`. `ExoPlayerBackend.tryResolveWebPage` catches that for an
http(s) source and asks `WebPageVideoResolver` (`data/web`) for the video behind it, once per
source and address:

- A **YouTube** address goes to `YouTubeExtractor` (muxed, so audio comes with it).
- Any other page is fetched with a browser User-Agent. The video address is picked out of it:
  `og:video` / `twitter:player:stream` meta tags and `<video>` / `<source>` tags first, then any
  `.m3u8`, `.mp4`, `.webm` or `.mpd` address in its scripts and JSON (un-escaped, relative
  addresses resolved). `pickBestVideo` prefers HLS over MP4, the higher named quality, and drops
  previews, thumbnails, trailers and adverts. A page with no video is searched one player frame
  (`<iframe>`) deep.
- The video plays with the page as `Referer` (and its origin and the User-Agent), which most hosts
  require. The source's address and headers are replaced in the source list, so Retry and later
  switches play the video, not the page.
- It finds what a page links to. It does not get past a login, captcha, DRM or a player that
  builds its address from script at run time.

If nothing is found the player moves to the next source (auto-fallback) or shows the error. That
screen says "This source isn't a video file the player can read. It may be a web page." with the
code in brackets, and offers **Open page** (in-app browser).

## Stremio addon sync: add-only, with an explicit reset

Syncing addons from a Stremio account never removes a local addon. `IntegrationsViewModel.syncAddons()`
(signing in by email, register, Facebook or Apple; Integrations -> Stremio -> Add New Addons) and
`StremioAddonsViewModel.syncFromStremio()` install what the account has and this profile lacks
(and fill in placeholder rows), keep every local addon with its settings, and order the account's
addons first in its order, then the rest as they were.

The one destructive path is **Reset Addons to Stremio** (Stremio management dialog). It first
asks: it counts the local addons (not the built-in Cinemeta and Streaming Catalogs) the account
doesn't list, shows that number and warns that their settings go too, then calls
`syncAddons(prune = true)` / `syncFromStremio(prune = true)`. An account that lists no addons is
refused rather than reset to an empty profile. The Stremio account is never changed by either.
Separate from this, **Reset Stremio Sync** signs out and removes every non-built-in addon.

## In-app browser

On a TV, addon configuration pages and the integrations' sign-in and API-key pages open in the
app's own browser (`ui/browser`) instead of the TV's, which is often missing. **The in-app browser
is for TVs only.** On a phone, tablet or Chromebook the same calls open the device's browser; an
addon's `stremio://` Install link then goes to whatever app handles it, not to the app's own
install confirmation.

- `Context.openInAppBrowser(url, onAddonLink)` on a TV finds the activity (`InAppBrowserHost`,
  implemented by `MainActivity`), which shows `InAppBrowser` in a full-screen dialog. Off a TV, or
  with no host, it goes to the device browser. Only http(s) addresses open.
- Remote control: the D-pad moves a pointer, OK clicks (touch events sent to the `WebView`),
  pushing past the bottom edge scrolls the page, and Up past the top reaches the toolbar (Back,
  Forward, Reload, Close). Clicking a text field gives the page focus so the system keyboard types
  into it. Back closes the keyboard, then goes back a page, then closes the browser.
- Link catching (`classifyLink`): `stremio://` and manifest addresses are not loaded. They go to
  the caller's `onAddonLink` and the browser closes. The Add-ons screen passes
  `viewModel.prepareInstall`, so a configuration page's Install button ends at the install
  confirmation. Other schemes (`intent:`, `market:`, `file:`) are blocked.
- Callers: Add-ons Configure (`AddonsScreen`), every link in `IntegrationsScreen` (debrid API-key
  pages, wutch, Stremio terms) and the Stremio social sign-in page in `ProfileScreen`. The TV QR
  code for signing in from a phone is unchanged.

## Crash reports: what is filed and what is not

- `AppErrors` skips expected source failures: peer-metadata timeouts, offline errors, and
  "Your debrid service couldn't deliver this torrent" (the session moves to the next source).
- Managers that keep tokens in `EncryptedSharedPreferences` (`DebridManager`, `TraktAuthManager`,
  `WutchManager`) do their first read on IO, never in the constructor: Hilt builds them on the
  caller's thread, often main during composition, and decryption through the Android Keystore
  can stall long enough for an ANR. Their state flows start empty and fill in a moment later.
