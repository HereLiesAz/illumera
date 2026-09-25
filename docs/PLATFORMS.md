# Platforms plan

illumera ships on Android (phone, tablet, TV) today. This is the plan for the other platforms, with a to-do list per step. Tick items as they land.

## Approach

- **Web** is a separate TypeScript app in [`web/`](../web/README.md): Preact on Vite. It targets Chromium 68, the engine in LG webOS 5 and Samsung Tizen 5.5 (2020 TVs), so one build serves the browser and both TV platforms.
- **Android** stays Kotlin. Behavior shared by both apps (stream parsing, ranking, addon requests) is written twice and kept in step, with [ADDONS.md](ADDONS.md) as the spec. A change to one side's parser or ranking needs the matching change on the other, with its tests.
- **Desktop** wraps the web build (Tauri, falling back to Electron if a feature needs it) and adds what browsers can't do: native codecs and a bundled streaming server.
- **Roku** can't run web code (it uses BrightScript and SceneGraph), so it's a separate app in [`roku/`](../roku/README.md) that reuses the addon protocol and ports the parser and ranking.

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
- [x] Deploy as a Cloudflare Worker with static assets (`web/wrangler.toml`): `.github/workflows/web-deploy.yml` runs the tests, builds and deploys on pushes touching `web/`, centralized as `cloudflare-worker-deploy` with a `web` purpose profile.
- [x] Run `web/` tests in CI, as part of each deploy.
- [x] Flexbox `gap` needs Chromium 84; older TVs get margin fallbacks (`.no-flex-gap`, detected at startup).
- [x] A full-screen button and a seek bar you can click, drag, or step with the remote's left/right.

## Step 2: Web, Stremio account

- [x] Sign in with email and password against `api.strem.io`, with the session kept in storage (`web/src/core/stremio.ts`, Settings → Stremio account).
- [x] Get addons from the account (`addonCollectionGet`, which replaces local addons in the account's order) and send them back (`addonCollectionSet`).
- [x] Two-way Continue Watching sync through the `datastoreMeta` / `datastoreGet` / `datastorePut` library API, with Android's merge rules: the newer side wins, and a title removed locally since the last sync is sent as a deletion. It runs on sign-in, at launch, and at most once a minute while watching.
- [x] Reset sync: sign out, forget the sync baseline and go back to the default addons, matching the Android reset.
- [ ] Facebook and Apple sign-in, through Stremio's hosted handoff (`login-fb` / `login-apple`, as on Android).

## Step 3: Web, torrents through the Stremio Service

- [x] Detect a streaming server at `http://127.0.0.1:11470`, or at an address entered in Settings → Torrents (`web/src/core/streamingServer.ts`). An https page can reach loopback but not a LAN server over http, so a LAN address works only from the TV and desktop packages.
- [x] Play `infoHash` streams through it:
  - `POST /{hash}/create` with the stream's `tracker:`/`dht:` sources
  - pick the file by `behaviorHints.filename`, then `fileIdx`, then the server's guess, then the largest video (Android's order)
  - play `/{hash}/{index}`
  - a failure falls back to the next source
- [ ] Check against a real Stremio Service. So far it's tested against a fake server; the `create` request and response follow stremio-web.
- [ ] Use its transcoding (HLS) for codecs the browser can't play.

## Step 4: Web, profiles, Trakt, debrid

- [x] **Profiles**, each with its own addons, settings, progress and accounts (`web/src/core/profiles.ts`; storage is keyed per profile, and the default profile keeps the original keys). With more than one profile, "Who's watching?" appears once per session. The streaming server setting is shared across the device.
- [x] **Trakt**
  - device-code sign-in; the token exchange and refresh go through the web Worker, which holds the client secret
  - scrobbling start, pause and stop, as Android's `TraktScrobbleManager`
  - **Needs:** a `TRAKT_CLIENT_ID` variable and a `TRAKT_CLIENT_SECRET` secret on the `illumera-web` Worker.
- [ ] Trakt watchlist and history sync (Android's `TraktSyncManager`). The web app has no watchlist yet.
- [x] **Debrid:** a saved provider key is filled into Torrentio installs (Android's `DebridAddonUrlHelper`).
- [x] **IntroDB:** a Skip intro button, or automatic skipping as a setting; Next episode appears from the outro. The requests go through the web Worker, because IntroDB only allows its own site.
- [x] **Soundtrack:** a button on details screens (the whole series grouped by episode) and in the player (the current episode), reading the Soundtrack addon. On the web it's IMDb only; Tunefind needs the Android WebView reader.

## Step 5: Desktop

- [x] **App:** `desktop/` is a Tauri app that wraps `web/` for Windows, macOS and Linux.
- [x] **Torrents without Stremio Service:** TorrServer (the engine Android uses, GPL-3.0) is bundled as a sidecar. It starts with the app on `127.0.0.1:8090`, and the web app finds it on its own. `npm run fetch-torrserver` downloads the pinned release for the build target.
- [x] **Releases:** `.github/workflows/desktop-release.yml` builds the desktop installers and the TV packages on pushes that touch `web/` or `desktop/`, then publishes one GitHub release. It's centralized as Multi-Platform App Release (HereLiesAz/workflows).
- [x] Checked locally: the Linux `.deb` builds with TorrServer inside. The app starts TorrServer with its data in the app's data folder, and TorrServer stops when the app exits.
- [ ] Code signing: an Apple Developer ID (with notarization) for macOS, and an Authenticode certificate for Windows.
- [ ] Intel Macs. The macOS build is Apple silicon only (`macos-latest`).
- [ ] Native playback (mpv or libVLC) for codecs the system webview lacks. Linux's WebKitGTK plays the fewest.

## Step 6: Samsung and LG TVs

- [x] **TV build:** `vite build --mode tv` produces one classic script (IIFE, hls.js inlined), because TV apps load from `file://` and Chromium won't run module scripts there. It calls the hosted Worker's `/api`.
- [x] **LG webOS:** `npm run package:webos` produces `com.hereliesaz.illumera_<version>_all.ipk` (`web/tv/webos/appinfo.json`, with `disableBackHistoryAPI` so the Back key reaches the app). To install on a TV in developer mode: `ares-install`.
- [x] **Samsung Tizen:** `npm run package:tizen` produces an unsigned `.wgt` (`web/tv/tizen/config.xml`, Tizen 5.5+).
- [x] Remote keys:
  - media keys are registered on Tizen
  - Back on the root screen exits the app, through each platform's API
- [ ] Sign the `.wgt` with a Samsung certificate (Tizen Studio: `tizen package -t wgt -s <profile>`). This needs a Samsung account and certificate.
- [ ] Test on real TVs. So far the TV build is tested in headless Chromium from `file://`.
- [ ] Build the packages in CI and attach them to releases.
- [ ] Store submissions: Samsung Seller Office, LG Seller Lounge.

## Step 7: Roku

- [x] **App:** a BrightScript/SceneGraph channel in `roku/`. It has home, search, details, sources, the player, addons and settings. The parser and ranking are ported from Android and `web/`, and unit-tested in the brs interpreter. The whole channel is validated by BrighterScript.
- [x] **Torrents:** played through a streaming server on the network (TorrServer or Stremio Service), set in Settings.
- [x] **Releases:** built into the desktop and TV release (`npm run package` produces a sideloadable zip).
- [ ] Test on a Roku device.
- [ ] Stremio account, Trakt, debrid, profiles and language filters, as on the web.
- [ ] Roku Channel Store submission. This needs a package signed on a Roku (Utilities → Packager).
