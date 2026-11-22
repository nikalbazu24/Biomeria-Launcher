package com.prissma.launcher.engine.java

import java.io.DataOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DualScreenBridgeTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun readsInventoryIncludingCraftingCursorAndNegativeCoordinates() {
        val bridge = DualScreenBridge.create(temporary.root)
        val timestamp = writeSnapshot(bridge.directory)
        val inventory = requireNotNull(bridge.readInventory(timestamp))

        assertEquals(7L, inventory.generation)
        assertTrue(inventory.interactive)
        assertEquals(PanelLayout.PERSONAL, inventory.layout)
        assertEquals(3, inventory.selectedSlot)
        assertEquals(-12, inventory.x)
        assertEquals(64, inventory.y)
        assertEquals(91, inventory.z)
        assertEquals(PanelStack("Roble", 8), inventory.held)
        assertEquals(PanelStack("Tablones", 4), inventory.slots.first())
        assertEquals(46, inventory.slots.size)
        assertEquals(15, inventory.mapRevision)
    }

    @Test
    fun ignoresStaleEmptyAndUnreadableSnapshots() {
        val bridge = DualScreenBridge.create(temporary.root)
        val timestamp = writeSnapshot(bridge.directory)
        assertNull(bridge.readInventory(timestamp + 1801))
        assertNull(bridge.readInventory(writeSnapshot(bridge.directory, selectedSlot = 9)))
        assertNull(bridge.readInventory(writeSnapshot(bridge.directory, ready = false)))
        assertNull(bridge.readInventory(writeSnapshot(bridge.directory, magic = 0)))
        val snapshot = File(bridge.directory, "snapshot")
        snapshot.writeBytes(byteArrayOf(0x50, 0x52, 0x53, 0x34))
        assertNull(bridge.readInventory(snapshot.lastModified()))
    }

    @Test
    fun recipeCommandsRequireTheLiveCraftingTableAndAnEmptyCursorForPlacement() {
        val bridge = DualScreenBridge.create(temporary.root)
        bridge.visibility(true)
        val personal = requireNotNull(bridge.readInventory(writeSnapshot(bridge.directory, recipeBookAvailable = true)))
        assertFalse(personal.recipeBookAvailable)
        assertFalse(bridge.send(personal, PanelCommand.PLACE_RECIPE, 501))

        val table = requireNotNull(bridge.readInventory(writeSnapshot(
            bridge.directory, generation = 8, layout = PanelLayout.CRAFTING_TABLE, recipeBookAvailable = true
        )))
        assertTrue(table.recipeBookAvailable)
        assertFalse(bridge.send(table, PanelCommand.INSPECT_RECIPE, 501))
        bridge.routeCrafting(8)
        bridge.viewRecipes(8)
        assertEquals("8", File(bridge.directory, "recipes-open").readText())
        assertTrue(bridge.send(table, PanelCommand.INSPECT_RECIPE, 501))
        assertFalse(bridge.send(table, PanelCommand.PLACE_RECIPE, 501))
        assertFalse(bridge.send(table, PanelCommand.INSPECT_RECIPE, -1))
        writeSnapshot(bridge.directory, generation = 8, layout = PanelLayout.CRAFTING_TABLE,
            recipeBookAvailable = true, heldCount = 0)
        assertTrue(bridge.send(table, PanelCommand.PLACE_RECIPE, 501))
        assertEquals("8 recipe 501", File(bridge.directory, "command-000000000002").readText())

        bridge.visibility(false)
        assertFalse(File(bridge.directory, "recipes-open").exists())
    }

    @Test
    fun craftingTableUsesItsOwnLayoutAndRejectsCommandsFromAnotherHandler() {
        val bridge = DualScreenBridge.create(temporary.root)
        bridge.visibility(true)
        val personal = requireNotNull(bridge.readInventory(writeSnapshot(bridge.directory)))
        val table = requireNotNull(bridge.readInventory(writeSnapshot(
            bridge.directory, generation = 8, layout = PanelLayout.CRAFTING_TABLE
        )))
        assertEquals(3, table.layout.craftSize)
        assertEquals(10, table.layout.inventoryStart)
        assertEquals(37, table.layout.hotbarStart)
        assertFalse(bridge.send(personal, PanelCommand.PICK, 9))
        assertFalse(bridge.send(table, PanelCommand.SELECT_HOTBAR, 0))
        assertFalse(bridge.send(table, PanelCommand.OPEN_XAERO))
        assertFalse(bridge.send(table, PanelCommand.PICK, 9))

        bridge.routeCrafting(table.generation)
        assertEquals("8", File(bridge.directory, "crafting").readText())
        assertTrue(bridge.send(table, PanelCommand.PICK, 9))
        assertTrue(bridge.send(table, PanelCommand.CLOSE_TABLE))
        writeSnapshot(bridge.directory, generation = 9)
        assertFalse(bridge.send(table, PanelCommand.CLOSE_TABLE))
        bridge.visibility(false)
        assertFalse(File(bridge.directory, "crafting").exists())
    }

    @Test
    fun xaeroMapRequiresSupportedModAndReadsBoundedWaypointList() {
        val bridge = DualScreenBridge.create(temporary.root)
        bridge.visibility(true)
        val withoutMods = requireNotNull(bridge.readInventory(writeSnapshot(bridge.directory)))
        assertFalse(bridge.send(withoutMods, PanelCommand.OPEN_XAERO))

        val mark = PanelWaypoint("Base", -20, 69, 12)
        val withMods = requireNotNull(bridge.readInventory(writeSnapshot(
            bridge.directory, xaeroWorldMap = true, xaeroMinimap = true, waypoints = listOf(mark)
        )))
        assertTrue(withMods.xaeroWorldMap)
        assertEquals(listOf(mark), withMods.waypoints)
        assertTrue(bridge.send(withMods, PanelCommand.OPEN_XAERO))
        assertNull(bridge.readInventory(writeSnapshot(bridge.directory, waypoints = listOf(mark))))
        assertNull(bridge.readInventory(writeSnapshot(bridge.directory, xaeroMinimap = true, waypoints = List(25) { mark })))
    }

    @Test
    fun preferencesKeepLegacyDefaultsAndRecoverInvalidValues() {
        assertEquals(PanelPage.CRAFT, PanelPage.fromPreference("CRAFT"))
        assertEquals(PanelPage.INVENTORY, PanelPage.fromPreference("OPTIONS"))
        assertEquals(PanelPage.INVENTORY, PanelPage.fromPreference(null))
        assertEquals(PanelMapSource.XAERO, PanelMapSource.fromPreference("XAERO"))
        assertEquals(PanelMapSource.MINECRAFT, PanelMapSource.fromPreference("unavailable"))
    }

    @Test
    fun commandsRequireVisiblePanelInteractiveSnapshotAndMatchingWorld() {
        val bridge = DualScreenBridge.create(temporary.root)
        val inventory = requireNotNull(bridge.readInventory(writeSnapshot(bridge.directory)))
        assertFalse(bridge.send(inventory, PanelCommand.PICK, 1))
        bridge.visibility(true)
        assertFalse(bridge.send(inventory, PanelCommand.SELECT_HOTBAR, 9))
        assertFalse(bridge.send(inventory, PanelCommand.PICK, 46))

        assertTrue(bridge.send(inventory, PanelCommand.SPLIT, 2))
        assertEquals("7 split 2", File(bridge.directory, "command-000000000001").readText())

        writeSnapshot(bridge.directory, generation = 8)
        assertFalse(bridge.send(inventory, PanelCommand.PICK, 1))
        writeSnapshot(bridge.directory, interactive = false)
        assertFalse(bridge.send(inventory, PanelCommand.PICK, 1))

        bridge.visibility(false)
        assertFalse(File(bridge.directory, "visible").exists())
        assertFalse(File(bridge.directory, "command-000000000001").exists())
    }

    @Test
    fun readsOnlyTheRequestedMapRevisionAndSize() {
        val bridge = DualScreenBridge.create(temporary.root)
        val file = File(bridge.directory, "map")
        val color = 0xff00aaff.toInt()
        DataOutputStream(file.outputStream()).use { out ->
            out.writeInt(15)
            repeat(128 * 128) { out.writeInt(color) }
        }
        assertNull(bridge.readMap(14))
        assertEquals(color, requireNotNull(bridge.readMap(15)).last())
        file.writeBytes(byteArrayOf(0, 0, 0, 15))
        assertNull(bridge.readMap(15))
    }

    @Test
    fun fullPanelUsesThePinnedVersionAndLoader() {
        assertTrue(supportsDualInventory("1.21.4", "fabric"))
        assertFalse(supportsDualInventory("1.21.4", "neoforge"))
        assertFalse(supportsDualInventory("1.21.4", null))
        assertFalse(supportsDualInventory("1.21.5", "fabric"))
    }

    private fun writeSnapshot(
        dir: File,
        generation: Long = 7,
        ready: Boolean = true,
        interactive: Boolean = true,
        selectedSlot: Int = 3,
        magic: Int = DUAL_SNAPSHOT_MAGIC,
        layout: PanelLayout = PanelLayout.PERSONAL,
        xaeroWorldMap: Boolean = false,
        xaeroMinimap: Boolean = false,
        waypoints: List<PanelWaypoint> = emptyList(),
        recipeBookAvailable: Boolean = false,
        heldCount: Int = 8
    ): Long {
        val file = File(dir, "snapshot")
        DataOutputStream(file.outputStream()).use { out ->
            out.writeInt(magic)
            out.writeLong(generation)
            out.writeBoolean(ready)
            out.writeBoolean(interactive)
            out.writeByte(layout.ordinal)
            out.writeInt(selectedSlot)
            out.writeDouble(-11.3)
            out.writeDouble(64.1)
            out.writeDouble(91.0)
            out.writeUTF("Roble")
            out.writeInt(heldCount)
            out.writeUTF("Tablones")
            out.writeInt(4)
            repeat(45) {
                out.writeUTF("")
                out.writeInt(0)
            }
            out.writeInt(15)
            out.writeBoolean(xaeroWorldMap)
            out.writeBoolean(xaeroMinimap)
            out.writeByte(waypoints.size)
            for (mark in waypoints) {
                out.writeUTF(mark.name)
                out.writeInt(mark.x)
                out.writeInt(mark.y)
                out.writeInt(mark.z)
            }
            out.writeBoolean(recipeBookAvailable)
        }
        return file.lastModified()
    }
}
