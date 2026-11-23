package com.biomeria.launcher.engine.java

import org.junit.Assert.assertEquals
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MinecraftOptionsTest {
    @Test
    fun desktopOverridesCannotForceAn854x480Window() {
        val options = minecraftOptions(listOf("overrideWidth:854", "overrideHeight:480", "fullscreen:true",
            "lang:en_us", "key_key.attack:key.mouse.left"), "es_cl")
        assertFalse(options.any { it.endsWith(":854") || it.endsWith(":480") })
        assertTrue(options.containsAll(listOf("overrideWidth:0", "overrideHeight:0", "fullscreen:false",
            "lang:es_cl", "key_key.attack:key.mouse.left")))
        assertEquals(1, options.count { it.startsWith("lang:") })
    }

    @Test
    fun systemSelectionKeepsInGameLanguage() {
        assertTrue("lang:fr_fr" in minecraftOptions(listOf("lang:fr_fr"), ""))
    }

    @Test
    fun oneCalibratedScrollStepReachesOneSlotAtLowSensitivity() {
        val options = listOf("mouseWheelSensitivity:0.25")
        assertEquals(1.0, hotbarScrollScale(options) * .25, .00000001)
    }

    @Test
    fun discreteScrollNeedsUnitSensitivityBecauseMinecraftDiscardsMagnitude() {
        val options = minecraftOptions(listOf("mouseWheelSensitivity:0.25", "discrete_mouse_scroll:true"), "")
        assertTrue("mouseWheelSensitivity:1.0" in options)
        assertEquals(1.0, hotbarScrollScale(options), .00000001)
    }

    @Test
    fun catalogKeepsAllChannelsAndLabelsUnsupportedRuntimes() {
        val manifest = JSONObject("""{"versions":[{"id":"26.3","type":"release"},
            {"id":"26.4-snapshot-10","type":"snapshot"},{"id":"1.20.1","type":"release"},
            {"id":"1.20.6","type":"release"},{"id":"b1.7.3","type":"old_beta"},{"id":"a1.2.6","type":"old_alpha"}]}""")
        val entries = minecraftCatalog(manifest)
        assertEquals(listOf("26.3", "26.4-snapshot-10", "1.20.1", "1.20.6", "b1.7.3", "a1.2.6"),
            entries.map { it.version })
        assertEquals(listOf(false, false, true, true, false, false), entries.map { it.playable })
        assertFalse(supportsFabric("1.20.1"))
        assertTrue(supportsFabric("1.20.6"))
    }

    @Test
    fun officialHistoricalVersionNamesMayContainSpaces() {
        assertTrue(validCatalogVersion("3D Shareware v1.34"))
        assertTrue(validCatalogVersion("1.14.2 Pre-Release 4"))
        assertFalse(validCatalogVersion("../versions"))
    }

    @Test
    fun vanillaAsmAndFabricAsmResolveToOneModule() {
        assertEquals(mavenModule("org.ow2.asm:asm:9.6"), mavenModule("org.ow2.asm:asm:9.7.1"))
        assertFalse(mavenModule("org.ow2.asm:asm-tree:9.6") == mavenModule("org.ow2.asm:asm:9.7.1"))
    }

    @Test
    fun fabricLibrariesUseMavenLayout() {
        assertEquals("net/fabricmc/sponge-mixin/0.15.4+mixin.0.8.7/sponge-mixin-0.15.4+mixin.0.8.7.jar",
            mavenPath("net.fabricmc:sponge-mixin:0.15.4+mixin.0.8.7"))
        assertThrows(IllegalArgumentException::class.java) { mavenPath("net.fabricmc:../bad:0.16.9") }
    }

    @Test
    fun minecraftsKeyboardTutorialIsOffUnlessAsked() {
        assertTrue("tutorialStep:none" in minecraftOptions(listOf("tutorialStep:movement"), ""))
        assertEquals(1, minecraftOptions(listOf("tutorialStep:movement"), "").count { it.startsWith("tutorialStep:") })
        assertTrue("tutorialStep:movement" in minecraftOptions(listOf("tutorialStep:movement"), "", tutorial = true))
    }
}
