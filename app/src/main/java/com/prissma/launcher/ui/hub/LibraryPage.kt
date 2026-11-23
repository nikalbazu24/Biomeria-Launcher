package com.prissma.launcher.ui.hub

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prissma.launcher.R
import com.prissma.launcher.data.EngineType
import com.prissma.launcher.data.GameInstance
import com.prissma.launcher.engine.bedrock.BedrockInstallation
import com.prissma.launcher.engine.bedrock.launchProblem
import com.prissma.launcher.engine.rust.StarlingInstallation
import com.prissma.launcher.engine.rust.launchProblem as starlingLaunchProblem
import com.prissma.launcher.ui.components.ControlShape
import com.prissma.launcher.ui.components.LauncherButton
import com.prissma.launcher.ui.components.PanelShape
import com.prissma.launcher.ui.components.StatusDot
import com.prissma.launcher.ui.components.gamepadClick
import com.prissma.launcher.ui.theme.Prissma
import com.prissma.launcher.ui.theme.biomeImage
import com.prissma.launcher.ui.theme.biomeLabel
import androidx.compose.animation.Crossfade
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow

@Composable
internal fun LibraryPage(
    instances: List<GameInstance>,
    selected: GameInstance?,
    favorites: Set<String>,
    filter: EngineType?,
    favoritesOnly: Boolean,
    order: LibraryOrder,
    query: String,
    bedrock: BedrockInstallation?,
    starling: StarlingInstallation?,
    onQuery: (String) -> Unit,
    onFilter: (EngineType?) -> Unit,
    onFavoritesFilter: () -> Unit,
    onOrder: () -> Unit,
    onSelect: (GameInstance) -> Unit,
    onNew: () -> Unit,
    onPlay: () -> Unit,
    onServers: () -> Unit,
    onBedrockApps: () -> Unit,
    onStarlingDiagnostics: () -> Unit,
    onWorkshop: () -> Unit,
    onEdit: () -> Unit,
    onFavorite: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier) {
        val wide = maxWidth >= 487.dp && maxHeight >= 250.dp
        val hero: @Composable (Modifier) -> Unit = {
            InstanceHero(selected, bedrock, starling, selected?.id?.let(favorites::contains) == true,
                onPlay, onServers, onBedrockApps, onStarlingDiagnostics, onWorkshop, onEdit, onFavorite, it)
        }
        val list: @Composable (Modifier) -> Unit = {
            LibraryList(instances, selected, favorites, filter, favoritesOnly, order, query, bedrock, starling,
                onQuery, onFilter, onFavoritesFilter, onOrder, onSelect, onNew, it)
        }
        if (wide) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                hero(Modifier.weight(1.28f).fillMaxHeight())
                list(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                hero(Modifier.fillMaxWidth().height(250.dp))
                list(Modifier.fillMaxWidth().height(328.dp))
            }
        }
    }
}

