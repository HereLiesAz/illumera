package com.hereliesaz.illumera.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayReleaseFeedTest {
    @Test fun theTitleTheWorkflowFilesIsParsed() {
        assertEquals(PlayRelease("0.18.2.1", 110080066), parsePlayReleaseTitle("Play release 0.18.2.1 (110080066)"))
        assertEquals(PlayRelease("1.0.0", 7), parsePlayReleaseTitle("  Play release 1.0.0 (7)  "))
    }

    @Test fun otherTitlesAreNotReleases() {
        assertNull(parsePlayReleaseTitle("Play publish deferred: daily save quota exceeded"))
        assertNull(parsePlayReleaseTitle("Play release 0.18.2.1"))
        assertNull(parsePlayReleaseTitle("Play release x.y (12)"))
        assertNull(parsePlayReleaseTitle("Crash: Play release 0.18.2.1 (110080066)"))
    }

    @Test fun theNewestByVersionCodeWinsWhateverTheOrder() {
        val json = """[
          {"number": 12, "title": "Play release 0.18.1.1 (110080065)"},
          {"number": 14, "title": "Play release 0.18.3.1 (110080068)"},
          {"number": 13, "title": "Play release 0.18.2.1 (110080066)"}
        ]"""
        assertEquals(PlayRelease("0.18.3.1", 110080068), latestPlayRelease(json))
    }

    @Test fun pullRequestsAndForeignIssuesAreIgnored() {
        val json = """[
          {"number": 1, "title": "Play release 9.9.9.9 (999999999)", "pull_request": {"url": "x"}},
          {"number": 2, "title": "Central run failing: illumera Android Play Release"},
          {"number": 3, "title": "Play release 0.18.2.1 (110080066)"}
        ]"""
        assertEquals(PlayRelease("0.18.2.1", 110080066), latestPlayRelease(json))
    }

    @Test fun nothingReadableIsNull() {
        assertNull(latestPlayRelease("[]"))
        assertNull(latestPlayRelease("""{"message": "Not Found"}"""))
        assertNull(latestPlayRelease("not json"))
    }

    @Test fun onlyAHigherVersionCodeIsNewer() {
        val release = PlayRelease("0.18.2.1", 110080066)
        assertTrue(isNewerThanInstalled(release, 110080065))
        assertFalse(isNewerThanInstalled(release, 110080066))
        assertFalse(isNewerThanInstalled(release, 110080070))
    }
}
