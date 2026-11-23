package com.biomeria.launcher.engine.java

import org.junit.Assert.*
import org.junit.Test

class PanelPadSlotsTest {
    @Test
    fun inventorySearchKeepsSlotNumbersAndSkipsEmptyOrUnmatchedSlots() {
        val rows = panelPadRows(PanelLayout.DOUBLE_CHEST, PanelPage.STORAGE)
        val items = MutableList(90) { PanelStack("", 0) }.apply {
            this[4] = PanelStack("Tablón de abeto", 19)
            this[53] = PanelStack("Tablón de roble", 4)
            this[88] = PanelStack("Roble, tablón", 6)
        }
        val matches = filterPanelSlotRows(rows, items, "ROBLE tablon")
        assertEquals(listOf(53, 88), matches.flatten().map { it.number })
        assertEquals(PanelPadSlot(88), movePanelPadSlot(matches, PanelPadSlot(53), PanelPadAction.DOWN))
        assertTrue(filterPanelSlotRows(rows, items, "Diamante").isEmpty())
    }

    @Test
    fun aDoubleChestReachesTheLastStorageSlotAndContinuesToPlayerInventory() {
        val rows = panelPadRows(PanelLayout.DOUBLE_CHEST, PanelPage.STORAGE)
        assertEquals((0 until 90).toList(), rows.flatten().map { it.number })
        assertEquals(PanelPadSlot(53), movePanelPadSlot(rows, PanelPadSlot(44), PanelPadAction.DOWN))
        assertEquals(PanelPadSlot(62), movePanelPadSlot(rows, PanelPadSlot(53), PanelPadAction.DOWN))
        assertEquals(PanelPadSlot(89), movePanelPadSlot(rows, PanelPadSlot(89), PanelPadAction.DOWN))
        assertEquals(PanelCommand.QUICK_MOVE, panelPadCommand(PanelPadAction.TRANSFER, PanelPadSlot(53)))
    }

    @Test
    fun furnaceAndCraftingOutputSlotsAreReachableAsShown() {
        val furnace = panelPadRows(PanelLayout.FURNACE, PanelPage.STORAGE)
        assertEquals(PanelPadSlot(2), movePanelPadSlot(furnace, PanelPadSlot(1), PanelPadAction.RIGHT))
        val crafting = panelPadRows(PanelLayout.CRAFTING_TABLE, PanelPage.CRAFT)
        assertEquals(PanelPadSlot(0), movePanelPadSlot(crafting, PanelPadSlot(6), PanelPadAction.RIGHT))
        assertEquals(46, crafting.flatten().distinct().size)
        assertTrue(panelPadRows(PanelLayout.CRAFTING_TABLE, PanelPage.INVENTORY).isEmpty())
    }

    @Test
    fun hopperAndDispenserReachEverySlotInTheirOwnShape() {
        val hopper = panelPadRows(PanelLayout.HOPPER, PanelPage.STORAGE)
        assertEquals((0..4).map { PanelPadSlot(it) }, hopper.first())
        assertEquals(PanelLayout.HOPPER.slotCount, hopper.flatten().distinct().size)
        val dispenser = panelPadRows(PanelLayout.DISPENSER, PanelPage.STORAGE)
        assertEquals(listOf(PanelPadSlot(3), PanelPadSlot(4), PanelPadSlot(5)), dispenser[1])
        assertEquals(PanelLayout.DISPENSER.slotCount, dispenser.flatten().distinct().size)
    }

    @Test
    fun hotbarSelectionIsSeparateFromMovingInventorySlots() {
        val personal = panelPadRows(PanelLayout.PERSONAL, PanelPage.INVENTORY)
        val selected = personal.last().last()
        assertEquals(PanelPadSlot(8, selectsHotbar = true), selected)
        assertEquals(PanelCommand.SELECT_HOTBAR, panelPadCommand(PanelPadAction.PICK, selected))
        assertNull(panelPadCommand(PanelPadAction.TRANSFER, selected))
        assertEquals(PanelCommand.SPLIT, panelPadCommand(PanelPadAction.SPLIT, PanelPadSlot(8)))
        assertEquals(personal.last(), panelPadRows(null, PanelPage.INVENTORY).single())
    }
}
