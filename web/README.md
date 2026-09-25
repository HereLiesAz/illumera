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
| `test/` | Vitest tests |

`core/parser.ts` and `core/sorting.ts` mirror Android's `StreamParser` and `StreamSortingService`. Change them together, and update [docs/ADDONS.md](../docs/ADDONS.md).

## Commands

~~~
npm ci
npm run dev      # local server
npm test         # unit tests
npm run build    # typecheck and build to dist/
~~~

## Deploy

`wrangler.toml` serves `dist/` as a Cloudflare Worker with static assets (`illumera-web`).

## What browsers can't do

- **Torrents:** `infoHash` sources play only through Stremio's streaming server (Stremio Service or the Stremio desktop app) on this computer; see Settings → Torrents.
- **Codecs:** MKV with HEVC, AC3, DTS or TrueHD may not play. A failing source falls back to the next one.
- **Request headers:** `proxyHeaders` are sent only for HLS, and never the headers browsers forbid (such as User-Agent and Referer).
- **Mixed content:** public `http://` URLs are upgraded to `https://`.
