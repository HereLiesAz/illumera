# Illumera feature acceptance — 2026-10-14

This is the source-of-truth checklist for the requested Home, Movies, Series, Addons, and Watchlist work. **Source changes alone do not count as verified completion.** Main contains feature PRs #221–#233; PR #234 addresses missing integration behavior.

| Request | Code state | Verification still required |
|---|---|---|
| Long-press menus open after 1 second and remain open after release | Poster/landscape cards use independent timers; PR #234 replaces key-repeat shortcuts in episode/source menus | Android TV: hold Center ~1 second, release, move to another option and select; ensure release does not click/dismiss |
| Native addon manager with Official, Community and stremio-addons.net | Present in the addon browser and catalog repository | Verify stremio-addons.net endpoint parsing, browsing, install, uninstall, and custom addon URLs |
| Default enabled addons: Cinemeta, Trailer, Streaming Catalogs, TMDB, SeedSphere, Stremio Stats | PR #234 seeds new profiles and one-time upgrades existing profiles | Check endpoint reachability and catalog/stream availability. Stremio Stats requires *personal account authorization*: generic preinstallation cannot create a valid per-user stats manifest |
| Home: top Hero + row-local cinematic previews | Implemented; PR #234 changes unset Hero to automatic, with explicit Disable | TV: Hero visible on a fresh profile; moving focus changes the preview above the corresponding row |
| Art shows until trailer first frame, trailer from requested addon | Trailer extraction and first-render-frame gating present; PR #234 keeps original backdrop during TMDB enrichment and adds conservative non-IMDb title mapping | Verify trailers on both IMDb and alternate provider IDs; unavailable trailer must never erase art or freeze focus |
| Movies and Series: same dual cinematic layout | Shared cinematic layout with top Hero, automatic unified source in PR #234 | TV focus/back behavior and scroll restoration |
| No Illumera Discover screen | No Discover tab; catalog sources feed Movies/Series | Verify no extra Discover item in navigation and no duplicate content heading |
| Combine enabled movie/series catalogs lazily **within each genre row**, with dedup; no unfiltered All Movies/All Series row | PR #234 introduced aggregation; follow-up genre-first branch removes the combined row and gives each enabled genre an independently lazy source cursor | Test addons with pagination and non-genre required extras; when an addon needs a missing configurable credential/argument, its catalog cannot be queried without setup |
| Every Stremio genre enabled by default, user can disable each genre from Settings → Screen Editor | 19 official movie genres, 22 series genres, plus addon-specific genuine genres; independent profile-screen disabled sets, scrolling checklist and enable/disable all | Verify selection persists across restarts and profile copies and appears only in chosen screen |
| Watchlist: Queue first + Stremio Library content and sorting | Main Watchlist uses Queue then Library with type and sorting controls | TV focus/scroll behavior, disconnected/offline state, sorting for watched/unwatched/times watched |
| Watchlist setup under Settings → Watchlist | Cloud-storage controls and default sort moved | Verify cloud play/delete routing, D-pad access |
| Two-way Stremio Library sync includes unplayed saved-only titles | PR #234 includes bookmarks in outbound reconciliation and baseline snapshots | Stremio sign-in and multi-profile integration tests, remote deletes, and local re-add protection |

**Current Watchlist order (October 15):** Suggested Next → Queue → compact Library sorting → poster grid; earlier Queue-first/Library-rail descriptions below are historical and superseded by the October 15 Watchlist follow-up.

## Release gate

Before describing this as done:

