package com.prissma.launcher.engine.java

import kotlin.math.hypot
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal const val WAYPOINT_LIMIT = 32
internal const val WAYPOINT_NAME_LIMIT = 36

internal enum class WaypointColor(val label: String, val argb: Long) {
    GOLD("Oro", 0xffffcf80), GREEN("Verde", 0xffb3ea8d), BLUE("Azul", 0xff85d8ff),
    RED("Rojo", 0xffff9b98), PURPLE("Lila", 0xffd1b8ff), WHITE("Blanco", 0xffeef5fa)
}

internal enum class WaypointIcon(val label: String) {
    DIAMOND("Rombo"), CIRCLE("Círculo"), TRIANGLE("Triángulo"), SQUARE("Cuadrado")
}

internal data class TerrainWaypoint(
    val x: Int,
    val y: Int,
    val z: Int,
    val name: String,
    val color: WaypointColor = WaypointColor.GOLD,
    val icon: WaypointIcon = WaypointIcon.DIAMOND
) {
    val id get() = "$x,$y,$z"

    fun distanceFrom(player: TerrainPosition): Int = hypot(x - player.x, z - player.z).roundToInt()
}

internal data class TerrainWaypointBook(
    val points: List<TerrainWaypoint> = emptyList(),
    val activeId: String? = null
) {
    val active get() = points.firstOrNull { it.id == activeId }

    fun save(draft: TerrainWaypoint): TerrainWaypointBook {
        val name = draft.name.trim().take(WAYPOINT_NAME_LIMIT)
        if (name.isEmpty()) return this
        val point = draft.copy(name = name)
        val exists = points.any { it.id == point.id }
        return when {
            exists -> copy(points = points.map { if (it.id == point.id) point else it })
            points.size < WAYPOINT_LIMIT -> copy(points = points + point)
            else -> this
        }
    }

    fun remove(id: String) = copy(points = points.filterNot { it.id == id }, activeId = activeId.takeUnless { it == id })

    fun follow(id: String?) = copy(activeId = id?.takeIf { key -> points.any { it.id == key } })

    fun encode(): String = JSONObject().apply {
        put("active", activeId)
        put("points", JSONArray().apply {
            for (point in points) {
                put(JSONObject().apply {
                    put("x", point.x)
                    put("y", point.y)
                    put("z", point.z)
                    put("name", point.name)
                    put("color", point.color.name)
                    put("icon", point.icon.name)
                })
            }
        })
    }.toString()

    companion object {
        fun decode(stored: String): TerrainWaypointBook {
            if (!stored.startsWith('{')) {
                val points = stored.split(';').mapNotNull { entry ->
                    val coordinates = entry.split(',')
                    if (coordinates.size != 3) return@mapNotNull null
                    val x = coordinates[0].toIntOrNull() ?: return@mapNotNull null
                    val y = coordinates[1].toIntOrNull() ?: return@mapNotNull null
                    val z = coordinates[2].toIntOrNull() ?: return@mapNotNull null
                    TerrainWaypoint(x, y, z, "")
                }.distinctBy { it.id }.take(WAYPOINT_LIMIT)
                return TerrainWaypointBook(points.mapIndexed { index, point -> point.copy(name = "Marca ${index + 1}") })
            }
            return try {
                val json = JSONObject(stored)
                val array = json.getJSONArray("points")
                val points = buildList {
                    val seen = mutableSetOf<String>()
                    for (index in 0 until array.length()) {
                        val entry = array.optJSONObject(index) ?: continue
                        val point = decodePoint(entry) ?: continue
                        if (seen.add(point.id)) add(point)
                        if (size == WAYPOINT_LIMIT) break
                    }
                }
                TerrainWaypointBook(points).follow(json.optString("active").ifEmpty { null })
            } catch (_: JSONException) {
                TerrainWaypointBook()
            }
        }

        private fun decodePoint(entry: JSONObject): TerrainWaypoint? = try {
            TerrainWaypoint(
                entry.getInt("x"), entry.getInt("y"), entry.getInt("z"),
                entry.optString("name").trim().take(WAYPOINT_NAME_LIMIT).ifEmpty { "Marca" },
                WaypointColor.entries.firstOrNull { it.name == entry.optString("color") } ?: WaypointColor.GOLD,
                WaypointIcon.entries.firstOrNull { it.name == entry.optString("icon") } ?: WaypointIcon.DIAMOND
            )
        } catch (_: JSONException) {
            null
        }
    }
}
