package com.biomeria.launcher.ui.launch

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.biomeria.launcher.data.GameInstance
import com.biomeria.launcher.engine.java.*
import com.biomeria.launcher.ui.components.LauncherButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun JavaServersDialog(instance: GameInstance, onPlay: (JavaServerAddress) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val bookmarks = remember(context) { JavaServerBookmarks(context) }
    val scope = rememberCoroutineScope()
    var servers by remember(instance.id) { mutableStateOf(bookmarks.load(instance.id)) }
    val statuses = remember { mutableStateMapOf<JavaServerAddress, String>() }
    var name by remember { mutableStateOf("") }
    var rawAddress by remember { mutableStateOf("") }
    val address = parseJavaServerAddress(rawAddress)
    val compatible = supportsJavaQuickPlay(instance.version)
    fun save(updated: List<JavaServer>) {
        bookmarks.save(instance.id, updated)
        servers = updated
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Servidores · ${instance.title}") },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(7.dp)) {
                if (!compatible) Text("Para entrar con un toque necesitas Minecraft Java 1.20 o posterior.")
                if (servers.isEmpty()) Text("Agrega la dirección de un servidor para tenerlo a mano.")
                servers.forEach { server ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(server.name, style = MaterialTheme.typography.titleSmall)
                            Text(server.address.text, style = MaterialTheme.typography.bodySmall)
                            statuses[server.address]?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                LauncherButton("Jugar", { onPlay(server.address) }, enabled = compatible, primary = true)
                                LauncherButton("Estado", {
                                    statuses[server.address] = "Consultando…"
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) { runCatching { pingJavaServer(server.address) } }
                                        statuses[server.address] = result.getOrNull()?.let {
                                            "${it.online}/${it.max} jugadores" + if (it.version.isBlank()) "" else " · ${it.version}"
                                        } ?: "Sin respuesta"
                                    }
                                }, enabled = statuses[server.address] != "Consultando…")
                                TextButton(onClick = { save(servers - server) }) { Text("Quitar") }
                            }
                        }
                    }
                }
                if (servers.size < 32) {
                    OutlinedTextField(name, { name = it.take(48) }, label = { Text("Nombre") }, singleLine = true)
                    OutlinedTextField(rawAddress, { rawAddress = it.take(260) }, label = { Text("Dirección o IP:puerto") },
                        isError = rawAddress.isNotBlank() && address == null, singleLine = true)
                    LauncherButton("Guardar servidor", {
                        val valid = address ?: return@LauncherButton
                        save((servers.filterNot { it.address == valid }) + JavaServer(name.trim().ifBlank { valid.text }, valid))
                        name = ""
                        rawAddress = ""
                    }, enabled = address != null)
                }
            }
        },
        confirmButton = { LauncherButton("Listo", onDismiss, primary = true) }
    )
}
