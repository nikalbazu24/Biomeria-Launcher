package com.prissma.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdaterPreviewTest {
    private fun release(tag: String) = UpdateInfo(tag, "", "https://github.com/preview.apk")

    @Test
    fun choosesTheHighestBuildNumberRegardlessOfListingOrder() {
        val releases = sequenceOf(release("v1.0.0-beta.70"), release("1.0.0-beta.100"), release("1.0.0-beta.71"))
        assertEquals("1.0.0-beta.100", newestPreview(releases, 69)?.versionTag)
    }

    @Test
    fun ignoresOlderAndAlreadyInstalledBuilds() {
        val releases = sequenceOf(release("v1.0.0-beta.64"), release("1.0.0-beta.70"))
        assertNull(newestPreview(releases, 70))
    }

    @Test
    fun ignoresTagsOutsideTheVersionScheme() {
        val releases = sequenceOf(release("v2.0.0"), release("prissma-nightly"), release("v1.0.0-beta.70"))
        assertEquals("v1.0.0-beta.70", newestPreview(releases, 69)?.versionTag)
        assertNull(betaBuildNumber("v1.0.0-beta.9223372036854775808"))
    }
}
