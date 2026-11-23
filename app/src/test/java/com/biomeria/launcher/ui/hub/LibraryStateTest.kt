package com.biomeria.launcher.ui.hub

import com.biomeria.launcher.data.EngineType
import com.biomeria.launcher.data.GameInstance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryStateTest {
    private val valley = GameInstance("valley", "Valle", EngineType.JAVA, "1.21.4", "Fabric 0.16.9")
    private val coast = GameInstance("coast", "Costa", EngineType.BEDROCK, "26.3", "MCPE")
    private val lake = GameInstance("lake", "Lago", EngineType.JAVA, "1.20.1", "Vanilla")
    private val all = listOf(valley, coast, lake)

    @Test
    fun filtersFavoritesByEngineAndLoaderIgnoringCase() {
        val matches = visibleLibrary(all, EngineType.JAVA, " FABRIC ", setOf(valley.id, coast.id),
            favoritesOnly = true, recent = emptyList(), order = LibraryOrder.NAME)
        assertEquals(listOf(valley), matches)
    }

    @Test
    fun recentOrderKeepsUnopenedEntriesInCatalogOrder() {
        val rows = visibleLibrary(all, null, "", emptySet(), favoritesOnly = false,
            recent = listOf("deleted", lake.id), order = LibraryOrder.RECENT)
        assertEquals(listOf(lake, valley, coast), rows)
    }

    @Test
    fun nameOrderAndSelectionUseStableInstanceIds() {
        val rows = visibleLibrary(all, null, "", emptySet(), favoritesOnly = false,
            recent = emptyList(), order = LibraryOrder.NAME)
        assertEquals(listOf(coast, lake, valley), rows)
        assertEquals(valley, librarySelection(rows, valley.id))
        assertEquals(coast, librarySelection(rows, "missing"))
        assertNull(librarySelection(emptyList(), valley.id))
    }
}
