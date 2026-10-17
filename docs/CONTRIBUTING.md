# Contributing

## Getting set up

See the main [`README.md`](../README.md#building) for the build
prerequisites. Read [`RELEASING.md`](RELEASING.md) before touching anything
under `playbackcore/`, `assrender/`, `ci/build-stremio-media.sh`, or
the release workflows (`.github/workflows/`, which hand off to
`HereLiesAz/workflows`) — the video playback stack is the most
fragile part of this project. A Media3 version mismatch between
`playbackcore/`'s custom ExoPlayer AAR and the declared `media3` version
fails silently at runtime until someone hits play; a Media3 version bump
that `assrender/`'s vendored `Renderer` overrides haven't caught up to
fails loudly at compile time instead (still worth checking for before you
push, not after CI tells you).

## Code style

- Kotlin, Jetpack Compose for TV, MVVM with Hilt. Follow the patterns
  already established in the package you're editing rather than
  introducing a new pattern for the same problem.
- New Composable screens go under `ui/<feature>/`, paired with a
  `@HiltViewModel` when they need state beyond simple `remember`s.
- New Room entities/DAOs go under `data/model/` and `data/local/`
  respectively; bump the database version and add a `Migration` in the
  `Database` class rather than relying on destructive fallback.
- Don't add a new built-in theme, addon source, or similar list-of-presets
  entry without checking whether it needs to be user-removable — built-ins
  (`isBuiltIn = true`) are intentionally permanent.

## Before opening a PR

- Run `./gradlew assembleDebug` (and `lint`/`test` if you touched logic
  those cover) locally first — faster feedback than waiting on CI.
- Shared CI/review automation is centralized in [`HereLiesAz/workflows`](https://github.com/HereLiesAz/workflows)
  and dispatched through its repository webhook gateway. The old local
  `ci.yml`, `glee-review.yml`, `glee-review-antigravity.yml`, and
  `glee-dual-audit.yml` files are intentionally retired; do not recreate
  them or add model API secrets to this repository. Glee uses the canonical
  repoless Codex audit and posts one PR audit comment. The workflow files
  that remain here are thin trackers for active centralized jobs; see
  [`ci/README.md`](../ci/README.md).
- If your change touches `playbackcore/`, the Stremio AAR pipeline, or
  `gradle/libs.versions.toml`'s `media3` version, say so explicitly in the
  PR description — those changes need the full CI release build to verify
  (a local build alone can't catch a Media3 ABI mismatch), and per
  [`RELEASING.md`](RELEASING.md) the only real confirmation is a green,
  published release build.
- Keep the PR scoped to one change. This repo's CI is slow and its release
  process is public-facing (every merge to `main` ships a real release to
  users via the in-app updater), so a mixed PR that's part feature and part
  unrelated refactor is harder to safely revert if the release it produces
  turns out to be broken.

## Reporting a bug

If the app crashes specifically **when starting playback** (not on
launch), read [`RELEASING.md`](RELEASING.md)'s section on the Stremio
playback module rebuild first — that exact symptom has a known root cause
(a Media3 version mismatch between the custom ExoPlayer AAR and the stock
Media3 artifacts) and has shipped in a real release before.
