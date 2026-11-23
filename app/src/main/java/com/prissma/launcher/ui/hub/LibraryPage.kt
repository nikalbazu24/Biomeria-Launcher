package com.prissma.launcher.ui.hub

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prissma.launcher.data.EngineType
import com.prissma.launcher.data.GameInstance
import com.prissma.launcher.engine.bedrock.BedrockInstallation
import com.prissma.launcher.engine.bedrock.launchProblem
import com.prissma.launcher.engine.rust.StarlingInstallation
import com.prissma.launcher.engine.rust.launchProblem as starlingLaunchProblem
import com.prissma.launcher.ui.components.BlockButtons
import com.prissma.launcher.ui.components.BlockButton
import com.prissma.launcher.ui.components.BlockIconButton
import com.prissma.launcher.ui.components.ControlShape
import com.prissma.launcher.ui.components.LauncherButton
import com.prissma.launcher.ui.components.PanelShape
import com.prissma.launcher.ui.components.gamepadClick
import com.prissma.launcher.ui.theme.Prissma
import com.prissma.launcher.ui.theme.biomeColor
import com.prissma.launcher.ui.theme.biomeImage
import com.prissma.launcher.ui.theme.biomeLabel
import com.prissma.launcher.ui.theme.biomeName

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
    val versionOf: (GameInstance) -> String? = {
        when (it.engine) {
            EngineType.JAVA -> it.version
            EngineType.BEDROCK -> bedrock?.versionName
            EngineType.RUST -> starling?.version
        }
    }
    BoxWithConstraints(modifier) {
        val wide = maxWidth >= 520.dp && maxHeight >= 300.dp
        val hero: @Composable (Modifier) -> Unit = {
            WorldHero(selected, selected?.let(versionOf), bedrock, starling, selected?.id?.let(favorites::contains) == true,
                onPlay, onServers, onBedrockApps, onStarlingDiagnostics, onWorkshop, onEdit, onFavorite, onNew, it)
        }
        val filters: @Composable () -> Unit = {
            BiomeFilters(filter, favoritesOnly, order, query, wide, onQuery, onFilter, onFavoritesFilter, onOrder)
        }
        val cards: @Composable (Modifier) -> Unit = {
            WorldCards(instances, selected, favorites, favoritesOnly, versionOf, onSelect, onNew, it)
        }
        if (wide) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                hero(Modifier.fillMaxWidth().weight(1f))
                filters()
                cards(Modifier.fillMaxWidth().height(132.dp))
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                hero(Modifier.fillMaxWidth().height(260.dp))
                filters()
                cards(Modifier.fillMaxWidth().height(132.dp))
            }
        }
    }
}

