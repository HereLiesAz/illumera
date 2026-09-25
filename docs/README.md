# illumera documentation

Deeper reference docs than the project [`README.md`](../README.md). Start
there for what the app does and how to build it; come here for how it's
built internally and how to work on it safely.

- [**ARCHITECTURE.md**](ARCHITECTURE.md) — module layout, data layer, DI,
  UI/navigation structure, the addon system, auto-update, QR remote input.
- [**RELEASING.md**](RELEASING.md) — the release pipeline, versioning,
  signing, and in detail: the Stremio Media3 fork rebuild that produces the
  app's custom ExoPlayer/decoder binaries. **Read this before touching
  `playbackcore/`, `ci/build-stremio-media.sh`, the `media3` version in
  `gradle/libs.versions.toml`, or `.github/workflows/release.yml`** — a
  mismatch there causes a crash that only reproduces when starting
  playback, which has shipped to users before.
- [**ADDONS.md**](ADDONS.md) — for addon developers: what illumera reads
  beyond the Stremio protocol, how stream titles are parsed and ranked, and
  what isn't supported, so one addon serves both apps well.
- [**PLATFORMS.md**](PLATFORMS.md) — the plan and to-do list for web, desktop,
  Samsung/LG TVs and Roku. The web app lives in [`web/`](../web/README.md).
- [**THEMING.md**](THEMING.md) — the built-in/custom theme system and how
  to add a new built-in theme.
- [**BRANDING.md**](BRANDING.md) — the logo assets, color palette, and
  every place the mark appears in the app.
- [**CONTRIBUTING.md**](CONTRIBUTING.md) — code style conventions and what
  to check before opening a PR.
- [**PLAY_STORE.md**](PLAY_STORE.md) — draft Data Safety form and content
  rating answers for a Google Play submission, plus the distribution-risk
  tradeoffs specific to this app's torrent/debrid features.
