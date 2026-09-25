package com.hereliesaz.illumera.data.soundtrack

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TunefindTest {

    @Test
    fun `slugs titles the way tunefind urls do`() {
        assertEquals("greys-anatomy", Tunefind.slug("Grey's Anatomy"))
        assertEquals("law-and-order", Tunefind.slug("Law & Order"))
        assertEquals("mr-robot", Tunefind.slug("Mr. Robot"))
    }

    @Test
    fun `matches titles loosely`() {
        assertTrue(Tunefind.sameTitle("The Bear Soundtrack | Tunefind", "Bear"))
        assertTrue(Tunefind.sameTitle("Pulp Fiction (1994) Soundtrack", "Pulp Fiction"))
        assertFalse(Tunefind.sameTitle("Jackie Brown Soundtrack", "Pulp Fiction"))
    }

    @Test
    fun `picks an episode by label, else by position`() {
        val links = listOf(Tunefind.Link("/e/10", "Episode 2"), Tunefind.Link("/e/11", "Episode 1"))
        assertEquals("/e/11", Tunefind.pickEpisode(links, 1))
        val unlabeled = listOf(Tunefind.Link("/e/1", "Pilot"), Tunefind.Link("/e/2", "Cat's in the Bag"))
        assertEquals("/e/2", Tunefind.pickEpisode(unlabeled, 2))
        assertNull(Tunefind.pickEpisode(unlabeled, 3))
    }

    @Test
    fun `episode 1 does not match episode 10`() {
        val links = listOf(Tunefind.Link("/e/10", "E10"), Tunefind.Link("/e/1", "E1"))
        assertEquals("/e/1", Tunefind.pickEpisode(links, 1))
    }

    @Test
    fun `parses pages and drops untitled songs`() {
        val page = Tunefind.parsePage("""{"page":"X","songs":[{"title":" A ","artist":""},{"title":""},{"title":"B","artist":"Band"}]}""")!!
        assertEquals(listOf(SoundtrackSong("A", null), SoundtrackSong("B", "Band")), page.songs)
        assertNull(Tunefind.parsePage("not json"))
    }

    @Test
    fun `merge keeps tunefind order, fills artists and appends imdb extras`() {
        val merged = Tunefind.merge(
            tunefind = listOf(SoundtrackSong("Jungle Boogie"), SoundtrackSong("Misirlou", "Dick Dale")),
            imdb = listOf(SoundtrackSong("Misirlou", "Dick Dale & His Del-Tones"), SoundtrackSong("jungle boogie!", "Kool & The Gang"), SoundtrackSong("It's Country")),
        )
        assertEquals(
            listOf(SoundtrackSong("Jungle Boogie", "Kool & The Gang"), SoundtrackSong("Misirlou", "Dick Dale"), SoundtrackSong("It's Country")),
            merged,
        )
    }

    private class FakePages(val pages: Map<String, String?>) : PageScriptRunner {
        val visited = mutableListOf<String>()
        override suspend fun run(url: String, script: String, timeoutMs: Long): String? {
            visited += url
            return pages[url]
        }
    }

    @Test
    fun `movie falls back to search when the guessed page is wrong`() = runTest {
        val pages = FakePages(mapOf(
            "https://www.tunefind.com/movie/heat" to """{"page":"Heat Wave","songs":[{"title":"Wrong"}]}""",
            "https://www.tunefind.com/search/site?q=Heat" to """[{"href":"/movie/heat-1995","text":"Heat"}]""",
            "https://www.tunefind.com/movie/heat-1995" to """{"page":"Heat (1995)","songs":[{"title":"Force Marker","artist":"Brian Eno"}]}""",
        ))
        val strict = FakePages(pages.pages + ("https://www.tunefind.com/movie/heat" to """{"page":"Something Else","songs":[{"title":"Wrong"}]}"""))
        assertEquals(listOf("Force Marker"), TunefindSource(strict).load("movie", "Heat")!!.map { it.title })
    }

    @Test
    fun `episode reads the season page then the episode page, and caches`() = runTest {
        val pages = FakePages(mapOf(
            "https://www.tunefind.com/show/breaking-bad/season-1" to
                """[{"href":"/show/breaking-bad/season-1/100","text":"S1 · E1 Pilot"},{"href":"/show/breaking-bad/season-1/101","text":"S1 · E2"}]""",
            "https://www.tunefind.com/show/breaking-bad/season-1/101" to """{"page":"x","songs":[{"title":"Song"}]}""",
        ))
        val source = TunefindSource(pages)
        assertEquals(listOf("Song"), source.load("series", "Breaking Bad", 1, 2)!!.map { it.title })
        source.load("series", "Breaking Bad", 1, 2)
        assertEquals(2, pages.visited.size)
    }

    @Test
    fun `whole series and unreadable pages give null`() = runTest {
        val source = TunefindSource(FakePages(emptyMap()))
        assertNull(source.load("series", "Breaking Bad"))
        assertNull(source.load("movie", "Nothing"))
    }
}
