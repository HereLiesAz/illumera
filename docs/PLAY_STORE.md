# Google Play submission notes

Draft answers for Play Console's **Data safety** form and **content rating**
(IARC) questionnaire, plus what else Play will ask for. Based on an audit of
what the app actually sends/stores (originally v0.6.0; the integrations and the `play` build type below are current as of October 2026) — re-verify anything marked
**[confirm]** against the current Play Console UI before submitting, since
Google revises the exact wording and category boundaries of these forms
periodically.

## Read this first: distribution risk

Play has increasingly removed or rejected "universal streaming client" apps
in illumera's category — Kodi-style and Stremio-compatible clients whose
value is pulling video from arbitrary, user-supplied third-party addons,
especially when the app also resolves magnet links (via the bundled
`TorrentService`) or debrid-service accounts. Google's policy teams treat
that combination as facilitating access to infringing content, independent
of whether the app hosts anything itself. Two features in particular raise
the risk profile above a typical "bring your own addons" client:

- The built-in torrent engine (`TorrentService`, `TorrServerEngine`) —
  resolves magnet links directly on-device.
- Debrid-provider integration (Real-Debrid, AllDebrid, Premiumize, TorBox,
  Debrid-Link, Offcloud, EasyDebrid) — these services exist specifically to
  turn torrents/cached links into direct streams.

Neither the README's existing disclaimer nor a well-filled-out Data Safety
form changes this — it's a content-policy risk, not a privacy one. If Play
distribution is the goal, decide up front whether to submit as-is and expect
scrutiny/possible rejection, or strip the torrent and debrid features
from the Play build to make it closer to a pure Stremio addon client.

Status: a `play` build type already exists in `app/build.gradle.kts`
(`assemblePlay`/`bundlePlay`, a Play-only manifest in `app/src/play/`). It
turns self-update off and Play in-app updates on, removes
`REQUEST_INSTALL_PACKAGES`, and keeps crash reporting off. It does **not**
strip the torrent engine or debrid support, so the risk above applies to it
as it stands. This doc assumes submitting that build; decide on a stripped
variant before spending time on one.

## Data safety form

### Does your app collect or share any of the required user data types?
**Yes.**

### Is all of the user data collected by your app encrypted in transit?
**[confirm against the current Play form before submitting.]** Internet-facing
account/provider integrations (Trakt, Stremio, TMDB, debrid APIs and crash
reporting in the GitHub build) use HTTPS, but the app intentionally also uses cleartext HTTP for
its loopback TorrServer (`http://127.0.0.1:8090`) and local-LAN pairing flows.
User-configured addon endpoints may also be plain `http://`. Do **not** claim
that every network call is HTTPS; if Play's question requires every supported
transport path to be encrypted, answer **No**.

