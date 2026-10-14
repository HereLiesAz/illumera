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
| Combine *all usable* enabled movie/series catalog choices lazily, with dedup | PR #234 enumerates standard catalogs plus required-genre variants, pages across source batches and deduplicates by IMDb or type/name/year | Test addons with pagination and non-genre required extras; when an addon needs a missing configurable credential/argument, its catalog cannot be queried without setup |
| Configurable genre rows in Settings → Screen Editor | Per-profile Movies/Series genre selection and rows | Verify selection persists across restarts and profile copies and appears only in chosen screen |
| Watchlist: Queue first + Stremio Library content and sorting | Main Watchlist uses Queue then Library with type and sorting controls | TV focus/scroll behavior, disconnected/offline state, sorting for watched/unwatched/times watched |
| Watchlist setup under Settings → Watchlist | Cloud-storage controls and default sort moved | Verify cloud play/delete routing, D-pad access |
| Two-way Stremio Library sync includes unplayed saved-only titles | PR #234 includes bookmarks in outbound reconciliation and baseline snapshots | Stremio sign-in and multi-profile integration tests, remote deletes, and local re-add protection |

## Release gate

Before describing this as done:

1. Merge only after reviewing PR #234's diff and all review comments.
2. Run `testDebugUnitTest`, `compileDebugKotlin`, `compileReleaseKotlin`, `compilePlayKotlin`, `lintRelease`, and APK/AAB packaging (using the repository's centralized CI workflow).
3. Install an APK on an actual Android TV device. Verify every D-pad, menu-release, Hero, trailer, genre, Library and settings case above.
4. Connect a test Stremio account and verify **both directions** of Library sync, including never-watched saved titles and series.
5. Verify stock addon endpoints in the currently deployed environment. If a provider requires user authentication (especially Stremio Stats), present configuration rather than silently pretending the addon is usable.

All items above still require runtime verification unless an attached successful CI log/device test says otherwise.
