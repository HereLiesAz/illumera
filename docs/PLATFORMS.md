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