### Does your app provide a way for users to request that their data be deleted?
**Yes, in-app.** Settings → Integrations has an explicit "Disconnect" action
for each connected service (Stremio, Trakt, each debrid provider) that wipes
the locally-stored credential immediately for the active profile — no waiting
period, no account needed to make the request. Note the scope: this deletes what *illumera*
holds (the token/API key on-device). Deleting data the third-party service
itself retains (e.g. a user's Trakt watch history on trakt.tv) is that
service's own responsibility, governed by their account-deletion flow, not
illumera's — Play's form has room to note this distinction if asked.

### Data types

| Category | Type | Collected? | Shared? | Purpose | Notes |
|---|---|---|---|---|---|
| Personal info | Email address | Yes | No | Account management | Stremio login only — sent directly from the device to `api.strem.io`, never touches an illumera-operated server. **[confirm]**: Play generally does not count "sent directly to a third-party service the user chose to connect, for that service's own use" as *shared by the app* — but confirm this reading against the current form's help text before answering "No" to Shared. |
| Personal info | User IDs | Yes | No | Account management | Trakt uses OAuth device-code flow (no password in-app); the app stores only the resulting access/refresh token, not a username/email. Debrid providers: the app stores the provider-returned username for display, plus the user-supplied API key. |
| App activity | App interactions | Yes | No | App functionality | Continue Watching / playback progress, sent to a connected Stremio account (`datastoreGet`/`Put`) and/or Trakt (scrobbles, watch history) — only when the user has explicitly connected that account. Not collected at all if no account is connected. |
| App info and performance | Crash logs | No (Play build) | No | Analytics (crash reporting) | The `play` build sets `CRASH_REPORTING_AVAILABLE = false`: ACRA is not initialized and nothing is sent. Only the GitHub release build reports crashes, via ACRA → the developer's HereLiesAz/workflows gateway Worker (`/crash-report/illumera`), which files a GitHub issue (stack trace, app/Android version, device model, memory; no credentials or tokens). **[confirm]** If a Play build ever turns crash reporting on, answer **Yes/Yes** for crash logs and diagnostics, since the report becomes a public GitHub issue. |
| App info and performance | Diagnostics | No (Play build) | No | Analytics (crash reporting) | Same as above. Play itself still collects crash and ANR data from users who opt in; that is Google's collection, not the app's. |
| App activity | Search history, in-app content ids | Yes | Yes | App functionality | Titles and IMDb/TMDB ids are sent to **TMDB** (metadata), the **Soundtrack addon** (IMDb ids) and **Tunefind** (page requests from an off-screen web view), and trailers are fetched from **YouTube**; every user-configured addon receives the ids and search text for the requests it serves. No account is involved and no identifiers beyond the device's IP address and the request itself. **[confirm]** whether Play counts these as "shared" (service providers acting on the app's behalf are usually exempt; arbitrary user-chosen addons are not). |
| Personal info | User IDs (wutch.tv) | Yes | No | Account management | Like Trakt: a personal API key the user pastes, stored encrypted on-device and sent only to `wutch.tv`. |

Everything else on Play's standard list — **location, financial info, health
&amp; fitness, messages, photos/videos, audio files, files &amp; docs, calendar,
contacts, web browsing history** — is **not collected**. The app has no
camera/microphone/location/contacts permission and no such API usage
anywhere in the codebase.

### Security practices to check on the form
- "Data is encrypted in transit" → **[confirm]** using the current Play definition; do not select Yes if the supported cleartext localhost/LAN/user-configured HTTP paths are disqualifying (see above).
- "You can request that data be deleted" → **Yes** (see above).
- "Data is encrypted at rest" — the three credential stores
  (`trakt_auth`, `stremio_secure_prefs`, `debrid_auth`) all use
  `androidx.security.crypto.EncryptedSharedPreferences` (AES-256-GCM/SIV
  backed by the Android Keystore). There is no plaintext
  fallback: if the device's Keystore is unrecoverably corrupted,
  `StremioAuthManager.kt` resets the store and, if that still fails, does not
  persist the Stremio credentials at all (the user must sign in again), and
  deletes any legacy plaintext prefs file. Answer "Yes" for the form.
- "Independent security review" — no, unless one has actually been
  commissioned; answer No/not applicable.

## Content rating (IARC) questionnaire

Answer as a **video/media player app**, not as a content publisher — the app
ships with zero content and zero catalogs of its own; every catalog, stream,
and piece of metadata comes from addons the user installs. Suggested
answers to IARC's category questions:

- **Violence, sexual content, profanity, controlled substances, gambling
  depictions** — the app itself contains none. However, IARC (and Play's
  own review) asks about content *accessible through* the app, not just
  bundled with it. Because installed addons can surface literally anything
  (mainstream film/TV catalogs, unmoderated user content, or worse), the
  honest answer to "can users access user-generated or unrated content
  through this app" is **Yes**, which typically pushes the rating to Mature
  17+ / AO-adjacent regardless of how the bundled UI itself behaves. Do not
  under-answer this to chase a lower rating — a mismatch between the
  declared rating and what the app can actually display is itself a policy
  violation.
- **User-generated content / user communication** — technically no in-app
  chat or posting, but addons can surface arbitrary third-party/UGC catalogs
  (e.g. YouTube-style or forum-sourced addons) — disclose this the same way.
- **Shares user location** — No.
- **Digital purchases** — No (no IAP/billing integration anywhere in the
  codebase).
- **Unrestricted internet access** — Yes, prominently: this is the app's
  entire purpose (fetching from arbitrary addon URLs the user supplies).

