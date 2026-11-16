package com.hereliesaz.illumera.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayListingVersionTest {
    @Test
    fun readsTheVersionBesideTheSdkEntry() {
        val html = """..."141":[[["0.12.107.1"]],[[[37]],[[[26,"8.0"]]]]],"146":[["Oct 30, 2026",[1793348608,607000000]]],..."""
        assertEquals("0.12.107.1", parsePlayListingVersion(html))
    }

    @Test
    fun aSingleLooseMatchIsAcceptedButAmbiguousOnesAreNot() {
        assertEquals("1.2.3", parsePlayListingVersion("""x [[["1.2.3"]] y"""))
        assertNull(parsePlayListingVersion("""[[["1.2.3"]] ... [[["4.5.6"]]"""))
        assertNull(parsePlayListingVersion("no version here"))
    }
}
