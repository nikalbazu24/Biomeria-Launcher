package com.prissma.launcher.data

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModrinthCompatibilityTest {
    private val dualLoader = JSONObject("""{"game_versions":["1.21.4","1.21.5"],"loaders":["fabric","quilt"]}""")

    @Test
    fun exactMinecraftVersionAndLoaderAreBothRequired() {
        assertTrue(dualLoader.matchesGame("1.21.4", WorkshopCategory.MODS, "fabric"))
        assertFalse(dualLoader.matchesGame("1.21.6", WorkshopCategory.MODS, "fabric"))
        assertFalse(dualLoader.matchesGame("1.21.4", WorkshopCategory.MODS, "forge"))
        assertFalse(dualLoader.matchesGame("1.21.4", WorkshopCategory.MODS, null))
    }

    @Test
    fun resourcePacksDoNotRequireAModLoader() {
        assertTrue(dualLoader.matchesGame("1.21.4", WorkshopCategory.RESOURCE_PACKS, null))
        assertFalse(dualLoader.matchesGame("1.21.8", WorkshopCategory.RESOURCE_PACKS, null))
    }
}
