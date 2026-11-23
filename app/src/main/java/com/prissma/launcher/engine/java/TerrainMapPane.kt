package com.prissma.launcher.engine.java

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import com.prissma.launcher.ui.theme.Prissma
import com.prissma.launcher.ui.components.BlockButtons
import com.prissma.launcher.ui.components.BlockButton
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.roundToInt

internal data class TerrainPadInput(
    val action: (PanelPadAction) -> Unit,
    val pan: (Float, Float) -> Unit,
    val managingWaypoints: () -> Boolean
)

@Composable
internal fun TerrainMapPane(
    bridge: DualScreenBridge,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    controllerActive: Boolean = false,
    onPadInput: ((TerrainPadInput?) -> Unit)? = null
) {
    val directory = bridge.terrainDirectory ?: return
    val context = LocalContext.current
    var position by remember(bridge) { mutableStateOf<TerrainPosition?>(null) }
    var entities by remember(bridge) { mutableStateOf<List<TerrainEntity>>(emptyList()) }
    LaunchedEffect(bridge) {
        var lastRead = 0L
        while (true) {
            val (next, nearby) = withContext(Dispatchers.IO) { bridge.readTerrainPosition() to bridge.readTerrainEntities() }
            entities = nearby
            val now = SystemClock.elapsedRealtime()
            if (next != null) {
                position = next
                lastRead = now
            } else if (now - lastRead > 1200L) {
                position = null
            }
            delay(180)
        }
    }
    val player = position
    if (player == null) {
        if (!compact) Text("Esperando datos del terreno…")
        return
    }
    key(player.scope) {
        TerrainView(directory, player, entities, context, modifier, compact, controllerActive, onPadInput)
    }
}

