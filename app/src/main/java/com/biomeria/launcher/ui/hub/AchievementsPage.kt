package com.biomeria.launcher.ui.hub

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.biomeria.launcher.data.EngineType
import com.biomeria.launcher.data.GameInstance
import com.biomeria.launcher.engine.java.Achievement
import com.biomeria.launcher.engine.java.AchievementCelebration
import com.biomeria.launcher.engine.java.AchievementKind
import com.biomeria.launcher.engine.java.AchievementLog
import com.biomeria.launcher.engine.java.modLabel
import com.biomeria.launcher.engine.java.rememberAchievementIcon
import com.biomeria.launcher.ui.components.BlockButton
import com.biomeria.launcher.ui.components.BlockButtons
import com.biomeria.launcher.ui.theme.PixelFont
import com.biomeria.launcher.ui.theme.Biomeria
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class AchievementFilter(val label: String) {
    ALL("Todos"), TASK("Logros"), GOAL("Metas"), CHALLENGE("Desafíos"), MODS("Mods")
}

private data class WorldAchievements(
    val unlocked: List<Achievement>,
    val done: Int,
    val total: Int,
    val byMod: Map<String, Pair<Int, Int>>,
    val seen: Set<String>,
    val names: Map<String, String> = emptyMap()
) {
    fun modName(namespace: String) = names[namespace] ?: modLabel(namespace)
}

/**
 * Achievements earned while playing through Biomeria, per Java world. Ones not earned yet are
 * only counted, never shown, so they stay a surprise. Mods get their own tab.
 */
@Composable
internal fun AchievementsPage(instances: List<GameInstance>, initialId: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val worlds = remember(instances) { instances.filter { it.engine == EngineType.JAVA } }
    var worldId by remember(worlds) { mutableStateOf(worlds.firstOrNull { it.id == initialId }?.id ?: worlds.firstOrNull()?.id) }
    var filter by remember { mutableStateOf(AchievementFilter.ALL) }
    var selectedId by remember(worldId) { mutableStateOf<String?>(null) }
    var replay by remember { mutableStateOf<Achievement?>(null) }
    val data by produceState<WorldAchievements?>(null, worldId) {
        val id = worldId ?: return@produceState
        value = withContext(Dispatchers.IO) {
            val unlocked = AchievementLog.read(context, id).sortedByDescending { it.at }
            val progress = AchievementLog.progress(context, id)
            WorldAchievements(unlocked, progress?.first?.first ?: unlocked.size, progress?.first?.second ?: 0,
                progress?.second.orEmpty(), AchievementLog.seen(context, id), AchievementLog.modNames(context, id))
        }
    }
    // Everything on screen counts as seen once the player leaves the page or changes world.
    DisposableEffect(worldId) {
        onDispose {
            val id = worldId
            val shown = data?.unlocked?.map { it.id }
            if (id != null && !shown.isNullOrEmpty()) AchievementLog.markSeen(context, id, shown)
        }
    }

    Box(modifier) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Logros", fontFamily = PixelFont, fontSize = 34.sp)
                Spacer(Modifier.weight(1f))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    worlds.forEach { world ->
                        ChoiceBlock(world.title, world.id == worldId) { worldId = world.id }
                    }
                }
            }
            val world = data
            when {
                worlds.isEmpty() -> EmptyNote("Crea un mundo de Java para empezar a coleccionar logros.")
                world == null -> EmptyNote("Leyendo tus logros…")
                else -> AchievementsBody(world, filter, { filter = it }, selectedId, { selectedId = it }, { replay = it },
                    Modifier.weight(1f))
            }
            Text("Se guardan los logros que consigues jugando desde Biomeria con Fabric 1.21.4. " +
                "Los que aún no consigues no se muestran para no arruinar la sorpresa.",
                color = Biomeria.colors.textMuted, style = MaterialTheme.typography.bodySmall)
        }
        replay?.let { shown ->
            Box(Modifier.fillMaxSize().background(Color(0x99000000))) {
                AchievementCelebration(shown, modifier = Modifier.fillMaxSize()) { replay = null }
            }
        }
    }
}

