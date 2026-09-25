package com.hereliesaz.illumera.ui.soundtrack

import com.hereliesaz.illumera.data.soundtrack.Soundtrack
import com.hereliesaz.illumera.data.soundtrack.SoundtrackGroup
import com.hereliesaz.illumera.data.soundtrack.SoundtrackSong
import org.junit.Assert.assertEquals
import org.junit.Test

class SoundtrackRowsTest {

    private fun songs(vararg titles: String) = titles.map { SoundtrackSong(it) }

    @Test
    fun `series rows have a heading per episode and restart numbering`() {
        val rows = soundtrackRows(Soundtrack("Show", listOf(
            SoundtrackGroup(1, 1, "Pilot", songs("A", "B")),
            SoundtrackGroup(1, 2, "Two", songs("C")),
        )))
        assertEquals(
            listOf("S1 · E1  Pilot", "1 A", "2 B", "S1 · E2  Two", "1 C"),
            rows.map { if (it is ListRow.Heading) it.text else (it as ListRow.Song).let { s -> "${s.number} ${s.song.title}" } }
        )
    }

    @Test
    fun `a movie has no headings`() {
        val rows = soundtrackRows(Soundtrack("Film", listOf(SoundtrackGroup(songs = songs("A", "B")))))
        assertEquals(2, rows.size)
        assertEquals(true, rows.all { it is ListRow.Song })
    }
}
