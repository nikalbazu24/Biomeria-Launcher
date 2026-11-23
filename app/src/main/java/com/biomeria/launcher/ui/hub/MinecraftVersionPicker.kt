package com.biomeria.launcher.ui.hub

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.biomeria.launcher.engine.java.MinecraftCatalogEntry
import com.biomeria.launcher.engine.java.MojangDownloader
import com.biomeria.launcher.engine.java.experimentalVersions
import com.biomeria.launcher.ui.components.LauncherButton
import com.biomeria.launcher.ui.components.gamepadClick
import com.biomeria.launcher.ui.theme.Biomeria

private val catalogChannels = linkedMapOf(
    "release" to "Estables", "" to "Todas", "snapshot" to "Snapshots",
    "old_beta" to "Betas antiguas", "old_alpha" to "Alfas"
)

@Composable
internal fun MinecraftVersionPicker(onDismiss: () -> Unit, onSelect: (MinecraftCatalogEntry) -> Unit) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var channel by rememberSaveable { mutableStateOf("release") }
    var catalog by remember { mutableStateOf<List<MinecraftCatalogEntry>?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(context) { catalog = MojangDownloader(context).catalog() }
    LaunchedEffect(query, channel) { listState.scrollToItem(0) }

    val matches = remember(catalog, channel, query) {
        catalog.orEmpty().filter { (channel.isBlank() || channel == it.channel) && it.version.contains(query.trim(), true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Versiones de Minecraft Java") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                OutlinedTextField(query, { query = it.take(64) }, Modifier.fillMaxWidth(),
                    label = { Text("Buscar versión") }, singleLine = true)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    catalogChannels.forEach { (id, name) ->
                        val select = { channel = id }
                        FilterChip(channel == id, select, { Text(name) }, modifier = Modifier.gamepadClick(onClick = select))
                    }
                }
                if (catalog == null) CircularProgressIndicator()
                else if (matches.isEmpty()) Text("No hay versiones para esta búsqueda.")
                else LazyColumn(Modifier.heightIn(max = 245.dp), state = listState, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    items(matches, key = { it.version }) { item ->
                        val select = { onSelect(item) }
                        val status = when {
                            !item.playable -> "Solo catálogo"
                            item.version in experimentalVersions -> "Vanilla en pruebas"
                            else -> "Java 21"
                        }
                        LauncherButton(item.version + " · " + status, select, Modifier.fillMaxWidth())
                    }
                }
                Text("Java 21 · jugables con el motor actual. Las demás requieren otra compatibilidad.",
                    color = Biomeria.colors.textMuted, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { LauncherButton("Volver", onDismiss) }
    )
}
