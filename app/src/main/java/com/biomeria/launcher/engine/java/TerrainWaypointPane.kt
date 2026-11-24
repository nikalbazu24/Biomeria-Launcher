package com.biomeria.launcher.engine.java

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private enum class WaypointField { NAME, COLOR, ICON, SAVE, DELETE }
private const val SHIFT_NAME_KEY = SEARCH_DONE + 1
private const val FIRST_EXTRA_KEY = SHIFT_NAME_KEY + 1
private const val EXTRA_NAME_CHARACTERS = "áéíóúü-_"
private val waypointNameRows = inventorySearchRows.dropLast(1) + listOf(
    EXTRA_NAME_CHARACTERS.indices.map { PanelPadSlot(FIRST_EXTRA_KEY + it) },
    listOf(SHIFT_NAME_KEY, SEARCH_SPACE, SEARCH_ERASE, SEARCH_DONE).map { PanelPadSlot(it) }
)

private fun waypointNameCharacter(key: Int): Char? =
    if (key < FIRST_EXTRA_KEY) inventorySearchCharacters.getOrNull(key)
    else EXTRA_NAME_CHARACTERS.getOrNull(key - FIRST_EXTRA_KEY)

@Composable
internal fun TerrainWaypointPane(
    book: TerrainWaypointBook,
    player: TerrainPosition,
    newPoint: TerrainWaypoint,
    createImmediately: Boolean,
    controllerActive: Boolean,
    onBookChange: (TerrainWaypointBook) -> Unit,
    follow: (TerrainWaypoint) -> Unit,
    onClose: () -> Unit,
    onPadInput: (((PanelPadAction) -> Unit)?) -> Unit
) {
    val existingPoint = book.points.firstOrNull { it.id == newPoint.id }
    val canCreate = existingPoint != null || book.points.size < WAYPOINT_LIMIT
    val canStartCreating = createImmediately && canCreate
    var selectedId by remember { mutableStateOf(book.activeId) }
    val selected = book.points.firstOrNull { it.id == selectedId } ?: book.points.firstOrNull()
    var draft by remember { mutableStateOf(if (canStartCreating) existingPoint ?: newPoint else null) }
    var field by remember { mutableStateOf(WaypointField.NAME) }
    var nameKey by remember { mutableStateOf<PanelPadSlot?>(null) }
    var uppercase by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    val point = draft
    val editingExisting = point != null && book.points.any { it.id == point.id }
    val fields = if (editingExisting) WaypointField.entries else WaypointField.entries.dropLast(1)

    fun edit(next: TerrainWaypoint) {
        draft = next
        field = WaypointField.NAME
    }

    fun cycleChoice(current: TerrainWaypoint, delta: Int) {
        draft = when (field) {
            WaypointField.COLOR -> current.copy(color = WaypointColor.entries[
                (current.color.ordinal + delta + WaypointColor.entries.size) % WaypointColor.entries.size
            ])
            WaypointField.ICON -> current.copy(icon = WaypointIcon.entries[
                (current.icon.ordinal + delta + WaypointIcon.entries.size) % WaypointIcon.entries.size
            ])
            else -> current
        }
    }

    fun save(current: TerrainWaypoint) {
        if (current.name.isBlank()) return
        onBookChange(book.save(current))
        selectedId = current.id
        draft = null
    }

    fun delete(current: TerrainWaypoint) {
        onBookChange(book.remove(current.id))
        confirmingDelete = false
        draft = null
    }

    fun editName(current: TerrainWaypoint) {
        uppercase = current.name.isEmpty()
        nameKey = PanelPadSlot(0)
    }

    fun clearName(current: TerrainWaypoint) {
        draft = current.copy(name = "")
        uppercase = true
    }

    fun applyNameKey(current: TerrainWaypoint, key: Int) {
        val name = when (key) {
            SEARCH_DONE -> {
                nameKey = null
                return
            }
            SEARCH_SPACE -> current.name + " "
            SEARCH_ERASE -> current.name.dropLast(1)
            SHIFT_NAME_KEY -> {
                uppercase = !uppercase
                return
            }
            else -> {
                val character = waypointNameCharacter(key) ?: return
                val next = current.name + if (uppercase) character.uppercaseChar() else character
                uppercase = false
                next
            }
        }
        draft = current.copy(name = name.take(WAYPOINT_NAME_LIMIT))
    }

    val padAction by rememberUpdatedState<(PanelPadAction) -> Unit>({ action ->
        val current = draft
        val key = nameKey
        when {
            current != null && confirmingDelete -> when (action) {
                PanelPadAction.PICK -> delete(current)
                PanelPadAction.BACK -> confirmingDelete = false
                else -> Unit
            }
            current != null && key != null -> when {
                action == PanelPadAction.BACK -> nameKey = null
                action == PanelPadAction.PICK -> applyNameKey(current, key.number)
                action == PanelPadAction.SPLIT -> applyNameKey(current, SEARCH_ERASE)
                action == PanelPadAction.TRANSFER -> clearName(current)
                action.directional -> nameKey = movePanelPadSlot(waypointNameRows, key, action)
                else -> Unit
            }
            current != null -> when (action) {
                PanelPadAction.BACK -> draft = null
                PanelPadAction.UP -> field = fields[(field.ordinal - 1).coerceAtLeast(0)]
                PanelPadAction.DOWN -> field = fields[(field.ordinal + 1).coerceAtMost(fields.lastIndex)]
                PanelPadAction.LEFT -> cycleChoice(current, -1)
                PanelPadAction.RIGHT -> cycleChoice(current, 1)
                PanelPadAction.PICK -> when (field) {
                    WaypointField.NAME -> editName(current)
                    WaypointField.COLOR, WaypointField.ICON -> cycleChoice(current, 1)
                    WaypointField.SAVE -> save(current)
                    WaypointField.DELETE -> confirmingDelete = true
                }
                else -> Unit
            }
            else -> when (action) {
                PanelPadAction.BACK, PanelPadAction.SEARCH -> onClose()
                PanelPadAction.UP, PanelPadAction.DOWN -> if (selected != null) {
                    val delta = if (action == PanelPadAction.UP) -1 else 1
                    val index = (book.points.indexOf(selected) + delta).coerceIn(0, book.points.lastIndex)
                    selectedId = book.points[index].id
                }
                PanelPadAction.PICK -> selected?.let(follow)
                PanelPadAction.SPLIT -> selected?.let(::edit)
                PanelPadAction.TRANSFER -> onBookChange(book.follow(null))
                PanelPadAction.MARK -> if (canCreate) edit(existingPoint ?: newPoint)
                else -> Unit
            }
        }
    })
    val register by rememberUpdatedState(onPadInput)
    DisposableEffect(Unit) {
        register { padAction(it) }
        onDispose { register(null) }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val title = when {
                confirmingDelete -> "Borrar marca"
                nameKey != null -> "Nombre de la marca"
                point == null -> "Marcas · ${book.points.size}/$WAYPOINT_LIMIT"
                editingExisting -> "Editar marca"
                else -> "Nueva marca"
            }
            Text(title, Modifier.weight(1f), style = PanelText.section)
            TextButton(onClick = { padAction(PanelPadAction.BACK) }) { Text(if (point == null) "Volver al mapa" else "Atrás") }
        }
        val hint = when {
            !controllerActive -> null
            confirmingDelete -> "A · Borrar   B · Cancelar"
            nameKey != null -> "Cruceta · Teclado   A · Escribir   X · Borrar   Y · Limpiar   B · Aceptar"
            point != null -> "↑/↓ · Campo   ←/→ · Cambiar   A · Elegir   B · Volver"
            else -> "↑/↓ · Marca   A · Seguir   X · Editar   Y · Dejar de seguir   R3 · Nueva"
        }
        hint?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = PanelText.detail) }

        when {
            point != null && confirmingDelete -> {
                Text("¿Borrar «${point.name}» de este mapa?", style = PanelText.body)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { confirmingDelete = false }) { Text("Cancelar") }
                    Button(onClick = { delete(point) }) { Text("Borrar marca") }
                }
            }
            point != null && nameKey != null -> InventorySearchKeyboard(
                point.name, nameKey.takeIf { controllerActive }, { applyNameKey(point, it) },
                { clearName(point) }, Modifier.weight(1f),
                placeholder = "Escribe un nombre", doneLabel = "Aceptar",
                rows = waypointNameRows, keyLabel = { key ->
                    if (key == SHIFT_NAME_KEY) "Mayús"
                    else waypointNameCharacter(key)?.let { if (uppercase) it.uppercase() else it.toString() }
                }
            )
            point != null -> {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("X ${point.x} · Y ${point.y} · Z ${point.z}", style = PanelText.detail)
                    WaypointFieldButton("Nombre · ${point.name.ifBlank { "Escribe un nombre" }}",
                        controllerActive && field == WaypointField.NAME, onClick = { editName(point) })
                    WaypointFieldButton("Color · ${point.color.label}", controllerActive && field == WaypointField.COLOR,
                        point, onClick = { field = WaypointField.COLOR; cycleChoice(point, 1) })
                    WaypointFieldButton("Icono · ${point.icon.label}", controllerActive && field == WaypointField.ICON,
                        point, onClick = { field = WaypointField.ICON; cycleChoice(point, 1) })
                    WaypointFieldButton("Guardar marca", controllerActive && field == WaypointField.SAVE,
                        enabled = point.name.isNotBlank(), onClick = { save(point) })
                    if (editingExisting) WaypointFieldButton("Borrar marca", controllerActive && field == WaypointField.DELETE,
                        onClick = { confirmingDelete = true })
                }
            }
            else -> {
                val listState = rememberLazyListState()
                LaunchedEffect(controllerActive, selected?.id) {
                    if (!controllerActive || selected == null) return@LaunchedEffect
                    val index = book.points.indexOf(selected)
                    if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) listState.scrollToItem(index)
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (book.points.isEmpty()) item { Text("Aún no tienes marcas en este mundo y dimensión.") }
                    items(book.points, key = { it.id }) { item ->
                        WaypointFieldButton(
                            item.name, highlighted = item.id == selected?.id, point = item,
                            detail = "${item.distanceFrom(player)} b${if (item.id == book.activeId) " · Activa" else ""}",
                            onClick = { selectedId = item.id }
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = { selected?.let(follow) }, enabled = selected != null,
                        modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 5.dp)) { Text("Seguir") }
                    FilledTonalButton(onClick = { selected?.let(::edit) }, enabled = selected != null,
                        modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 5.dp)) { Text("Editar") }
                    FilledTonalButton(onClick = { edit(existingPoint ?: newPoint) }, enabled = canCreate,
                        modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 5.dp)) { Text("Nueva") }
                    if (book.active != null) OutlinedButton(onClick = { onBookChange(book.follow(null)) },
                        modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 5.dp)) { Text("Dejar de seguir") }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun WaypointFieldButton(
    label: String, highlighted: Boolean, point: TerrainWaypoint? = null,
    enabled: Boolean = true, detail: String? = null, onClick: () -> Unit
) {
    val reveal = remember { BringIntoViewRequester() }
    LaunchedEffect(highlighted) { if (highlighted) reveal.bringIntoView() }
    FilledTonalButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().bringIntoViewRequester(reveal),
        border = if (highlighted) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        contentPadding = PaddingValues(horizontal = 10.dp)) {
        if (point != null) Canvas(Modifier.size(24.dp)) { drawWaypoint(point, center, 5.dp.toPx(), false) }
        Text(label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (detail != null) Text(detail, Modifier.padding(start = 5.dp), style = PanelText.detail)
    }
}

internal fun DrawScope.drawWaypoint(point: TerrainWaypoint, anchor: Offset, radius: Float, active: Boolean) {
    drawCircle(Color(0xff101c23), radius * 1.45f, anchor)
    if (active) drawCircle(Color.White, radius * 1.65f, anchor, style = Stroke(1.5.dp.toPx()))
    val color = Color(point.color.argb)
    when (point.icon) {
        WaypointIcon.CIRCLE -> drawCircle(color, radius, anchor)
        WaypointIcon.SQUARE -> drawRect(color, Offset(anchor.x - radius, anchor.y - radius), Size(radius * 2, radius * 2))
        WaypointIcon.DIAMOND -> drawPath(Path().apply {
            moveTo(anchor.x, anchor.y - radius)
            lineTo(anchor.x + radius, anchor.y)
            lineTo(anchor.x, anchor.y + radius)
            lineTo(anchor.x - radius, anchor.y)
            close()
        }, color)
        WaypointIcon.TRIANGLE -> drawPath(Path().apply {
            moveTo(anchor.x, anchor.y - radius)
            lineTo(anchor.x + radius, anchor.y + radius)
            lineTo(anchor.x - radius, anchor.y + radius)
            close()
        }, color)
    }
}
