package com.hereliesaz.illumera.data.soundtrack

import com.google.gson.Gson
import com.hereliesaz.illumera.data.cache.boundedCache
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Songs from Tunefind, read on the device through [PageScriptRunner]. Tunefind offers no
 * usable API, so its public pages are read the way a browser shows them. Covers movies and
 * single episodes; a whole series would take one page per episode and is left to IMDb.
 *
 * Page structure is not guaranteed: every step returns null on anything unexpected, and
 * callers fall back to IMDb alone.
 */
@Singleton
class TunefindSource @Inject constructor(private val pages: PageScriptRunner) {
    private val cache = boundedCache<String, List<SoundtrackSong>>(100)

    /** Songs in order of appearance, or null when the title can't be found or read. */
    suspend fun load(type: String, title: String, season: Int? = null, episode: Int? = null): List<SoundtrackSong>? {
        val movie = type == "movie"
        if (!movie && (season == null || episode == null)) return null
        val key = "$type/${Tunefind.slug(title)}/$season/$episode"
        cache[key]?.let { return it }
        val songs = if (movie) movie(title) else episode(title, season!!, episode!!)
        return songs?.takeIf { it.isNotEmpty() }?.also { cache[key] = it }
    }

    private suspend fun movie(title: String): List<SoundtrackSong>? {
        val guessed = "/movie/${Tunefind.slug(title)}"
        songs(guessed, title)?.let { return it }
        val found = search(title, Tunefind.MOVIE_PATH) ?: return null
        return if (found == guessed) null else songs(found, title)
    }

    private suspend fun episode(title: String, season: Int, episode: Int): List<SoundtrackSong>? {
        val show = "/show/${Tunefind.slug(title)}"
        val links = seasonLinks(show, season)
            ?: search(title, Tunefind.SHOW_PATH)?.takeIf { it != show }?.let { seasonLinks(it, season) }
            ?: return null
        val path = Tunefind.pickEpisode(links, episode) ?: return null
        return songs(path, null)
    }

    private suspend fun seasonLinks(show: String, season: Int): List<Tunefind.Link>? {
        // Paths here are slugs ([a-z0-9-/]), so they need no regex escaping in JS.
        val pattern = "^$show/season-$season/\\d+/?$"
        return pages.run("$BASE$show/season-$season", Tunefind.linksScript(pattern), PAGE_TIMEOUT_MS)
            ?.let(Tunefind::parseLinks)?.takeIf { it.isNotEmpty() }
    }

    /** First search hit under [pathPattern] (movie or show), as a site path. */
    private suspend fun search(title: String, pathPattern: String): String? {
        val q = URLEncoder.encode(title, "UTF-8")
        return pages.run("$BASE/search/site?q=$q", Tunefind.linksScript(pathPattern), PAGE_TIMEOUT_MS)
            ?.let(Tunefind::parseLinks)?.firstOrNull()?.href
    }

    /** Songs on [path]; when [expectTitle] is set the page must be about that title. */
    private suspend fun songs(path: String, expectTitle: String?): List<SoundtrackSong>? {
        val page = pages.run("$BASE$path", Tunefind.SONGS_SCRIPT, PAGE_TIMEOUT_MS)?.let(Tunefind::parsePage) ?: return null
        if (expectTitle != null && !Tunefind.sameTitle(page.page, expectTitle)) return null
        return page.songs
    }

    private companion object {
        const val BASE = "https://www.tunefind.com"
        const val PAGE_TIMEOUT_MS = 20_000L
    }
}

/** Pure parts of [TunefindSource]: URL building, page scripts and result parsing. */
internal object Tunefind {
    const val MOVIE_PATH = "^/movie/[^/]+/?$"
    const val SHOW_PATH = "^/show/[^/]+/?$"

    private val gson = Gson()

    data class Link(val href: String = "", val text: String = "")
    data class Page(val page: String = "", val songs: List<SoundtrackSong> = emptyList())
    private data class RawSong(val title: String? = null, val artist: String? = null)
    private data class RawPage(val page: String? = null, val songs: List<RawSong>? = null)

    /** Tunefind's URL form of a title: "Grey's Anatomy" → "greys-anatomy". */
    fun slug(title: String): String = title.lowercase()
        .replace("&", " and ")
        .replace(Regex("['’.]"), "")
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')

    /** Loose title match: case, punctuation and a leading "The" are ignored. */
    fun sameTitle(pageTitle: String, title: String): Boolean {
        fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim().removePrefix("the ")
        return norm(pageTitle).contains(norm(title))
    }

