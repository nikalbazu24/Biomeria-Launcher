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
        snapshot.writeBytes(byteArrayOf(0x50, 0x52, 0x53, 0x32))
        assertNull(bridge.readInventory(snapshot.lastModified()))
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
        magic: Int = DUAL_SNAPSHOT_MAGIC
    ): Long {
        val file = File(dir, "snapshot")
        DataOutputStream(file.outputStream()).use { out ->
            out.writeInt(magic)
            out.writeLong(generation)
            out.writeBoolean(ready)
            out.writeBoolean(interactive)
            out.writeInt(selectedSlot)
            out.writeDouble(-11.3)
            out.writeDouble(64.1)
            out.writeDouble(91.0)
            out.writeUTF("Roble")
            out.writeInt(8)
            out.writeUTF("Tablones")
            out.writeInt(4)
            repeat(45) {
                out.writeUTF("")
                out.writeInt(0)
            }
            out.writeInt(15)
        }
        return file.lastModified()
    }
}
