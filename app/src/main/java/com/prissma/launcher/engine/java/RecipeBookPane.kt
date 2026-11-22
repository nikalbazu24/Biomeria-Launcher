package com.prissma.launcher.engine.java

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.prissma.launcher.ui.theme.Prissma
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun RecipeBookPane(
    bridge: DualScreenBridge,
    inventory: PanelInventory,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var book by remember { mutableStateOf<PanelRecipeBook?>(null) }
    var selectedId by remember { mutableStateOf<Int?>(null) }
    var query by remember { mutableStateOf("") }
    var typing by remember { mutableStateOf(false) }
    var onlyCraftable by remember { mutableStateOf(false) }
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
    val selected = snapshot?.recipes?.firstOrNull { it.id == selectedId }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                typing -> OutlinedButton(onClick = { typing = false }) { Text("Ver recetas") }
                selectedId != null -> OutlinedButton(onClick = { selectedId = null }) { Text("Recetas") }
                else -> OutlinedButton(onClick = onBack) { Text("Mesa 3×3") }
            }
            Text(if (typing) query.ifBlank { "Buscar por nombre" } else "Libro de recetas",
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall)
            if (!typing && selectedId == null) {
                OutlinedButton(onClick = { typing = true }) { Text("Buscar") }
            }
        }

        when {
            typing -> RecipeSearchKeys(query, { query = it }, Modifier.weight(1f))
            snapshot == null -> Text("Leyendo las recetas de Minecraft…", color = Prissma.colors.textMuted)
            selected != null -> RecipeDetails(
                selected, snapshot, hasCursorItem = inventory.held.count != 0, modifier = Modifier.weight(1f)
            ) {
                if (bridge.send(inventory, PanelCommand.PLACE_RECIPE, selected.id)) onBack()
            }
            selectedId != null -> Text("La receta ya no está en el libro.", color = Prissma.colors.textMuted)
            else -> {
                val results = remember(snapshot.recipes, query, onlyCraftable) {
                    filterPanelRecipes(snapshot.recipes, query, onlyCraftable)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(onlyCraftable, { onlyCraftable = !onlyCraftable }, label = { Text("Solo fabricables") })
                    if (query.isNotBlank()) {
                        FilterChip(true, { typing = true }, label = {
                            Text(query, modifier = Modifier.widthIn(max = 150.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        })
                    }
                }
                if (results.isEmpty()) {
                    Text(if (snapshot.recipes.isEmpty()) "Todavía no has desbloqueado recetas para la mesa."
                        else "No hay recetas que coincidan.", color = Prissma.colors.textMuted)
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(results, key = { it.id }) { recipe ->
                        Surface(
                            onClick = { if (bridge.send(inventory, PanelCommand.INSPECT_RECIPE, recipe.id)) selectedId = recipe.id },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(recipe.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (recipe.resultCount > 1) Text("×${recipe.resultCount}")
                                val status = when (recipe.state) {
                                    PanelRecipeState.READY -> "Disponible"
                                    PanelRecipeState.MISSING -> "Faltan ingredientes"
                                    PanelRecipeState.MANUAL -> "Manual"
                                }
                                Text(status, style = MaterialTheme.typography.labelMedium,
                                    color = if (recipe.state == PanelRecipeState.READY) MaterialTheme.colorScheme.primary
                                        else Prissma.colors.textMuted)
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
    modifier: Modifier,
    onPlace: () -> Unit
) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (recipe.resultCount > 1) "${recipe.name} ×${recipe.resultCount}" else recipe.name,
            style = MaterialTheme.typography.titleMedium)
        when (recipe.state) {
            PanelRecipeState.READY -> {
                Button(onClick = onPlace, enabled = !hasCursorItem) { Text("Colocar ingredientes") }
                Text(if (hasCursorItem) "Guarda el objeto que llevas en el cursor."
                    else "Recoge el resultado en la mesa.", color = Prissma.colors.textMuted)
            }
            PanelRecipeState.MISSING -> {
                Text("Ingredientes que faltan", style = MaterialTheme.typography.titleSmall)
                if (book.selectedId != recipe.id) {
                    Text("Comprobando…", color = Prissma.colors.textMuted)
                } else {
                    book.missing.forEach { ingredient ->
                        Text("×${ingredient.count}  ${ingredient.name.ifBlank { "Ingrediente de la receta" }}")
                    }
                }
            }
            PanelRecipeState.MANUAL -> Text("Puedes preparar esta receta colocando los ingredientes a mano.")
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