1. Merge only after reviewing PR #234's diff and all review comments.
2. Run `testDebugUnitTest`, `compileDebugKotlin`, `compileReleaseKotlin`, `compilePlayKotlin`, `lintRelease`, and APK/AAB packaging (using the repository's centralized CI workflow).
3. Install an APK on an actual Android TV device. Verify every D-pad, menu-release, Hero, trailer, genre, Library and settings case above.
4. Connect a test Stremio account and verify **both directions** of Library sync, including never-watched saved titles and series.
5. Verify stock addon endpoints in the currently deployed environment. If a provider requires user authentication (especially Stremio Stats), present configuration rather than silently pretending the addon is usable.

All items above still require runtime verification unless an attached successful CI log/device test says otherwise.

## October 14 regression follow-up (same PR)

Reported from an actual device: trailers never auto-play, episode lists absent from both Details and Player, and cinematic panels did not expand with row focus.

Source-level remedies added:
- The trailer resolver no longer cancels the extractor after 12 seconds; it allows the bounded multi-request extraction pipeline, caches signed URLs briefly, and prefers Android-TV-compatible H.264 previews. The first-frame observer no longer depends on making the player surface invisible, and pause/resume no longer clears the first-frame state. Art stays on screen while the new source loads.
- Series Details now enriches title-only metadata with a full Cinemeta episode index (only matching IMDb IDs or conservative title/year matches). Episode sidebar retry shows a loading state and an explicit missing-provider message. The same recovery is used by Player after a missing episode-list handoff.
- Cinematic previews are expanded via AnimatedVisibility directly above the newly focused poster row; active row changes trigger a one-time vertical scroll to reveal the expansion, rather than rendering a single persistent preview at the top.
- Unit tests cover episode recovery with numbered episodes and unknown IDs.

**Still unverified:** APK build/Media3 lint, actual YouTube extractor availability (the trailer addon can return a *YouTube search page*, not a playable trailer), Android TV video decoding and first-frame overlays, Details/Player on-device D-pad navigation and episode switching. A provider cannot conjure an episode or trailer that its upstream sources do not expose.

## Genre-first browsing regression correction

The previous combined All Movies/All Series row was explicitly **not** requested. Movies and Series now render a separate row per enabled Stremio genre and no catch-all or per-addon category rows. All genres are enabled by default; Settings → Screen Editor → Movies/Series → Manage Genres allows disabling each genre or all genres. Every genre row merges title results from every eligible enabled catalog source, deduplicates titles and fetches more batches as the row is browsed. Unseen rows defer network loading until they enter the vertical viewport.

TV acceptance: fresh install defaults to 19 Movie and 22 Series genre rows; no All Movies/All Series row is visible; disabling e.g. Horror only hides Horror on the chosen screen and persists after relaunch; no options that are actually years are displayed as genres; Movies and Series have separate choices; Home remains unchanged.

## Paired-category Home and mixed Watchlist Library

Home must preserve one row per distinct category, merging paired movies and series within it rather than combining all categories into a single row: Latest Movies + Latest Series → Latest, Popular Movies + Popular Series → Popular, Netflix Movies + Netflix Series → Netflix, etc. Identically named categories from enabled providers are combined and deduplicated within the row. Each category has independent lazy paging. Continue Watching, Hero and custom Hubs remain in place. Movies and Series tabs retain their genre-first rows.

In Watchlist, Queue remains first, followed by **one** deduplicated Movies & Series Library row, keeping saved titles and synchronized Stremio history together with its existing sort modes.

**Acceptance checks:** Distinct Latest/Popular/Trending/Netflix/HBO Max rows remain distinct; each contains both media types when available; same-title duplicates across addon sources appear once; a movie and series adaptation never collapse into one item; one-sided categories still appear; paginating one category does not modify another; Watchlist maintains one sortable combined rail and restores D-pad focus after removing a title. Build and on-device verification remain outstanding.

## Cinematic aspect ratio, gap and genre-filter regression

- Cinematic Home, Movies and Series content rows all render horizontal 16:9 cards, including Continue Watching and custom Hub rows. The Simple layout retains its own preferences.
- October 14 shortened the row-local preview from 360dp to 282dp; October 15 further shortened it to **184dp** without inflating its artwork. Title/description remain bottom-aligned; the landscape poster rail reserves 122dp rather than an unused 210dp.
- Movie/Series genre rows are restricted to genuine film/TV genres. Addon-supplied language, country, video-quality and release-recency options are not genres and must not appear as rows or Screen Editor choices. Separate regression tests cover these examples.
- Watchlist itself has Queue, Movies and Series Library rows, not a discover/genre-row source. No Watchlist titles should be removed to solve this issue.

Check the focused panel-to-card distance with short and long descriptions and with a TV remote. Android build/device validation is still outstanding.

## Paired Home categories and mixed Watchlist acceptance

- Home does **not** display one giant Movies & Series row. Latest Movies + Latest Series produce one **Latest** row; Popular Movies + Popular Series produce one **Popular** row; Netflix Movie + Netflix Series produce one **Netflix** row, etc. Unrelated categories and providers remain separate.
- Every paired row preserves the two original catalog URLs and its own pagination progress per source, deduplicates provider aliases and retains each item's original movie/series type.
- Watchlist Queue remains above a **single combined Library row**, which keeps the Stremio sorting controls and optional type filter. Series episode history must become one show card; movie and show with the same name must not merge.
- Acceptance requires CI plus Android TV focus, paging, sorting and profile-switch verification. These changes are committed but not runtime-verified.

## October 15 cinematic/genre/trailer fixes — verification pending

- Movies and Series omit empty genre rows from the vertical LazyColumn and D-pad focus chain; the original row index remains stable for poster focus keys. Three background lanes probe all enabled genres instead of requiring a user to focus empty rows to load later content. Initial genre probes cover up to two source layers, where paging is supported.
- Catalog items are routed to only their lead genre, prioritizing public Cinemeta metadata by IMDb ID or verified title/year instead of addon genre order. Items found in secondary-genre catalogs are transferred to their primary-genre row rather than disappearing; cross-row transfers deduplicate against existing destination cards. When the central lookup is unavailable, a stable sorted addon-genre fallback prevents relying on the first addon label. Unclassified items are withheld rather than duplicated across multiple rows.
- Cinematic previews are 184dp tall. Autoplay trailer candidates use the enabled addon, TMDB video metadata when configured, and public Cinemeta trailer records. Silent previews prefer muxed hardware-compatible video when available.
- Regression unit tests cover canonical vs addon genre order, canonical-source retry after network failure, and exact title/year matching for non-IMDb IDs.

**Unverified:** Android Gradle compilation, APK/AAB CI, real addon catalog availability, first-frame trailer playback on Android TV, and D-pad focus/restoration while rows appear asynchronously. Keep this release gate open until those checks pass.

## Inline cinematic poster follow-up (October 15, 2026)

The Hero Carousel remains the first ordinary LazyColumn item: it must scroll entirely away as the user navigates into the rows, and must **not** be pinned or persist above them. Home, Movies, and Series cinematic media rows (including Continue Watching) use portrait 140×210dp posters rather than 16:9 mini-backdrops. When a title receives focus its **existing** Surface widens up to 340dp (bounded by screen width) and displays the compact cinematic trailer/backdrop, title and metadata **in that poster slot**. There is no additional full-width preview above any row. When focus moves, the former item returns to its portrait poster and the newly active poster expands; focusing another row collapses the old row. Long-press menus, selection, D-pad focus restoration, lazy pagination, and finite/looping View More cards must behave exactly as before. The separate simple layout remains unchanged.

**Device acceptance:** From the Hero, press Down repeatedly: the Hero disappears with normal vertical scrolling, no sticky carousel remains, and the active poster widens without dislodging focus or adding vertical blank space. Move horizontally, then vertically, return from Details, test after dynamic genre insertion and across all three rail modes. Verify trailer first-frame/art fallback and menu-on-release behavior. Build, tests, and on-device confirmation are outstanding.

### Review fixes for PR #245

- Losing focus to the navigation drawer or top bar clears the active inline preview and unmounts its muted ExoPlayer; moving within the row does not.
- Finite/infinite View More end padding uses the terminal card's actual width (120dp portrait; 190dp landscape), independent of the expanded 340dp poster width.
- Continue Watching and other portrait rails select cached enriched posters when original history/suggestion cards lack artwork; no blank collapsed card after asynchronous enrichment.

Android compilation and TV D-pad/end-of-row focus require device verification before release.
### Review fixes for PR #243

- Distinct canonical IMDb IDs never read one another's title/year genre cache; same-name/same-year IMDb collision covered by a fixed-data regression test.
- Non-IMDb catalog entries resolve TMDB trailers through a conservatively matched IMDb ID when available, not through their unrecognized addon prefix.
- When later aggregate catalog pages return empty/duplicate/reclassified results, up to four later source pages are probed so pagination does not stall.
- Primary genres are classified in six-item batches and appended to the correct visible row as each batch finishes. Slow Cinemeta metadata must not keep an entire 100-title page hidden.
- Trailer extraction gives at most one addon candidate, one TMDB candidate, and two Cinemeta candidates 15 seconds each, preserving time for later sources within the resolver deadline.

These are source-reviewed fixes, not proof of Android TV playback; Gradle and device validation remain required.

## Watchlist reorganization (October 15, 2026) — awaiting device verification

- Suggested Next, with **Refresh** directly beside the heading, is the first screen element. It remains visible even when there are no suggestions; the refresh control still accepts focus when suggestions are disabled and explains where to enable them.
- Queue follows Suggested Next, preserving manual reordering and removal. Suggestion thumbs, moving, dismissals, and refresh behavior are unchanged. All seven queue option controls formerly above the cards (enabled, movie, episode, whole-show, play-history, Trakt and unseen-only toggles) now live in Settings → Watchlist; the same QueueManager updates them.
- Library uses a responsive **vertical grid** of posters in the parent LazyColumn rather than a horizontal rail, with 122dp poster slots on compact screens and 140dp on TV. The sort buttons above the Library are smaller (11sp labels, 8dp horizontal × 5dp vertical padding), keeping all existing sort modes.
- Profile sorting, mixed movie/series identities, watched badges, lazy metadata enrichment, card actions and saved focus keys must remain intact. The parent vertical grid scroll state is retained in WatchlistViewModel. Every poster is a first-class stable-keyed LazyVerticalGrid item, so focused titles do not lose their card state when sorting moves them across row boundaries.
- Unit assertions cover column capacity for small phone, ordinary phone, compact tablet and TV widths; expected values are fixed independently from implementation arithmetic.

**Still required:** Android build, FOSS variant unit tests, cold adversarial GLEE review, Android TV/device D-pad and focus restoration, empty/disabled-suggestions scenarios, large libraries and sort changes. Do not treat this as device-verified completion.

### PR #246 review follow-ups

- QueueManager persists empty suggestions as soon as a source, unseen-only, or allowed-content preference changes; Watchlist `ensureSuggestions` reconstructs the row after Settings is dismissed, even when a view-local coroutine was cancelled and the previous row had ten suggestions. Existing manual Queue items and per-profile preferences remain intact.
- Left-arrow navigation in the poster grid shares one repeat debounce across cells. Reaching column zero by holding Left does not immediately escape to the drawer; a fresh deliberate press does.
- Grid children are composed with stable `key(media.type, media.id)` identities, preserving card/long-press state across reorders or deduplication within a row.
- QueueManager regression tests cover persisted invalidation, manual-queue retention, and in-flight refresh results rejected after a preference switch; the old async request cannot resurrect titles from a now-disabled source. Full Android builds and remote-device verification remain required.

- Review follow-up: Suggested Next and Refresh stack vertically on compact screens while retaining a horizontal header on TV.
- Review follow-up: disabled suggestions retain a visibly focused Refresh control; last Library card consumes Right when top navigation is active.
