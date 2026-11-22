package com.prissma.launcher.engine.java

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.roundToInt

internal data class TerrainPadInput(val action: (PanelPadAction) -> Unit, val pan: (Float, Float) -> Unit)

@Composable
internal fun TerrainMapPane(
    bridge: DualScreenBridge,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onPadInput: ((TerrainPadInput?) -> Unit)? = null
) {
    val directory = bridge.terrainDirectory ?: return
    val context = LocalContext.current
    var position by remember(bridge) { mutableStateOf<TerrainPosition?>(null) }
    LaunchedEffect(bridge) {
        while (true) {
            position = withContext(Dispatchers.IO) { bridge.readTerrainPosition() }
            delay(180)
        }
    }
    val player = position
    if (player == null) {
        if (!compact) Text("Esperando datos del terreno…")
        return
    }
    key(player.scope) {
        TerrainView(directory, player, context, modifier, compact, onPadInput)
    }
}

@Composable
private fun TerrainView(
    directory: java.io.File,
    player: TerrainPosition,
    context: Context,
    modifier: Modifier,
    compact: Boolean,
    onPadInput: ((TerrainPadInput?) -> Unit)?
) {
    var view by remember { mutableStateOf(TerrainCamera(player.x, player.z, if (compact) 80f else 112f)) }
    val camera = if (view.follow) view.copy(x = player.x, z = player.z) else view
    val preference = remember(context) { context.getSharedPreferences("terrain-waypoints", Context.MODE_PRIVATE) }
    var waypoints by remember(player.scope) {
        mutableStateOf(preference.getString(player.scope, "").orEmpty().split(';').mapNotNull { point ->
            val values = point.split(',').mapNotNull(String::toIntOrNull)
            if (values.size == 3) TerrainWaypoint(values[0], values[1], values[2]) else null
        }.take(32))
    }
    var waypointMenu by remember { mutableStateOf(false) }
    fun updateWaypoints(updated: List<TerrainWaypoint>) {
        waypoints = updated
        preference.edit().putString(player.scope, updated.joinToString(";") { "${it.x},${it.y},${it.z}" }).apply()
    }

    fun addWaypoint() {
        val point = TerrainWaypoint(floor(camera.x).toInt(), floor(player.y).toInt(), floor(camera.z).toInt())
        if (point !in waypoints) updateWaypoints((waypoints + point).takeLast(32))
    }

    val requested by rememberUpdatedState(camera.chunks())
    var images by remember { mutableStateOf<Map<TerrainChunk, ImageBitmap>>(emptyMap()) }
    LaunchedEffect(directory, player.scope) {
        var tiles = emptyMap<TerrainChunk, TerrainTile>()
        while (true) {
            val current = requested
            val previousImages = images
            val (nextTiles, nextImages) = withContext(Dispatchers.IO) {
                val fresh = readTerrainTiles(directory, player.scope, current, tiles)
                fresh to fresh.mapValues { (chunk, tile) ->
                    previousImages[chunk]?.takeIf { tiles[chunk] === tile } ?: Bitmap.createBitmap(
                        tile.pixels, TERRAIN_TILE_SIZE, TERRAIN_TILE_SIZE, Bitmap.Config.ARGB_8888
                    ).asImageBitmap()
                }
            }
            tiles = nextTiles
            images = nextImages
            delay(if (compact) 750 else 500)
        }
    }
    val input by rememberUpdatedState(TerrainPadInput(
        action = { action ->
            when (action) {
                PanelPadAction.PICK -> view = camera.copy(follow = true)
                PanelPadAction.SPLIT -> view = camera.zoom(1.25f)
                PanelPadAction.TRANSFER -> view = camera.zoom(.8f)
                PanelPadAction.MARK -> addWaypoint()
                else -> Unit
            }
        },
        pan = { x, y -> view = camera.pan(x * camera.span * .06, y * camera.span * .06) }
    ))
    val register by rememberUpdatedState(onPadInput)
    DisposableEffect(Unit) {
        register?.invoke(TerrainPadInput({ input.action(it) }, { x, y -> input.pan(x, y) }))
        onDispose { register?.invoke(null) }
    }
    val shape = RoundedCornerShape(if (compact) 12.dp else 16.dp)
    val frame = modifier.clip(shape).background(Color(0xff101c23)).border(1.dp, Color(0xff506372), shape)
    val liveCamera by rememberUpdatedState(camera)
    Box(frame) {
        Canvas(Modifier.fillMaxSize().semantics { contentDescription = "Mapa de terreno de PRISSMA" }.then(
            if (compact) Modifier else Modifier.pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val current = liveCamera
                    val scale = maxOf(size.width, size.height).toFloat() / current.span
                    if (scale > 0f) {
                        val moved = if (pan == Offset.Zero) current else current.pan(-pan.x / scale.toDouble(), -pan.y / scale.toDouble())
                        view = moved.zoom(1f / zoom)
                    }
                }
            }
        )) {
            val scale = size.maxDimension / camera.span
            fun screen(x: Double, z: Double) = Offset(
                center.x + ((x - camera.x) * scale).toFloat(),
                center.y + ((z - camera.z) * scale).toFloat()
            )
            for ((chunk, image) in images) {
                val start = screen((chunk.x * TERRAIN_TILE_SIZE).toDouble(), (chunk.z * TERRAIN_TILE_SIZE).toDouble())
                val end = screen((chunk.x * TERRAIN_TILE_SIZE + TERRAIN_TILE_SIZE).toDouble(),
                    (chunk.z * TERRAIN_TILE_SIZE + TERRAIN_TILE_SIZE).toDouble())
                val left = start.x.roundToInt()
                val top = start.y.roundToInt()
                drawImage(image, dstOffset = IntOffset(left, top),
                    dstSize = IntSize((end.x.roundToInt() - left).coerceAtLeast(1), (end.y.roundToInt() - top).coerceAtLeast(1)),
                    filterQuality = FilterQuality.None)
            }
            if (!compact) waypoints.forEach { point ->
                val anchor = screen(point.x.toDouble(), point.z.toDouble())
                drawCircle(Color(0xff0e1c22), 7.dp.toPx(), anchor)
                drawCircle(Color(0xffffcf80), 4.dp.toPx(), anchor)
            }
            val cursor = screen(player.x, player.z)
            val cursorSize = (if (compact) 8.dp else 11.dp).toPx()
            rotate(player.yaw + 180f, cursor) {
                val arrow = Path().apply {
                    moveTo(cursor.x, cursor.y - cursorSize)
                    lineTo(cursor.x - cursorSize * .7f, cursor.y + cursorSize * .7f)
                    lineTo(cursor.x, cursor.y + cursorSize * .27f)
                    lineTo(cursor.x + cursorSize * .7f, cursor.y + cursorSize * .7f)
                    close()
                }
                drawCircle(Color(0xff10232c), cursorSize * 1.1f, cursor)
                drawPath(arrow, Color(0xffd7ffa8))
            }
        }
        MapLabel(if (compact) "N ↑" else "N ↑    ${camera.span.toInt()} bloques", Modifier.align(Alignment.TopStart).padding(8.dp))
        if (!compact) {
            Row(Modifier.align(Alignment.TopEnd).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledTonalButton(onClick = { view = camera.zoom(1.25f) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("−") }
                FilledTonalButton(onClick = { view = camera.zoom(.8f) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("+") }
                FilledTonalButton(onClick = { view = camera.copy(follow = true) }) { Text("Centrar") }
            }
            MapLabel("X ${floor(player.x).toInt()}   Y ${floor(player.y).toInt()}   Z ${floor(player.z).toInt()}",
                Modifier.align(Alignment.BottomStart).padding(8.dp))
            Row(Modifier.align(Alignment.BottomEnd).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledTonalButton(onClick = ::addWaypoint, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("+ Marca") }
                Box {
                    FilledTonalButton(onClick = { waypointMenu = true }, enabled = waypoints.isNotEmpty(),
                        contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Marcas ${waypoints.size}") }
                    DropdownMenu(waypointMenu, onDismissRequest = { waypointMenu = false }) {
                        waypoints.forEachIndexed { index, point ->
                            DropdownMenuItem(
                                text = { Text("Marca ${index + 1} · ${point.x}, ${point.z}") },
                                onClick = {
                                    view = camera.copy(x = point.x.toDouble(), z = point.z.toDouble(), follow = false)
                                    waypointMenu = false
                                },
                                trailingIcon = {
                                    TextButton(onClick = {
                                        updateWaypoints(waypoints - point)
                                        if (waypoints.isEmpty()) waypointMenu = false
                                    }) { Text("Borrar") }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MapLabel(text: String, modifier: Modifier) {
    Text(text, modifier.background(Color(0xe51c2932), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 4.dp),
        color = Color(0xffe2f1e8), style = MaterialTheme.typography.labelMedium)
}
