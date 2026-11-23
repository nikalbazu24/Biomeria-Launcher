package com.biomeria.launcher.engine.java

internal data class PanelPadSlot(val number: Int, val selectsHotbar: Boolean = false)

internal fun filterPanelSlotRows(
    rows: List<List<PanelPadSlot>>, items: List<PanelStack>, query: String
): List<List<PanelPadSlot>> {
    val words = panelSearchText(query).trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    if (words.isEmpty()) return rows
    return rows.map { row ->
        row.filter { slot ->
            val item = items.getOrNull(slot.number)
            !slot.selectsHotbar && item != null && item.count > 0 &&
                panelSearchText(item.name).let { name -> words.all(name::contains) }
        }
    }.filter { it.isNotEmpty() }
}

internal fun panelPadRows(layout: PanelLayout?, page: PanelPage): List<List<PanelPadSlot>> {
    if (layout == null) {
        return if (page == PanelPage.INVENTORY) listOf((0..8).map { PanelPadSlot(it, selectsHotbar = true) }) else emptyList()
    }
    val rows = mutableListOf<List<Int>>()
    fun row(first: Int, size: Int) = (first until first + size).toList()
    when {
        layout == PanelLayout.PERSONAL && page == PanelPage.INVENTORY -> {
            rows += listOf(5, 6, 7, 8, 45)
            rows += row(layout.hotbarStart, 9)
        }
        page == PanelPage.CRAFT && (layout == PanelLayout.PERSONAL || layout == PanelLayout.CRAFTING_TABLE) -> {
            val side = layout.craftSize
            repeat(side) { line ->
                val inputs = row(1 + line * side, side)
                rows += if (line == (side - 1) / 2) inputs + 0 else inputs
            }
        }
        page == PanelPage.STORAGE && layout.chestRows > 0 -> repeat(layout.chestRows) {
            rows += row(it * layout.containerColumns, layout.containerColumns)
        }
        page == PanelPage.STORAGE && layout == PanelLayout.FURNACE -> rows += listOf(0, 1, 2)
        else -> return emptyList()
    }
    for (first in layout.inventoryStart until layout.hotbarStart step 9) rows += row(first, 9)
    if (page != PanelPage.INVENTORY) rows += row(layout.hotbarStart, 9)
    val targets = rows.map { row -> row.map { PanelPadSlot(it) } }
    return if (page == PanelPage.INVENTORY) targets + listOf((0..8).map { PanelPadSlot(it, selectsHotbar = true) }) else targets
}

internal fun movePanelPadSlot(
    rows: List<List<PanelPadSlot>>, current: PanelPadSlot?, action: PanelPadAction
): PanelPadSlot? {
    val rowIndex = rows.indexOfFirst { current in it }
    if (rowIndex < 0) return rows.firstOrNull()?.firstOrNull()
    val column = rows[rowIndex].indexOf(current)
    val nextRow = when (action) {
        PanelPadAction.UP -> (rowIndex - 1).coerceAtLeast(0)
        PanelPadAction.DOWN -> (rowIndex + 1).coerceAtMost(rows.lastIndex)
        else -> rowIndex
    }
    val nextColumn = when (action) {
        PanelPadAction.LEFT -> column - 1
        PanelPadAction.RIGHT -> column + 1
        else -> column
    }
    return rows[nextRow][nextColumn.coerceIn(0, rows[nextRow].lastIndex)]
}

internal fun panelPadCommand(action: PanelPadAction, slot: PanelPadSlot): PanelCommand? {
    if (slot.selectsHotbar) return PanelCommand.SELECT_HOTBAR.takeIf { action == PanelPadAction.PICK }
    return when (action) {
        PanelPadAction.PICK -> PanelCommand.PICK
        PanelPadAction.SPLIT -> PanelCommand.SPLIT
        PanelPadAction.TRANSFER -> PanelCommand.QUICK_MOVE
        else -> null
    }
}
