package com.biomeria.launcher.ui.hub

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.biomeria.launcher.data.EngineType
import com.biomeria.launcher.data.GameInstance
import com.biomeria.launcher.ui.components.BlockButton
import com.biomeria.launcher.ui.components.BlockButtons
import com.biomeria.launcher.ui.components.PanelShape
import com.biomeria.launcher.ui.theme.Biomeria
import com.biomeria.launcher.ui.theme.PixelFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Lets the hub know a screenshot is open, so B closes it instead of leaving the page. */
internal object GalleryViewer {
    var open by mutableStateOf(false)
}

private class Shot(val file: File, val worldId: String, val world: String, val modified: Long)

private fun loadShots(context: android.content.Context, worlds: List<GameInstance>): List<Shot> =
    worlds.flatMap { world ->
        val dir = File(context.filesDir, "instances/${world.id}/screenshots")
        dir.listFiles { f -> f.isFile && f.extension.lowercase() in setOf("png", "jpg", "jpeg") }.orEmpty()
            .map { Shot(it, world.id, world.title, it.lastModified()) }
    }.sortedByDescending { it.modified }

private fun decode(file: File, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (bounds.outWidth / sample > maxSide * 2) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}

/** The screenshots taken in Minecraft (F2), grouped by world. */
@Composable
internal fun GalleryPage(instances: List<GameInstance>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val palette = Biomeria.colors
    val worlds = remember(instances) { instances.filter { it.engine == EngineType.JAVA } }
    var reload by remember { mutableStateOf(0) }
    val shots by produceState<List<Shot>?>(null, worlds, reload) {
        value = withContext(Dispatchers.IO) { loadShots(context, worlds) }
    }
    var filter by remember { mutableStateOf<String?>(null) }
    var openIndex by remember { mutableStateOf(-1) }
    var confirmDelete by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    val all = shots.orEmpty()
    val shown = all.filter { filter == null || it.worldId == filter }
    val withShots = worlds.filter { w -> all.any { it.worldId == w.id } }
    val viewing = openIndex in shown.indices
    LaunchedEffect(viewing) { GalleryViewer.open = viewing }
    DisposableEffect(Unit) { onDispose { GalleryViewer.open = false } }

    fun share(shot: Shot) {
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", shot.file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "Compartir captura").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { notice = "No se pudo compartir esta captura." }
    }
    fun delete(shot: Shot) {
        shot.file.delete()
        confirmDelete = false
        openIndex = -1
        reload++
    }

    Column(modifier.padding(horizontal = 24.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Galería", fontFamily = PixelFont, fontSize = 34.sp)
            Spacer(Modifier.weight(1f))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (withShots.size > 1) {
                    GalleryChoice("Todos", filter == null) { filter = null; openIndex = -1 }
                    withShots.forEach { w -> GalleryChoice(w.title, filter == w.id) { filter = w.id; openIndex = -1 } }
                }
            }
        }
        when {
            shots == null -> Text("Buscando tus capturas…", color = palette.textMuted)
            shown.isEmpty() -> Text("Aún no hay capturas. Saca una con la tecla F2 dentro del juego y aparecerá aquí.",
                color = palette.textMuted, style = MaterialTheme.typography.bodyLarge)
            viewing -> Viewer(shown, openIndex, confirmDelete, notice,
                onIndex = { openIndex = it; confirmDelete = false; notice = "" },
                onShare = { share(shown[openIndex]) },
                onDelete = { if (confirmDelete) delete(shown[openIndex]) else confirmDelete = true },
                onClose = { openIndex = -1; confirmDelete = false; notice = "" },
                Modifier.weight(1f))
            else -> LazyVerticalGrid(GridCells.Adaptive(190.dp), Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(shown.size) { index ->
                    val shot = shown[index]
                    val thumb by produceState<Bitmap?>(null, shot.file) { value = withContext(Dispatchers.IO) { decode(shot.file, 380) } }
                    BlockButton({ openIndex = index }, Modifier.fillMaxWidth(), BlockButtons.neutral, contentPadding = PaddingValues(0.dp)) {
                        Column(Modifier.fillMaxWidth()) {
                            Box(Modifier.fillMaxWidth().height(110.dp).background(palette.ink)) {
                                thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                                    filterQuality = FilterQuality.None) }
                            }
                            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(shot.world, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(shot.modified)),
                                    color = palette.textMuted, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
        Text("Aquí aparecen las capturas de Minecraft (F2) de tus mundos de Java.", color = palette.textMuted,
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Viewer(
    shots: List<Shot>, index: Int, confirmDelete: Boolean, notice: String,
    onIndex: (Int) -> Unit, onShare: () -> Unit, onDelete: () -> Unit, onClose: () -> Unit, modifier: Modifier
) {
    val palette = Biomeria.colors
    val shot = shots[index]
    val image by produceState<Bitmap?>(null, shot.file) { value = withContext(Dispatchers.IO) { decode(shot.file, 1280) } }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val step = { d: Int -> onIndex((index + d + shots.size) % shots.size) }
    Row(modifier.focusRequester(focus).focusable().onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
        when (e.key) {
            Key.ButtonL1 -> { step(-1); true }
            Key.ButtonR1 -> { step(1); true }
            Key.ButtonA -> { onShare(); true }
            Key.ButtonY -> { onDelete(); true }
            Key.ButtonB -> { onClose(); true }
            else -> false
        }
    }, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.weight(1f).fillMaxHeight().clip(PanelShape).background(palette.ink).border(2.dp, palette.surfaceElevated, PanelShape)) {
            image?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            BlockButton({ step(-1) }, Modifier.align(Alignment.CenterStart).padding(8.dp), BlockButtons.neutral) { Text("LB") }
            BlockButton({ step(1) }, Modifier.align(Alignment.CenterEnd).padding(8.dp), BlockButtons.neutral) { Text("RB") }
        }
        Column(Modifier.width(230.dp).fillMaxHeight().background(palette.surfaceCard, PanelShape)
            .border(2.dp, palette.surfaceElevated, PanelShape).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(shot.world, fontFamily = PixelFont, fontSize = 20.sp)
            Text(SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(shot.modified)), color = palette.textMuted)
            Text("Captura ${index + 1} de ${shots.size}", color = palette.textMuted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            if (notice.isNotEmpty()) Text(notice, color = palette.gold, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.bodySmall)
            BlockButton(onShare, Modifier.fillMaxWidth(), BlockButtons.primary) { Text("Compartir", fontWeight = FontWeight.ExtraBold) }
            BlockButton(onDelete, Modifier.fillMaxWidth(), if (confirmDelete) BlockButtons.danger else BlockButtons.neutral) {
                Text(if (confirmDelete) "¿Seguro? Borrar" else "Borrar", fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

@Composable
private fun GalleryChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    BlockButton(onClick, colors = if (selected) BlockButtons.primary else BlockButtons.neutral,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
        Text(label, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
}
