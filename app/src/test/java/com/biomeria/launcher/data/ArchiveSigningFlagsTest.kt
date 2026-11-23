package com.biomeria.launcher.data

import android.content.pm.PackageManager
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

@Suppress("DEPRECATION")
class ArchiveSigningFlagsTest {
    @Test
    fun olderAndroidVersionsRequestLegacyCertificates() {
        assertEquals(PackageManager.GET_SIGNATURES, archiveSigningFlags(Build.VERSION_CODES.O_MR1))
    }

    @Test
    fun androidPieCollectsAndExposesModernCertificates() {
        assertEquals(
            PackageManager.GET_SIGNATURES or PackageManager.GET_SIGNING_CERTIFICATES,
            archiveSigningFlags(Build.VERSION_CODES.P)
        )
    }

    @Test
    fun currentAndroidVersionsStillExposeModernCertificates() {
        val flags = archiveSigningFlags(Build.VERSION_CODES.VANILLA_ICE_CREAM)
        assertEquals(PackageManager.GET_SIGNING_CERTIFICATES, flags and PackageManager.GET_SIGNING_CERTIFICATES)
    }
}
