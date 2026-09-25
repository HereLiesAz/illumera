# Platforms plan

illumera ships on Android (phone, tablet, TV) today. This is the plan for the other platforms, with a to-do list per step. Tick items as they land.

## Approach

- **Web** is a separate TypeScript app in [`web/`](../web/README.md): Preact on Vite. It targets Chromium 68, the engine in LG webOS 5 and Samsung Tizen 5.5 (2020 TVs), so one build serves the browser and both TV platforms.
- **Android** stays Kotlin. Behavior shared by both apps (stream parsing, ranking, addon requests) is written twice and kept in step, with [ADDONS.md](ADDONS.md) as the spec. A change to one side's parser or ranking needs the matching change on the other, with its tests.
- **Desktop** wraps the web build (Tauri, falling back to Electron if a feature needs it) and adds what browsers can't do: native codecs and a bundled streaming server.
- **Roku** can't run web code (it uses BrightScript and SceneGraph), so it's a separate app that reuses only the addon protocol and the ranking rules.

## Step 1: Web, core browse and play

- [x] Scaffold `web/` (Preact, TypeScript, Vite, Vitest), with the build targeting Chromium 68.
- [x] Addon client: install by URL or `stremio://`, catalogs with `skip` paging, Cinemeta search, and meta resolution in Android's order. Streams are gated by `idPrefixes`, and subtitles carry file-hint extras.
- [x] Public `http://` is upgraded to `https://` and local hosts are kept, matching Android's CleartextGuard.
- [x] Stream parser and ranking ported from Android (`StreamParser`, `StreamQuality`, `StreamSortingService`), with the same defaults.
- [x] Screens: home (Continue Watching and catalog rows), search, details (seasons and episodes), source list, addons, settings.
- [x] Player:
  - [x] HTML5 video, plus hls.js for HLS
  - [x] resume position and saved progress
  - [x] SRT and ASS converted to WebVTT, with a default subtitle language
  - [x] choosing a source, and falling back to the next one on errors or placeholder clips
  - [x] the next episode, preferring the same `bingeGroup` and addon
  - [x] TV media keys
- [x] D-pad navigation, including the Back keys for Tizen (10009) and webOS (461).
- [x] Link-only (`externalUrl`) sources open in a new tab.
- [ ] Deploy as a Cloudflare Worker with static assets (`web/wrangler.toml`) through the central workflows: add `web/` to the `cloudflare-worker-deploy` profile.
- [ ] Run `web/` tests in CI.
- [ ] Stop `web/`-only pushes from starting an Android release: add `web/**` to the release workflow's ignored paths in the central workflows repo.
- [ ] Flexbox `gap` needs Chromium 84; add margin fallbacks for older TVs.
- [ ] A full-screen button and a scrubbable seek bar (for mouse and touch).

## Step 2: Web, Stremio account

- [ ] Sign in with email and password against `api.strem.io`, with the session kept in storage.
- [ ] Import and export the addon collection (`addonCollectionGet` / `addonCollectionSet`).
- [ ] Two-way Continue Watching sync through the `datastoreMeta` / `datastoreGet` / `datastorePut` library API, as in Android's `StremioLibrarySyncManager`.
- [ ] Reset sync: forget the account link and local addon changes, then resync from scratch (matching the Android reset).

## Step 3: Web, torrents through the Stremio Service

- [ ] Detect a streaming server at `http://127.0.0.1:11470`, or at an address the user enters.
- [ ] Play `infoHash` streams through it: create the torrent, pick the file by `behaviorHints.filename`, then `fileIdx`, then the largest video (Android's order), and pass on `tracker:` sources.
- [ ] Use its transcoding for codecs the browser can't play.

## Step 4: Web, profiles, Trakt, debrid

- [ ] Profiles, each with its own addons, settings and progress.
- [ ] Trakt: device-code sign-in, scrobbling, watchlist sync.
- [ ] Debrid: store keys and inject them into Torrentio URLs (Android's `DebridAddonUrlHelper`).
- [ ] Skip intro and outro with IntroDB.
- [ ] The Soundtrack button (movies and episodes), reading the Soundtrack addon.

## Step 5: Desktop

- [ ] Wrap `web/` in Tauri for Windows, macOS and Linux.
- [ ] Bundle or detect the Stremio Service for torrents.
- [ ] Native playback (mpv or libVLC) for codecs browsers lack, such as MKV with HEVC, DTS or TrueHD.
- [ ] Releases through the central release workflow.

## Step 6: Samsung and LG TVs

- [ ] Package `web/` as a Tizen `.wgt` app and a webOS `.ipk` app, using the TV remote keys already handled.
- [ ] Register remote keys (media keys and color keys) through each platform's API.
- [ ] Store submissions: Samsung Seller Office, LG Seller Lounge.

## Step 7: Roku

- [ ] A BrightScript/SceneGraph app with the addon client, ranking and a player.
- [ ] Roku Channel Store submission.
