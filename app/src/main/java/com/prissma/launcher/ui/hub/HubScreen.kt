package com.prissma.launcher.ui.hub

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
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prissma.launcher.data.InstanceStorage
import com.prissma.launcher.ui.launch.JavaLaunchScreen
import com.prissma.launcher.ui.theme.Prissma
import com.prissma.launcher.ui.workshop.ContentWorkshopScreen
import java.util.Locale
import java.util.UUID

private val PrismGradientColors = listOf(
    Color(0xFF00F0FF), // Cian
    Color(0xFF8A2BE2), // Púrpura
    Color(0xFFFF007F), // Magenta
    Color(0xFFFF8C00)  // Naranja
)

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
fun HubScreen(
    externalLaunchInstanceId: String? = null,
    sourceFrontendPackage: String? = null,
    onConsumeExternalLaunch: () -> Unit = {}
) {
    val context = LocalContext.current
    val storage = remember { InstanceStorage(context) }

    val initialProfile = remember { storage.loadProfile() }
    var playerName by remember { mutableStateOf(initialProfile.playerName) }
    var accountType by remember { mutableStateOf(initialProfile.accountType) }

    var selectedFilter by remember { mutableStateOf(EngineFilter.ALL) }
    var searchQuery by remember { mutableStateOf("") }
    var focusedIndex by remember { mutableIntStateOf(0) }
    var bannerMessage by remember { mutableStateOf<String?>(null) }

    var showNewInstanceDialog by remember { mutableStateOf(false) }
    var showAccountDialog by remember { mutableStateOf(false) }
    var showFrontendBridgeDialog by remember { mutableStateOf(false) }
    var workshopTargetInstance by remember { mutableStateOf<GameInstance?>(null) }
    var launchingJavaInstance by remember { mutableStateOf<GameInstance?>(null) }

    var instances by remember { mutableStateOf(storage.loadInstances()) }

    val prismBrush = remember { Brush.linearGradient(PrismGradientColors) }

    fun triggerLaunch(target: GameInstance) {
        if (target.engine == EngineType.JAVA) {
            launchingJavaInstance = target
        } else {
            bannerMessage = "Motor ${target.engine.displayName} seleccionado. Usa una instancia JAVA para iniciar Minecraft oficial."
        }
    }

    LaunchedEffect(externalLaunchInstanceId) {
        val targetId = externalLaunchInstanceId ?: return@LaunchedEffect
        selectedFilter = EngineFilter.ALL
        searchQuery = ""
        val foundIndex = instances.indexOfFirst { it.id == targetId }
        if (foundIndex >= 0) {
            focusedIndex = foundIndex
            triggerLaunch(instances[foundIndex])
        } else {
            bannerMessage = "Instancia solicitada no encontrada ($targetId)"
        }
        onConsumeExternalLaunch()
    }

    launchingJavaInstance?.let { target ->
        JavaLaunchScreen(
            instance = target,
            playerName = playerName,
            accountType = accountType,
            onBack = { launchingJavaInstance = null }
        )
        return
    }

    workshopTargetInstance?.let { target ->
        ContentWorkshopScreen(
            instance = target,
            onBack = { workshopTargetInstance = null }
        )
        return
    }

    val visibleInstances = remember(instances, selectedFilter, searchQuery) {
        val target = selectedFilter.type
        val q = searchQuery.trim()
        instances.filter { inst ->
            val matchesEngine = target == null || inst.engine == target
            val matchesQuery = q.isEmpty() ||
                inst.title.contains(q, ignoreCase = true) ||
                inst.version.contains(q, ignoreCase = true) ||
                inst.loader.contains(q, ignoreCase = true)
            matchesEngine && matchesQuery
        }
    }

    LaunchedEffect(selectedFilter, searchQuery) {
        if (focusedIndex > visibleInstances.lastIndex) {
            focusedIndex = 0
        }
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
                        focusedIndex = 0
                        true
                    }
                    Key.ButtonR1 -> {
                        val list = EngineFilter.entries
                        val next = (list.indexOf(selectedFilter) + 1) % list.size
                        selectedFilter = list[next]
                        focusedIndex = 0
                        true
                    }
                    Key.ButtonA, Key.DirectionCenter, Key.Enter -> {
                        focusedInstance?.let { triggerLaunch(it) }
                        true
                    }
                    Key.ButtonX -> {
                        focusedInstance?.let { workshopTargetInstance = it }
                        true
                    }
                    Key.ButtonY -> {
                        focusedInstance?.let {
                            bannerMessage = "Sala P2P lista para ${it.title} (${it.engine.displayName})"
                        }
                        true
                    }
                    Key.ButtonStart -> {
                        showFrontendBridgeDialog = true
                        true
                    }
                    else -> false
                }
            }
    ) {
        // Resplandor ambiental prismático sutil
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Prissma.colors.surfaceVoid,
                        0.5f to activeAccent.copy(alpha = 0.11f),
                        1.0f to Prissma.colors.surfaceVoid
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Barra Superior: Perfil + Filtros + Buscador PRISSMA + Botón Nueva
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Prissma.colors.surfaceCard)
                        .border(1.dp, prismBrush, CircleShape)
                        .clickable { showAccountDialog = true }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .background(prismBrush, CircleShape)
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

                // Filtros por motor [L1 / R1]
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
                            ?: Color(0xFF00F0FF)
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) Prissma.colors.surfaceElevated else Color.Transparent
                                )
                                .clickable {
                                    selectedFilter = filter
                                    focusedIndex = 0
                                }
                                .padding(horizontal = 12.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = filter.label,
                                color = if (isSelected) color else Prissma.colors.textMuted,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // Barra de búsqueda retro-moderna: PRISSMA > Buscar...
                Row(
                    modifier = Modifier
                        .width(210.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Prissma.colors.surfaceCard)
                        .border(1.dp, Prissma.colors.borderSubtle, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "PRISSMA >",
                        color = Color(0xFF00F0FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "Buscar...",
                                color = Prissma.colors.textMuted,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = Prissma.colors.textPrimary,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            ),
                            cursorBrush = SolidColor(Color(0xFF00F0FF)),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(prismBrush)
                        .clickable { showNewInstanceDialog = true }
                        .padding(horizontal = 15.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "+ Nueva",
                        color = Prissma.colors.surfaceVoid,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            bannerMessage?.let { msg ->
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Prissma.colors.surfaceElevated)
                        .border(1.dp, prismBrush, RoundedCornerShape(10.dp))
                        .clickable { bannerMessage = null }
                        .padding(horizontal = 18.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = msg,
                        color = Prissma.colors.textPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            if (visibleInstances.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Sin resultados. Borra el filtro o toca '+ Nueva' para crear una instancia.",
                        color = Prissma.colors.textMuted,
                        fontSize = 14.sp
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
                            prismBrush = prismBrush,
                            focusRequester = focusRequesters[index],
                            onFocus = { focusedIndex = index },
                            onClick = {
                                if (isFocused) {
                                    triggerLaunch(item)
                                } else {
                                    focusedIndex = index
                                }
                            }
                        )
                    }
                }
            }

            // Barra inferior de controles + sello Beta oficial
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ActionPill(
                        glyph = "A",
                        label = "Jugar",
                        color = Color(0xFF00F0FF),
                        onClick = { focusedInstance?.let { triggerLaunch(it) } }
                    )
                    ActionPill(
                        glyph = "Y",
                        label = "Sala P2P",
                        color = Prissma.colors.netP2P,
                        onClick = {
                            focusedInstance?.let {
                                bannerMessage = "Sala P2P lista para ${it.title} (${it.engine.displayName})"
                            }
                        }
                    )
                    ActionPill(
                        glyph = "X",
                        label = "Mods y Shaders",
                        color = activeAccent,
                        onClick = {
                            focusedInstance?.let { workshopTargetInstance = it }
                        }
                    )
                    ActionPill(
                        glyph = "START",
                        label = "Frontends",
                        color = Prissma.colors.textPrimary,
                        onClick = { showFrontendBridgeDialog = true }
                    )
                }

                Text(
                    text = "PRISSMA v1.0.0-beta.1 · ARM64",
                    color = Prissma.colors.textMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

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
                    val updated = listOf(newItem) + instances
                    instances = updated
                    storage.saveInstances(updated)
                    selectedFilter = EngineFilter.ALL
                    searchQuery = ""
                    focusedIndex = 0
                    showNewInstanceDialog = false
                    bannerMessage = "Instancia '$title' guardada y exportada (.mcinst)"
                }
            )
        }

        if (showAccountDialog) {
            AccountDialog(
                currentName = playerName,
                currentType = accountType,
                onDismiss = { showAccountDialog = false },
                onSave = { newName, newType ->
                    playerName = newName
                    accountType = newType
                    storage.saveProfile(newName, newType)
                    showAccountDialog = false
                }
            )
        }

        if (showFrontendBridgeDialog) {
            FrontendBridgeDialog(
                exportFolderPath = storage.exportInstancesDir.absolutePath,
                selectedInstance = focusedInstance,
                canDeleteSelected = focusedInstance != null && instances.size > 1,
                onDeleteSelected = {
                    val target = focusedInstance ?: return@FrontendBridgeDialog
                    val updated = instances.filterNot { it.id == target.id }
                    instances = updated
                    storage.saveInstances(updated)
                    focusedIndex = 0
                    showFrontendBridgeDialog = false
                    bannerMessage = "Instancia '${target.title}' eliminada"
                },
                onDismiss = { showFrontendBridgeDialog = false }
            )
        }
    }
}

