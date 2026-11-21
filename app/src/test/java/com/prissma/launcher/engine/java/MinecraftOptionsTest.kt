package com.prissma.launcher.engine.java

import org.junit.Assert.assertEquals
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
}