## Target audience &amp; ads declaration
- **Target age group**: general audience is not accurate given the content
  risk above — select an adult-skewing range (18+) rather than a children's
  or "13+" bracket. There's no age gate, parental control, or content filter
  anywhere in the app.
- **Contains ads**: No (no ad SDK integrated).
- **In-app purchases**: No.
- **Made for families / target children**: No — do not opt into the
  Families program; nothing about this app is appropriate for it.

## Permissions Play will ask you to justify

Play's Play Console flags certain "sensitive" permissions and asks for a
declaration form/justification. From the manifest:

| Permission | Justification to give Play |
|---|---|
| `REQUEST_INSTALL_PACKAGES` | **Not requested by the `play` build**: `app/src/play/AndroidManifest.xml` removes it, `ENABLE_SELF_UPDATE` is false and updates use Play's in-app update flow. Only the GitHub release build declares it, for `AppUpdateManager`'s self-update from GitHub Releases. Don't declare it in Play Console. |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` | `TorrentService` runs as a foreground service while actively streaming/downloading a torrent, so Android doesn't kill the process mid-playback. |
| `POST_NOTIFICATIONS` | Required alongside the foreground service above — shows the persistent "Torrent Download" notification Android mandates for a running foreground service. |
| `WAKE_LOCK` | Keeps the device awake during active torrent streaming so playback doesn't stall. |
| `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` | Connectivity checks and LAN IP discovery for the QR-pairing remote-input servers (phone-to-TV addon/avatar/credential entry). |

## Privacy policy

Play requires a hosted, publicly reachable privacy policy URL for any app
handling accounts/credentials (this one does) — the text below is a draft;
it still needs to be **published somewhere with a stable URL** (e.g. a page
on hereliesaz.com, or a GitHub Pages site for this repo) before it can be
entered in Play Console. Point Play at the eventual specific URL.

<details>
<summary>Draft privacy policy text</summary>

**illumera Privacy Policy**

illumera is a media client for Stremio-compatible addons. This policy covers
what the app itself does with your data — it does not cover the practices of
third-party addons you install, or of the third-party services (Stremio,
Trakt, debrid providers) you may choose to connect.

*What we collect*: illumera does not operate its own backend or user-account
system. When you connect a third-party account, your credentials are sent
directly from your device to that service (Stremio's, Trakt's, or your
debrid provider's own servers) and stored only on your device, encrypted via
Android's Keystore-backed encrypted storage. We never see or store your
credentials on any server we operate.

*Crash reports*: the Google Play build sends none. In the GitHub release
build, if the app crashes (or stops responding), a report containing the
stack trace, app/Android version, and device model/memory may be sent to our
crash reporting endpoint and filed as an issue in our public GitHub
repository, unless you turn this off in Settings. This report never contains
your account credentials or personal watch data.

*Third-party services*: connecting Stremio, Trakt, wutch.tv, or a debrid
provider sends the data described above directly to that service, governed
by its own privacy policy. Metadata and playback features also contact TMDB,
Tunefind and YouTube, and playing a torrent source joins the BitTorrent
network, whose peers and trackers can see your IP address. See the full
policy in [`PRIVACY_POLICY.md`](PRIVACY_POLICY.md). Uninstalling addons or
disconnecting an account (Settings → Integrations) removes the corresponding
data from your device immediately.

*Children*: illumera is not directed at children and is not appropriate for
them, given that it can be configured to access unrated/unmoderated content
through third-party addons.

*Contact*: [add a contact email/URL before publishing].

</details>

## Store listing content

- **Short description**: reuse the README's opening line — "A feature-rich
  Android TV streaming application for Stremio-compatible addons."
- **Screenshots**: `screenshots/*.png` in this repo are ready to reuse
  as-is (already TV-shaped 16:9 captures).
- **Feature graphic / icon**: `docs/illumera.png` (transparent) and
  `docs/illumera2.png` (black background) are the master art per
  [`BRANDING.md`](BRANDING.md) — derive Play's required feature graphic
  (1024×500) and hi-res icon (512×512) from these rather than redrawing.
