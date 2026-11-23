package com.biomeria.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class WorkshopInstallProgressTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun downloadPercentUsesTransferredBytesAndStaysWithinBounds() {
        val download = WorkshopInstallProgress(WorkshopInstallStage.DOWNLOADING, downloadedBytes = 256, totalBytes = 1024)
        assertEquals(25, download.percent)
        assertEquals(100, download.copy(downloadedBytes = 2048).percent)
        assertEquals(0, download.copy(downloadedBytes = 0).percent)
        assertNull(download.copy(totalBytes = -1).percent)
    }

    @Test
    fun verificationAndInstallationDoNotDisplayADownloadPercentage() {
        val download = WorkshopInstallProgress(WorkshopInstallStage.DOWNLOADING, downloadedBytes = 1024, totalBytes = 1024)
        assertEquals(100, download.percent)
        assertNull(download.copy(stage = WorkshopInstallStage.VERIFYING).percent)
        assertNull(download.copy(stage = WorkshopInstallStage.INSTALLING).percent)
    }

    @Test
    fun failedPublicationRestoresExistingFilesAndClearsFilesAddedByTheJob() {
        val mods = folder.newFolder("mods")
        val stage = folder.newFolder("staging")
        val existing = File(mods, "existing.jar").apply { writeText("original") }
        val added = File(mods, "dependency.jar")
        val update = File(stage, "replacement").apply { writeText("updated") }
        val dependency = File(stage, "dependency").apply { writeText("verified") }
        val missing = File(stage, "missing")
        val changes = linkedMapOf(existing to update, added to dependency, File(mods, "last.jar") to missing)

        assertThrows(IOException::class.java) { publishWorkshopFiles(changes, stage) }

        assertEquals("original", existing.readText())
        assertFalse(added.exists())
    }
}
