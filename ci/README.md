# CI release signing

`ci-debug.keystore` is a fixed, checked-in keystore used **only** as a fallback
when no real release keystore is configured — see
`.github/workflows/release.yml` and the signing block in `app/build.gradle.kts`.

It exists because Android's package installer rejects an app upgrade whose
signing certificate doesn't match the currently-installed one, and
`AppUpdateManager` now checks this explicitly too. Without a fixed keystore,
every GitHub Actions run would get a different randomly-generated debug
keystore (Android Studio's implicit `debug` signing config is generated
per-machine on first use), so consecutive CI releases would silently stop
being installable as upgrades over each other.

Its password is intentionally public (`illumera-ci-debug`, for both the
keystore and the key) — it provides **no security**, only a consistent
identity for test/debug releases. **Never use it for a real published
release** you expect users to trust or keep long-term; if this repo's CI
starts publishing releases meant for real users, add a real release keystore
(see below) so those builds stop being debug-signed.

## Switching to a real release keystore

1. Generate one (keep it somewhere safe — losing it means you can never sign
   an upgrade to an already-installed release again):
   ```bash
   keytool -genkeypair -v -keystore release.keystore -alias illumera-release \
     -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Base64-encode it and add these as **Actions secrets** on the
   `HereLiesAz/illumera` repo (Settings → Secrets and variables → Actions):
   - `KEYSTORE_RAW` — output of `base64 -w0 release.keystore`
   - `KEYSTORE_PASSWORD`
   - `KEY_ALIAS`
   - `KEY_PASSWORD`

   The following are optional certificate metadata (owner DN, SHA-1/SHA-256
   fingerprints, exported public/private keys and cert chain) some workflows
   generate alongside a keystore — handy for things like verifying
   `assetlinks.json` or enrolling in Play App Signing, but not read by this
   pipeline: `KEYSTORE_OWNER`, `KEYSTORE_SHA1`, `KEYSTORE_SHA256`,
   `KEYSTORE_PRIVATE`, `KEYSTORE_PUBLIC`, `KEYSTORE_CHAIN`, `KEYSTORE_RSA`.
3. Once all four required secrets are set, `.github/workflows/release.yml`
   picks them up automatically on the next push to `main` and stops using
   `ci-debug.keystore`. Note this is a one-way switch for real users: once
   they've installed a build signed with the real keystore, they can never
   go back to a `ci-debug.keystore`-signed build without uninstalling first
   (Android will refuse the "upgrade" as a signature mismatch).

For a local `./gradlew assembleRelease` signed with the real keystore instead
of the CI fallback, add the same four values to `local.properties` (not
committed) as `release.storeFile`, `release.storePassword`,
`release.keyAlias`, `release.keyPassword` — `release.storeFile` should be a
path to the keystore file, relative to the repo root or absolute.

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
the release build runs (`.github/workflows/release.yml` here only tracks it):
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

`../cloudflare-worker/` is the standalone relay this replaced; it isn't deployed.

## Automated PR review (Glee)

Two independent workflows run the same adversarial "glee" review (an
auditor whose job is to find failures, not admire the work) on every
non-draft pull request, each through a different model:

- `.github/workflows/glee-review.yml` — Claude, via
  `anthropics/claude-code-action`. Add this as an **Actions secret**:
  - `ANTHROPIC_API_KEY` — an Anthropic API key with access to a Claude model
- `.github/workflows/glee-review-antigravity.yml` — Google's Antigravity
  SDK, via the community `rsamborski/run-agy-sdk` action (pinned to a
  commit, not `@main` — see the comment in that workflow for why). Add this
  as an **Actions secret**:
  - `ANTIGRAVITY_API_KEY` — a Gemini/Antigravity API key from Google AI Studio

Either workflow's job simply fails at the review step if its secret is
missing; the other workflow (and the rest of CI) is unaffected.

`.github/copilot-instructions.md` also carries the glee persona for GitHub's
own built-in Copilot code review — useful if that's ever requested on a PR
(manually, or via a repo Ruleset), but nothing here auto-requests it.
