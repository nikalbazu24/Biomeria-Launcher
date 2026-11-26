package com.biomeria.launcher.ui.workshop

import android.content.Intent
import android.graphics.BitmapFactory
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.biomeria.launcher.data.GameInstance
import com.biomeria.launcher.data.GameLog
import com.biomeria.launcher.data.ImportKind
import com.biomeria.launcher.data.InstalledFileItem
import com.biomeria.launcher.data.InstanceContent
import com.biomeria.launcher.data.ManagedMod
import com.biomeria.launcher.data.ModUpdate
import com.biomeria.launcher.data.requiredBy
import com.biomeria.launcher.ui.components.BiomeriaDialog
import com.biomeria.launcher.ui.components.BlockButton
import com.biomeria.launcher.ui.components.BlockButtons
import com.biomeria.launcher.ui.components.ControlShape
import com.biomeria.launcher.ui.components.LauncherButton
import com.biomeria.launcher.ui.components.PanelShape
import com.biomeria.launcher.ui.theme.Biomeria
import com.biomeria.launcher.ui.theme.PixelFont
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class MyModsTab(val label: String) { MODS("Mods"), OTHERS("Shaders y texturas"), LOGS("Registros"), IMPORT("Importar") }

/** Everything already in this world: mods to switch on, off or update, the game's logs, and files to import. */
@Composable
internal fun MyModsPanel(
    instance: GameInstance,
    others: List<InstalledFileItem>,
    busy: Boolean,
    onRemoveOther: (InstalledFileItem) -> Unit,
    onChanged: () -> Unit
) {
    val context = LocalContext.current
    val content = remember(context, instance.id) { InstanceContent(context, instance.id) }
    val scope = rememberCoroutineScope()
    val loader = instance.configuredModLoader
    var tab by rememberSaveable(instance.id) { mutableStateOf(if (loader == null) MyModsTab.OTHERS else MyModsTab.MODS) }
    var mods by remember { mutableStateOf<List<ManagedMod>?>(null) }
    var logs by remember { mutableStateOf<List<GameLog>?>(null) }
    var updates by remember { mutableStateOf<Map<java.io.File, ModUpdate>>(emptyMap()) }
    var checking by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<ManagedMod?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    fun task(label: String, block: suspend () -> String?) {
        if (working != null) return
        working = label
        message = null
        scope.launch {
            try {
                message = block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                message = error.message ?: "Algo salió mal. Intenta otra vez."
            } finally {
                working = null
                reload++
                onChanged()
            }
        }
    }

    LaunchedEffect(reload, instance.id) {
        mods = content.mods()
        logs = content.logs()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) task("Importando…") {
            val (kind, name) = content.import(uri)
            when (kind) {
                ImportKind.WORLD -> "Listo: el mundo «$name» ya está en esta instancia."
                else -> "Listo: ${kind.label.lowercase()} $name agregado."
            }
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MyModsTab.entries.forEach { item ->
                val count = when (item) {
                    MyModsTab.MODS -> mods?.size
                    MyModsTab.OTHERS -> others.size
                    else -> null
                }
                BlockButton({ tab = item }, colors = if (tab == item) BlockButtons.primary else BlockButtons.neutral) {
                    Text(item.label + (count?.let { " ($it)" } ?: ""), fontWeight = FontWeight.ExtraBold, maxLines = 1)
                }
            }
            Spacer(Modifier.weight(1f))
            if (updates.isNotEmpty()) Text("${updates.size} actualizaciones",
                Modifier.background(Biomeria.colors.gold, ControlShape).padding(horizontal = 8.dp, vertical = 2.dp),
                color = Biomeria.colors.ink, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold)
        }
        (working ?: message)?.let {
            Text(it, color = if (working != null) Biomeria.colors.netP2P else Biomeria.colors.textPrimary,
                style = MaterialTheme.typography.bodySmall)
        }

        when (tab) {
            MyModsTab.MODS -> ModsTab(
                mods, updates, checking, loader != null, busy || working != null,
                onCheck = {
                    val list = mods.orEmpty()
                    if (loader != null && !checking) {
                        checking = true
                        message = null
                        scope.launch {
                            try {
                                updates = content.findUpdates(list, instance.version, loader)
                                message = if (updates.isEmpty()) "Todos tus mods están al día." else null
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                message = "No se pudo consultar Modrinth. Revisa tu conexión."
                            } finally {
                                checking = false
                            }
                        }
                    }
                },
                onUpdateAll = {
                    val pending = mods.orEmpty().mapNotNull { mod -> updates[mod.file]?.let { mod to it } }
                    task("Actualizando ${pending.size} mods…") {
                        pending.forEach { (mod, update) -> content.applyUpdate(mod, update) }
                        updates = emptyMap()
                        "Listo: ${pending.size} mods actualizados."
                    }
                },
                onUpdate = { mod, update ->
                    task("Actualizando ${mod.name}…") {
                        content.applyUpdate(mod, update)
                        updates = updates - mod.file
                        "${mod.name} quedó en la versión ${update.versionNumber}."
                    }
                },
                onToggle = { mod ->
                    val needers = requiredBy(mod, mods.orEmpty())
                    if (mod.enabled && needers.isNotEmpty()) {
                        message = "${mod.name} no se puede apagar: lo usa ${needers.joinToString(", ")}."
                    } else task(if (mod.enabled) "Apagando ${mod.name}…" else "Activando ${mod.name}…") {
                        content.setEnabled(mod, !mod.enabled)
                        null
                    }
                },
                onRemove = { mod ->
                    val needers = requiredBy(mod, mods.orEmpty())
                    if (needers.isNotEmpty()) message = "Primero quita los mods que usan ${mod.name}: ${needers.joinToString(", ")}."
                    else removing = mod
                },
                requiredOf = { requiredBy(it, mods.orEmpty()) }
            )
            MyModsTab.OTHERS -> if (others.isEmpty()) Hint("Todavía no hay shaders ni paquetes de texturas en este mundo.")
            else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(others, key = { it.file.absolutePath }) { item ->
                    Row(rowModifier(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(item.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            Text(item.categoryLabel + " · " + item.sizeKb + " KB", color = Biomeria.colors.textMuted,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        LauncherButton("Quitar", { onRemoveOther(item) }, enabled = !busy)
                    }
                }
            }
            MyModsTab.LOGS -> LogsTab(logs) { log ->
                runCatching {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", log.file)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "Registro de Biomeria · ${instance.title}")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, "Compartir registro"))
                }.onFailure { message = "No se pudo compartir el registro." }
            }
            MyModsTab.IMPORT -> ImportTab(enabled = working == null && !busy) { picker.launch(arrayOf("*/*")) }
        }
    }

    removing?.let { mod ->
        BiomeriaDialog(
            onDismissRequest = { removing = null }, accent = Biomeria.colors.engineRust, chip = "Cuidado",
            title = { Text("¿Quitar ${mod.name}?") },
            text = { Text("Se borra el archivo ${mod.file.name}. Si solo quieres probar sin él, mejor apágalo.") },
            dismissButton = { LauncherButton("Cancelar", { removing = null }, primary = true) },
            confirmButton = {
                LauncherButton("Quitar", {
                    removing = null
                    task("Quitando ${mod.name}…") {
                        if (content.remove(mod.file)) "${mod.name} quitado." else "No se pudo quitar ${mod.name}."
                    }
                })
            }
        )
    }
}

@Composable
private fun ModsTab(
    mods: List<ManagedMod>?, updates: Map<java.io.File, ModUpdate>, checking: Boolean, supportsMods: Boolean, busy: Boolean,
    onCheck: () -> Unit, onUpdateAll: () -> Unit, onUpdate: (ManagedMod, ModUpdate) -> Unit,
    onToggle: (ManagedMod) -> Unit, onRemove: (ManagedMod) -> Unit, requiredOf: (ManagedMod) -> List<String>
) {
    if (!supportsMods) return Hint("Este mundo no usa Fabric, Quilt ni NeoForge, así que no tiene mods. Puedes elegir uno al editar el mundo.")
    if (mods == null) return Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp))
    }
    var query by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            com.biomeria.launcher.ui.components.BiomeriaTextField(query, { query = it.take(48) }, Modifier.weight(1f),
                placeholder = "Buscar en tus mods", singleLine = true)
            if (updates.isEmpty()) LauncherButton(if (checking) "Buscando…" else "Buscar actualizaciones", onCheck,
                enabled = !checking && !busy && mods.isNotEmpty())
            else LauncherButton("Actualizar todo", onUpdateAll, primary = true, enabled = !busy)
        }
        if (mods.isEmpty()) return@Column Hint("Todavía no hay mods. Búscalos en la pestaña Mods del Taller o impórtalos.")
        val shown = mods.filter { query.isBlank() || it.name.contains(query.trim(), true) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(shown, key = { it.file.absolutePath }) { mod ->
                val update = updates[mod.file]
                val needers = requiredOf(mod)
                Row(rowModifier().then(if (mod.enabled) Modifier else Modifier.background(Biomeria.colors.surfaceVoid, PanelShape)),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ModIcon(mod)
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(mod.name, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontFamily = PixelFont, fontSize = 16.sp,
                                color = if (mod.enabled) Biomeria.colors.textPrimary else Biomeria.colors.textMuted)
                            mod.version?.let { Text(it, color = Biomeria.colors.textMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                        }
                        Text(
                            when {
                                !mod.enabled -> "Apagado · Minecraft no lo carga"
                                needers.isNotEmpty() -> "Lo necesita " + needers.joinToString(", ")
                                else -> mod.description ?: mod.file.name
                            },
                            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                            color = Biomeria.colors.textMuted
                        )
                    }
                    if (update != null) BlockButton({ onUpdate(mod, update) }, colors = BlockButtons.gold, enabled = !busy) {
                        Text("Actualizar a " + update.versionNumber.take(16), fontWeight = FontWeight.ExtraBold, maxLines = 1)
                    }
                    LauncherButton("Quitar", { onRemove(mod) }, enabled = !busy)
                    BlockButton({ onToggle(mod) }, Modifier.width(118.dp),
                        if (mod.enabled) BlockButtons.primary else BlockButtons.neutral, enabled = !busy) {
                        Text(if (mod.enabled) "Activado" else "Apagado", fontWeight = FontWeight.ExtraBold, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun ModIcon(mod: ManagedMod) {
    val bitmap = remember(mod.file, mod.iconBytes) {
        mod.iconBytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
    }
    Box(Modifier.size(40.dp).clip(ControlShape).background(Biomeria.colors.surfaceElevated)
        .border(2.dp, Biomeria.colors.ink, ControlShape), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap, null, Modifier.fillMaxSize())
        else Text(mod.name.take(1).uppercase(), fontFamily = PixelFont, fontSize = 18.sp, color = Biomeria.colors.brandAccent)
    }
}

@Composable
private fun LogsTab(logs: List<GameLog>?, onShare: (GameLog) -> Unit) {
    if (logs == null) return
    if (logs.isEmpty()) return Hint("Aquí aparecerán los registros cuando juegues en este mundo.")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(logs, key = { it.file.absolutePath }) { log ->
                Row(rowModifier(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(12.dp).background(if (log.crashed) Biomeria.colors.error else Biomeria.colors.brandAccent, ControlShape))
                    Column(Modifier.weight(1f)) {
                        val time = DateUtils.getRelativeTimeSpanString(log.playedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                        Text("$time · " + if (log.crashed) "el juego se cerró" else "sin errores", fontWeight = FontWeight.SemiBold)
                        Text(log.cause ?: if (log.crashed) "Comparte el registro para averiguar qué pasó." else
                            if (log.current) "La última partida." else "Partida anterior.",
                            style = MaterialTheme.typography.bodySmall, color = Biomeria.colors.textMuted, maxLines = 2)
                    }
                    LauncherButton("Compartir", { onShare(log) }, primary = log.crashed)
                }
            }
        }
        Text("Se guardan los últimos 10 registros de este mundo.", style = MaterialTheme.typography.bodySmall,
            color = Biomeria.colors.textMuted)
    }
}

@Composable
private fun ImportTab(enabled: Boolean, onPick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ImportKind.entries.forEach { kind ->
                Column(rowModifier().weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(kind.label, fontFamily = PixelFont, fontSize = 16.sp,
                        color = when (kind) {
                            ImportKind.MOD -> Biomeria.colors.brandAccent
                            ImportKind.SHADER -> Biomeria.colors.gold
                            ImportKind.RESOURCE_PACK -> Biomeria.colors.netP2P
                            ImportKind.WORLD -> Biomeria.colors.engineRust
                        })
                    Text(if (kind == ImportKind.WORLD) "Se descomprime solo" else "Va a ${kind.folder}",
                        style = MaterialTheme.typography.bodySmall, color = Biomeria.colors.textMuted)
                }
            }
        }
        Text("Elige un archivo y Biomeria reconoce qué es y lo pone en su lugar.", color = Biomeria.colors.textMuted)
        LauncherButton("Elegir archivo", onPick, primary = true, enabled = enabled)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(vertical = 18.dp), color = Biomeria.colors.textMuted,
        style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun rowModifier() = Modifier.fillMaxWidth().background(Biomeria.colors.surfaceCard, PanelShape)
    .border(1.dp, Biomeria.colors.borderSubtle, PanelShape).padding(horizontal = 10.dp, vertical = 8.dp)
