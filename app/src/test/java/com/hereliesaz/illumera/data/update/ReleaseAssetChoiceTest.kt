package com.hereliesaz.illumera.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseAssetChoiceTest {
    @Test fun theRealGroupedReleaseResolvesToItsBuild() {
        // HereLiesAz/illumera release 0.18.2 as published: one tag, the build's version in the file name.
        assertEquals(
            "0.18.2.1",
            AppUpdateManager.githubReleaseVersion("0.18.2", "illumera-0.18.2.1-release.apk")
        )
        assertTrue(AppUpdateManager.isVersionNewer("0.18.2.1", "0.18.1.1"))
        assertTrue(!AppUpdateManager.isVersionNewer("0.18.2.1", "0.18.2.1"))
    }

    @Test fun aGroupedReleaseWithSeveralBuildsOffersTheNewest() {
        // Re-run builds add 0.18.2.2 and 0.18.2.3 to the same release, listed in upload order.
        val assets = listOf(
            "illumera-0.18.2.1-release.apk",
            "mapping.txt",
            "illumera-0.18.2.3-release.apk",
            "illumera-0.18.2.2-release.apk"
        )
        assertEquals("illumera-0.18.2.3-release.apk", AppUpdateManager.newestApkAsset("0.18.2", assets))
    }

    @Test fun aSingleApkIsChosenAndNoApkIsNull() {
        assertEquals(
            "illumera-0.18.2.1-release.apk",
            AppUpdateManager.newestApkAsset("0.18.2", listOf("illumera-0.18.2.1-release.apk", "notes.txt"))
        )
        assertNull(AppUpdateManager.newestApkAsset("0.18.2", listOf("notes.txt")))
        assertNull(AppUpdateManager.newestApkAsset("0.18.2", emptyList()))
    }

    @Test fun anApkWithAnUnfamiliarNameFallsBackToTheTagAndLosesToAVersionedOne() {
        // "universal.apk" can't be matched to a build, so it counts as the tag (0.18.2); the
        // versioned build 0.18.2.1 is newer than that.
        assertEquals(
            "illumera-0.18.2.1-release.apk",
            AppUpdateManager.newestApkAsset("0.18.2", listOf("universal.apk", "illumera-0.18.2.1-release.apk"))
        )
    }
}
