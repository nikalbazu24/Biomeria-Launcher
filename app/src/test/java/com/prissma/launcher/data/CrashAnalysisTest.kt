package com.prissma.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashAnalysisTest {
    @Test
    fun classifiesLevels() {
        assertEquals(LogLevel.INFO, classifyLogLine("[12:04:31] [main/INFO]: Loading Minecraft 1.21.4"))
        assertEquals(LogLevel.WARN, classifyLogLine("[12:04:41] [Render thread/WARN]: Missing sound"))
        assertEquals(LogLevel.ERROR, classifyLogLine("[12:05:02] [Render thread/ERROR]: Failed to load texture"))
        assertEquals(LogLevel.ERROR, classifyLogLine("[ERROR] No se pudo iniciar el hilo de Java (3)."))
        assertEquals(LogLevel.WARN, classifyLogLine("[AVISO] Instala Java 21 ARM64 para iniciar Minecraft."))
        assertEquals(LogLevel.ERROR, classifyLogLine("Caused by: java.lang.NullPointerException"))
    }

    @Test
    fun cleanQuitIsNotACrash() {
        val log = "[main/INFO]: Loading Minecraft\n[Render thread/WARN]: Missing sound\n[Render thread/INFO]: Stopping!\n"
        val reading = readCrash(log)
        assertFalse(reading.crashed)
        assertNull(reading.cause)
    }

    @Test
    fun explainsMissingFabricVersion() {
        val log = """
            [main/INFO]: Loading Minecraft 1.21.4 with Fabric Loader 0.16.9
            [main/ERROR]: Incompatible mods found!
             - Mod 'Example Mod' (examplemod) 1.0.0 requires version 0.17.0 or later of fabricloader, but only the wrong version is present: 0.16.9!
        """.trimIndent()
        val reading = readCrash(log)
        assertTrue(reading.crashed)
        val cause = reading.cause
        assertNotNull(cause)
        assertTrue(cause!!, cause.startsWith("El mod Example Mod necesita Fabric 0.17.0 o posterior"))
    }

    @Test
    fun explainsMissingMod() {
        val cause = crashCause("Mod 'Sodium' (sodium) 0.6.0 requires any version of mod 'fabric-api', which is missing!")
        assertEquals("El mod Sodium necesita fabric-api y no está instalado. Agrégalo desde el Taller.", cause)
    }

    @Test
    fun explainsMemoryAndUnknownErrors() {
        assertTrue(readCrash("Exception in thread \"main\" java.lang.OutOfMemoryError: Java heap space").cause!!.contains("memoria"))
        val unknown = readCrash("---- Minecraft Crash Report ----\nDescription: Something odd")
        assertTrue(unknown.crashed)
        assertNull(unknown.cause)
    }

    @Test
    fun keepsOnlyTheLastLines() {
        val log = (1..100).joinToString("\n") { "[main/INFO]: line $it" } + "\n#@!@# Game crashed!"
        val reading = readCrash(log, tailLines = 10)
        assertEquals(10, reading.tail.size)
        assertEquals("#@!@# Game crashed!", reading.tail.last())
    }
}
