package com.biomeria.launcher.engine.java

import org.junit.Assert.*
import org.junit.Test

class TerrainWaypointsTest {
    @Test
    fun keepsTheCoordinatesAndOrderOfOldWaypoints() {
        val book = TerrainWaypointBook.decode("-12,64,8;91,-20,-5;5,invalid,9;1,wrong,2,3;-12,64,8")
        assertEquals(listOf("-12,64,8", "91,-20,-5"), book.points.map { it.id })
        assertEquals(listOf("Marca 1", "Marca 2"), book.points.map { it.name })
        assertEquals(WaypointColor.GOLD, book.points.first().color)
        assertEquals(WaypointIcon.DIAMOND, book.points.first().icon)
        assertNull(book.active)
        assertEquals(book, TerrainWaypointBook.decode(book.encode()))
    }

    @Test
    fun preservesTheActiveWaypointWhileRenamingAndCustomizingIt() {
        val legacy = TerrainWaypointBook.decode("-12,64,8;91,64,-5")
        val point = legacy.points.first()
        val edited = point.copy(name = " Base; río, montaña ", color = WaypointColor.BLUE, icon = WaypointIcon.SQUARE)
        val saved = legacy.follow(point.id).save(edited)
        val loaded = TerrainWaypointBook.decode(saved.encode())
        assertEquals(saved, loaded)
        assertEquals(edited.copy(name = "Base; río, montaña"), loaded.active)
        assertEquals(legacy.points.last(), loaded.points.last())
        assertNull(loaded.remove(point.id).activeId)
        assertNull(loaded.follow(null).activeId)
        assertNull(loaded.follow("not-in-this-dimension").activeId)
    }

    @Test
    fun refusesToEvictAnOldWaypointWhenTheBookIsFull() {
        val book = TerrainWaypointBook.decode((1..32).joinToString(";") { "$it,64,0" })
        val extra = TerrainWaypoint(33, 64, 0, "Portal")
        assertEquals(book, book.save(extra))
        val first = book.points.first()
        assertEquals(book, book.save(first.copy(name = "  ")))
        val renamed = book.save(first.copy(name = "a".repeat(100)))
        assertEquals(WAYPOINT_NAME_LIMIT, renamed.points.first().name.length)
        assertEquals(book.points.drop(1), renamed.points.drop(1))
        assertEquals(extra, book.remove(first.id).save(extra).points.last())
    }

    @Test
    fun measuresHorizontalDistanceFromCurrentPosition() {
        val point = TerrainWaypoint(-4, 64, -3, "Portal")
        val player = TerrainPosition("scope", 0.0, 300.0, 0.0, 32f)
        assertEquals(5, point.distanceFrom(player))
        assertEquals(0, point.distanceFrom(player.copy(x = -4.0, z = -3.0)))
        assertEquals(8, point.distanceFrom(player.copy(x = 4.0, z = -3.0)))
    }

    @Test
    fun fallsBackToDefaultAppearanceAndDiscardsAnOrphanedTarget() {
        val saved = """{"active":"99,64,0","points":[{"x":1,"y":64,"z":0,"color":"OLD","icon":"OLD"},{"x":2}]}"""
        val book = TerrainWaypointBook.decode(saved)
        assertEquals(listOf(TerrainWaypoint(1, 64, 0, "Marca")), book.points)
        assertNull(book.activeId)
        assertEquals(TerrainWaypointBook(), TerrainWaypointBook.decode("{broken"))
    }
}
