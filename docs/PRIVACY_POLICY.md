# Privacy Policy for illumera

**Effective date:** October 16, 2026

illumera is an Android TV media client developed by **HereLiesAz**. This Privacy Policy explains what information illumera accesses, stores, transmits, and shares when you use the app.

## Information illumera handles

### Information stored on your device

illumera may store information needed to provide its features, including:

- App profiles, preferences, theme settings, addon configuration, and playback settings.
- Watch history, playback progress, recent searches, queue information, and source-selection preferences.
- Account connection information and authentication tokens for services you choose to connect, such as Stremio, Trakt, wutch.tv, and supported debrid providers.
- Profile images, custom hub images, and other files you choose to provide to the app.

This information is used to provide the features you request. illumera does not sell this information.

### Information sent to third-party services

illumera connects to internet services only as needed to provide app functionality. Depending on the features and accounts you use, information may be sent to:

- **Stremio services**, for account authentication, addon synchronization, library synchronization, and playback-progress synchronization.
- **Trakt**, if you connect a Trakt account, for authentication, watch-history synchronization, scrobbling, recommendations, and related account features.
- **wutch.tv**, if you connect it with a personal API key, to import your watchlist and progress and to write progress and watchlist changes back.
- **The Movie Database (TMDB)**, for metadata enrichment (titles, descriptions, images, ratings, trailer listings). Requests carry the title or its IMDb/TMDB identifier and the language of your profile.
- **Tunefind**, for the song list of a movie or episode. illumera loads Tunefind's public pages on your device in an off-screen web view, so Tunefind receives an ordinary page request from your device. Song lists also come from the Soundtrack addon, which receives the IMDb id of the title.
- **YouTube**, to play trailers. illumera requests the trailer's page and stream details directly from YouTube, which receives ordinary request information from your device.
- **BitTorrent peers and trackers**, when you play a torrent source. illumera includes a TorrServer engine that runs on your device (`127.0.0.1`) and joins the BitTorrent network to fetch the file; the peers and trackers it contacts can see your IP address and the torrent's identifier. A torrent source can also be played through a TorrServer or Stremio streaming server you run elsewhere.
- **Debrid providers**, if you configure one, to resolve or manage media sources using the provider's API.
- **Stremio-compatible addons and other media APIs**, to retrieve catalogs, metadata, subtitles, streams, images, and related media information.
- **IntroDB or similar metadata services used by the app**, to obtain playback metadata such as intro and outro timing.
- **GitHub**, to check for application updates and retrieve release information.

Third-party services process information according to their own privacy policies and terms. illumera does not control those services.

## Crash reports

Crash reporting depends on which build you installed:

- **GitHub release builds** use **ACRA** to send crash reports, and ANR (app not responding) reports, to a developer-operated relay, which may forward them to the illumera GitHub repository for debugging. It is on by default and you can turn it off in Settings.
- **Google Play builds** include no crash reporting and send no crash or ANR reports.
- **Debug builds** do not send them either.

When reporting is on, a crash report may contain technical information such as the app version, Android version, device characteristics, stack traces, error messages, and other diagnostic information produced at the time of the crash. Crash reporting is used only to diagnose and fix software defects.

Crash reports are not used for advertising or profiling and are not sold.

## Network and device information

When illumera communicates with an internet service, that service may receive ordinary network information such as your IP address, request headers, device or app version information, and the information necessary to fulfill the request. This is inherent in internet communication and may also be logged by the service provider.

illumera does not use advertising identifiers and does not include advertising or behavioral-advertising SDKs.

## User-provided addons and content

illumera allows users to connect Stremio-compatible addons and other third-party services. These services can be operated by parties unrelated to illumera and may provide content or collect information independently of illumera.

Users are responsible for the addons and services they choose to configure. illumera does not host or control content supplied by third-party addons.

## Data sharing

illumera does not sell personal information.

Information is shared only when necessary to operate a feature you choose to use, such as communicating with a connected Stremio, Trakt, wutch.tv, debrid, addon, metadata (such as TMDB, Tunefind or YouTube), BitTorrent, update, or crash-reporting service.

## Security

illumera uses Android platform security features and protected local storage where appropriate. Network requests to services controlled by the developer use encrypted HTTPS connections. User-configured third-party addon endpoints are controlled by their respective operators, and their security practices may differ.

No method of storage or transmission can be guaranteed to be completely secure.

## Data retention and deletion

Locally stored illumera data remains on your device until you remove it through the app, clear the app's data, or uninstall the app, subject to Android system behavior.

Data transmitted to third-party services is retained according to those services' own policies. If you disconnect a third-party account from illumera, this does not automatically delete information already held by that service.

Where crash reporting is on (GitHub release builds), crash-report information may be retained for as long as reasonably necessary to investigate, document, and resolve software defects. Public or private GitHub issues created from crash reports may be deleted or redacted when they are no longer needed or when they contain information that should not have been included.

illumera does not create or operate a separate illumera cloud account system. To delete data held by a connected third-party account, use that provider's account or deletion controls.

## Children's privacy

illumera is a general-purpose media client and is not specifically directed to children. Because third-party addons and services may expose media intended for mature audiences, parents and guardians should supervise use where appropriate.

## Changes to this policy

This Privacy Policy may be updated when illumera's features, integrations, or data practices change. The effective date at the top of this document will be updated when material changes are made.

## Contact

For privacy questions, requests, or concerns, contact the developer through the illumera GitHub repository:

https://github.com/HereLiesAz/illumera/issues