@Composable
private fun TerrainView(
    directory: java.io.File,
    player: TerrainPosition,
    entities: List<TerrainEntity>,
    context: Context,
    modifier: Modifier,
    compact: Boolean,
    controllerActive: Boolean,
    onPadInput: ((TerrainPadInput?) -> Unit)?
) {
    var view by remember { mutableStateOf(TerrainCamera(player.x, player.z, if (compact) 80f else 112f)) }
    val camera = if (view.follow) view.copy(x = player.x, z = player.z) else view
    val preference = remember(context) { context.getSharedPreferences("terrain-waypoints", Context.MODE_PRIVATE) }
    fun readBook() = TerrainWaypointBook.decode(preference.getString(player.scope, "").orEmpty())
    var waypoints by remember(preference, player.scope) { mutableStateOf(readBook()) }
    DisposableEffect(preference, player.scope) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == player.scope) waypoints = readBook()
        }
        preference.registerOnSharedPreferenceChangeListener(listener)
        waypoints = readBook()
        onDispose { preference.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    var managingWaypoints by remember { mutableStateOf(false) }
    var createImmediately by remember { mutableStateOf(false) }
    var waypointPadInput by remember { mutableStateOf<((PanelPadAction) -> Unit)?>(null) }
    fun openWaypoints(create: Boolean) {
        createImmediately = create
        managingWaypoints = true
    }
    fun saveBook(updated: TerrainWaypointBook) {
        if (updated == waypoints) return
        waypoints = updated
        preference.edit().putString(player.scope, updated.encode()).apply()
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
            if (managingWaypoints) waypointPadInput?.invoke(action)
            else when (action) {
                PanelPadAction.PICK -> view = camera.copy(follow = true)
                PanelPadAction.SPLIT -> view = camera.zoom(1.25f)
                PanelPadAction.TRANSFER -> view = camera.zoom(.8f)
                PanelPadAction.MARK -> openWaypoints(true)
                PanelPadAction.SEARCH -> openWaypoints(false)
                else -> Unit
            }
        },
        pan = { x, y -> if (!managingWaypoints) view = camera.pan(x * camera.span * .06, y * camera.span * .06) },
        managingWaypoints = { managingWaypoints }
    ))
    val register by rememberUpdatedState(onPadInput)
    DisposableEffect(Unit) {
        register?.invoke(TerrainPadInput({ input.action(it) }, { x, y -> input.pan(x, y) }, { input.managingWaypoints() }))
        onDispose { register?.invoke(null) }
    }
    val faceIds = remember(entities) { entities.map { if (it.kind == EntityKind.PLAYER) "minecraft:player" else it.id }.toSet() }
    var faces by remember { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }
    LaunchedEffect(faceIds, compact) {
        val icons = ItemIcons.current
        if (compact || icons == null) return@LaunchedEffect
        val missing = faceIds - faces.keys
        if (missing.isEmpty()) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            missing.mapNotNull { id -> icons.face(id)?.let { id to it.asImageBitmap() } }.toMap()
        }
        faces = faces + loaded
    }
    val names = rememberTextMeasurer()
    val nameStyle = MaterialTheme.typography.labelSmall.copy(color = Color.White)
    val shape = RoundedCornerShape(if (compact) 12.dp else 16.dp)
    val liveCamera by rememberUpdatedState(camera)
    var expanded by rememberSaveable { mutableStateOf(false) }
    val sidePanel = !compact && !expanded && !managingWaypoints
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    Box(if (sidePanel) Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true).clip(shape)
            .background(Color(0xff101c23)).border(3.dp, Color(0xff0b0e11), shape)
        else Modifier.weight(1f).fillMaxHeight().clip(shape).background(Color(0xff101c23)).border(3.dp, Color(0xff0b0e11), shape)) {
        if (managingWaypoints) {
            TerrainWaypointPane(waypoints, player, TerrainWaypoint(
                floor(camera.x).toInt(), floor(player.y).toInt(), floor(camera.z).toInt(), "Marca ${waypoints.points.size + 1}"
            ), createImmediately, controllerActive, ::saveBook, follow = { point ->
                saveBook(waypoints.follow(point.id))
                view = camera.copy(x = point.x.toDouble(), z = point.z.toDouble(), follow = false)
                managingWaypoints = false
            }, onClose = { managingWaypoints = false }, onPadInput = { waypointPadInput = it })
            return@Box
        }
        val active = waypoints.active
        Canvas(Modifier.fillMaxSize().semantics { contentDescription = "Mapa de terreno de Biomeria" }.then(
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
            for (point in waypoints.points) {
                if (point.id == active?.id || compact) continue
                drawWaypoint(point, screen(point.x.toDouble(), point.z.toDouble()), 5.dp.toPx(), false)
            }
            if (active != null) {
                val radius = (if (compact) 4.dp else 6.dp).toPx()
                val anchor = screen(active.x.toDouble(), active.z.toDouble())
                // Keep the target visible on the edge of the minimap when it is outside the current view.
                val target = if (compact) {
                    val inset = (radius * 2).coerceAtMost(size.minDimension / 2)
                    val bearing = anchor - center
                    val ratio = maxOf(kotlin.math.abs(bearing.x), kotlin.math.abs(bearing.y))
                    if (ratio > size.minDimension / 2 - inset) {
                        center + bearing * ((size.minDimension / 2 - inset) / ratio)
                    } else anchor
                } else anchor
                drawWaypoint(active, target, radius, true)
            }
            for (entity in entities) {
                val at = screen(entity.x, entity.z)
                if (at.x < -12f || at.y < -12f || at.x > size.width + 12f || at.y > size.height + 12f) continue
                if (compact) {
                    drawCircle(Color(0xff0b0e11), 3.5.dp.toPx(), at)
                    drawCircle(entity.kind.color, 2.5.dp.toPx(), at)
                    continue
                }
                val radius = 8.dp.toPx()
                drawCircle(Color(0xff0b0e11), radius + 1.5.dp.toPx(), at)
                drawCircle(entity.kind.color, radius, at)
                val face = faces[if (entity.kind == EntityKind.PLAYER) "minecraft:player" else entity.id]
                if (face != null) {
                    val side = (radius * 1.3f).roundToInt()
                    drawImage(face, dstOffset = IntOffset((at.x - side / 2f).roundToInt(), (at.y - side / 2f).roundToInt()),
                        dstSize = IntSize(side, side), filterQuality = FilterQuality.None)
                } else {
                    drawCircle(Color(0xff0b0e11), radius * .45f, at)
                }
                if (entity.kind == EntityKind.PLAYER && entity.name.isNotBlank()) {
                    val text = names.measure(entity.name, nameStyle)
                    val origin = Offset(at.x - text.size.width / 2f, at.y - radius - text.size.height - 3.dp.toPx())
                    drawRect(Color(0xcc0b0e11), origin - Offset(3.dp.toPx(), 1.dp.toPx()),
                        androidx.compose.ui.geometry.Size(text.size.width + 6.dp.toPx(), text.size.height + 2.dp.toPx()))
                    drawText(text, topLeft = origin)
                }
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
        Column(Modifier.align(Alignment.TopStart).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MapLabel(if (compact) "N ↑" else "N ↑    ${camera.span.toInt()} bloques")
            if (!compact && entities.isNotEmpty()) {
                val hostile = entities.count { it.kind == EntityKind.HOSTILE }
                val calm = entities.count { it.kind == EntityKind.PASSIVE }
                val nearest = entities.firstOrNull { it.kind == EntityKind.HOSTILE }
                MapLabel("Cerca: $hostile hostiles · $calm pacíficos")
                if (nearest != null) MapLabel("⚠ ${nearest.label} a ${kotlin.math.hypot(nearest.x - player.x, nearest.z - player.z).roundToInt()} b",
                    Modifier.widthIn(max = 200.dp))
            }
            if (active != null && !compact) {
                MapLabel(active.name, Modifier.widthIn(max = 170.dp))
                MapLabel("${active.distanceFrom(player)} bloques")
            }
        }
        if (!compact) MapLabel("X ${floor(player.x).toInt()}   Y ${floor(player.y).toInt()}   Z ${floor(player.z).toInt()}",
            Modifier.align(Alignment.BottomStart).padding(8.dp))
        if (active != null && compact) {
            MapLabel("${active.distanceFrom(player)} b", Modifier.align(Alignment.BottomStart).padding(5.dp))
        }
        if (!compact && expanded) {
            // Enlarged: the map takes the whole panel; small controls float over it.
            Row(Modifier.align(Alignment.TopEnd).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MapBlock("−", "Alejar") { view = camera.zoom(1.25f) }
                MapBlock("+", "Acercar") { view = camera.zoom(.8f) }
                MapBlock("⤡", "Reducir mapa") { expanded = false }
            }
        }
    }
    if (sidePanel) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MapBlock("−", "Alejar", Modifier.weight(1f)) { view = camera.zoom(1.25f) }
                MapBlock("+", "Acercar", Modifier.weight(1f)) { view = camera.zoom(.8f) }
                MapBlock("Centrar", "Centrar en ti", Modifier.weight(2f)) { view = camera.copy(follow = true) }
            }
            MapBlock("⤢ Ampliar mapa", "Ampliar el mapa a todo el panel", Modifier.fillMaxWidth()) { expanded = true }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Marcas · ${waypoints.points.size}/$WAYPOINT_LIMIT", Modifier.weight(1f), fontWeight = FontWeight.ExtraBold,
                    style = MaterialTheme.typography.titleSmall)
                BlockButton({ openWaypoints(true) }, colors = BlockButtons.primary,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                    Text("+ Nueva", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge)
                }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (waypoints.points.isEmpty()) item {
                    Text("Sin marcas todavía. Crea una donde estás para volver después.", color = Prissma.colors.textMuted,
                        style = MaterialTheme.typography.bodySmall)
                }
                items(waypoints.points, key = { it.id }) { point ->
                    val on = point.id == waypoints.activeId
                    Row(Modifier.fillMaxWidth().background(if (on) Color(0xFF1E252C) else Color(0xFF161B21), RoundedCornerShape(5.dp))
                        .border(2.dp, if (on) Prissma.colors.gold else Color(0xFF232B33), RoundedCornerShape(5.dp))
                        .clickable { view = camera.copy(x = point.x.toDouble(), z = point.z.toDouble(), follow = false) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Canvas(Modifier.size(22.dp)) { drawWaypoint(point, center, 5.dp.toPx(), on) }
                        Column(Modifier.weight(1f)) {
                            Text(point.name, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelLarge)
                            Text("X ${point.x} · Z ${point.z} · ${point.distanceFrom(player)} b", color = Prissma.colors.textMuted,
                                style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                        BlockButton({ saveBook(waypoints.follow(if (on) null else point.id)) },
                            colors = if (on) BlockButtons.gold else BlockButtons.neutral,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                            Text(if (on) "Dejar" else "Seguir", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            if (waypoints.points.isNotEmpty()) MapBlock("Editar marcas", "Editar o borrar marcas", Modifier.fillMaxWidth()) {
                openWaypoints(false)
            }
        }
    }
    }
}

@Composable
private fun MapBlock(label: String, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    BlockButton(onClick, modifier.semantics { contentDescription = description }, BlockButtons.neutral,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
        Text(label, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge, maxLines = 1,
            modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun MapLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.background(Color(0xe51c2932), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 4.dp),
        color = Color(0xffe2f1e8), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
