package com.prissma.launcher.ui.hub

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prissma.launcher.ui.theme.Prissma
import java.util.Locale
import java.util.UUID

enum class EngineType(val displayName: String, val defaultLoader: String) {
    JAVA("JAVA", "Fabric 0.16.9"),
    RUST("RUST", "Starling Native"),
    BEDROCK("BEDROCK", "MCPE 26.x")
}

enum class EngineFilter(val label: String, val type: EngineType?) {
    ALL("Todos", null),
    JAVA("Java", EngineType.JAVA),
    RUST("Rust", EngineType.RUST),
    BEDROCK("Bedrock", EngineType.BEDROCK)
}

data class GameInstance(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val engine: EngineType,
    val version: String,
    val loader: String,
    val playtimeHours: Float = 0f,
    val friendsOnline: Int = 0
)

@Composable
fun HubScreen() {
    var playerName by remember { mutableStateOf("Jugador_Local") }
    var accountType by remember { mutableStateOf("Offline") }
    var selectedFilter by remember { mutableStateOf(EngineFilter.ALL) }
    var focusedIndex by remember { mutableIntStateOf(0) }
    var bannerMessage by remember { mutableStateOf<String?>(null) }

    var showNewInstanceDialog by remember { mutableStateOf(false) }
    var showAccountDialog by remember { mutableStateOf(false) }

    var instances by remember {
        mutableStateOf(
            listOf(
                GameInstance(
                    title = "Minecraft Rust 120FPS",
                    engine = EngineType.RUST,
                    version = "26.3",
                    loader = "Starling wgpu",
                    playtimeHours = 18.5f,
                    friendsOnline = 2
                ),
                GameInstance(
                    title = "Survival Con Mods",
                    engine = EngineType.JAVA,
                    version = "26.3",
                    loader = "Fabric 0.16.9",
                    playtimeHours = 42.0f,
                    friendsOnline = 1
                ),
                GameInstance(
                    title = "Crossplay Amigos",
                    engine = EngineType.BEDROCK,
                    version = "26.10",
                    loader = "MCPE Oficial",
                    playtimeHours = 11.2f,
                    friendsOnline = 3
                )
            )
        )
    }

    val visibleInstances = remember(instances, selectedFilter) {
        val target = selectedFilter.type
        if (target == null) instances else instances.filter { it.engine == target }
    }

    LaunchedEffect(selectedFilter) {
        focusedIndex = 0
    }

    val listState = rememberLazyListState()
    val focusRequesters = remember(visibleInstances.size) {
        List(visibleInstances.size) { FocusRequester() }
    }

    val focusedInstance = visibleInstances.getOrNull(focusedIndex)
    val activeAccent = focusedInstance?.let {
        Prissma.colors.engineColor(it.engine.name)
    } ?: Prissma.colors.brandAccent

    LaunchedEffect(focusedIndex, visibleInstances.size) {
        if (visibleInstances.isNotEmpty() && focusedIndex in visibleInstances.indices) {
            listState.animateScrollToItem(index = focusedIndex, scrollOffset = -100)
            runCatching { focusRequesters[focusedIndex].requestFocus() }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Prissma.colors.surfaceVoid)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        if (focusedIndex > 0) {
                            focusedIndex -= 1
                            true
                        } else false
                    }
                    Key.DirectionRight -> {
                        if (focusedIndex < visibleInstances.lastIndex) {
                            focusedIndex += 1
                            true
                        } else false
                    }
                    Key.ButtonL1 -> {
                        val list = EngineFilter.entries
                        val prev = (list.indexOf(selectedFilter) - 1 + list.size) % list.size
                        selectedFilter = list[prev]
                        true
                    }
                    Key.ButtonR1 -> {
                        val list = EngineFilter.entries
                        val next = (list.indexOf(selectedFilter) + 1) % list.size
                        selectedFilter = list[next]
                        true
                    }
                    Key.ButtonA, Key.DirectionCenter, Key.Enter -> {
                        focusedInstance?.let {
                            bannerMessage = "Iniciando ${it.title} con motor ${it.engine.displayName}..."
                        }
                        true
                    }
                    Key.ButtonX -> {
                        focusedInstance?.let {
                            bannerMessage = "Abriendo taller de mods y shaders para ${it.title}"
                        }
                        true
                    }
                    Key.ButtonY -> {
                        focusedInstance?.let {
                            bannerMessage = "Abriendo sala P2P y mundos de ${it.title}"
                        }
                        true
                    }
                    else -> false
                }
            }
    ) {
        // Degradado ambiental sutil según el motor seleccionado
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Prissma.colors.surfaceVoid,
                        0.5f to activeAccent.copy(alpha = 0.10f),
                        1.0f to Prissma.colors.surfaceVoid
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Barra Superior
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Perfil Omni-Account
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Prissma.colors.surfaceCard)
                        .border(1.dp, Prissma.colors.borderSubtle, CircleShape)
                        .clickable { showAccountDialog = true }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(Prissma.colors.brandAccent, CircleShape)
                    )
                    Text(
                        text = playerName,
                        color = Prissma.colors.textPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = accountType,
                        color = Prissma.colors.netP2P,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Filtros por Motor
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Prissma.colors.surfaceCard)
                        .border(1.dp, Prissma.colors.borderSubtle, CircleShape)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    EngineFilter.entries.forEach { filter ->
                        val isSelected = filter == selectedFilter
                        val color = filter.type?.let { Prissma.colors.engineColor(it.name) }
                            ?: Prissma.colors.brandAccent
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) Prissma.colors.surfaceElevated else Color.Transparent
                                )
                                .clickable { selectedFilter = filter }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = filter.label,
                                color = if (isSelected) color else Prissma.colors.textMuted,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // Botón Crear Nueva Instancia
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Prissma.colors.brandAccent)
                        .clickable { showNewInstanceDialog = true }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "+ Nueva",
                        color = Prissma.colors.surfaceVoid,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Aviso interactivo superior si el usuario pulsó alguna acción
            bannerMessage?.let { msg ->
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Prissma.colors.surfaceElevated)
                        .border(1.dp, activeAccent, RoundedCornerShape(10.dp))
                        .clickable { bannerMessage = null }
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = msg,
                        color = Prissma.colors.textPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Carrusel Horizontal de Instancias
            if (visibleInstances.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No hay instancias en esta categoría. Toca '+ Nueva' para crear una.",
                        color = Prissma.colors.textMuted,
                        fontSize = 15.sp
                    )
                }
            } else {
                LazyRow(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = 44.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    itemsIndexed(visibleInstances, key = { _, item -> item.id }) { index, item ->
                        val isFocused = index == focusedIndex
                        InstanceCard(
                            instance = item,
                            isFocused = isFocused,
                            focusRequester = focusRequesters[index],
                            onFocus = { focusedIndex = index },
                            onClick = {
                                if (isFocused) {
                                    bannerMessage = "Iniciando ${item.title} (${item.engine.displayName})..."
                                } else {
                                    focusedIndex = index
                                }
                            }
                        )
                    }
                }
            }

            // Guía inferior de controles (táctil y mando)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ActionPill(
                        glyph = "A",
                        label = "Jugar",
                        color = Prissma.colors.brandAccent,
                        onClick = {
                            focusedInstance?.let {
                                bannerMessage = "Iniciando ${it.title} (${it.engine.displayName})..."
                            }
                        }
                    )
                    ActionPill(
                        glyph = "Y",
                        label = "Sala P2P",
                        color = Prissma.colors.netP2P,
                        onClick = {
                            focusedInstance?.let {
                                bannerMessage = "Sala P2P lista para ${it.title}"
                            }
                        }
                    )
                    ActionPill(
                        glyph = "X",
                        label = "Mods",
                        color = activeAccent,
                        onClick = {
                            focusedInstance?.let {
                                bannerMessage = "Abriendo gestor de mods de ${it.title}"
                            }
                        }
                    )
                }

                Text(
                    text = "PRISSMA v0.2 · L1/R1 Filtrar",
                    color = Prissma.colors.textMuted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Ventana para crear nueva instancia
        if (showNewInstanceDialog) {
            NewInstanceDialog(
                onDismiss = { showNewInstanceDialog = false },
                onCreate = { title, engine, version ->
                    val newItem = GameInstance(
                        title = title,
                        engine = engine,
                        version = version,
                        loader = engine.defaultLoader
                    )
                    instances = listOf(newItem) + instances
                    selectedFilter = EngineFilter.ALL
                    focusedIndex = 0
                    showNewInstanceDialog = false
                    bannerMessage = "Instancia '$title' creada en motor ${engine.displayName}"
                }
            )
        }

        // Ventana para cambiar perfil Omni-Account
        if (showAccountDialog) {
            AccountDialog(
                currentName = playerName,
                currentType = accountType,
                onDismiss = { showAccountDialog = false },
                onSave = { newName, newType ->
                    playerName = newName
                    accountType = newType
                    showAccountDialog = false
                }
            )
        }
    }
}