@Composable
private fun WorldHero(
    instance: GameInstance?,
    version: String?,
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
    onNew: () -> Unit,
    modifier: Modifier
) {
    val palette = Prissma.colors
    val drift = rememberInfiniteTransition(label = "drift")
    val pan by drift.animateFloat(0f, 1f, infiniteRepeatable(tween(18_000, easing = LinearEasing), RepeatMode.Reverse), label = "pan")
    Box(modifier.clip(PanelShape).background(palette.surfaceCard).border(2.dp, palette.surfaceElevated, PanelShape)) {
        // Each world shows its biome: the valley for Java, the ocean for Bedrock, the Nether for Starling.
        Crossfade(instance?.engine ?: EngineType.JAVA, Modifier.matchParentSize(), label = "biome") { engine ->
            Image(
                painterResource(engine.biomeImage), contentDescription = null,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = 1.06f; scaleY = 1.06f
                    translationX = -pan * size.width * .025f
                },
                contentScale = ContentScale.Crop, alignment = Alignment.CenterEnd
            )
        }
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
            0f to Color(0xD90A0D10), .5f to Color(0x8C0A0D10), .8f to Color(0x000A0D10)
        )))
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.Bottom) {
            if (instance != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(instance.engine.biomeLabel,
                        Modifier.background(instance.engine.biomeColor, androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                            .padding(horizontal = 10.dp, vertical = 3.dp),
                        color = palette.ink, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold)
                    val detail = if (instance.engine == EngineType.JAVA) "${instance.version} · ${instance.loader}" else version
                    if (detail != null) Text(detail,
                        Modifier.background(Color(0x1FFFFFFF), androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                            .padding(horizontal = 10.dp, vertical = 3.dp),
                        color = palette.textPrimary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                        maxLines = 1)
                }
                Spacer(Modifier.height(8.dp))
            }
            Text(
                instance?.title ?: "Tus mundos",
                style = MaterialTheme.typography.headlineLarge.copy(shadow = Shadow(Color(0x8C000000), Offset(5f, 5f))),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (instance == null) "Crea tu primer mundo o elige uno de abajo." else when (instance.engine) {
                    EngineType.JAVA -> "Minecraft Java con mods, mapa con marcas y pantalla dual para tu consola."
                    EngineType.BEDROCK -> bedrock.launchProblem() ?: "Abre la app oficial de Minecraft para jugar con amigos de otras plataformas."
                    EngineType.RUST -> starling.starlingLaunchProblem() ?: "El motor nativo Starling, pensado para correr fluido en consolas Android."
                },
                color = Color(0xFFD6DEE2), style = MaterialTheme.typography.bodyMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 440.dp)
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (instance == null) {
                    PlayButton("Nuevo mundo", Icons.Default.Add, onNew)
                } else {
                    when (instance.engine) {
                        EngineType.JAVA -> {
                            PlayButton("Jugar", Icons.Default.PlayArrow, onPlay)
                            LauncherButton("Taller", onWorkshop)
                            LauncherButton("Servidores", onServers)
                        }
                        EngineType.BEDROCK -> {
                            if (bedrock?.canOpen == true) {
                                PlayButton("Jugar", Icons.Default.PlayArrow, onPlay)
                                LauncherButton("Cambiar app", onBedrockApps)
                            } else {
                                PlayButton("Buscar Minecraft", Icons.Default.Search, onBedrockApps)
                            }
                            LauncherButton("Taller", onWorkshop)
                        }
                        EngineType.RUST -> {
                            PlayButton("Jugar", Icons.Default.PlayArrow, onPlay, enabled = starling?.canOpen == true)
                            LauncherButton("Taller", onWorkshop, enabled = starling?.canOpen == true)
                            LauncherButton("Diagnóstico", onStarlingDiagnostics)
                        }
                    }
                    BlockIconButton(if (favorite) "Quitar de favoritos" else "Marcar como favorito", Icons.Default.Star,
                        onFavorite, tint = if (favorite) palette.gold else palette.textMuted)
                    BlockIconButton("Editar mundo", Icons.Default.Edit, onEdit)
                }
            }
        }
    }
}

/** The big green button of the banner: the one action the screen is about. */
@Composable
private fun PlayButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, enabled: Boolean = true) {
    BlockButton(onClick, Modifier.heightIn(min = 52.dp), BlockButtons.primary, enabled,
        PaddingValues(horizontal = 26.dp, vertical = 6.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
}

@Composable
private fun BiomeFilters(
    filter: EngineType?,
    favoritesOnly: Boolean,
    order: LibraryOrder,
    query: String,
    wide: Boolean,
    onQuery: (String) -> Unit,
    onFilter: (EngineType?) -> Unit,
    onFavoritesFilter: () -> Unit,
    onOrder: () -> Unit
) {
    val palette = Prissma.colors
    val chips: @Composable RowScope.() -> Unit = {
        FilterBlock("Todos", palette.textPrimary, filter == null) { onFilter(null) }
        EngineType.entries.forEach { engine ->
            FilterBlock(engine.biomeLabel, engine.biomeColor, filter == engine) { onFilter(if (filter == engine) null else engine) }
        }
        FilterBlock("Favoritos", palette.gold, favoritesOnly, onFavoritesFilter)
        FilterBlock("Orden: " + order.label.lowercase(), null, false, onOrder)
    }
    if (wide) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), content = chips)
            WorldSearch(query, onQuery, Modifier.width(210.dp))
        }
    } else {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), content = chips)
        WorldSearch(query, onQuery, Modifier.fillMaxWidth())
    }
}

