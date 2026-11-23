package com.biomeria.launcher.engine.java

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.biomeria.launcher.ui.theme.PixelFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val GAP = 8.dp

// ---------------------------------------------------------------- Inventory

/**
 * The inventory as it opens: the 27 backpack slots in big Minecraft slots, the hotbar under a line,
 * and above them three small cards: crafting, what you can do with the chosen item, and the character.
 */
@Composable
internal fun InventoryPage(
    live: PanelInventory,
    bridge: DualScreenBridge,
    interactive: Boolean,
    chosen: Int?,
    padFocus: PanelPadSlot?,
    matchingSlots: Set<Int>?,
    query: String,
    editingSearch: Boolean,
    onTap: (Int) -> Unit,
    onCommand: (PanelCommand, Int) -> Unit,
    onOpenCraft: () -> Unit,
    onCustomize: () -> Unit,
    onSearch: () -> Unit,
    onClearSearch: () -> Unit,
    keyboard: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier
) {
    val look = LocalPanelLook.current
    BoxWithConstraints(modifier.panelFrame().padding(horizontal = 16.dp, vertical = 12.dp)) {
        val byWidth = (maxWidth - GAP * 8) / 9
        // Header, labels and gaps take about 150 dp; the cards take about two slots.
        val byHeight = (maxHeight - 150.dp) / 6.1f
        val slot = minOf(byWidth, maxOf(byHeight, 40.dp), 112.dp)
        val band = maxOf(slot * 2f, 124.dp)
        val gridWidth = slot * 9 + GAP * 8
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.width(gridWidth).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (editingSearch || query.isNotBlank()) {
                    Box(Modifier.weight(1f).heightIn(min = 44.dp).background(if (look.classic) Color.White else Color(0xFF0B0E11))
                        .border(3.dp, Color(0xFFF5E663)).clickable(onClick = onSearch).padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart) {
                        Text(query.ifBlank { "Buscar: escribe “pico”, “madera”…" }, color = if (query.isBlank()) look.muted else look.ink,
                            fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                    PanelButton(onClearSearch, Modifier.semantics { contentDescription = "Cerrar búsqueda" }) {
                        Text("✕", color = look.ink, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                    }
                } else {
                    Text("Inventario", Modifier.weight(1f), color = look.ink, fontFamily = PixelFont, fontSize = 26.sp,
                        fontWeight = FontWeight.Bold)
                    PanelButton(onSearch, Modifier.semantics { contentDescription = "Buscar objeto" }) { SearchGlyph(look.ink) }
                }
            }

            if (editingSearch) {
                keyboard(Modifier.width(gridWidth).height(band + 20.dp))
            } else {
                Row(Modifier.width(gridWidth).height(band), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CraftShortcut(live, Modifier.weight(.27f).fillMaxHeight(), onOpenCraft)
                    ItemActions(live, chosen, interactive, onCommand, Modifier.weight(.5f).fillMaxHeight())
                    CharacterCorner(live, bridge, interactive, slot, band, padFocus, onTap, onCustomize,
                        Modifier.weight(.23f).fillMaxHeight())
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
                for (first in live.layout.inventoryStart until live.layout.hotbarStart step 9) {
                    Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                        for (number in first until first + 9) McSlot(number, live, slot, interactive, onTap,
                            chosen = chosen == number, focused = padFocus?.number == number,
                            dim = matchingSlots != null && number !in matchingSlots)
                    }
                }
            }
            Column(Modifier.width(gridWidth), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Barra rápida", color = look.muted, fontFamily = PixelFont, fontSize = 16.sp)
                Box(Modifier.fillMaxWidth().height(4.dp).background(if (look.classic) look.slot else look.button))
                Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                    val start = live.layout.hotbarStart
                    for (number in start until start + 9) McSlot(number, live, slot, interactive, onTap,
                        chosen = chosen == number, inHand = number == start + live.selectedSlot,
                        focused = padFocus?.number == number, dim = matchingSlots != null && number !in matchingSlots)
                }
            }
        }
    }
}

@Composable
private fun SearchGlyph(color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(24.dp)) {
        val w = 3.5.dp.toPx()
        drawRect(color, topLeft = androidx.compose.ui.geometry.Offset(3.dp.toPx(), 3.dp.toPx()),
            size = androidx.compose.ui.geometry.Size(12.dp.toPx(), 12.dp.toPx()),
            style = androidx.compose.ui.graphics.drawscope.Stroke(w))
        drawLine(color, androidx.compose.ui.geometry.Offset(15.dp.toPx(), 15.dp.toPx()),
            androidx.compose.ui.geometry.Offset(22.dp.toPx(), 22.dp.toPx()), strokeWidth = 4.dp.toPx())
    }
}

