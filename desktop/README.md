# illumera for the desktop

A Tauri app that wraps the web app (`../web`) for Windows, macOS and Linux. It bundles TorrServer, so torrent sources play without installing anything else. The plan is in [docs/PLATFORMS.md](../docs/PLATFORMS.md).

## How it works

- `src-tauri/src/main.rs` opens the window and starts TorrServer as a sidecar on `127.0.0.1:8090`, with its data in the app's data folder. It stops TorrServer on exit.
- The web app finds TorrServer on that port by itself (`web/src/core/streamingServer.ts`); no Tauri APIs are involved.
- The `/api` calls (IntroDB, Trakt) go to the hosted web Worker.

## Build

Linux needs `libwebkit2gtk-4.1-dev libayatana-appindicator3-dev librsvg2-dev patchelf`.

~~~
npm ci
npm run fetch-torrserver    # TorrServer for this machine's target (pinned in scripts/fetch-torrserver.mjs)
npx tauri dev               # runs web/'s dev server in a window
npx tauri build             # installers in src-tauri/target/release/bundle/
~~~

Releases are built by `.github/workflows/desktop-release.yml`.

## Licenses

TorrServer (https://github.com/YouROK/TorrServer) is GPL-3.0. It is shipped unmodified as a separate program, downloaded from its releases.
