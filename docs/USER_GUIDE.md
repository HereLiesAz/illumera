# illumera user guide

illumera is an Android TV streaming app. It finds titles through Stremio-compatible addons and plays them from the sources those addons return. This guide covers the everyday things; the About screen shows it too (Settings → About → User Guide).

## Getting started

1. **Pick or create a profile.** Each profile keeps its own addons, theme, layout and playback settings.
2. **Add addons.** Cinemeta, Streaming Catalogs and Torrentio come built in. Add more from **Add-ons** in the side menu, or connect a Stremio account under **Settings → Integrations** to import the addons you already use.
3. **Connect a debrid service (optional).** Under **Settings → Integrations → Debrid Service**. Torrent sources then play through the service instead of your own connection.

## Moving around

- **Side menu:** press **Left** at the left edge of any screen to open it. **Right** or **Back** closes it.
- **Back** steps out of whatever you are in: a panel, a dialog, then the screen.
- **Long-press** (or hold OK on) a card for its menu: add to the queue, mark watched, and **Love**, **Like** or **Don't like**. Those choices shape the suggestions you see.
- **Search** takes typed or spoken queries (the *Voice* key under the on-screen keyboard) and remembers recent searches per profile.

## Browsing

- **Home** has the hero carousel, **Continue watching**, rows from your addons' catalogs and up to two personal rows built from what you watched and rated.
- **Movies** and **Series** are organised by genre, one row per genre that has something in it.
- **Hub rows** are your own collections of categories. Create and arrange them from the dashboard editor in Settings.
- **Watchlist** shows suggestions, your **Queue**, and your **Library** (bookmarks plus a connected Stremio library) with sort chips above the grid.

## Playing something

1. Open a title to see its **Details**. For a series, pick an episode.
2. Press **Play**. If you have watched part of it, you can resume.
3. illumera asks your addons for sources, ranks them, and tries the best one. Turn on automatic source selection in Settings to skip the list; otherwise you pick from **Select Source**.

### The source list

Each row is one source. Torrent sources show where they stand in your debrid service:

- **In debrid · 100%**: already in your account, ready to play.
- **In debrid · N%** with a bar: your service is still downloading it.
- **Adding to debrid · 0%**: being sent to your service now.

Long-press a row to exclude it from the automatic queue.

### While it loads

The loading screen lists what is happening, step by step. For debrid it also lists **every source sent to your service for this title**, each with its own percentage and bar, so you can see which one is closest to ready.

You do not have to wait. **Next source** and **More sources** are available while a source is still loading. A source you move on from yourself is not brought back on its own; one that simply ran out of time is, if it turns out to be ready, but only after the next source has been tried.

### In the player

Buttons along the bottom: play/pause, episodes and next episode (series), next source, audio and subtitle choices, **More sources**, aspect ratio and close. Subtitles can be resized, moved and delayed from the player. If a source fails, the player moves to the next one (unless you turned that off).

## Add-ons

**Add-ons** is a top-level screen. Tabs along the top:

- **All**, **Official**, **Community**, **3rd Party** and any custom repositories you enabled: browse what is available.
- **Installed**: what you have, with enable/disable, configure and uninstall.
- **Not installed**: hides everything you already have, so only new addons show.
- **Install URL**: paste a manifest URL, or send one from your phone.

Choose which repositories appear, and add your own, under **Settings → Integrations**.

## Settings

- **Personalization**: autoplay previews, preview sound, navigation sounds, theme and layout.
- **Playback**: player preference, subtitles, language defaults, **Disable 3D** for TVs without 3D.
- **Watchlist**: the queue and the default Library sort.
- **Integrations**: Stremio account, debrid services, addon repositories, TorrentClaw and Trakt.
- **About**: version, **Check for Update**, **Change Log**, this guide and a short description of the app.

## Updates

**Settings → About → Check for Update** looks for a newer version and shows the update dialog if there is one, or **No new updates** with a **Play Store** button. A copy installed from Google Play updates through Google Play; a copy installed from GitHub downloads and installs the release itself.

**Change Log** shows what changed in the version you have.

## When something goes wrong

- **A source will not play:** use **Next source**, or pick another from **More sources**.
- **"Your debrid service couldn't deliver this torrent":** that source was not cached or ran out of time. The next source is tried automatically.
- **Nothing shows up:** check that the addons you expect are installed and enabled under **Add-ons → Installed**.
- **Buttons or text look wrong:** tell us what you saw; a photo of the screen helps.

illumera does not host or provide any content. You are responsible for the addons you install and what you watch with them.
