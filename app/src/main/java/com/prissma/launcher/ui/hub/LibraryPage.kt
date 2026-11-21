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
import com.prissma.launcher.ui.components.ControlShape
import com.prissma.launcher.ui.components.LauncherButton
import com.prissma.launcher.ui.components.PanelShape
import com.prissma.launcher.ui.components.StatusDot
import com.prissma.launcher.ui.components.gamepadClick
import com.prissma.launcher.ui.theme.Prissma

@Composable
internal fun LibraryPage(
    instances: List<GameInstance>,
    selected: GameInstance?,
    favorites: Set<String>,
    filter: EngineType?,
    favoritesOnly: Boolean,
    order: LibraryOrder,
    query: String,
    onQuery: (String) -> Unit,
    onFilter: (EngineType?) -> Unit,
    onFavoritesFilter: () -> Unit,
    onOrder: () -> Unit,
    onSelect: (GameInstance) -> Unit,
    onNew: () -> Unit,
    onPlay: () -> Unit,
    onWorkshop: () -> Unit,
    onEdit: () -> Unit,
    onFavorite: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier) {
        val wide = maxWidth >= 487.dp && maxHeight >= 250.dp
        val hero: @Composable (Modifier) -> Unit = {
            InstanceHero(selected, selected?.id?.let(favorites::contains) == true, onPlay, onWorkshop, onEdit, onFavorite, it)
        }
        val list: @Composable (Modifier) -> Unit = {
            LibraryList(instances, selected, favorites, filter, favoritesOnly, order, query,
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
    favorite: Boolean,
    onPlay: () -> Unit,
    onWorkshop: () -> Unit,
    onEdit: () -> Unit,
    onFavorite: () -> Unit,
    modifier: Modifier
) {
    val palette = Prissma.colors
    Box(modifier.clip(PanelShape).background(palette.surfaceCard).border(1.dp, palette.borderSubtle, PanelShape)) {
        Image(
            painterResource(R.drawable.prissma_valley), contentDescription = null,
            modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop,
            alignment = Alignment.CenterEnd
        )
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
            0f to Color(0xDC0F1719), .65f to Color(0x55101719), 1f to Color(0x160F1719)
        )))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            0f to Color(0x4010181A), .4f to Color(0x3C10181A), 1f to Color(0xFF111719)
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
                        StatusDot(tint)
                        Text(instance.engine.displayName, color = tint,
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.weight(1f))
                    HeroIcon(if (favorite) "Quitar de favoritos" else "Marcar como favorita",
                        Icons.Default.Star, onFavorite, if (favorite) palette.brandAccent else palette.textMuted)
                    HeroIcon("Gestionar instancia", Icons.Default.Edit, onEdit, palette.textPrimary)
                }
            }
            Spacer(Modifier.weight(1f))
            Text(instance?.title ?: "Tu biblioteca", style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(5.dp))
            Text(
                when {
                    instance == null -> "Selecciona una instancia para ver sus opciones."
                    !instance.engine.canLaunch -> "Versión " + instance.version + " · Motor en preparación"
                    instance.configuredModLoader != null -> "Minecraft " + instance.version + " · Inicio vanilla"
                    else -> "Minecraft " + instance.version + " · " + instance.loader
                },
                color = palette.textMuted, style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (instance != null) {
                Spacer(Modifier.height(14.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LauncherButton("Preparar juego", onPlay, icon = Icons.Default.PlayArrow,
                        primary = true, enabled = instance.engine.canLaunch)
                    LauncherButton("Taller", onWorkshop, enabled = instance.engine.canLaunch)
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
            .border(1.dp, if (focused) Prissma.colors.brandAccent else Color.Transparent, ControlShape)
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
                Text("Instancias", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
                    label = { Text(filter?.displayName ?: "Filtrar") },
                    trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, Modifier.size(14.dp)) },
                    modifier = Modifier.gamepadClick(onClick = open))
                DropdownMenu(filtersOpen, onDismissRequest = { filtersOpen = false }) {
                    (listOf(null) + EngineType.entries).forEach { engine ->
                        val choose = { onFilter(engine); filtersOpen = false }
                        DropdownMenuItem(text = { Text(engine?.displayName ?: "Todos los motores") },
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
                Text(if (favoritesOnly) "No hay favoritos en esta vista." else "No se encontraron instancias.",
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
                    LibraryEntry(it, selected?.id == it.id, it.id in favorites) { onSelect(it) }
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
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Buscar instancias" },
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
                    if (query.isEmpty()) Text("Buscar instancias", color = palette.textMuted,
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
private fun LibraryEntry(instance: GameInstance, selected: Boolean, favorite: Boolean, onSelect: () -> Unit) {
    val palette = Prissma.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val tint = palette.engineColor(instance.engine.name)
    val border = if (focused) palette.brandAccent else if (selected) palette.borderSubtle else Color.Transparent
    Row(
        Modifier.fillMaxWidth().clip(ControlShape)
            .background(if (selected || focused) palette.surfaceElevated else Color.Transparent)
            .border(1.dp, border, ControlShape).gamepadClick(onClick = onSelect)
            .selectable(selected, interactionSource = source, indication = null, role = Role.Button, onClick = onSelect)
            .heightIn(min = 49.dp).padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Box(Modifier.size(30.dp).background(tint.copy(alpha = .13f), ControlShape), contentAlignment = Alignment.Center) {
            Text(instance.engine.displayName.take(1), color = tint,
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f)) {
            Text(instance.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(instance.engine.displayName + " · " + instance.version, color = palette.textMuted,
                style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        if (favorite) Icon(Icons.Default.Star, "Favorita", Modifier.size(15.dp), tint = palette.brandAccent)
        if (selected) StatusDot(palette.brandAccent, Modifier.padding(end = 2.dp))
    }
}
