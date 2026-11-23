package com.biomeria.launcher.engine.java

import androidx.compose.foundation.BorderStroke
import com.biomeria.launcher.ui.theme.PixelFont
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biomeria.launcher.ui.theme.Biomeria
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun RecipeBookPane(
    bridge: DualScreenBridge,
    inventory: PanelInventory,
    controllerActive: Boolean,
    onBack: () -> Unit,
    onPlaced: () -> Unit,
    onPadInput: (((PanelPadAction) -> Unit)?) -> Unit,
    modifier: Modifier = Modifier
) {
    var book by remember(inventory.generation) { mutableStateOf<PanelRecipeBook?>(null) }
    var navigation by remember(inventory.generation) { mutableStateOf(RecipeBookNavigation()) }
    var query by remember(inventory.generation) { mutableStateOf("") }
    val listState = rememberLazyListState()
    DisposableEffect(bridge, inventory.generation) {
        bridge.viewRecipes(inventory.generation)
        onDispose { bridge.viewRecipes(null) }
    }
    LaunchedEffect(bridge, inventory.generation) {
        while (true) {
            val previous = book
            book = withContext(Dispatchers.IO) { bridge.readRecipeBook(inventory.generation, previous) }
            delay(250)
        }
    }

    val snapshot = book
    var cooking by remember(inventory.generation) { mutableStateOf<Boolean?>(null) }
    val results = remember(snapshot?.recipes, query, navigation.onlyCraftable, cooking) {
        filterPanelRecipes(snapshot?.recipes.orEmpty(), query, navigation.onlyCraftable)
            .filter { cooking == null || (it.state == PanelRecipeState.COOKING) == cooking }
    }
    val selected = snapshot?.recipes?.firstOrNull { it.id == navigation.detailId }
    val focused = navigation.focusedRecipe(results)
    val hasCursorItem = inventory.held.count > 0

    fun back() {
        val previous = navigation.back()
        if (previous == null) onBack() else navigation = previous
    }

    fun inspect(recipe: PanelRecipe) {
        if (bridge.send(inventory, PanelCommand.INSPECT_RECIPE, recipe.id)) navigation = navigation.open(recipe)
    }

    fun place(recipe: PanelRecipe) {
        if (recipe.state == PanelRecipeState.READY && !hasCursorItem &&
            bridge.send(inventory, PanelCommand.PLACE_RECIPE, recipe.id)
        ) onPlaced()
    }

    val padAction by rememberUpdatedState<(PanelPadAction) -> Unit>({ action ->
        when {
            action == PanelPadAction.BACK -> back()
            navigation.searching -> Unit
            navigation.detailId != null -> {
                if (action == PanelPadAction.PICK && selected?.id == navigation.detailId) selected?.let(::place)
            }
            action == PanelPadAction.UP -> navigation = navigation.move(results, -1)
            action == PanelPadAction.DOWN -> navigation = navigation.move(results, 1)
            action == PanelPadAction.SPLIT -> navigation = navigation.copy(onlyCraftable = !navigation.onlyCraftable)
            action == PanelPadAction.PICK -> navigation.focusedRecipe(results)?.let(::inspect)
            else -> Unit
        }
    })
    val updatePadInput by rememberUpdatedState(onPadInput)
    DisposableEffect(Unit) {
        updatePadInput { padAction(it) }
        onDispose { updatePadInput(null) }
    }
    LaunchedEffect(controllerActive, focused?.id, navigation.detailId, navigation.searching) {
        if (controllerActive && focused != null && navigation.detailId == null && !navigation.searching) {
            val index = results.indexOf(focused)
            if (listState.layoutInfo.visibleItemsInfo.none { it.key == focused.id }) listState.scrollToItem(index)
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                navigation.searching -> OutlinedButton(onClick = ::back) { Text("Ver recetas") }
                navigation.detailId != null -> OutlinedButton(onClick = ::back) { Text("Recetas") }
                else -> OutlinedButton(onClick = onBack) { Text(if (inventory.layout == PanelLayout.CRAFTING_TABLE) "Mesa 3×3" else "Crafteo 2×2") }
            }
            Text(if (navigation.searching) query.ifBlank { "Buscar por nombre" } else "Libro de recetas",
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall)
            if (!navigation.searching && navigation.detailId == null) {
                OutlinedButton(onClick = { navigation = navigation.copy(searching = true) }) { Text("Buscar") }
            }
        }

        when {
            navigation.searching -> RecipeSearchKeys(query, { query = it }, Modifier.weight(1f))
            snapshot == null -> Text("Leyendo las recetas de Minecraft…", color = Biomeria.colors.textMuted)
            selected != null -> RecipeDetails(
                selected, snapshot, hasCursorItem, controllerActive, modifier = Modifier.weight(1f)
            ) { place(selected) }
            navigation.detailId != null -> Text("La receta ya no está en el libro.", color = Biomeria.colors.textMuted)
            else -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(cooking == false, { cooking = if (cooking == false) null else false }, label = { Text("Crafteo") })
                    FilterChip(cooking == true, { cooking = if (cooking == true) null else true }, label = { Text("Horno") })
                    FilterChip(navigation.onlyCraftable,
                        { navigation = navigation.copy(onlyCraftable = !navigation.onlyCraftable) },
                        label = { Text(if (controllerActive) "Solo fabricables [X]" else "Solo fabricables") })
                    if (query.isNotBlank()) {
                        FilterChip(true, { navigation = navigation.copy(searching = true) }, label = {
                            Text(query, modifier = Modifier.widthIn(max = 150.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        })
                    }
                }
                if (results.isEmpty()) {
                    Text(if (snapshot.recipes.isEmpty()) "Todavía no has desbloqueado recetas. Aparecen aquí en cuanto Minecraft te las da."
                        else "No hay recetas que coincidan.", color = Biomeria.colors.textMuted)
                }
                val fresh = snapshot.recipes.count { it.isNew }
                if (fresh > 0) Text("$fresh ${if (fresh == 1) "receta nueva" else "recetas nuevas"} desde la última vez",
                    color = Biomeria.colors.gold, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge)
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(results, key = { it.id }) { recipe ->
                        Surface(
                            onClick = { inspect(recipe) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = if (controllerActive && recipe.id == focused?.id) {
                                BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                            } else null
                        ) {
                            Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                RecipeIcon(recipe.resultId, 40.dp)
                                Text(recipe.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.Bold)
                                if (recipe.isNew) NewBadge()
                                if (recipe.resultCount > 1) Text("×${recipe.resultCount}", fontWeight = FontWeight.ExtraBold)
                                val status = recipeStatus(recipe.state)
                                Text(status, style = MaterialTheme.typography.labelMedium,
                                    color = if (recipe.state == PanelRecipeState.READY) MaterialTheme.colorScheme.primary
                                        else Biomeria.colors.textMuted)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecipeDetails(
    recipe: PanelRecipe,
    book: PanelRecipeBook,
    hasCursorItem: Boolean,
    controllerActive: Boolean,
    modifier: Modifier,
    onPlace: () -> Unit
) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (book.selectedId == recipe.id && book.pattern.size == 9) {
                if (recipe.state == PanelRecipeState.COOKING) RecipeIcon(book.pattern[0], 56.dp) else RecipeGrid(book.pattern)
            }
            Text("→", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Biomeria.colors.textMuted)
            Box(contentAlignment = Alignment.BottomEnd) {
                RecipeIcon(recipe.resultId, 64.dp)
                if (recipe.resultCount > 1) Text("${recipe.resultCount}", fontFamily = PixelFont, fontSize = 18.sp)
            }
        }
        Text(recipe.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
        when (recipe.state) {
            PanelRecipeState.READY -> {
                Button(onClick = onPlace, enabled = !hasCursorItem) {
                    Text(if (controllerActive) "Colocar ingredientes [A]" else "Colocar ingredientes")
                }
                Text(if (hasCursorItem) "Guarda el objeto que llevas en el cursor."
                    else "Recoge el resultado en la mesa.", color = Biomeria.colors.textMuted)
            }
            PanelRecipeState.MISSING -> {
                Text("Ingredientes que faltan", style = MaterialTheme.typography.titleSmall)
                if (book.selectedId != recipe.id) {
                    Text("Comprobando…", color = Biomeria.colors.textMuted)
                } else {
                    book.missing.forEach { ingredient ->
                        Text("×${ingredient.count}  ${ingredient.name.ifBlank { "Ingrediente de la receta" }}")
                    }
                }
            }
            PanelRecipeState.MANUAL -> Text("Puedes preparar esta receta colocando los ingredientes a mano.")
            PanelRecipeState.NEEDS_TABLE -> Text("Esta receta necesita la mesa de crafteo de 3×3.", color = Biomeria.colors.textMuted)
            PanelRecipeState.COOKING -> {
                val station = book.pattern.getOrNull(1).orEmpty()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (station.isNotBlank()) RecipeIcon(station, 36.dp)
                    Text("Se cocina en " + when (station.substringAfter(':')) {
                        "smoker" -> "un ahumador"
                        "blast_furnace" -> "un alto horno"
                        "campfire", "soul_campfire" -> "una fogata"
                        "furnace" -> "un horno"
                        else -> "un horno"
                    } + ". Pon el ingrediente arriba y combustible abajo.", color = Biomeria.colors.textMuted)
                }
            }
        }
    }
}

// The secondary window stays non-focusable, so search uses touch without moving the gamepad focus.
@Composable
private fun RecipeSearchKeys(query: String, onQuery: (String) -> Unit, modifier: Modifier) {
    var numeric by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        val rows = if (numeric) listOf("1234567890") else listOf("qwertyuiop", "asdfghjklñ", "zxcvbnm")
        rows.forEach { keys ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                keys.forEach { key ->
                    FilledTonalButton(onClick = { if (query.length < 48) onQuery(query + key) },
                        modifier = Modifier.weight(1f).height(40.dp), contentPadding = PaddingValues(0.dp)) {
                        Text(key.uppercase(), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FilledTonalButton(onClick = { numeric = !numeric }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(if (numeric) "ABC" else "123")
            }
            FilledTonalButton(onClick = { if (query.length < 48) onQuery(query + " ") },
                modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Espacio") }
            FilledTonalButton(onClick = { onQuery(query.dropLast(1)) }, enabled = query.isNotEmpty(),
                modifier = Modifier.semantics { contentDescription = "Borrar último carácter" },
                contentPadding = PaddingValues(horizontal = 8.dp)) { Text("⌫") }
            TextButton(onClick = { onQuery("") }, enabled = query.isNotEmpty(),
                contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Limpiar") }
        }
    }
}

private fun recipeStatus(state: PanelRecipeState) = when (state) {
    PanelRecipeState.READY -> "Disponible"
    PanelRecipeState.MISSING -> "Faltan ingredientes"
    PanelRecipeState.MANUAL -> "Manual"
    PanelRecipeState.NEEDS_TABLE -> "Necesita mesa"
    PanelRecipeState.COOKING -> "Horno"
}

/** The item's icon from the game files; a plain slot while it loads or for unknown items. */
@Composable
private fun RecipeIcon(id: String, size: androidx.compose.ui.unit.Dp) {
    val icon = if (id.isBlank()) null else rememberItemIcon(id)
    Box(Modifier.size(size).background(Color(0xFF1A2026), RoundedCornerShape(3.dp))
        .border(2.dp, Biomeria.colors.ink, RoundedCornerShape(3.dp)), contentAlignment = Alignment.Center) {
        if (icon != null) Image(icon, null, Modifier.fillMaxSize(.78f), filterQuality = FilterQuality.None)
    }
}

@Composable
private fun RecipeGrid(pattern: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (row in 0 until 3) Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (column in 0 until 3) RecipeIcon(pattern[row * 3 + column], 40.dp)
        }
    }
}

@Composable
private fun NewBadge() {
    Text("NUEVA", Modifier.background(Biomeria.colors.gold, RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        color = Biomeria.colors.ink, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
}