@Composable
private fun InstanceHero(
    instance: GameInstance?,
    bedrock: BedrockInstallation?,
    starling: StarlingInstallation?,
    favorite: Boolean,
    onPlay: () -> Unit,
    onServers: () -> Unit,
    onBedrockApps: () -> Unit,
    onStarlingDiagnostics: () -> Unit,
    onWorkshop: () -> Unit,
    onEdit: () -> Unit,
    onFavorite: () -> Unit,
    modifier: Modifier
) {
    val palette = Prissma.colors
    Box(modifier.clip(PanelShape).background(palette.surfaceCard).border(1.dp, palette.borderSubtle, PanelShape)) {
        // Each world shows its biome: the valley for Java, the ocean for Bedrock, the Nether for Starling.
        Crossfade(instance?.engine ?: EngineType.JAVA, Modifier.matchParentSize(), label = "biome") { engine ->
            Image(
                painterResource(engine.biomeImage), contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                alignment = Alignment.CenterEnd
            )
        }
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
            0f to Color(0xD60A0D10), .6f to Color(0x660A0D10), 1f to Color(0x100A0D10)
        )))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            0f to Color(0x300A0D10), .45f to Color(0x300A0D10), 1f to Color(0xF00A0D10)
        )))
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            if (instance != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    val tint = palette.engineColor(instance.engine.name)
                    Row(
                        Modifier.background(palette.surfaceGlass, ControlShape).padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(Modifier.size(10.dp).background(tint))
                        Text(instance.engine.biomeLabel, color = tint,
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold)
                    }
                    Spacer(Modifier.weight(1f))
                    HeroIcon(if (favorite) "Quitar de favoritos" else "Marcar como favorita",
                        Icons.Default.Star, onFavorite, if (favorite) palette.gold else palette.textMuted)
                    HeroIcon("Gestionar instancia", Icons.Default.Edit, onEdit, palette.textPrimary)
                }
            }
            Spacer(Modifier.weight(1f))
            Text(instance?.title ?: "Tus mundos", style = MaterialTheme.typography.headlineLarge.copy(
                    shadow = Shadow(Color(0x99000000), Offset(5f, 5f))),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(5.dp))
            Text(
                if (instance == null) "Elige un mundo para ver sus opciones." else when (instance.engine) {
                    EngineType.RUST -> starling.starlingLaunchProblem() ?: checkNotNull(starling).label
                    EngineType.BEDROCK -> bedrock.launchProblem() ?: checkNotNull(bedrock).label
                    EngineType.JAVA -> "Minecraft ${instance.version} · ${instance.loader}"
                },
                color = palette.textMuted, style = MaterialTheme.typography.bodyMedium,
                maxLines = if (instance?.engine == EngineType.JAVA) 1 else 2, overflow = TextOverflow.Ellipsis
            )
            if (instance != null) {
                Spacer(Modifier.height(14.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (instance.engine) {
                        EngineType.JAVA -> {
                            LauncherButton("Preparar juego", onPlay, icon = Icons.Default.PlayArrow, primary = true)
                            LauncherButton("Servidores", onServers)
                            LauncherButton("Taller", onWorkshop)
                        }
                        EngineType.BEDROCK -> {
                            if (bedrock?.canOpen == true) {
                                LauncherButton("Abrir Minecraft", onPlay, icon = Icons.Default.PlayArrow, primary = true)
                                LauncherButton("Cambiar aplicación", onBedrockApps)
                            } else {
                                LauncherButton("Buscar Minecraft", onBedrockApps, icon = Icons.Default.Search, primary = true)
                            }
                            LauncherButton("Taller", onWorkshop)
                        }
                        EngineType.RUST -> {
                            LauncherButton("Jugar", onPlay, icon = Icons.Default.PlayArrow, primary = true,
                                enabled = starling?.canOpen == true)
                            LauncherButton("Taller", onWorkshop, enabled = starling?.canOpen == true)
                            LauncherButton("Diagnóstico", onStarlingDiagnostics)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroIcon(label: String, icon: ImageVector, onClick: () -> Unit, tint: Color) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    IconButton(
        onClick = onClick, interactionSource = source,
        modifier = Modifier.size(44.dp).background(Prissma.colors.surfaceGlass, ControlShape)
            .border(3.dp, if (focused) Prissma.colors.gold else Color.Transparent, ControlShape)
            .gamepadClick(onClick = onClick)
    ) { Icon(icon, label, tint = tint, modifier = Modifier.size(20.dp)) }
}

@Composable
private fun LibraryList(
    instances: List<GameInstance>,
    selected: GameInstance?,
    favorites: Set<String>,
    filter: EngineType?,
    favoritesOnly: Boolean,
    order: LibraryOrder,
    query: String,
    bedrock: BedrockInstallation?,
    starling: StarlingInstallation?,
    onQuery: (String) -> Unit,
    onFilter: (EngineType?) -> Unit,
    onFavoritesFilter: () -> Unit,
    onOrder: () -> Unit,
    onSelect: (GameInstance) -> Unit,
    onNew: () -> Unit,
    modifier: Modifier
) {
    val palette = Prissma.colors
    Column(modifier.background(palette.surfaceCard, PanelShape).border(1.dp, palette.borderSubtle, PanelShape)
        .padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Mundos", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                Text(if (instances.size == 1) "1 resultado" else instances.size.toString() + " resultados",
                    style = MaterialTheme.typography.labelSmall, color = palette.textMuted)
            }
            LauncherButton("Nueva", onNew, icon = Icons.Default.Add)
        }
        LibrarySearch(query, onQuery)
        var filtersOpen by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Box {
                val open = { filtersOpen = true }
                FilterChip(filter != null || order != LibraryOrder.RECENT, open,
                    label = { Text(filter?.biomeLabel ?: "Filtrar") },
                    trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, Modifier.size(14.dp)) },
                    modifier = Modifier.gamepadClick(onClick = open))
                DropdownMenu(filtersOpen, onDismissRequest = { filtersOpen = false }) {
                    (listOf(null) + EngineType.entries).forEach { engine ->
                        val choose = { onFilter(engine); filtersOpen = false }
                        DropdownMenuItem(text = { Text(engine?.biomeLabel ?: "Todos los biomas") },
                            onClick = choose, modifier = Modifier.gamepadClick(onClick = choose))
                    }
                    HorizontalDivider()
                    val reorder = { onOrder(); filtersOpen = false }
                    DropdownMenuItem(text = { Text("Orden · " + order.label) },
                        onClick = reorder, modifier = Modifier.gamepadClick(onClick = reorder))
                }
            }
            FilterChip(selected = favoritesOnly, onClick = onFavoritesFilter,
                label = { Text("Favoritos") },
                leadingIcon = { Icon(Icons.Default.Star, null, Modifier.size(14.dp)) },
                modifier = Modifier.gamepadClick(onClick = onFavoritesFilter))
        }
        if (instances.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(if (favoritesOnly) "No hay favoritos en esta vista." else "No se encontraron mundos.",
                    style = MaterialTheme.typography.bodyMedium, color = palette.textMuted)
            }
        } else {
            val state = rememberLazyListState()
            LaunchedEffect(instances, selected?.id) {
                val index = instances.indexOfFirst { it.id == selected?.id }
                if (index >= 0 && state.layoutInfo.visibleItemsInfo.none { it.index == index }) state.scrollToItem(index)
            }
            LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(instances, key = { it.id }) {
                    val version = when (it.engine) {
                        EngineType.JAVA -> it.version
                        EngineType.BEDROCK -> bedrock?.versionName
                        EngineType.RUST -> starling?.version
                    }
                    LibraryEntry(it, version, selected?.id == it.id, it.id in favorites) { onSelect(it) }
                }
            }
        }
    }
}

