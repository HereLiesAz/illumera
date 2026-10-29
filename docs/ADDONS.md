# Writing addons for illumera

illumera speaks the [Stremio addon protocol](https://github.com/Stremio/stremio-addon-sdk/tree/master/docs/api). An addon that works in Stremio installs in illumera unchanged. This page lists where illumera reads more than Stremio does, where it reads differently, and what it ignores, so one addon can serve both well.

Everything here reflects the current code. When the code changes, this page changes with it.

## Home-screen trailer presentation

The Hero Carousel is the first normal scrolling item, and Up from the first focused content row scrolls back and restores Hero focus. Only the active row uses portrait posters and one inline trailer; all inactive media rows use landscape cards. Artwork remains visible until video plays.
## Managing addons in Illumera

Add-ons has its own main side-menu/top-navigation destination rather than a Settings section. The browser contains **All**, **Installed**, enabled built-in catalog sources (Official, Community, **3rd Party** / stremio-addons.net), and a named tab for each enabled custom repository. It has no Collection management button or section. Configure/install/uninstall remain accessible, and manual manifest URLs and phone-to-TV paste still work; no Stremio account is required.

Manage catalog repositories in **Settings → Integrations → Third-party Add-on Repositories**. The repository entry is first and Integrations scrolls on shorter displays. Toggle each built-in or custom source independently; add a custom collection/repository URL, move it, or remove it. Custom URLs are validated against actual addon catalogs before being saved, and earlier saved sources default to enabled. A disabled repository remains saved but is excluded from both the Add-ons tabs and the All feed. Installed addons are independent of discovery catalogs: disabling a repository does not uninstall addons.

The **3rd Party** source (stremio-addons.net) is available as a native browser catalog through its public read-only /api/v0/addons endpoint. Its manifest URLs install through Illumera's existing installer. The source is visibly credited to the provider. The API is experimental; changes to its response schema may require updates. [Usage terms](https://docs.stremio-addons.net/developers/api) still apply.

## Contents

- [What illumera reads that Stremio doesn't](#what-illumera-reads-that-stremio-doesnt)
- [Writing stream titles illumera can parse](#writing-stream-titles-illumera-can-parse)
- [How streams are ranked](#how-streams-are-ranked)
- [Behavior hints](#behavior-hints)
- [Requests illumera makes](#requests-illumera-makes)
- [Not supported](#not-supported)
- [Checklist](#checklist)

## What illumera reads that Stremio doesn't

### Subtitle extras on the chosen stream

Once a source is picked, illumera asks subtitle addons again with that file's details. It sends every hint the stream has:

~~~
/subtitles/{type}/{id}/videoHash={hash}&videoSize={bytes}&filename={name}.json
~~~

The values are URL-encoded, with spaces as `%20`, and results from this request are listed ahead of the generic ones. A stream addon that fills in `behaviorHints.videoHash`, `videoSize` and `filename` gets matching subtitles in illumera.

### Stream-embedded subtitles

The objects in `stream.subtitles[]` may also carry:

| Field | Meaning in illumera |
|---|---|
| `name` | Track label; also read for the subtitle-language filter. |
| `transportUrl` | Base for resolving a relative `url`. When absent, the stream's own addon is the base. |

Relative subtitle URLs are resolved against the addon, in both stream-embedded subtitles and `/subtitles/` results. Absolute URLs that aren't `http(s)` are dropped.

### Lenient field names

| Where | Also accepted |
|---|---|
| `meta.videos[].title` | `name` |
| `stream.behaviorHints.bingeGroup` | `group` |
| manifest `resources` | `"subtitle"` as well as `"subtitles"` |

### Soundtracks

illumera has a **Soundtrack** button in the player and on details screens. It reads the [Soundtrack addon](https://github.com/HereLiesAz/stremio-soundtrack) (IMDb credits). For a movie or a single episode it also reads Tunefind on the device and merges the two lists. The addon's URL is fixed at build time, so another addon can't take its place yet. Its response format is:

~~~
GET /soundtrack/{movie|series}/{ttID or ttID:season:episode}.json

{ "title": "…",
  "groups": [ { "season": 1, "episode": 2, "title": "…",
                "songs": [ { "title": "…", "artist": "…" } ] } ] }
~~~

A group is one episode, or the whole movie when `season` is null. Songs are in order of appearance.

### Episode switching with `bingeGroup`

When moving to the next episode with autoplay or auto-select on, illumera prefers a stream with the **same `bingeGroup` from the same addon**. Then it tries the source the user picked last time, then the first playable stream. Keep `bingeGroup` stable across a season: same release group, same quality, same debrid service.

## Writing stream titles illumera can parse

illumera reads quality, size, seeders and formats out of stream text. The fields it reads are `name`, `title`, `description` and `behaviorHints.filename`.

### Quality

Quality comes from the **first field that has one**, in the order `filename`, `title`, `description`, `name`. Explicit resolutions win over words.

| Recognized | Result |
|---|---|
| `2160p`, `1080p`, `720p`, `480p` (also `…i`, or bare `2160`) | that resolution |
| `4K`, `UHD`, `Ultra HD` | 4K |
| `FHD` | 1080p |
| `HD` | 720p |
| `SD`, `DVD`, `DVDRip` | SD |
| `CAM`, `CAMRip`, `TS`, `TeleSync`, `HDTS`, `HDCAM`, `TC`, `Telecine` | CAM |

By default illumera shows only **4K, 1080p, 720p and unknown**, so SD and CAM streams are hidden unless the user turns them on. `TS` as a whole word counts as CAM, so don't let a bare `.ts` extension be the only quality marker in a filename.

### Size

Set `behaviorHints.videoSize` in bytes; illumera uses it first. Otherwise it takes the first `12.3 GB`-style match, with units `KB`, `MB`, `GB` or `TB` counted in 1024s. `GiB` and `MiB` are **not** recognized. Size matters for ranking, as the next section shows.

### Seeders

The first of these, in this order: `👤 123`, `seeds: 123` / `seed 123`, then `S: 123`. Commas and dots are stripped. Peer counts are not treated as seeders.

### Formats

These are used only by the user's "exclude formats" filter:

| Filter | Matches |
|---|---|
| Dolby Vision | `Dolby Vision`, `DoVi`, `DV` |
| HDR | `HDR`, `HDR10`, `HDR10+`, `HLG` |
| DTS | `DTS`, `DTS-HD`, `DTS-X`, `DTS-MA` |
| Dolby audio | `Dolby Digital`, `Atmos`, `DD5.1`, `DD+`, `AC3`, `EAC3` |
| HEVC | `HEVC`, `H.265`, `x265` |
| AV1 | `AV1` |
| 3D | `3D`, `SBS`, `Half-SBS`, `HOU` |

### Languages

The language filters are off by default. When a user turns them on:

- **Audio:** a language name in `filename` or `name` counts. In `title` or `description` it must sit within 32 characters of `audio`, `dub`, `dubbed` or `dual`.
- **Subtitles:** `subtitles[].lang`, a language name in `subtitles[].name`, or a language name within 32 characters of `sub`, `subs`, `subtitles`, `subbed`, `CC` or `captions`.

Write languages as English names or ISO 639-2 codes (`English`, `eng`, `Spanish`, `spa`). Two-letter codes and flag emoji are not recognized in free text.

### Display

The stream list shows `description`, falling back to `title` and then `name`, with `[Addon name] name` underneath.

## How streams are ranked

Stremio keeps each addon's own order. illumera re-sorts every stream from every addon, in this order:

1. **Availability:** streams falling below the user's minimum-seeders threshold (default 5) rank lower, proportional to the shortfall. Unknown seeder counts are neutral, not automatically penalized.
2. **Target-size band:** size proximity to the user's movie/episode target (3000 MB and 750 MB by default) is grouped into quarter-target bands, not ordered by exact byte distance. Streams without readable size rank after known-size streams when a target is configured.
3. **Addon order:** the configured preference breaks ties before quality.
4. **Quality, then size, then seeders:** user sort priorities and remaining tie-breakers apply within equally ranked availability/size bands.

This order is shared across Android, web and Roku. Reporting accurate size and seed counts improves ranking without letting tiny size differences outrank a source that can actually play.

## Behavior hints

| Hint | illumera |
|---|---|
| `proxyHeaders.request` | Sent on playback requests, including HLS and DASH segments. Not sent to subtitle hosts or external players. |
| `proxyHeaders.response` | Ignored. |
| `bingeGroup` | Episode switching; see [above](#episode-switching-with-bingegroup). |
| `filename` | Quality parsing, audio language, choosing the file inside a torrent, debrid library matching, subtitle extras. |
| `videoSize` | Size ranking, subtitle extras. |
| `videoHash` | Subtitle extras. |
| `notWebReady`, `countryWhitelist` | Ignored. |

## Requests illumera makes

### Transport

- **HTTPS only for public hosts.** A public `http://` addon, stream or subtitle URL is upgraded to `https://` (port 80 becomes 443; other ports are kept), and HTTPS→HTTP redirects are refused. Local hosts are left alone: `localhost`, `*.local`, `*.lan`, `*.home.arpa`, and private and link-local IPs. **An addon reachable only over public HTTP will not work.**
- **User-Agent:** addon requests use OkHttp's default. Playback uses a desktop Chrome UA.
- **No URL-encoding** of IDs or search queries in request paths. Use IDs that are safe in a URL path.
- **Config in the path.** A manifest URL with a `?query` keeps the query in the addon's base URL, which breaks resource URLs. Put configuration in path segments (`/{config}/manifest.json`). illumera has no configure page; users install an already-configured URL.
- `stremio://` install links are rewritten to `https://`.

### Timeouts

| Request | Timeout |
|---|---|
| Catalog | 10 s |
| Stream | 20 s |
| Meta, from the catalog's own addon | 5 s |
| Meta, from other addons | 10 s |
| Subtitles, per addon | 8 s |
| Manifest at install | 30 s |

Stream results are cached for 3 minutes.

### Catalogs

- Pagination only runs when the catalog declares a `skip` extra: `/catalog/{type}/{id}/skip={n}.json`, where `n` is the number of unique items fetched so far. `hasMore` is ignored.
- A catalog whose first page is empty is hidden. Metas missing `id`, `name` or `type` are dropped.
- **Return full metas in catalogs.** A home-screen item missing any of `poster`, `background`, `logo`, `description`, `releaseInfo`, `imdbRating`, `runtime` or `genres` triggers an extra meta request.

### Meta

illumera asks, in order:

1. The addon the item came from.
2. Addons whose `types` include the type.
3. Addons for a type guessed from the ID.
4. The first meta addon.
5. Cinemeta.

For steps 2–4 the returned `meta.id` must equal the requested ID. `idPrefixes` is **not** checked for meta.

### Streams

- Every addon with a `stream` resource whose top-level `idPrefixes` match is asked. Matching is a case-insensitive prefix, and an empty list matches everything. Object-form `types` and `idPrefixes` inside `resources` are not read for streams or meta. **Declare `idPrefixes` at the top level**, even for IDs your own catalog produced.
- Episode stream IDs are `video.id`, falling back to `{seriesId}:{season}:{episode}`. Next-episode requests always use type `series`.
- A stream with `externalUrl` and no `url` or `infoHash` opens in the device's browser when picked from a details screen. Many TV devices have no browser; illumera then shows the link instead. These streams are never auto-selected.

### Subtitles

- Addons whose manifest declares no matching subtitle resource are skipped. If the manifest can't be fetched, the addon is asked anyway.
- Object-form resource `types` and `idPrefixes` **are** honored here. Declared prefixes are lowercased before matching, so write them in lowercase.
- The track label is the subtitle's `label` when given, otherwise the addon's name (or, for stream-embedded subtitles, `name`). Square brackets are stripped. Format comes from the URL extension (`.vtt`, `.ass`/`.ssa`, `.ttml`/`.dfxp`, `.srt`), and anything else is sniffed.

### Player and library events

- Addons whose manifest declares a `player` or `library` resource receive the viewer's activity, matched by `types` and `idPrefixes` (object-form resources honored; a string resource uses the manifest-level filters). Addons that don't declare them never receive it, and an unreachable manifest sends nothing. Responses are ignored.
- `player`: `/player/{type}/{videoId}/action=start|pause|stop&currentTime={ms}&duration={ms}.json`, sent with the same start/pause/stop as Trakt scrobbles.
- `library`: `/library/{type}/{id}/action=libraryAdd|libraryRemove.json` on watchlist changes, and `action=watched|unwatched` on watched marks. Episode and whole-series marks add `&videoId=` with URL-encoded, comma-separated video IDs, at most 100 per request.

### Torrents

- A magnet link is built from `infoHash`. Addon `sources` entries starting with `tracker:` are added when switching episodes, but not on the first play from a details screen. `dht:` entries are ignored.
- File choice inside the torrent, in order: the video file matching `behaviorHints.filename`, then `fileIdx`, then the largest video file. **Setting `filename` makes file choice reliable.**

### Placeholder videos

illumera treats some short videos as placeholders and moves on to the next source:

| Length | Treated as |
|---|---|
| About 30 s | Debrid still downloading; illumera polls the debrid library by `infoHash` or `filename`. |
| About 2 min | Removed from debrid. |
| Under 4.5 min (movie) or 1.5 min (other types) | Too short; the next source is tried. |

Don't serve real content that short as a movie or episode stream.

## Not supported

Don't rely on these in illumera:

- Stream `ytId`. Such streams are listed but can't be played.
- Meta `trailers` and `trailerStreams` from an addon (Home-screen previews read them only from Cinemeta; see below), `links`, `posterShape`, `behaviorHints.defaultVideoId`, `videos[].streams` and `videos[].available`.
- Addon search catalogs. Search uses Cinemeta.
- Catalog extras other than `skip` and `genre`. `genre` is supported: a catalog with an optional `genre` extra is filtered by it when the genre screens ask for one, and one whose `genre` is required is requested once per `options` value. On the Movies and Series screens, a catalog with any other required extra (such as `search`) is skipped; elsewhere catalogs are requested without extras.
- Manifest `config`, `background` and `contactEmail`, and catalog `hasMore`.
- The native live-TV programme guide (`behaviorHints.epgProvider`, `date` extra on `tv` catalogs). `tv` catalogs show as ordinary channel lists.
- ASS/SSA subtitles identified only by content: give their URL a `.ass` or `.ssa` extension.
- `cacheMaxAge` and the other cache hints.
- Intro and outro skipping comes from IntroDB; addons can't supply it. Home-screen trailer previews come from, in order, the `stremio-trailer-addon` (if it is installed and enabled; its stream `ytId`, `externalUrl` or YouTube `url`), TMDB's video index, and Cinemeta's meta `trailerStreams`/`trailers`. No other addon can supply trailers.
- When a profile turns on TMDB enrichment, TMDB overwrites the addon's `name`, `description`, images, `genres`, `releaseInfo`, `imdbRating` and `runtime`.

## Checklist

- [ ] Serve over HTTPS.
- [ ] Declare `idPrefixes` at the top level of the manifest.
- [ ] Put configuration in path segments, not a query string.
- [ ] Return full metas in catalogs.
- [ ] Put the resolution in `behaviorHints.filename` or the title (`1080p`, not only `FHD`).
- [ ] Set `behaviorHints.videoSize`, or write sizes as `GB`/`MB`, not `GiB`/`MiB`.
- [ ] Set `behaviorHints.filename` and `videoHash` for torrent file choice and subtitle matching.
- [ ] Keep `bingeGroup` stable across a season.
- [ ] Give playable `url` or `infoHash` streams; `ytId` does nothing here, and `externalUrl` only opens a browser.