@Composable
private fun AchievementsBody(
    world: WorldAchievements,
    filter: AchievementFilter,
    onFilter: (AchievementFilter) -> Unit,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onReplay: (Achievement) -> Unit,
    modifier: Modifier
) {
    val total = maxOf(world.total, world.done)
    Row(Modifier.fillMaxWidth().background(Biomeria.colors.surfaceCard, RoundedCornerShape(6.dp))
        .border(2.dp, Biomeria.colors.surfaceElevated, RoundedCornerShape(6.dp)).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(buildString { append(world.done); if (total > 0) append(" / $total") }, fontFamily = PixelFont, fontSize = 36.sp,
            color = Biomeria.colors.gold)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (total > 0) "${world.done * 100 / total} % completado" else "Juega una partida para ver tu avance",
                fontWeight = FontWeight.ExtraBold)
            BlockBar(if (total > 0) world.done.toFloat() / total else 0f)
        }
        AchievementKind.entries.forEach { kind ->
            Tally(kind, world.unlocked.count { it.kind == kind })
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AchievementFilter.entries.forEach { option -> ChoiceBlock(option.label, option == filter) { onFilter(option) } }
    }

    val shown = world.unlocked.filter {
        when (filter) {
            AchievementFilter.ALL -> true
            AchievementFilter.TASK -> it.kind == AchievementKind.TASK
            AchievementFilter.GOAL -> it.kind == AchievementKind.GOAL
            AchievementFilter.CHALLENGE -> it.kind == AchievementKind.CHALLENGE
            AchievementFilter.MODS -> it.fromMod
        }
    }
    val selected = shown.firstOrNull { it.id == selectedId } ?: shown.firstOrNull()
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LazyVerticalGrid(GridCells.Adaptive(220.dp), Modifier.weight(1f).fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (filter == AchievementFilter.MODS) {
                val mods = (world.byMod.keys - "minecraft") + shown.map { it.namespace }
                if (mods.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyNote("Ninguno de los mods de este mundo trae logros, o todavía no has jugado con ellos.")
                }
                mods.distinct().sorted().forEach { namespace ->
                    val counts = world.byMod[namespace]
                    item(span = { GridItemSpan(maxLineSpan) }, key = "mod-$namespace") {
                        ModHeader(world.modName(namespace), counts?.first ?: shown.count { it.namespace == namespace }, counts?.second ?: 0)
                    }
                    val ofMod = shown.filter { it.namespace == namespace }
                    items(ofMod, key = { it.id }) { achievement ->
                        AchievementCard(achievement, achievement.id == selected?.id, achievement.id !in world.seen) { onSelect(achievement.id) }
                    }
                    if (ofMod.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "empty-$namespace") {
                        EmptyNote("Aún no consigues logros de este mod.")
                    }
                }
            } else {
                items(shown, key = { it.id }) { achievement ->
                    AchievementCard(achievement, achievement.id == selected?.id, achievement.id !in world.seen) { onSelect(achievement.id) }
                }
                val left = maxOf(0, total - world.done)
                if (left > 0 && filter == AchievementFilter.ALL) item(key = "left") {
                    Box(Modifier.height(96.dp).border(2.dp, Biomeria.colors.surfaceElevated, RoundedCornerShape(5.dp)).padding(10.dp),
                        contentAlignment = Alignment.Center) {
                        Text("$left por descubrir.\nJuega para encontrarlos.", color = Biomeria.colors.textMuted,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyNote(if (world.unlocked.isEmpty()) "Todavía no hay logros guardados en este mundo. ¡A jugar!"
                        else "No hay logros de este tipo todavía.")
                }
            }
        }
        if (selected != null) AchievementDetail(selected, world.modName(selected.namespace), Modifier.width(300.dp).fillMaxHeight()) { onReplay(selected) }
    }
}

@Composable
private fun AchievementCard(achievement: Achievement, selected: Boolean, fresh: Boolean, onClick: () -> Unit) {
    BlockButton(onClick, Modifier.fillMaxWidth().height(96.dp), BlockButtons.neutral,
        contentPadding = PaddingValues(10.dp)) {
        Box(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AchievementFrame(achievement, 54.dp)
                Column(Modifier.weight(1f)) {
                    Text(achievement.title, fontFamily = PixelFont, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        lineHeight = 18.sp, color = if (selected) achievement.kind.color else Color(0xFFF2F5EF))
                    Text(whenText(achievement.at), color = Biomeria.colors.textMuted, fontSize = 12.sp)
                }
            }
            if (fresh) Text("NUEVO", Modifier.align(Alignment.TopEnd).background(Biomeria.colors.gold, RoundedCornerShape(3.dp))
                .padding(horizontal = 6.dp, vertical = 1.dp), color = Biomeria.colors.ink, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
        }
    }
}