@Composable
private fun InstanceCard(
    instance: GameInstance,
    isFocused: Boolean,
    prismBrush: Brush,
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

    val borderModifier = if (isFocused) {
        Modifier.border(borderWidth, prismBrush, RoundedCornerShape(16.dp))
    } else {
        Modifier.border(borderWidth, Prissma.colors.borderSubtle, RoundedCornerShape(16.dp))
    }

    Column(
        modifier = Modifier
            .width(290.dp)
            .height(200.dp)
            .scale(scale)
            .clip(RoundedCornerShape(16.dp))
            .background(Prissma.colors.surfaceCard)
            .then(borderModifier)
            .focusRequester(focusRequester)
            .onFocusChanged { if (it.isFocused) onFocus() }
            .focusable()
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(126.dp)
                .background(
                    Brush.linearGradient(
                        listOf(
                            engineColor.copy(alpha = if (isFocused) 0.36f else 0.22f),
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
private fun FrontendBridgeDialog(
    exportFolderPath: String,
    selectedInstance: GameInstance?,
    canDeleteSelected: Boolean,
    onDeleteSelected: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Prissma.colors.surfaceCard,
        title = {
            Text(
                text = "Integración Cocoon, iiSU y Díptico",
                color = Prissma.colors.textPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Carpeta de cartuchos .mcinst (para Cocoon):",
                    color = Prissma.colors.textMuted,
                    fontSize = 12.sp
                )
                Text(
                    text = exportFolderPath,
                    color = Prissma.colors.brandAccent,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                selectedInstance?.let { inst ->
                    Text(
                        text = "Enlace directo de '${inst.title}':",
                        color = Prissma.colors.textMuted,
                        fontSize = 12.sp
                    )
                    Text(
                        text = "prissma://launch?instance_id=${inst.id}",
                        color = Prissma.colors.netP2P,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Prissma.colors.brandAccent)
            ) {
                Text("Listo", color = Prissma.colors.surfaceVoid, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            if (canDeleteSelected) {
                TextButton(onClick = onDeleteSelected) {
                    Text("Eliminar Instancia", color = Color(0xFFF87171))
                }
            }
        }
    )
}

@Composable
private fun NewInstanceDialog(
    onDismiss: () -> Unit,
    onCreate: (String, EngineType, String) -> Unit
) {
    var title by remember { mutableStateOf("Mi Nuevo Mundo") }
    var selectedEngine by remember { mutableStateOf(EngineType.JAVA) }
    var version by remember { mutableStateOf("1.21.4") }

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
                            label = {
                                Text(
                                    engine.displayName,
                                    color = if (active) color else Prissma.colors.textMuted
                                )
                            }
                        )
                    }
                }

                OutlinedTextField(
                    value = version,
                    onValueChange = { version = it },
                    label = { Text("Versión (ej. 1.21.4 o 26.3)") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank()) onCreate(title.trim(), selectedEngine, version.trim())
                },
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
