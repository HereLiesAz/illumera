# illumera for Roku

A BrightScript/SceneGraph channel that browses, searches and plays from Stremio-compatible addons. It's step 7 of [docs/PLATFORMS.md](../docs/PLATFORMS.md). Roku can't run the web app, so this is its own code. It follows the same addon rules ([docs/ADDONS.md](../docs/ADDONS.md)), and its stream parser and ranking are ported from Android and `web/`.

## What it does

- **Home:** Continue Watching, then a row for every catalog of the installed addons. Cinemeta and OpenSubtitles are installed on first run.
- **Search:** Cinemeta search for movies and series.
- **Details:** seasons and episodes. **Sources** come from every stream addon whose `idPrefixes` accept the id, ranked like Android: target size first, then seeders, addon order and quality. SD and CAM are hidden.
- **Player:**
  - the system player with Stremio subtitles and resume
  - `proxyHeaders` are sent as HTTP headers
  - falls back to the next source on errors or placeholder clips
- **Torrents:** played through a streaming server on the network, set in Settings: TorrServer (8090), for example the illumera desktop app's, or Stremio Service (11470).
- **Addons:** install by address, reorder, disable, remove.

## Not yet

- Stremio account sign-in, Trakt, debrid, profiles.
- Language filters in the ranking.
- A way to install addons other than typing the address.
- Testing on a Roku device. So far it's validated with BrighterScript and unit-tested in the brs interpreter.

## Commands

~~~
npm ci
npm test          # parser and ranking tests (brs interpreter)
npm run lint      # BrighterScript validation of the whole channel
npm run package   # packages/illumera-roku-<version>.zip
~~~

To sideload: enable developer mode on the Roku (Home ×3, Up ×2, Right, Left, Right, Left, Right), open `http://<roku-ip>` and upload the zip. The Channel Store needs a package signed on a Roku (Utilities → Packager).

## Limits

- The registry holds 16 KB per channel. Manifests are trimmed to the fields the app reads, and progress keeps the newest 40 titles.
- Playback depends on the model's decoders. MKV and HEVC need a 4K-capable Roku.