@Composable
private fun AchievementDetail(achievement: Achievement, modName: String, modifier: Modifier, onReplay: () -> Unit) {
    val kind = achievement.kind
    Column(modifier.background(Biomeria.colors.surfaceCard, RoundedCornerShape(6.dp)).border(3.dp, kind.color, RoundedCornerShape(6.dp))
        .padding(18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AchievementFrame(achievement, 84.dp)
        Text(kind.banner, color = kind.color, fontFamily = PixelFont, fontSize = 13.sp)
        Text(achievement.title, fontFamily = PixelFont, fontSize = 26.sp, lineHeight = 28.sp)
        if (achievement.description.isNotBlank()) Text(achievement.description, color = Color(0xFFC3CDD2))
        if (achievement.fromMod) Text("Del mod $modName", color = Biomeria.colors.textMuted, fontSize = 14.sp)
        Text("Desbloqueado ${whenText(achievement.at)}", color = Biomeria.colors.textMuted, fontSize = 14.sp)
        BlockButton(onReplay, Modifier.fillMaxWidth(), BlockButtons.primary) {
            Text("Ver otra vez la animación", fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
private fun AchievementFrame(achievement: Achievement, size: Dp) {
    val shape: Shape = when (achievement.kind) {
        AchievementKind.TASK -> RoundedCornerShape(4.dp)
        AchievementKind.GOAL -> RoundedCornerShape(size / 6)
        AchievementKind.CHALLENGE -> CircleShape
    }
    Box(Modifier.size(size).clip(shape).background(Color(0xFF1A2026)).border(4.dp, achievement.kind.color, shape),
        contentAlignment = Alignment.Center) {
        val icon = rememberAchievementIcon(achievement.icon)
        if (icon != null) Image(icon, null, Modifier.fillMaxSize(.6f), filterQuality = FilterQuality.None)
        else Box(Modifier.size(size / 3).background(achievement.kind.color))
    }
}

@Composable
private fun ModHeader(name: String, done: Int, total: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(name, fontFamily = PixelFont, fontSize = 20.sp)
        Text(if (total > 0) "$done de $total" else "$done conseguidos", color = Biomeria.colors.textMuted, fontWeight = FontWeight.Bold)
        if (total > 0) BlockBar(done.toFloat() / total, Modifier.weight(1f))
    }
}

@Composable
private fun Tally(kind: AchievementKind, count: Int) {
    Column(Modifier.widthIn(min = 70.dp).background(Biomeria.colors.ink, RoundedCornerShape(4.dp))
        .border(2.dp, kind.color, RoundedCornerShape(4.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$count", fontFamily = PixelFont, fontSize = 20.sp, color = kind.color)
        Text(when (kind) {
            AchievementKind.TASK -> "Logros"
            AchievementKind.GOAL -> "Metas"
            AchievementKind.CHALLENGE -> "Desafíos"
        }, fontSize = 12.sp, color = Color(0xFFC3CDD2))
    }
}

@Composable
private fun BlockBar(fraction: Float, modifier: Modifier = Modifier.fillMaxWidth()) {
    val filled = (fraction.coerceIn(0f, 1f) * 20).toInt()
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(20) { index ->
            Box(Modifier.weight(1f).height(14.dp).background(if (index < filled) Biomeria.colors.gold else Color(0xFF1A2026))
                .border(2.dp, Biomeria.colors.ink))
        }
    }
}

@Composable
private fun ChoiceBlock(label: String, selected: Boolean, onClick: () -> Unit) {
    BlockButton(onClick, colors = if (selected) BlockButtons.primary else BlockButtons.neutral,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
        Text(label, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(text, color = Biomeria.colors.textMuted, modifier = Modifier.padding(vertical = 8.dp))
}

private fun whenText(at: Long): String {
    if (at <= 0) return "antes"
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = at }
    val days = ((startOfDay(now) - startOfDay(then)) / 86_400_000L).toInt()
    return when {
        days <= 0 -> "hoy, " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))
        days == 1 -> "ayer"
        days < 7 -> "hace $days días"
        else -> SimpleDateFormat("d 'de' MMMM", Locale("es")).format(Date(at))
    }
}

private fun startOfDay(calendar: Calendar): Long = (calendar.clone() as Calendar).apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis
