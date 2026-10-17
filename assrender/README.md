# assrender

ASS/SSA subtitle renderer for Android Media3/ExoPlayer.

**The problem:** ExoPlayer's built-in ASS/SSA support strips all styling (fonts, colors, positioning, animations). Subtitles render as plain text.

**The solution:** Intercept raw ASS data from ExoPlayer's extraction pipeline and render it with libass, preserving full styling. No FFmpeg needed — works with any source ExoPlayer supports (HTTP, HLS, DASH, local files, torrents).

## Architecture

```
MKV/MP4 Container
    |
    v
AssMatroskaExtractor (extends MatroskaExtractor)
    |--- extracts font attachments --> AssHandler.onFontAttachment() --> libass
    |--- wraps ExtractorOutput via reflection
    v
AssTrackOutput (eavesdrops on raw ASS data)
    |--- captures ASS headers from initializationData
    |--- captures dialogue events from sampleData
    |--- forwards everything to ExoPlayer unchanged
    v
AssHandler (coordinates libass rendering)
    |--- stores events per track for instant track switching
    |--- syncs with ExoPlayer's track selection
    |--- render loop driven by Choreographer (once per display frame),
    |    reading player.currentPosition
    v
SubtitleOverlayView (transparent bitmap canvas on top of video)
```

## Usage

```kotlin
val overlay = SubtitleOverlayView(context)          // add this view above the video surface
val assHandler = AssHandler(context, overlay)

val player = ExoPlayer.Builder(context, AssRenderersFactory(context, assHandler))
    .setMediaSourceFactory(
        DefaultMediaSourceFactory(dataSourceFactory, AssExtractorsFactory(assHandler))
    )
    .build()
assHandler.player = player

// When the viewer picks an ASS track:  assHandler.selectTrack(textTrackIndex)
// For a non-ASS track or subtitles off: assHandler.clearOverlay()
// When done:                            assHandler.release()
```

`AssHandler` must be released and then replaced, not reused. Consumers that
minify must keep the rules in `consumer-rules.pro` (shipped with the AAR).

## Features

- Full ASS/SSA styling: fonts, colors, positioning, animations, karaoke
- Embedded font extraction from MKV containers
- Instant track switching with event replay
- No FFmpeg dependency: only libass, FreeType, FriBidi and HarfBuzz
- Works with any ExoPlayer source (HTTP, HLS, local, torrent)
- Minimal integration: two factory swaps
- Render bitmap follows the overlay's size and aspect ratio (capped at 1920 on the longest side)

## Fonts

libass is built **without fontconfig**, so it has no system font provider. At
start-up `AssHandler` therefore adds the `.ttf`/`.otf`/`.ttc` files found in
`/system/fonts`, `/system_ext/fonts`, `/product/fonts` and `<filesDir>/fonts` via
`ass_add_font` (smallest files first, at most 32 MB per file and 64 MB in total,
because libass keeps a copy of each font in memory). Fonts embedded in MKV
attachments are added on top. Limitation: libass can only fall back to fonts it was
given by name, so glyphs missing from the chosen font (for example CJK text in a
script that names a Latin font) are not substituted automatically the way a
fontconfig build would; ship the script's fonts as attachments, or drop extra
fonts into `<filesDir>/fonts`.

## Target Architectures

- `arm64-v8a` — modern Android TV, phones
- `armeabi-v7a` — budget Android TV sticks

## Building Native Libraries

Only needed for development. The AAR includes prebuilt native libraries
(`prebuilt/<abi>/lib`: libass, FreeType, FriBidi, HarfBuzz).

Requires the Android NDK on Linux, macOS (needs `sha256sum`) or WSL, plus
`curl`, `make`, `cmake` and the usual autotools build prerequisites.

```bash
export ANDROID_NDK_HOME=~/Android/Sdk/ndk/<version>   # optional: newest installed NDK is used otherwise
./scripts/build-libass.sh arm64    # arm64-v8a
./scripts/build-libass.sh arm      # armeabi-v7a
./scripts/build-all.sh             # both ABIs
```

Source tarballs are pinned by version and verified against SHA-256 hashes in
`scripts/build-libass.sh`; update the hash together with the version.

## License

Apache License 2.0 for this module's code (see `LICENSE`). The Illumera repository
that hosts it is GPLv3; see `NOTICE` for how the two relate and for third-party licenses.
