package com.biomeria.launcher.data

import com.biomeria.launcher.engine.java.supportsJavaLoader
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QuiltSupportTest {
    @Test
    fun quiltAcceptsFabricMods() {
        assertEquals(listOf("quilt", "fabric"), modrinthLoaders("quilt"))
        assertEquals(listOf("fabric"), modrinthLoaders("fabric"))
        val fabricOnly = JSONObject().put("game_versions", JSONArray().put("1.21.4")).put("loaders", JSONArray().put("fabric"))
        assertTrue(fabricOnly.matchesGame("1.21.4", WorkshopCategory.MODS, "quilt"))
        assertFalse(fabricOnly.matchesGame("1.21.4", WorkshopCategory.MODS, "neoforge"))
        assertEquals("Quilt", workshopModLoaderName("quilt"))
    }

    @Test
    fun quiltIsAValidLoader() {
        assertTrue(supportsJavaLoader("1.21.4", "Quilt"))
        assertTrue(supportsJavaLoader("1.20.1", "Quilt 0.29.2"))
        assertFalse(supportsJavaLoader("1.16.5", "Quilt"))
        val pack = decodeModpack(JSONObject().put("formatVersion", 1).put("game", "minecraft").put("name", "Q")
            .put("dependencies", JSONObject().put("minecraft", "1.21.4").put("quilt-loader", "0.29.2"))
            .put("files", JSONArray()))
        assertEquals("Quilt 0.29.2", pack.loader)
    }
}
