package com.biomeria.launcher.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportTest {
    private val trace = """
        java.lang.NullPointerException: Attempt to invoke virtual method on a null object reference
            at com.biomeria.launcher.engine.java.OptionsWindow.GamePage(OptionsWindow.kt:412)
            at com.biomeria.launcher.engine.java.OptionsWindow.Rail(OptionsWindow.kt:120)
            at androidx.compose.runtime.Recomposer.run(Recomposer.kt:99)
    """.trimIndent()

    @Test
    fun sameErrorOnAnotherDeviceHasTheSameCode() {
        val a = buildReport("crash", trace, mapOf("Equipo" to "AYN Thor"))
        val b = buildReport("crash", trace.replace("412", "413"), mapOf("Equipo" to "Pixel 8"))
        assertEquals(a.fingerprint, b.fingerprint)
        assertEquals(8, a.fingerprint.length)
    }

    @Test
    fun differentErrorsHaveDifferentCodes() {
        val other = trace.replace("OptionsWindow.GamePage", "ModCatalog.readInstalledMods")
        assertNotEquals(buildReport("crash", trace, emptyMap()).fingerprint, buildReport("crash", other, emptyMap()).fingerprint)
        assertNotEquals(buildReport("crash", trace, emptyMap()).fingerprint, buildReport("juego", trace, emptyMap()).fingerprint)
    }

    @Test
    fun scrubsWhatCouldIdentifyThePlayer() {
        val dirty = "Setting user: Steve\nauthorization: Bearer abcdef0123456789abcdef\n--accessToken eyJhbGciOi.abcdefgh12.zzzzzzzz99\n" +
            "mail steve@example.com id 123e4567-e89b-12d3-a456-426614174000 token ghp_abcdefghijklmnopqrstuvwxyz0123456789"
        val clean = scrub(dirty)
        listOf("Steve", "abcdef0123456789abcdef", "eyJhbGciOi", "steve@example.com", "123e4567", "ghp_abcdef").forEach {
            assertFalse("$it sigue en el texto", it in clean)
        }
        assertTrue("[oculto]" in clean)
    }

    @Test
    fun titleHasTheCodeAndTheBodyStaysUnderGitHubsLimit() {
        val report = buildReport("crash", "Boom\n" + "x".repeat(200_000), mapOf("Versión" to "1.0.8"))
        assertTrue(report.title.endsWith(report.fingerprint))
        assertTrue(report.body().length <= CrashReport.MAX_BODY)
        assertNotNull(CrashReport.fromJson(report.toJson()))
    }
}