    /** The link for [episode]: by its label ("E3", "Episode 3") first, else by position. */
    fun pickEpisode(links: List<Link>, episode: Int): String? {
        val label = Regex("\\b(?:e|ep|episode)\\s*0*$episode\\b", RegexOption.IGNORE_CASE)
        return links.firstOrNull { label.containsMatchIn(it.text) }?.href ?: links.getOrNull(episode - 1)?.href
    }

    fun parseLinks(json: String): List<Link> =
        runCatching { gson.fromJson(json, Array<Link>::class.java).toList() }.getOrDefault(emptyList())

    fun parsePage(json: String): Page? = runCatching {
        val raw = gson.fromJson(json, RawPage::class.java)
        val songs = raw.songs.orEmpty().mapNotNull { s ->
            val title = s.title?.trim().orEmpty()
            if (title.isEmpty()) null else SoundtrackSong(title, s.artist?.trim()?.takeIf { it.isNotEmpty() })
        }
        Page(raw.page.orEmpty(), songs)
    }.getOrNull()

    /** Tunefind first, in its order; IMDb songs it lacks follow; IMDb fills missing artists. */
    fun merge(tunefind: List<SoundtrackSong>, imdb: List<SoundtrackSong>): List<SoundtrackSong> {
        fun key(s: SoundtrackSong) = s.title.lowercase().replace(Regex("[^a-z0-9]"), "")
        val imdbByKey = imdb.associateBy(::key)
        val primary = tunefind.map { t -> if (t.artist == null) t.copy(artist = imdbByKey[key(t)]?.artist) else t }
        val seen = primary.map(::key).toSet()
        return primary + imdb.filter { key(it) !in seen }
    }

    /** Null while an interstitial is up; else same-site links matching the pattern, in page order. */
    fun linksScript(pattern: String): String = """
        (function(re){
          if (/just a moment|attention required/i.test(document.title)) return null;
          var r = new RegExp(re), seen = {}, out = [];
          document.querySelectorAll('a[href]').forEach(function(a){
            var u; try { u = new URL(a.getAttribute('href'), location.href); } catch (e) { return; }
            if (u.host !== location.host || !r.test(u.pathname) || seen[u.pathname]) return;
            seen[u.pathname] = 1;
            out.push({href: u.pathname.replace(/\/$/, ''), text: (a.textContent || '').replace(/\s+/g, ' ').trim()});
          });
          return out.length ? JSON.stringify(out) : null;
        })(${gson.toJson(pattern)})
    """.trimIndent()

    /**
     * Null until songs render. Reads embedded page data when present (any array of objects
     * with a name and artists), else rows holding artist links.
     */
    val SONGS_SCRIPT = """
        (function(){
          if (/just a moment|attention required/i.test(document.title)) return null;
          function clean(s){ return (s || '').replace(/\s+/g, ' ').trim(); }
          function artistsOf(a){
            if (Array.isArray(a)) return a.map(function(x){ return typeof x === 'string' ? x : (x && x.name); }).filter(Boolean).join(', ');
            return a && typeof a === 'object' ? a.name : a;
          }
          var out = [];
          try {
            var nd = document.getElementById('__NEXT_DATA__'), best = null;
            if (nd) (function walk(o, d){
              if (!o || typeof o !== 'object' || d > 40) return;
              if (Array.isArray(o)) {
                var s = o.filter(function(x){ return x && typeof x === 'object' && typeof (x.name || x.title) === 'string' && (x.artists || x.artist); });
                if (s.length && s.length * 2 >= o.length && (!best || s.length > best.length)) best = s;
                o.forEach(function(x){ walk(x, d + 1); });
              } else for (var k in o) walk(o[k], d + 1);
            })(JSON.parse(nd.textContent), 0);
            if (best) best.forEach(function(s){ out.push({title: clean(s.name || s.title), artist: clean(artistsOf(s.artists || s.artist)) || null}); });
          } catch (e) {}
          if (!out.length) {
            var rows = [];
            document.querySelectorAll('a[href*="/artist/"]').forEach(function(a){
              var row = a.closest('[class*="Song"],[class*="song"],article') || a.closest('li');
              if (!row || rows.indexOf(row) >= 0) return;
              rows.push(row);
              var t = row.querySelector('a[href*="/song/"],h3,h4,[class*="Title"],[class*="title"]');
              if (!t) return;
              var artists = Array.prototype.map.call(row.querySelectorAll('a[href*="/artist/"]'), function(x){ return clean(x.textContent); }).filter(Boolean);
              out.push({title: clean(t.textContent), artist: artists.join(', ') || null});
            });
          }
          return out.length ? JSON.stringify({page: document.title, songs: out}) : null;
        })()
    """.trimIndent()
}