@Composable
private fun InstanceCard(
    instance: GameInstance,
    isFocused: Boolean,
    focusRequester: FocusRequester,
    onFocus: () -> Unit,
    onClick: () -> Unit
) {
    val engineColor = Prissma.colors.engineColor(instance.engine.name)
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 0.95f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "scale"
    )
    val borderWidth by animateDpAsState(
        targetValue = if (isFocused) 2.5.dp else 1.dp,
        animationSpec = tween(150),
        label = "border"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isFocused) engineColor else Prissma.colors.borderSubtle,
        animationSpec = tween(150),
        label = "color"
    )

    Column(
        modifier = Modifier
            .width(290.dp)
            .height(200.dp)
            .scale(scale)
            .clip(RoundedCornerShape(16.dp))
            .background(Prissma.colors.surfaceCard)
            .border(borderWidth, borderColor, RoundedCornerShape(16.dp))
            .focusRequester(focusRequester)
            .onFocusChanged { if (it.isFocused) onFocus() }
            .focusable()
            .clickable(onClick = onClick)
    ) {
        // Cabecera visual de la tarjeta
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(126.dp)
                .background(
                    Brush.linearGradient(
                        listOf(
                            engineColor.copy(alpha = 0.28f),
                            Prissma.colors.surfaceElevated
                        )
                    )
                )
                .padding(12.dp)
        ) {
            Text(
                text = instance.engine.displayName,
                color = engineColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .background(Prissma.colors.surfaceGlass, RoundedCornerShape(6.dp))
                    .border(1.dp, engineColor, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )

            if (instance.friendsOnline > 0) {
                Text(
                    text = "${instance.friendsOnline} amigos en línea",
                    color = Prissma.colors.brandAccent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .background(Prissma.colors.surfaceGlass, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }

        // Pie de tarjeta
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = instance.title,
                color = Prissma.colors.textPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${instance.loader} · ${instance.version}",
                    color = if (isFocused) engineColor else Prissma.colors.textMuted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = String.format(Locale.US, "%.1f h", instance.playtimeHours),
                    color = Prissma.colors.textMuted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun ActionPill(
    glyph: String,
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = glyph,
            color = Prissma.colors.surfaceVoid,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(color, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp)
        )
        Text(
            text = label,
            color = Prissma.colors.textMuted,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun NewInstanceDialog(
    onDismiss: () -> Unit,
    onCreate: (String, EngineType, String) -> Unit
) {
    var title by remember { mutableStateOf("Mi Nuevo Mundo") }
    var selectedEngine by remember { mutableStateOf(EngineType.RUST) }
    var version by remember { mutableStateOf("26.3") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Prissma.colors.surfaceCard,
        title = { Text("Nueva Instancia", color = Prissma.colors.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Nombre de la instancia") },
                    singleLine = true
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EngineType.entries.forEach { engine ->
                        val active = engine == selectedEngine
                        val color = Prissma.colors.engineColor(engine.name)
                        FilterChip(
                            selected = active,
                            onClick = { selectedEngine = engine },
                            label = { Text(engine.displayName, color = if (active) color else Prissma.colors.textMuted) }
                        )
                    }
                }

                OutlinedTextField(
                    value = version,
                    onValueChange = { version = it },
                    label = { Text("Versión (ej. 26.3)") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (title.isNotBlank()) onCreate(title.trim(), selectedEngine, version.trim()) },
                colors = ButtonDefaults.buttonColors(containerColor = Prissma.colors.brandAccent)
            ) {
                Text("Crear", color = Prissma.colors.surfaceVoid, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar", color = Prissma.colors.textMuted)
            }
        }
    )
}

@Composable
private fun AccountDialog(
    currentName: String,
    currentType: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var name by remember { mutableStateOf(currentName) }
    var type by remember { mutableStateOf(currentType) }
    val types = listOf("Offline", "Ely.by", "Microsoft")

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Prissma.colors.surfaceCard,
        title = { Text("Perfil Omni-Account", color = Prissma.colors.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre de jugador") },
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    types.forEach { item ->
                        FilterChip(
                            selected = type == item,
                            onClick = { type = item },
                            label = { Text(item) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank()) onSave(name.trim(), type) },
                colors = ButtonDefaults.buttonColors(containerColor = Prissma.colors.brandAccent)
            ) {
                Text("Guardar", color = Prissma.colors.surfaceVoid, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cerrar", color = Prissma.colors.textMuted)
            }
        }
    )
}
