package com.biomeria.launcher.ui.hub

import com.biomeria.launcher.data.EngineType
import com.biomeria.launcher.data.GameInstance

internal enum class LibraryOrder(val label: String) {
    RECENT("Recientes"), NAME("Nombre")
}

internal fun visibleLibrary(
    instances: List<GameInstance>,
    engine: EngineType?,
    query: String,
    favorites: Set<String>,
    favoritesOnly: Boolean,
    recent: List<String>,
    order: LibraryOrder
): List<GameInstance> {
    val search = query.trim()
    val matching = instances.filter {
        (engine == null || it.engine == engine) &&
            (!favoritesOnly || it.id in favorites) &&
            (search.isEmpty() || listOf(it.title, it.version, it.loader, it.engine.displayName)
                .any { value -> value.contains(search, ignoreCase = true) })
    }
    return when (order) {
        LibraryOrder.NAME -> matching.sortedBy { it.title.lowercase() }
        LibraryOrder.RECENT -> {
            val rank = recent.withIndex().associate { it.value to it.index }
            matching.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
        }
    }
}

internal fun librarySelection(instances: List<GameInstance>, selectedId: String?): GameInstance? =
    instances.firstOrNull { it.id == selectedId } ?: instances.firstOrNull()
