# Release pipeline

## Overview

Releases are built in [`HereLiesAz/workflows`](https://github.com/HereLiesAz/workflows),
not in this repo. The workflows here are hand-off stubs that hold no secrets:
[`android-github-release.yml`](../.github/workflows/android-github-release.yml)
and [`android-play-release.yml`](../.github/workflows/android-play-release.yml)
for Android, [`desktop-release.yml`](../.github/workflows/desktop-release.yml)
for the desktop and TV packages, and
[`web-deploy.yml`](../.github/workflows/web-deploy.yml) for the web app. A push
to `main` that touches the module's paths (or a manual `workflow_dispatch`)
starts the central run; the stub's job only points to it, and the result is
reported on the commit as a status named after the stub's workflow path.
The Android GitHub run builds a signed release APK and publishes it as a
GitHub Release; no tag needs to be pushed by hand.

`AppUpdateManager` (in-app) polls this repo's *latest* non-prerelease
release, so the release shape must keep matching what it expects: exactly
one `*.apk` asset, and a `SHA-256: <64 hex chars>` line in the release body.

## Versioning

The central run passes the version to Gradle as `-PversionCode` and
`-PversionName` (`app/build.gradle.kts`). `versionCode` is one more than the
highest Google Play has accepted, and `versionName` raises only its last
field; the name has four components, `major.minor.patch.build`. Without those
properties (a local build) Gradle reads the last published pair,
`versionCode` and `versionName`, from
[`version.properties`](../version.properties). Versions strictly increase,
which both Android's package installer and `AppUpdateManager`'s version
comparison require to recognize an upgrade.

## Signing

Published GitHub and Play artifacts require the real release/upload keystore;
there is no public fallback key. The central release workflow supplies the
signing material, and `app/build.gradle.kts` fails distributable
release/Play tasks when those credentials are absent rather than emitting an
uninstallable unsigned artifact.

For local signed release builds, configure the four `release.*` properties
documented in [`../ci/README.md`](../ci/README.md). Changing signing
certificates is not an upgrade path: Android refuses to install a differently
signed package over an existing installation.

## The Stremio playback module rebuild

This is the most fragile part of the pipeline, worth understanding in full
before touching it.

illumera doesn't use stock `androidx.media3` for its ExoPlayer core and
decoder extensions — it uses **Stremio's own fork** (`Stremio/media`),
built from source on every release and linked in as local AARs:

```
playbackcore/libs/lib-exoplayer-release.aar
playbackcore/libs/lib-decoder-av1-release.aar
playbackcore/libs/lib-decoder-ffmpeg-release.aar
playbackcore/libs/lib-decoder-iamf-release.aar
playbackcore/libs/lib-decoder-mpegh-release.aar
```

`app/build.gradle.kts` pulls these in via `implementation(files(...))`
alongside the stock `media3-common`/`media3-ui`/`media3-session`/etc.
artifacts declared in `gradle/libs.versions.toml` (`media3` version). **These
two must be built from binary-compatible Media3 versions.** ExoPlayer's
internals aren't guaranteed stable across minor versions, so pairing a
stale `lib-exoplayer-release.aar` against a newer `media3-common` (or vice
versa) produces a `NoSuchMethodError`/`LinkageError` — and because these
classes only load lazily when `ExoPlayer.Builder(...).build()` runs, the
symptom is: **the app opens fine and crashes only when you start
playback.** This exact bug shipped in a real release once (see the commit
history around `f3493c4`/`d2d5629`/`16a16b7` and
[`HereLiesAz/illumera#18`](https://github.com/HereLiesAz/illumera/pull/18)
for the full incident) — if you ever see that symptom reported, check this
first.

### How the rebuild works

In the central Android release workflow (`HereLiesAz/workflows`):

1. `STREMIO_MEDIA_REF` (a pinned commit SHA) and `STREMIO_AAR_CACHE_VERSION`
   are set as env vars at the top of the job. Bump
   `STREMIO_AAR_CACHE_VERSION` whenever you change anything about how the
   AARs are built (toolchain, script) without changing `STREMIO_MEDIA_REF`,
   so stale cached artifacts can't be reused.
2. The Stremio/media AARs are cached by `(STREMIO_MEDIA_REF, cache version)`.
   On a cache miss, `Stremio/media` is checked out at that ref and
   [`ci/build-stremio-media.sh`](../ci/build-stremio-media.sh) builds
   `:lib-exoplayer`, `:lib-decoder-av1`, `:lib-decoder-ffmpeg`,
   `:lib-decoder-iamf`, `:lib-decoder-mpegh` with Gradle, using a pinned NDK
   (`27.0.12077973`) and CMake version.
3. The five resulting AARs are copied into `playbackcore/libs/`, overwriting
   whatever was checked into the repo, **before** `assembleRelease` runs.
   The workflow refuses to publish if any of the five is missing.

**Important:** the AARs checked into `playbackcore/libs/` in git are
therefore only ever used by a local `./gradlew assembleDebug`/`assembleRelease`
— the actual published release always gets a freshly-built set matching
`STREMIO_MEDIA_REF`. If you build locally without also running
`ci/build-stremio-media.sh` yourself, you're using whatever stale AARs
happen to be checked in, which is exactly the trap above.

Gotcha specific to `Stremio/media`'s build: its root `gradle.properties` sets
`buildDir=buildout`, which Gradle applies as a project property to *every*
subproject — so each module's build output lands under
`<module>/buildout/outputs/aar/`, not the Gradle-default
`<module>/build/outputs/aar/`. `build-stremio-media.sh`'s
`copy_release_aar()` checks both locations for this reason.

### Bumping the Media3 version

To move to a newer Media3 base:

1. Update `media3` in `gradle/libs.versions.toml`.
2. Update `STREMIO_MEDIA_REF` to a `Stremio/media` commit built against a
   compatible (ideally identical) Media3 version, and bump
   `STREMIO_AAR_CACHE_VERSION`.
3. Check `assrender/`'s `Renderer` subclasses (`AssSubtitleRenderer.kt`,
   `AssTextRenderer.kt`) against the new Media3 version's
   `androidx.media3.exoplayer.BaseRenderer`/`Renderer` method signatures —
   `onEnabled`, `onStreamChanged`, `onPositionReset`, `onDisabled`, `render`,
   `isReady`, `isEnded`. These have changed across Media3 versions before
   (`onPositionReset` gained a third `sampleStreamIsResetToKeyFrame`
   parameter between 1.4.1 and 1.10.1) and the compiler will only catch it
   if the signature actually changed shape rather than just semantics —
   don't rely on it silently. The actual current signatures for any Media3
   version are in that version's `-sources.jar`, downloadable from Google's
   Maven repo, e.g.
   `https://dl.google.com/android/maven2/androidx/media3/media3-exoplayer/<version>/media3-exoplayer-<version>-sources.jar`
   (not Maven Central — androidx.media3 core artifacts aren't published
   there).
4. Push to a branch and watch the central Android release run (its status
   appears on the commit) through to a successful, published release — a green build is the only
   real confirmation the pairing is compatible; there's no offline check
   for the `playbackcore/` AAR side of this. If it fails, read the actual
   Gradle error in the failed job's logs rather than assuming it's the
   version pairing — of the failures this pipeline has actually hit in
   practice, two were CI/toolchain issues (wrong NDK version, wrong
   output-directory assumption) and one was exactly the `assrender`
   signature drift described in step 3.