@Composable
private fun LibrarySearch(query: String, onQuery: (String) -> Unit) {
    val palette = Prissma.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    BasicTextField(
        query, onQuery, singleLine = true, interactionSource = source,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = palette.textPrimary),
        cursorBrush = SolidColor(palette.brandAccent),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Buscar mundos" },
        decorationBox = { textField ->
            Row(
                Modifier.heightIn(min = 43.dp).background(palette.surfaceVoid, ControlShape)
                    .border(1.dp, if (focused) palette.brandAccent else palette.borderSubtle, ControlShape)
                    .padding(start = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Icon(Icons.Default.Search, null, Modifier.size(16.dp), tint = palette.textMuted)
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Buscar mundos", color = palette.textMuted,
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    textField()
                }
                if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Default.Close, "Borrar búsqueda", Modifier.size(16.dp))
                }
            }
        }
    )
}

@Composable
private fun LibraryEntry(instance: GameInstance, version: String?, selected: Boolean, favorite: Boolean, onSelect: () -> Unit) {
    val palette = Prissma.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val tint = palette.engineColor(instance.engine.name)
    val border = if (focused) palette.gold else if (selected) tint else Color.Transparent
    Row(
        Modifier.fillMaxWidth().clip(ControlShape)
            .background(if (selected || focused) palette.surfaceElevated else Color.Transparent)
            .border(if (focused || selected) 2.dp else 0.dp, border, ControlShape).gamepadClick(onClick = onSelect)
            .selectable(selected, interactionSource = source, indication = null, role = Role.Button, onClick = onSelect)
            .heightIn(min = 49.dp).padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Image(
            painterResource(instance.engine.biomeImage), contentDescription = null,
            modifier = Modifier.size(52.dp, 36.dp).clip(ControlShape).border(2.dp, tint, ControlShape),
            contentScale = ContentScale.Crop, alignment = Alignment.CenterEnd
        )
        Column(Modifier.weight(1f)) {
            Text(instance.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(instance.engine.biomeLabel, version).joinToString(" · "), color = palette.textMuted,
                style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        if (favorite) Icon(Icons.Default.Star, "Favorita", Modifier.size(15.dp), tint = palette.gold)
        if (selected) StatusDot(palette.brandAccent, Modifier.padding(end = 2.dp))
    }
}
