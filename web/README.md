# illumera for the web

The browser version of illumera, which also becomes the Samsung (Tizen) and LG (webOS) TV apps and the desktop app. The plan and to-do list are in [docs/PLATFORMS.md](../docs/PLATFORMS.md).

## Stack

- Preact, TypeScript and Vite, built for Chromium 68 (`vite.config.ts`). Don't use APIs newer than that without a fallback.
- No UI framework. D-pad navigation is `src/ui/spatial.ts`.
- hls.js loads only when an HLS stream plays.

## Layout

| Path | What |
|---|---|
| `src/core/` | Addon client, stream parser and ranking (ported from Android), settings, progress, storage |
| `src/player/` | Playback session, subtitle conversion |
| `src/ui/` | Screens, router, spatial navigation |
| `worker/` | The Worker's `/api` (IntroDB proxy, Trakt token exchange) |
| `tv/` | TV app manifests and icons (webOS `appinfo.json`, Tizen `config.xml`) |
| `scripts/package-tv.mjs` | Builds and packages the TV apps |
| `test/` | Vitest tests |

`core/parser.ts` and `core/sorting.ts` mirror Android's `StreamParser` and `StreamSortingService`. Change them together, and update [docs/ADDONS.md](../docs/ADDONS.md).

## Credentials

The Stremio auth key, Trakt tokens and debrid key are never stored in clear text (`src/core/secrets.ts`). They're encrypted with AES-GCM under a non-extractable WebCrypto key that IndexedDB keeps. Where WebCrypto or IndexedDB isn't available, they last only for the session. Credentials saved in clear text by earlier builds are migrated on first load.

## Commands

~~~
npm ci
npm run dev      # local server
npm test         # unit tests
npm run build    # typecheck and build to dist/
npm run package:webos   # LG TV app → packages/*.ipk
npm run package:tizen   # Samsung TV app → packages/*-unsigned.wgt (sign before installing)
~~~

## Deploy

`wrangler.toml` deploys the `illumera-web` Worker. It serves `dist/` as static assets, and `worker/index.ts` answers `/api`:
- an IntroDB proxy
- the Trakt token exchange

Trakt needs a `TRAKT_CLIENT_ID` variable and a `TRAKT_CLIENT_SECRET` secret on the Worker. Pushes to `main` that touch `web/` deploy through the central workflows (`.github/workflows/web-deploy.yml`).

TV and desktop packages run from `file://`, so they call the hosted Worker's `/api` (`src/core/api.ts`).

## What browsers can't do

- **Torrents:** `infoHash` sources play only through Stremio's streaming server (Stremio Service or the Stremio desktop app) on this computer; see Settings → Torrents.
- **Codecs:** MKV with HEVC, AC3, DTS or TrueHD may not play. A failing source falls back to the next one.
- **Request headers:** `proxyHeaders` are sent only for HLS, and never the headers browsers forbid (such as User-Agent and Referer).
- **Mixed content:** public `http://` URLs are upgraded to `https://`.
