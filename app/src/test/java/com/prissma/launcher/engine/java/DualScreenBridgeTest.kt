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
        snapshot.writeBytes(byteArrayOf(0x50, 0x52, 0x53, 0x35))
        assertNull(bridge.readInventory(snapshot.lastModified()))
    }

    @Test
    fun acceptsAnAtomicReplacementThatArrivesJustAfterTheReadersClockSample() {
        val bridge = DualScreenBridge.create(temporary.root)
        val stamp = writeSnapshot(bridge.directory, layout = PanelLayout.CRAFTING_TABLE)
        assertEquals(PanelLayout.CRAFTING_TABLE, bridge.readInventory(stamp - 10)?.layout)
        assertNull(bridge.readInventory(stamp - 151))
    }

    @Test
    fun aBriefReadGapKeepsTheTableButDropsCommandsUntilAFreshSnapshotArrives() {
        val bridge = DualScreenBridge.create(temporary.root)
        val history = PanelSnapshotHistory()
        bridge.visibility(true)
        bridge.routeMenu(7)
        val stamp = writeSnapshot(bridge.directory, layout = PanelLayout.CRAFTING_TABLE)
        val table = requireNotNull(history.update(bridge.readSnapshot(stamp), 1000L))

        val malformed = writeSnapshot(bridge.directory, layout = PanelLayout.CRAFTING_TABLE, selectedSlot = 9)
        assertSame(table, history.update(bridge.readSnapshot(malformed), 1120L))
        assertSame(table, history.update(null, 1360L))
        assertFalse(bridge.send(table, PanelCommand.PICK, 1))
        assertNull(history.update(null, 1400L))

        val fresh = writeSnapshot(bridge.directory, layout = PanelLayout.CRAFTING_TABLE)
        assertEquals(table, history.update(bridge.readSnapshot(fresh), 1450L))
        val closed = writeSnapshot(bridge.directory, ready = false, generation = 8)
        assertNotNull(bridge.readSnapshot(closed))
        assertNull(history.update(bridge.readSnapshot(closed), 1460L))
    }

    @Test
    fun closingTheLowerDisplayRemovesTheOpeningPolicy() {
        val bridge = DualScreenBridge.create(temporary.root)
        val policy = File(bridge.directory, "menu-policy")
        bridge.visibility(true)
        bridge.prepareMenus(1 shl PanelLayout.CRAFTING_TABLE.ordinal)
        assertEquals("2", policy.readText())
        bridge.visibility(false)
        assertFalse(policy.exists())
    }

    @Test
    fun recipeCommandsRequireTheLiveCraftingTableAndAnEmptyCursorForPlacement() {
        val bridge = DualScreenBridge.create(temporary.root)
        bridge.visibility(true)
        val personal = requireNotNull(bridge.readInventory(writeSnapshot(bridge.directory, recipeBookAvailable = true)))
        assertTrue(personal.recipeBookAvailable)
        assertFalse(bridge.send(personal, PanelCommand.PLACE_RECIPE, 501))

        val table = requireNotNull(bridge.readInventory(writeSnapshot(
            bridge.directory, generation = 8, layout = PanelLayout.CRAFTING_TABLE, recipeBookAvailable = true
        )))
        assertTrue(table.recipeBookAvailable)
        assertFalse(bridge.send(table, PanelCommand.INSPECT_RECIPE, 501))
        bridge.routeMenu(8)
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
        assertFalse(bridge.send(table, PanelCommand.PICK, 9))

        bridge.routeMenu(table.generation)
        assertEquals("8", File(bridge.directory, "routed-menu").readText())
        assertTrue(bridge.send(table, PanelCommand.PICK, 9))
        assertTrue(bridge.send(table, PanelCommand.CLOSE_MENU))
        writeSnapshot(bridge.directory, generation = 9)
        assertFalse(bridge.send(table, PanelCommand.CLOSE_MENU))
        bridge.visibility(false)
        assertFalse(File(bridge.directory, "routed-menu").exists())
    }

    @Test
    fun decodesDoubleChestAndValidatesItsLastSlotsAgainstTheActiveMenu() {
        val bridge = DualScreenBridge.create(temporary.root)
        bridge.visibility(true)
        val double = requireNotNull(bridge.readInventory(writeSnapshot(
            bridge.directory, generation = 13, layout = PanelLayout.DOUBLE_CHEST, menuTitle = "Cofre grande"
        )))
        assertEquals("Cofre grande", double.menuTitle)
        assertEquals(90, double.slots.size)
        assertEquals(6, double.layout.chestRows)
        assertEquals(54, double.layout.inventoryStart)
        assertEquals(81, double.layout.hotbarStart)
        assertFalse(bridge.send(double, PanelCommand.PICK, 53))
        bridge.routeMenu(13)
        assertTrue(bridge.send(double, PanelCommand.PICK, 53))
        assertTrue(bridge.send(double, PanelCommand.QUICK_MOVE, 89))
        assertFalse(bridge.send(double, PanelCommand.QUICK_MOVE, 90))
        assertFalse(bridge.send(double, PanelCommand.PLACE_RECIPE, 40))
        val single = requireNotNull(bridge.readInventory(writeSnapshot(
            bridge.directory, generation = 14, layout = PanelLayout.CHEST
        )))
        assertEquals(63, single.slots.size)
        assertEquals(3, single.layout.chestRows)
        assertFalse(bridge.send(double, PanelCommand.PICK, 53))
    }

    @Test
    fun decodesFurnaceProgressAndSanitizesBounds() {
        val bridge = DualScreenBridge.create(temporary.root)
        val furnace = requireNotNull(bridge.readInventory(writeSnapshot(
            bridge.directory, layout = PanelLayout.FURNACE, furnace = PanelFurnace(.35f, 1.4f, true)
        )))
        assertEquals(39, furnace.slots.size)
        assertEquals(3, furnace.layout.inventoryStart)
        assertEquals(30, furnace.layout.hotbarStart)
        assertEquals(PanelFurnace(.35f, 1f, true), furnace.furnace)
        assertNull(bridge.readInventory(writeSnapshot(
            bridge.directory, layout = PanelLayout.FURNACE, furnace = PanelFurnace(Float.NaN, 0f, false)
        )))
    }

    @Test
    fun preferencesKeepLegacyDefaultsAndRecoverInvalidValues() {
        assertEquals(PanelPage.CRAFT, PanelPage.fromPreference("CRAFT"))
        assertEquals(PanelPage.INVENTORY, PanelPage.fromPreference("OPTIONS"))
        assertEquals(PanelPage.INVENTORY, PanelPage.fromPreference("STORAGE"))
        assertEquals(PanelPage.INVENTORY, PanelPage.fromPreference(null))
        assertEquals(PanelMapSource.PRISSMA, PanelMapSource.fromPreference("XAERO"))
        assertEquals(PanelMapSource.MINECRAFT, PanelMapSource.fromPreference("MINECRAFT"))
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

    @Test
    fun readsItemIdsWhenTheModSendsThem() {
        val bridge = DualScreenBridge.create(temporary.root)
        val ids = listOf("minecraft:oak_log", "minecraft:oak_planks") + List(PanelLayout.PERSONAL.slotCount - 1) { "" }
        val stamp = writeSnapshot(bridge.directory, ids = ids)
        val inventory = requireNotNull(bridge.readInventory(stamp))
        assertEquals("minecraft:oak_log", inventory.held.id)
        assertEquals("minecraft:oak_planks", inventory.slots[0].id)
        assertEquals("", inventory.slots[1].id)
    }

    @Test
    fun rejectsAnIdBlockOfTheWrongSize() {
        val bridge = DualScreenBridge.create(temporary.root)
        val stamp = writeSnapshot(bridge.directory, ids = listOf("minecraft:stone"))
        assertNull(bridge.readInventory(stamp))
    }

    @Test
    fun readsSnapshotsWithoutIds() {
        val bridge = DualScreenBridge.create(temporary.root)
        val stamp = writeSnapshot(bridge.directory)
        assertEquals("", requireNotNull(bridge.readInventory(stamp)).held.id)
    }

    private fun writeSnapshot(
        dir: File,
        generation: Long = 7,
        ready: Boolean = true,
        interactive: Boolean = true,
        selectedSlot: Int = 3,
        magic: Int = DUAL_SNAPSHOT_MAGIC,
        layout: PanelLayout = PanelLayout.PERSONAL,
        recipeBookAvailable: Boolean = false,
        heldCount: Int = 8,
        menuTitle: String = "",
        furnace: PanelFurnace = PanelFurnace(0f, 0f, false),
        ids: List<String>? = null,
    ): Long {
        val file = File(dir, "snapshot")
        DataOutputStream(file.outputStream()).use { out ->
            out.writeInt(magic)
            out.writeLong(generation)
            out.writeBoolean(ready)
            if (!ready) return@use
            out.writeBoolean(interactive)
            out.writeByte(layout.ordinal)
            out.writeInt(selectedSlot)
            out.writeDouble(-11.3)
            out.writeDouble(64.1)
            out.writeDouble(91.0)
            out.writeUTF("Roble")
            out.writeInt(heldCount)
            repeat(layout.slotCount) { slot ->
                out.writeUTF(if (slot == 0) "Tablones" else "")
                out.writeInt(if (slot == 0) 4 else 0)
            }
            out.writeInt(15)
            out.writeBoolean(recipeBookAvailable)
            out.writeUTF(menuTitle)
            if (layout == PanelLayout.FURNACE) {
                out.writeFloat(furnace.cook)
                out.writeFloat(furnace.fuel)
                out.writeBoolean(furnace.burning)
            }
            if (ids != null) {
                out.writeShort(ids.size)
                ids.forEach(out::writeUTF)
            }
        }
        return file.lastModified()
    }
}
