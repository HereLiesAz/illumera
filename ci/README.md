# CI and release credentials

## Release signing

Release and Play artifacts require the real release/upload keystore. The app
build deliberately fails `assembleRelease`, `bundleRelease`,
`assemblePlay`, and `bundlePlay` when the four signing values are absent;
it never reports success for an unsigned distributable artifact.

The checked-in `ci/ci-debug.keystore` is a legacy test identity and is **not**
a release-signing fallback. Do not publish builds signed with it.

The centralized release workflow in `HereLiesAz/workflows` supplies the
release signing material for published builds. For local distributable builds,
set these in `local.properties` (never commit them):

- `release.storeFile`
- `release.storePassword`
- `release.keyAlias`
- `release.keyPassword`

The equivalent CI environment variables are `KEYSTORE_FILE`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` (supplied by
`HereLiesAz/workflows` `android-release.yml`).

Release versions also come from that workflow as `-PversionCode` / `-PversionName`:
versionCode is one more than the highest Google Play has accepted, and versionName
raises only its last field. Local builds use the last published pair recorded in
`version.properties`.

Changing a release signing certificate is not an upgrade path: Android will
reject a package signed by a different key over an existing installation.

## Trakt API credentials

The Trakt integration (device-code login, scrobbling, sync) needs a Trakt API
app's client ID/secret at build time. Like release signing, `app/build.gradle.kts`
reads these from `local.properties` (`TRAKT_CLIENT_ID` / `TRAKT_CLIENT_SECRET`,
for local dev) and falls back to environment variables for CI. Without either,
they build in as empty strings and the app shows `DeviceAuthState.NotConfigured`
(Settings → Integrations → Trakt) instead of attempting the device code request.

**As of mid-2026, Trakt requires a paid Trakt VIP subscription just to register
a developer application** (trakt.tv/oauth/applications now shows "Creating new
apps requires Trakt VIP" and gives no free path to a client ID/secret) — this
used to be free when this integration was originally built, and isn't
something this repo can work around. If VIP is worth it: register an app at
https://trakt.tv/oauth/applications (redirect URI `urn:ietf:wg:oauth:2.0:oob`)
and add these as **Actions secrets** on the `HereLiesAz/workflows` repo, where
the release build runs (the Android release stubs in `.github/workflows/` here only hand off to it):
- `TRAKT_CLIENT_ID`
- `TRAKT_CLIENT_SECRET`

The next release build picks them up. In its "Build signed AAB" step log, a set
secret shows as `***` and a missing one as blank.

**Free alternative** (no illumera-side credentials needed): Trakt's *watchlist,
history, and recommendations as catalogs* — as opposed to illumera actively
scrobbling playback to Trakt — can be pulled in for free via Stremio itself.
Enable Trakt Scrobbling at stremio.com/acc-settings → Integrations, which
auto-installs a personal "Trakt Integration" addon into that Stremio account's
addon collection; then connect the Stremio account in illumera (Settings →
Integrations → Stremio) and use "Add New Addons" to import it like any other
addon. This does not give illumera two-way scrobbling/sync (that specifically
requires the VIP-gated client ID above), only the catalog rows.

## wutch.tv

Needs nothing here. wutch.tv's API takes the user's own account: signing in with
email and password makes a personal API key, which is all the app keeps
(`data/wutch/`). API reference: https://docs.wutch.tv/api.

## Crash report relay (ACRA)

Nothing to set up. Crashes and ANRs go to the HereLiesAz/workflows gateway
(`https://workflows.hereliesaz.workers.dev/crash-report/illumera`), which files
each as a GitHub issue on this repo, deduplicated by crash signature, using its
own GitHub App (`worker/src/crash-report.js` there). `app/build.gradle.kts`
defaults `ACRA_URL` and `ACRA_TOKEN` to that address and the app's key, which
ships in the APK and is only a spam filter. `acra.url`/`acra.token` in
`local.properties`, or `ACRA_URL`/`ACRA_TOKEN` secrets, override them.

## Automated PR review (Glee)

PR review is centrally managed by `HereLiesAz/workflows`. Repository webhook
events are routed through the central gateway, and the canonical Glee behavior
is the repoless Codex audit that posts one PR audit comment.

The former repository-local `glee-review.yml`,
`glee-review-antigravity.yml`, and `glee-dual-audit.yml` workflows were
removed intentionally. They must not be restored, and this repository no
longer needs `ANTHROPIC_API_KEY` or `ANTIGRAVITY_API_KEY` secrets for PR
review. Shared reviewer/provider credentials live with the centralized
automation instead.