@Composable
private fun FilterBlock(label: String, swatch: Color?, selected: Boolean, onClick: () -> Unit) {
    val palette = Prissma.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Row(
        Modifier.heightIn(min = 38.dp).clip(ControlShape)
            .background(if (selected) palette.textPrimary else palette.surfaceCard)
            .border(if (focused) 3.dp else 2.dp, when {
                focused -> palette.gold
                selected -> palette.textPrimary
                else -> palette.surfaceElevated
            }, ControlShape)
            .gamepadClick(onClick = onClick)
            .selectable(selected, interactionSource = source, indication = null, role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (swatch != null) Box(Modifier.size(10.dp).background(swatch))
        Text(label, color = if (selected) palette.ink else Color(0xFFD6DEE2), style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
}

@Composable
private fun WorldSearch(query: String, onQuery: (String) -> Unit, modifier: Modifier) {
    val palette = Prissma.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    BasicTextField(
        query, onQuery, singleLine = true, interactionSource = source,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = palette.textPrimary),
        cursorBrush = SolidColor(palette.brandAccent),
        modifier = modifier.semantics { contentDescription = "Buscar mundos" },
        decorationBox = { textField ->
            Row(
                Modifier.heightIn(min = 38.dp).background(palette.surfaceCard, ControlShape)
                    .border(if (focused) 3.dp else 2.dp, if (focused) palette.gold else palette.surfaceElevated, ControlShape)
                    .padding(start = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Search, null, Modifier.size(16.dp), tint = palette.textMuted)
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Buscar mundos", color = palette.textMuted,
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    textField()
                }
                if (query.isNotEmpty()) Box(
                    Modifier.size(30.dp).clip(ControlShape).clickable(role = Role.Button) { onQuery("") },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.Close, "Borrar búsqueda", Modifier.size(16.dp), tint = palette.textMuted) }
            }
        }
    )
}

@Composable
private fun WorldCards(
    instances: List<GameInstance>,
    selected: GameInstance?,
    favorites: Set<String>,
    favoritesOnly: Boolean,
    versionOf: (GameInstance) -> String?,
    onSelect: (GameInstance) -> Unit,
    onNew: () -> Unit,
    modifier: Modifier
) {
    val state = rememberLazyListState()
    LaunchedEffect(instances, selected?.id) {
        val index = instances.indexOfFirst { it.id == selected?.id }
        if (index >= 0 && state.layoutInfo.visibleItemsInfo.none { it.index == index }) state.animateScrollToItem(index)
    }
    LazyRow(modifier, state = state, horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Bottom, contentPadding = PaddingValues(top = 6.dp, end = 4.dp)) {
        items(instances, key = { it.id }) {
            WorldCard(it, versionOf(it), selected?.id == it.id, it.id in favorites) { onSelect(it) }
        }
        if (instances.isEmpty()) item {
            Box(Modifier.width(220.dp).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                Text(if (favoritesOnly) "No hay favoritos con este filtro." else "No hay mundos con este filtro.",
                    color = Prissma.colors.textMuted, style = MaterialTheme.typography.bodyMedium)
            }
        }
        item { NewWorldCard(onNew) }
    }
}

@Composable
private fun WorldCard(instance: GameInstance, version: String?, selected: Boolean, favorite: Boolean, onSelect: () -> Unit) {
    val palette = Prissma.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val tint = instance.engine.biomeColor
    val lift by animateDpAsState(if (selected || focused) 0.dp else 5.dp, label = "lift")
    Column(
        Modifier.width(158.dp).padding(top = lift).clip(PanelShape).background(palette.surfaceCard)
            .border(if (focused) 3.dp else 2.dp, when {
                focused -> palette.gold
                selected -> tint
                else -> palette.surfaceElevated
            }, PanelShape)
            .gamepadClick(onClick = onSelect)
            .selectable(selected, interactionSource = source, indication = null, role = Role.Button, onClick = onSelect)
    ) {
        Box(Modifier.fillMaxWidth().height(62.dp)) {
            Image(painterResource(instance.engine.biomeImage), null, Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop, alignment = Alignment.CenterEnd)
            if (favorite) Icon(Icons.Default.Star, "Favorito", Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp),
                tint = palette.gold)
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Text(instance.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(instance.engine.biomeName, version).joinToString(" · "), color = palette.textMuted,
                style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun NewWorldCard(onNew: () -> Unit) {
    val palette = Prissma.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val dash = if (focused) palette.gold else palette.borderSubtle
    Column(
        Modifier.width(132.dp).height(121.dp).clip(PanelShape)
            .drawBehind {
                drawRoundRect(dash, style = Stroke(width = (if (focused) 3 else 2).dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
            }
            .gamepadClick(onClick = onNew)
            .clickable(source, indication = null, role = Role.Button, onClick = onNew),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Add, null, Modifier.size(28.dp), tint = palette.textMuted)
        Spacer(Modifier.height(6.dp))
        Text("Nuevo mundo", color = palette.textMuted, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold)
    }
}
