package com.prissma.launcher.engine.java

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal const val SEARCH_SPACE = 37
internal const val SEARCH_ERASE = 38
internal const val SEARCH_DONE = 39
internal val inventorySearchCharacters = "qwertyuiopasdfghjklñzxcvbnm1234567890"
internal val inventorySearchRows = listOf(
    0..9, 10..19, 20..26, 27..36, SEARCH_SPACE..SEARCH_DONE
).map { row -> row.map { PanelPadSlot(it) } }

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun InventorySearchKeyboard(
    query: String,
    focus: PanelPadSlot?,
    onKey: (Int) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(query.ifBlank { "Buscar por nombre" }, Modifier.weight(1f).padding(8.dp),
                style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = onClear, enabled = query.isNotEmpty()) { Text("Limpiar") }
        }
        inventorySearchRows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                row.forEach { key ->
                    val reveal = remember(key) { BringIntoViewRequester() }
                    LaunchedEffect(focus == key) {
                        if (focus == key) reveal.bringIntoView()
                    }
                    val label = when (key.number) {
                        SEARCH_SPACE -> "Espacio"
                        SEARCH_ERASE -> "Borrar"
                        SEARCH_DONE -> "Ver objetos"
                        else -> inventorySearchCharacters[key.number].uppercaseChar().toString()
                    }
                    FilledTonalButton(
                        onClick = { onKey(key.number) }, modifier = Modifier.weight(1f).height(38.dp).bringIntoViewRequester(reveal),
                        border = if (focus == key) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                        contentPadding = PaddingValues(0.dp)
                    ) { Text(label, style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
    }
}