@Composable
private fun CraftShortcut(live: PanelInventory, modifier: Modifier, onOpen: () -> Unit) {
    val look = LocalPanelLook.current
    val side = live.layout.craftSize
    Column(modifier.panelCard().clickable(role = Role.Button, onClick = onOpen).padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween) {
        Text("Crafteo", color = look.ink, fontFamily = PixelFont, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(side) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        repeat(side) { column ->
                            val item = live.slots.getOrNull(1 + row * side + column)
                            McIcon(item?.takeIf { it.count > 0 }?.id.orEmpty(), if (side == 3) 22.dp else 30.dp)
                        }
                    }
                }
            }
            Text("→", color = look.ink, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
            val result = live.slots.getOrNull(0)?.takeIf { it.count > 0 }
            McIcon(result?.id.orEmpty(), 38.dp, count = result?.count ?: 0)
        }
        Text("Abrir crafteo y recetas →", color = look.muted, fontWeight = FontWeight.ExtraBold,
            style = MaterialTheme.typography.labelMedium, maxLines = 2)
    }
}

/** What you can do with the chosen item, in plain words, each with its controller button. */
@Composable
private fun ItemActions(
    live: PanelInventory,
    chosen: Int?,
    interactive: Boolean,
    onCommand: (PanelCommand, Int) -> Unit,
    modifier: Modifier
) {
    val look = LocalPanelLook.current
    val held = live.held.takeIf { it.count > 0 }
    val item = chosen?.let { live.slots.getOrNull(it) }?.takeIf { it.count > 0 }
    Column(modifier.panelCard().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            held != null -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    McIcon(held.id, 48.dp, count = held.count)
                    Column(Modifier.weight(1f)) {
                        Text("Llevas ${held.name}", color = look.ink, fontWeight = FontWeight.ExtraBold,
                            style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("Toca la casilla donde lo quieres dejar.", color = look.muted, fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item != null && chosen != null -> {
                val start = live.layout.hotbarStart
                val inHotbar = chosen in start until start + 9
                val worn = chosen in 5..8 || chosen == 45
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    McIcon(item.id, 48.dp)
                    Column(Modifier.weight(1f)) {
                        Text(item.name, color = look.ink, fontFamily = PixelFont, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Tienes ${item.count} · " + when {
                            worn -> "lo llevas puesto"
                            inHotbar -> "en la barra rápida"
                            else -> "en la mochila"
                        }, color = look.muted, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge)
                    }
                }
                val actions = listOf(
                    Triple("A", "Tomar para moverlo", PanelCommand.PICK),
                    Triple("X", when {
                        worn -> "Quitar y guardar"
                        inHotbar -> "Guardar en la mochila"
                        else -> "Pasar a la barra rápida"
                    }, PanelCommand.QUICK_MOVE),
                    Triple("Y", if (item.count > 1) "Tomar la mitad (${(item.count + 1) / 2})" else "Tomar la mitad", PanelCommand.SPLIT),
                    Triple("L2", "Tirar 1 al suelo", PanelCommand.THROW)
                )
                for (pair in actions.chunked(2)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((key, label, command) in pair) {
                            PanelButton({ onCommand(command, chosen) }, Modifier.weight(1f),
                                enabled = interactive && (command != PanelCommand.SPLIT || item.count > 1)) {
                                PadKey(key)
                                Text(label, color = look.ink, fontWeight = FontWeight.ExtraBold,
                                    style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Toca un objeto o elígelo con A para ver qué puedes hacer con él.", color = look.muted,
                    fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.bodyLarge,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

/** The character, small, in the right corner with its armour and off hand. Tap it to customise. */
@Composable
private fun CharacterCorner(
    live: PanelInventory,
    bridge: DualScreenBridge,
    interactive: Boolean,
    slot: Dp,
    band: Dp,
    padFocus: PanelPadSlot?,
    onTap: (Int) -> Unit,
    onCustomize: () -> Unit,
    modifier: Modifier
) {
    val look = rememberCharacterLook()
    val small = maxOf(minOf(slot * .52f, (band - 40.dp) / 3), 28.dp)
    Row(modifier.panelCard().padding(8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
        Box(Modifier.clickable(onClickLabel = "Personalizar tu personaje", onClick = onCustomize)) {
            CharacterFigure(look, bridge, back = false, height = (band - 34.dp).coerceAtLeast(70.dp))
        }
        if (live.layout == PanelLayout.PERSONAL) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(listOf(5, 6), listOf(7, 8), listOf(45)).forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        line.forEach { number ->
                            McSlot(number, live, small, interactive, onTap, focused = padFocus?.number == number,
                                placeholder = when (number) { 5 -> "Casco"; 6 -> "Pechera"; 7 -> "Pantalones"; 8 -> "Botas"; else -> "Segunda mano" })
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Crafting

private enum class RecipeShelf(val label: String) { ALL("Todo"), TOOLS("Herramientas"), BLOCKS("Bloques"), FOOD("Comida"), FURNACE("Horno") }

private val toolWords = listOf("pickaxe", "_axe", "shovel", "_hoe", "sword", "bow", "shield", "helmet", "chestplate", "leggings",
    "boots", "fishing_rod", "shears", "flint_and_steel", "compass", "clock", "bucket", "arrow", "spyglass", "brush", "lead",
    "saddle", "mace", "trident", "carrot_on_a_stick", "map")
private val foodWords = listOf("bread", "cake", "cookie", "pie", "stew", "soup", "golden_apple", "golden_carrot", "honey_bottle",
    "sugar", "dried_kelp", "glistering", "suspicious")

private fun shelfOf(recipe: PanelRecipe): RecipeShelf {
    if (recipe.state == PanelRecipeState.COOKING) return RecipeShelf.FURNACE
    val path = recipe.resultId.substringAfter(':')
    return when {
        toolWords.any { path.contains(it) } -> RecipeShelf.TOOLS
        foodWords.any { path.contains(it) } -> RecipeShelf.FOOD
        else -> RecipeShelf.BLOCKS
    }
}

private data class PendingCraft(val resultId: String, val until: Long)

/**
 * Crafting on its own page: recipes on the left by shelf, the real grid on the right with the
 * result, what is missing, and two buttons: craft one or craft everything the inventory allows.
 */
@Composable
internal fun CraftPage(
    bridge: DualScreenBridge,
    live: PanelInventory,
    interactive: Boolean,
    controllerActive: Boolean,
    padFocus: PanelPadSlot?,
    onTap: (Int) -> Unit,
    onPadInput: (((PanelPadAction) -> Unit)?) -> Unit,
    modifier: Modifier = Modifier
) {
    val look = LocalPanelLook.current
    val recipesHere = live.recipeBookAvailable
    var manual by remember(live.layout) { mutableStateOf(!recipesHere) }
    val showRecipes = recipesHere && !manual
    var book by remember(live.generation) { mutableStateOf<PanelRecipeBook?>(null) }
    if (showRecipes) {
        DisposableEffect(bridge, live.generation) {
            bridge.viewRecipes(live.generation)
            onDispose { bridge.viewRecipes(null) }
        }
        LaunchedEffect(bridge, live.generation) {
            while (true) {
                val previous = book
                book = withContext(Dispatchers.IO) { bridge.readRecipeBook(live.generation, previous) }
                delay(250)
            }
        }
    }
    var shelf by remember { mutableStateOf(RecipeShelf.ALL) }
    var selectedId by remember(live.generation) { mutableStateOf<Int?>(null) }
    var focusIndex by remember(live.generation, shelf) { mutableIntStateOf(0) }
    val snapshot = book
    val results = remember(snapshot?.recipes, shelf) {
        snapshot?.recipes.orEmpty().filter { shelf == RecipeShelf.ALL || shelfOf(it) == shelf }
    }
    val selected = snapshot?.recipes?.firstOrNull { it.id == selectedId }
    var pending by remember { mutableStateOf<PendingCraft?>(null) }
    val busy = pending != null

    fun inspect(recipe: PanelRecipe) {
        selectedId = recipe.id
        bridge.send(live, PanelCommand.INSPECT_RECIPE, recipe.id)
    }
    fun craft(all: Boolean) {
        val recipe = selected ?: return
        if (recipe.state != PanelRecipeState.READY || live.held.count > 0 || !interactive || busy) return
        if (bridge.send(live, if (all) PanelCommand.PLACE_ALL else PanelCommand.PLACE_RECIPE, recipe.id)) {
            pending = PendingCraft(recipe.resultId, SystemClock.elapsedRealtime() + 2500L)
        }
    }
    // Once Minecraft fills the grid, the result goes straight into the inventory, like shift-clicking it.
    LaunchedEffect(live, pending) {
        val wait = pending ?: return@LaunchedEffect
        val result = live.slots.getOrNull(0)
        if (result != null && result.count > 0 && (wait.resultId.isBlank() || result.id == wait.resultId)) {
            bridge.send(live, PanelCommand.QUICK_MOVE, 0)
            pending = null
        } else {
            delay((wait.until - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            pending = null
        }
    }

    var columns by remember { mutableIntStateOf(5) }
    val gridState = rememberLazyGridState()
    val padAction by rememberUpdatedState<(PanelPadAction) -> Unit>({ action ->
        val focused = results.getOrNull(focusIndex.coerceIn(0, (results.size - 1).coerceAtLeast(0)))
        when (action) {
            PanelPadAction.LEFT -> focusIndex = (focusIndex - 1).coerceAtLeast(0)
            PanelPadAction.RIGHT -> focusIndex = (focusIndex + 1).coerceAtMost((results.size - 1).coerceAtLeast(0))
            PanelPadAction.UP -> focusIndex = (focusIndex - columns).coerceAtLeast(0)
            PanelPadAction.DOWN -> focusIndex = (focusIndex + columns).coerceAtMost((results.size - 1).coerceAtLeast(0))
            PanelPadAction.PICK -> if (focused != null) { if (focused.id == selectedId) craft(false) else inspect(focused) }
            PanelPadAction.SPLIT -> craft(true)
            PanelPadAction.TRANSFER -> shelf = RecipeShelf.entries[(shelf.ordinal + 1) % RecipeShelf.entries.size]
            else -> Unit
        }
        Unit
    })
    val register by rememberUpdatedState(onPadInput)
    DisposableEffect(showRecipes) {
        register(if (showRecipes) { action: PanelPadAction -> padAction(action) } else null)
        onDispose { register(null) }
    }
    LaunchedEffect(controllerActive, focusIndex, showRecipes) {
        if (controllerActive && showRecipes && results.isNotEmpty()) gridState.animateScrollToItem(focusIndex.coerceIn(0, results.lastIndex))
    }

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(.44f).fillMaxHeight().panelFrame().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (showRecipes) "Recetas" else "Tu inventario", Modifier.weight(1f), color = look.ink, fontFamily = PixelFont,
                    fontSize = 22.sp, fontWeight = FontWeight.Bold)
                if (recipesHere) PanelButton({ manual = !manual }) {
                    Text(if (manual) "Ver recetas" else "Colocar a mano", color = look.ink, fontWeight = FontWeight.ExtraBold,
                        style = MaterialTheme.typography.labelLarge)
                }
            }
            if (showRecipes) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RecipeShelf.entries.forEach { entry ->
                        PanelButton({ shelf = entry }, face = if (shelf == entry) Color(0xFF7BC74D) else null) {
                            Text(entry.label, color = if (shelf == entry) Color(0xFF0E1A08) else look.ink, fontWeight = FontWeight.ExtraBold,
                                style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        }
                    }
                }
                when {
                    snapshot == null -> Text("Leyendo las recetas de Minecraft…", color = look.muted)
                    results.isEmpty() -> Text(if (snapshot.recipes.isEmpty()) "Todavía no tienes recetas. Minecraft te las da al recoger materiales."
                        else "No hay recetas en esta categoría.", color = look.muted, fontWeight = FontWeight.Bold)
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val tile = 64.dp
                    columns = ((maxWidth + GAP) / (tile + GAP)).toInt().coerceAtLeast(3)
                    LazyVerticalGrid(GridCells.Fixed(columns), state = gridState, horizontalArrangement = Arrangement.spacedBy(GAP),
                        verticalArrangement = Arrangement.spacedBy(GAP), contentPadding = PaddingValues(top = 8.dp, end = 8.dp)) {
                        itemsIndexed(results, key = { _, recipe -> recipe.id }) { index, recipe ->
                            RecipeTile(recipe, recipe.id == selectedId, controllerActive && index == focusIndex) {
                                focusIndex = index
                                if (recipe.id == selectedId && recipe.state == PanelRecipeState.READY) craft(false) else inspect(recipe)
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Legend(Color(0xFF7BC74D), "Puedes craftearla")
                    Legend(Color(0xFF6B6B6B), "Te faltan cosas")
                }
            } else {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val small = minOf((maxWidth - 4.dp * 8) / 9, 52.dp)
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (first in live.layout.inventoryStart until live.layout.hotbarStart step 9) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (number in first until first + 9) McSlot(number, live, small, interactive, onTap,
                                    focused = padFocus?.number == number)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            val start = live.layout.hotbarStart
                            for (number in start until start + 9) McSlot(number, live, small, interactive, onTap,
                                inHand = number == start + live.selectedSlot, focused = padFocus?.number == number)
                        }
                    }
                }
            }
        }

        Column(Modifier.weight(.56f).fillMaxHeight().panelFrame().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val side = live.layout.craftSize
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(selected?.name ?: if (side == 3) "Mesa de crafteo" else "Crafteo 2×2", Modifier.weight(1f, fill = false), color = look.ink,
                    fontFamily = PixelFont, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(selected?.let { recipeWhere(it, side) } ?: "Pon los materiales en la cuadrícula", color = look.muted,
                    fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val cell = minOf((maxWidth - 140.dp) / (side + 1.4f), 96.dp).coerceAtLeast(36.dp)
                val pattern = snapshot?.takeIf { selected != null && it.selectedId == selected.id && it.pattern.size == 9 }?.pattern
                val gridEmpty = (1..side * side).all { (live.slots.getOrNull(it)?.count ?: 0) == 0 }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(side) { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                repeat(side) { column ->
                                    val number = 1 + row * side + column
                                    // A 2×2 grid shows the top-left corner of the recipe's 3×3 pattern.
                                    val ghost = if (gridEmpty) pattern?.getOrNull(row * 3 + column) else null
                                    McSlot(number, live, cell, interactive, onTap, focused = padFocus?.number == number, ghostId = ghost)
                                }
                            }
                        }
                    }
                    Text("→", color = look.ink, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold)
                    McSlot(0, live, cell * 1.35f, interactive, onTap, focused = padFocus?.number == 0,
                        ghostId = selected?.resultId, placeholder = "Resultado")
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (selected != null) {
                    Text("Necesitas", color = look.muted, fontFamily = PixelFont, fontSize = 16.sp)
                    when (selected.state) {
                        PanelRecipeState.READY -> NeedRow(look.good, "Tienes todo lo necesario", null)
                        PanelRecipeState.MISSING -> {
                            val missing = snapshot?.takeIf { it.selectedId == selected.id }?.missing.orEmpty()
                            if (missing.isEmpty()) NeedRow(look.muted, "Comprobando…", null)
                            missing.forEach { NeedRow(look.bad, "Te faltan ${it.count}", it.name.ifBlank { "Ingrediente" }, it.id) }
                        }
                        PanelRecipeState.NEEDS_TABLE -> NeedRow(look.bad, "Necesita una mesa de crafteo", null)
                        PanelRecipeState.COOKING -> NeedRow(look.muted, "Se hace en el horno: ingrediente arriba, combustible abajo", null)
                        PanelRecipeState.MANUAL -> NeedRow(look.muted, "Colócala a mano en la cuadrícula", null)
                    }
                } else if (live.held.count > 0) {
                    NeedRow(look.muted, "Llevas ${live.held.name}: toca una casilla para dejarlo", null)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val ready = selected?.state == PanelRecipeState.READY && interactive && live.held.count == 0 && !busy
                if (selected != null) {
                    PanelButton({ craft(false) }, Modifier.weight(1f).heightIn(min = 56.dp), enabled = ready, face = Color(0xFF7BC74D)) {
                        Text("Craftear 1", color = Color(0xFF0E1A08), fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                        PadKey("A")
                    }
                    PanelButton({ craft(true) }, Modifier.weight(1.3f).heightIn(min = 56.dp), enabled = ready) {
                        Text("Craftear todo lo posible", color = look.ink, fontWeight = FontWeight.ExtraBold,
                            style = MaterialTheme.typography.titleSmall, maxLines = 2)
                        PadKey("Y")
                    }
                } else {
                    val result = live.slots.getOrNull(0)?.takeIf { it.count > 0 }
                    PanelButton({ bridge.send(live, PanelCommand.QUICK_MOVE, 0) }, Modifier.weight(1f).heightIn(min = 56.dp),
                        enabled = result != null && interactive, face = Color(0xFF7BC74D)) {
                        Text(if (result != null) "Tomar ${result.name}" else "Tomar el resultado", color = Color(0xFF0E1A08),
                            fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

private fun recipeWhere(recipe: PanelRecipe, side: Int) = when (recipe.state) {
    PanelRecipeState.COOKING -> "Se hace en el horno"
    PanelRecipeState.NEEDS_TABLE -> "Necesita mesa de crafteo"
    else -> if (side == 3) "Mesa de crafteo" else "Crafteo 2×2"
}

@Composable
private fun RecipeTile(recipe: PanelRecipe, selected: Boolean, focused: Boolean, onClick: () -> Unit) {
    val ready = recipe.state == PanelRecipeState.READY
    Box(Modifier.aspectRatio(1f)
        .then(if (selected || focused) Modifier.border(3.dp, Color(0xFFF5E663)).padding(3.dp) else Modifier)
        .then(if (ready) Modifier.border(3.dp, Color(0xFF7BC74D)) else Modifier)
        .clickable(role = Role.Button, onClickLabel = recipe.name, onClick = onClick)
        .semantics { contentDescription = recipe.name }) {
        McIcon(recipe.resultId, 64.dp, Modifier.fillMaxSize(), count = recipe.resultCount, faded = !ready)
        if (recipe.isNew) Text("NUEVA", Modifier.align(Alignment.TopEnd).background(Color(0xFFF5E663), RoundedCornerShape(2.dp))
            .border(2.dp, Color(0xFF0B0E11), RoundedCornerShape(2.dp)).padding(horizontal = 3.dp),
            color = Color(0xFF0B0E11), fontWeight = FontWeight.ExtraBold, fontSize = 9.sp)
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    val look = LocalPanelLook.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(14.dp).background(color).border(2.dp, Color(0xFF0B0E11)))
        Text(label, color = look.muted, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun NeedRow(color: Color, text: String, name: String?, id: String = "") {
    val look = LocalPanelLook.current
    Row(Modifier.fillMaxWidth().panelCard().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (id.isNotBlank()) McIcon(id, 32.dp)
        if (name != null) Text(name, Modifier.weight(1f), color = look.ink, fontWeight = FontWeight.ExtraBold,
            style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(text, if (name == null) Modifier.weight(1f) else Modifier, color = color, fontWeight = FontWeight.ExtraBold,
            style = MaterialTheme.typography.bodyMedium)
    }
}

// ---------------------------------------------------------------- Containers

/** Chests, hoppers, dispensers and furnaces: the container on top, the inventory below. */
@Composable
internal fun StoragePage(
    live: PanelInventory,
    interactive: Boolean,
    padFocus: PanelPadSlot?,
    matchingSlots: Set<Int>?,
    onTap: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val look = LocalPanelLook.current
    BoxWithConstraints(modifier.panelFrame().padding(horizontal = 16.dp, vertical = 12.dp)) {
        val rows = (live.layout.hotbarStart - live.layout.inventoryStart) / 9 + 1 + maxOf(live.layout.chestRows, 1)
        val slot = minOf((maxWidth - GAP * 8) / 9, (maxHeight - 120.dp) / (rows + .4f), 96.dp).coerceAtLeast(34.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(GAP)) {
            Text(live.menuTitle.ifBlank { "Contenedor" }, Modifier.width(slot * 9 + GAP * 8), color = look.ink, fontFamily = PixelFont,
                fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val furnace = live.furnace
            if (furnace != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        McSlot(0, live, slot, interactive, onTap, focused = padFocus?.number == 0, placeholder = "Material")
                        Meter(if (furnace.burning) furnace.fuel else 0f, Color(0xFFF07D4A), slot)
                        McSlot(1, live, slot, interactive, onTap, focused = padFocus?.number == 1, placeholder = "Combustible")
                    }
                    Meter(furnace.cook, Color.White, slot * 1.4f)
                    McSlot(2, live, slot * 1.3f, interactive, onTap, focused = padFocus?.number == 2, placeholder = "Resultado")
                }
            } else {
                val columns = live.layout.containerColumns
                repeat(live.layout.chestRows) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                        for (number in row * columns until (row + 1) * columns) McSlot(number, live, slot, interactive, onTap,
                            focused = padFocus?.number == number, dim = matchingSlots != null && number !in matchingSlots)
                    }
                }
            }
            Text("Tu inventario", Modifier.width(slot * 9 + GAP * 8).padding(top = 6.dp), color = look.muted, fontFamily = PixelFont, fontSize = 16.sp)
            for (first in live.layout.inventoryStart until live.layout.hotbarStart step 9) {
                Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                    for (number in first until first + 9) McSlot(number, live, slot, interactive, onTap,
                        focused = padFocus?.number == number, dim = matchingSlots != null && number !in matchingSlots)
                }
            }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                val start = live.layout.hotbarStart
                for (number in start until start + 9) McSlot(number, live, slot, interactive, onTap,
                    inHand = number == start + live.selectedSlot, focused = padFocus?.number == number,
                    dim = matchingSlots != null && number !in matchingSlots)
            }
        }
    }
}

@Composable
private fun Meter(progress: Float, color: Color, length: Dp) {
    Box(Modifier.width(length).height(10.dp).background(Color(0xFF373737)).border(2.dp, Color(0xFF0B0E11))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(color))
    }
}

// ---------------------------------------------------------------- Options

internal enum class OptionGroup(val label: String, val detail: String) {
    TEXT("Texto y contraste", "Tamaño de letra y colores"),
    HUD("HUD", "Lo que ves en la pantalla de arriba"),
    CONTROLS("Controles", "Cámara y botones táctiles"),
    MAP("Mapa", "Minimapa y criaturas"),
    SCREENS("Doble pantalla", "Qué se abre aquí abajo"),
    PERFORMANCE("Rendimiento", "FPS y batería")
}

/** The options, grouped: categories on the left, their switches and choices on the right. */
@Composable
internal fun OptionsPage(
    hud: GameHudSettings,
    onHud: (GameHudSettings) -> Unit,
    group: OptionGroup,
    onGroup: (OptionGroup) -> Unit,
    canTurnOff: Boolean,
    modifier: Modifier = Modifier
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(.34f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OptionGroup.entries.forEach { entry ->
                val on = entry == group
                Column(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .background(if (on) Color(0xFF7BC74D) else Color(0xFF1E252C), RoundedCornerShape(4.dp))
                    .border(2.dp, Color(0xFF0B0E11), RoundedCornerShape(4.dp))
                    .clickable(role = Role.Tab, onClick = { onGroup(entry) }).padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.Center) {
                    Text(entry.label, color = if (on) Color(0xFF0E1A08) else Color(0xFFF2F5EF), fontFamily = PixelFont, fontSize = 18.sp,
                        fontWeight = FontWeight.Bold)
                    Text(entry.detail, color = if (on) Color(0xFF2C4A1B) else Color(0xFFB9C4CA), fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Column(Modifier.weight(.66f).fillMaxHeight().background(Color(0xFF161B21), RoundedCornerShape(6.dp))
            .border(3.dp, Color(0xFF232B33), RoundedCornerShape(6.dp)).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(group.label, color = Color(0xFFF2F5EF), fontFamily = PixelFont, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            when (group) {
                OptionGroup.TEXT -> {
                    ChoiceRow("Tamaño de la interfaz", "Achica o agranda todo este panel", InterfaceSize.entries, hud.interfaceSize,
                        { it.label }) { onHud(hud.copy(interfaceSize = it)) }
                    ChoiceRow("Tamaño del texto", "Para los paneles de esta pantalla", PanelTextSize.entries, hud.textSize, { it.label }) {
                        onHud(hud.copy(textSize = it))
                    }
                    ChoiceRow("Contraste", "Alto hace el texto más fácil de leer", listOf(false, true), hud.highContrast,
                        { if (it) "Alto" else "Normal" }) { onHud(hud.copy(highContrast = it)) }
                    ChoiceRow("Estilo del inventario", "Gris como Minecraft u oscuro de Biomeria", PanelStyle.entries, hud.panelStyle,
                        { it.label }) { onHud(hud.copy(panelStyle = it)) }
                    SwitchRow("Menos animaciones", "Quita movimientos y destellos de los logros", hud.reduceMotion) {
                        onHud(hud.copy(reduceMotion = it))
                    }
                }
                OptionGroup.HUD -> {
                    ChoiceRow("Tamaño de los indicadores", "FPS, coordenadas y batería", listOf(.85f, 1f, 1.25f),
                        listOf(.85f, 1f, 1.25f).minBy { kotlin.math.abs(it - hud.widgetScale) },
                        { when (it) { .85f -> "Chico"; 1f -> "Normal"; else -> "Grande" } }) { onHud(hud.copy(widgetScale = it)) }
                    SwitchRow("Mostrar FPS", "Pequeño, arriba a la izquierda", hud.showFps) { onHud(hud.copy(showFps = it)) }
                    SwitchRow("Mostrar coordenadas", "Dónde estás en el mundo", hud.showCoordinates) { onHud(hud.copy(showCoordinates = it)) }
                    SwitchRow("Nivel de batería", "Junto a los otros indicadores", hud.showBattery) { onHud(hud.copy(showBattery = it)) }
                    ChoiceRow("Logros", "Cómo se anuncian arriba", AchievementStyle.entries, hud.achievements, { it.label }) {
                        onHud(hud.copy(achievements = it))
                    }
                    SwitchRow("Consejos de Biomeria", "Avisos con los botones de tu mando, solo la primera vez", hud.biomeriaTips) {
                        onHud(hud.copy(biomeriaTips = it))
                    }
                    SwitchRow("Consejos de abajo", "La franja con consejos en esta pantalla", hud.panelTips) {
                        onHud(hud.copy(panelTips = it))
                    }
                    ChoiceRow("Dónde salen los consejos", "Esquina de la pantalla de arriba", TipCorner.entries, hud.tipCorner,
                        { it.label }) { onHud(hud.copy(tipCorner = it)) }
                    SwitchRow("Tutorial de Minecraft", "Los avisos de teclado. Cambia al volver a entrar al mundo", hud.minecraftTutorial) {
                        onHud(hud.copy(minecraftTutorial = it))
                    }
                }
                OptionGroup.CONTROLS -> {
                    ChoiceRow("Velocidad de la cámara", "Qué tan rápido gira con el stick derecho", CameraSpeed.entries, hud.cameraSpeed,
                        { it.label }) { onHud(hud.copy(cameraSpeed = it)) }
                    SwitchRow("Controles táctiles", "Botones en pantalla para jugar sin mando", hud.showTouch) { onHud(hud.copy(showTouch = it)) }
                }
                OptionGroup.MAP -> {
                    ChoiceRow("Mapa de esta pantalla", "El de Biomeria o el mapa que llevas en la mano", PanelMapSource.entries, hud.panelMap,
                        { if (it == PanelMapSource.BIOMERIA) "Biomeria" else "Equipado" }) { onHud(hud.copy(panelMap = it)) }
                    SwitchRow("Minimapa en el juego", "Pequeño, en la pantalla de arriba", hud.showMinimap) { onHud(hud.copy(showMinimap = it)) }
                    SwitchRow("Criaturas en el mapa", "Solo en tus mundos, nunca en servidores", hud.showEntities) { onHud(hud.copy(showEntities = it)) }
                }
                OptionGroup.SCREENS -> {
                    SwitchRow("Mesa de crafteo abajo", "Se abre en esta pantalla", hud.routeCrafting) { onHud(hud.copy(routeCrafting = it)) }
                    SwitchRow("Cofres, tolvas y dispensadores abajo", "Se abren en esta pantalla", hud.routeChests) { onHud(hud.copy(routeChests = it)) }
                    SwitchRow("Hornos abajo", "Se abren en esta pantalla", hud.routeFurnaces) { onHud(hud.copy(routeFurnaces = it)) }
                    SwitchRow("Indicador del mando", "El botón \"Juego · Select\" de arriba", hud.showPadChip) {
                        onHud(hud.copy(showPadChip = it))
                    }
                    SwitchRow("Indicadores en esta pantalla", "FPS y coordenadas aquí en vez de arriba", hud.widgetsBelow) {
                        onHud(hud.copy(widgetsBelow = it))
                    }
                    PanelButton({ onHud(hud.copy(dualScreen = false)) }, Modifier.fillMaxWidth(), enabled = canTurnOff,
                        face = Color(0xFF3A1F19)) {
                        Text(if (canTurnOff) "Desactivar doble pantalla" else "Guarda el objeto que llevas antes de desactivarla",
                            color = Color(0xFFFFD2C4), fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleSmall)
                    }
                }
                OptionGroup.PERFORMANCE -> {
                    ChoiceRow("Límite de FPS", "Menos FPS gasta menos batería", javaFpsLimits, hud.fpsLimit,
                        { if (it == 0) "Del juego" else it.toString() }) { onHud(hud.copy(fpsLimit = it)) }
                    SwitchRow("Ahorro en segundo plano", "15 FPS cuando el juego no está al frente", hud.batterySaver) {
                        onHud(hud.copy(batterySaver = it))
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).background(Color(0xFF1E252C), RoundedCornerShape(5.dp))
        .clickable(role = Role.Switch, onClick = { onChange(!on) }).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        OptionText(label, detail, Modifier.weight(1f))
        Box(Modifier.size(68.dp, 36.dp).background(if (on) Color(0xFF7BC74D) else Color(0xFF3A454F), RoundedCornerShape(4.dp))
            .border(3.dp, Color(0xFF0B0E11), RoundedCornerShape(4.dp)).padding(4.dp),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.size(24.dp).background(Color(0xFFF2F5EF), RoundedCornerShape(2.dp)).border(2.dp, Color(0xFF0B0E11), RoundedCornerShape(2.dp)))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceRow(label: String, detail: String, options: List<T>, selected: T, name: (T) -> String, onPick: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color(0xFF1E252C), RoundedCornerShape(5.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OptionText(label, detail)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { option ->
                val on = option == selected
                Box(Modifier.heightIn(min = 42.dp).background(if (on) Color(0xFF7BC74D) else Color(0xFF2A333B), RoundedCornerShape(3.dp))
                    .border(2.dp, Color(0xFF0B0E11), RoundedCornerShape(3.dp))
                    .clickable(role = Role.RadioButton, onClick = { onPick(option) }).padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center) {
                    Text(name(option), color = if (on) Color(0xFF0E1A08) else Color(0xFFF2F5EF), fontWeight = FontWeight.ExtraBold,
                        style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun OptionText(label: String, detail: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, color = Color(0xFFF2F5EF), fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleSmall)
        Text(detail, color = Color(0xFFB9C4CA), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
    }
}

// ---------------------------------------------------------------- Tips

/** One piece of a tip: plain words, or a controller button drawn as a key. */
internal data class TipPart(val text: String, val key: Boolean = false)

private fun tip(vararg parts: String) = parts.map { if (it.startsWith("[") && it.endsWith("]")) TipPart(it.trim('[', ']'), true) else TipPart(it) }

internal fun panelTips(page: PanelPage): List<List<TipPart>> = when (page) {
    PanelPage.INVENTORY -> listOf(
        tip("En el juego pulsa", "[X]", "y tu inventario aparece aquí abajo. Cambia de sección con", "[LB]", "y", "[RB]"),
        tip("Elige un objeto con", "[A]", "y luego la casilla donde lo quieres dejar."),
        tip("Mantén", "[Select]", "para usar el mando en esta pantalla; vuelve al juego con", "[B]")
    )
    PanelPage.CRAFT -> listOf(
        tip("Con", "[A]", "crafteas una vez y con", "[Y]", "todo lo que te alcance. Vuelve con", "[B]"),
        tip("Las recetas en verde se pueden hacer ya. Cambia de categoría con", "[X]")
    )
    PanelPage.MAP -> listOf(
        tip("Mueve el mapa con el stick derecho.", "[X]", "acerca,", "[Y]", "aleja y", "[A]", "vuelve a ti."),
        tip("Marca tu casa con", "[R3]", "para no perderte.")
    )
    PanelPage.OPTIONS -> listOf(tip("Pulsa", "[Start]", "en el juego para pausar; los cambios de aquí se guardan solos."))
    PanelPage.STORAGE -> listOf(tip("Toca un objeto para tomarlo y otra casilla para dejarlo. Cierra con", "[B]"))
}

/** The strip at the bottom: a tip for this page, or what each button does while the controller is here. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PanelTipStrip(parts: List<TipPart>, label: String, onNext: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().background(Color(0xFF0B0E11)).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, color = Color(0xFFF5E663), fontFamily = PixelFont, fontSize = 15.sp)
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            parts.forEach { part ->
                if (part.key) PadKey(part.text)
                else Text(part.text, color = Color(0xFFDCE3E6), fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge)
            }
        }
        if (onNext != null) Box(Modifier.heightIn(min = 40.dp).background(Color(0xFF2A333B), RoundedCornerShape(4.dp))
            .border(2.dp, Color(0xFF0B0E11), RoundedCornerShape(4.dp)).clickable(onClick = onNext).padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center) {
            Text("Otro consejo", color = Color(0xFFF2F5EF), fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelMedium)
        }
    }
}
