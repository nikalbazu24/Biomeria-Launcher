package com.biomeria.launcher.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.biomeria.launcher.data.GameInstance
import com.biomeria.launcher.data.PlayStats
import com.biomeria.launcher.data.PlaySummary
import com.biomeria.launcher.engine.java.AchievementLog
import com.biomeria.launcher.ui.components.BlockButton
import com.biomeria.launcher.ui.components.BlockButtons
import com.biomeria.launcher.ui.components.PanelShape
import com.biomeria.launcher.ui.theme.Biomeria
import com.biomeria.launcher.ui.theme.PixelFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class StatsRange(val label: String, val days: Int?) {
    WEEK("7 días", 7), MONTH("30 días", 30), ALL("Todo", null)
}

private class StatsData(val summary: PlaySummary, val achievements: Int)

/** Time played, sessions and a bar chart per day, for 7 days, 30 days or everything. */
@Composable
internal fun StatsPage(instances: List<GameInstance>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val palette = Biomeria.colors
    var range by remember { mutableStateOf(StatsRange.WEEK) }
    val data by produceState<StatsData?>(null, range, instances) {
        value = withContext(Dispatchers.IO) {
            val summary = PlayStats.summary(PlayStats.sessions(context), range.days)
            val done = instances.sumOf { AchievementLog.progress(context, it.id)?.first?.first ?: 0 }
            StatsData(summary, done)
        }
    }
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Estadísticas", fontFamily = PixelFont, fontSize = 34.sp)
            Spacer(Modifier.weight(1f))
            StatsRange.entries.forEach { item ->
                BlockButton({ range = item }, colors = if (item == range) BlockButtons.primary else BlockButtons.neutral,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                    Text(item.label, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                }
            }
        }
        val stats = data
        if (stats == null) {
            Text("Leyendo tus partidas…", color = palette.textMuted)
            return@Column
        }
        val s = stats.summary
        if (s.sessions == 0) {
            Text("Aún no hay partidas en este periodo. Juega un mundo de Java y aquí aparecerá tu tiempo.",
                color = palette.textMuted, style = MaterialTheme.typography.bodyLarge)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("Tiempo jugado", PlayStats.format(s.seconds), palette.engineJava, Modifier.weight(1f))
            StatCard("Sesiones", s.sessions.toString(), palette.engineBedrock, Modifier.weight(1f))
            StatCard("Logros", stats.achievements.toString(), palette.gold, Modifier.weight(1f))
            StatCard("Día más jugado",
                s.bestDayStartMs?.let { SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(it)) } ?: "—",
                palette.engineRust, Modifier.weight(1f), sub = if (s.bestDaySeconds > 0) PlayStats.format(s.bestDaySeconds) else null)
        }
        DayChart(s)
        Text("Tiempo por mundo", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
        val worlds = s.perWorld.entries.sortedByDescending { it.value }
        val max = worlds.firstOrNull()?.value?.coerceAtLeast(1L) ?: 1L
        worlds.forEach { (id, seconds) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(instances.firstOrNull { it.id == id }?.title ?: id, Modifier.width(160.dp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                Box(Modifier.weight(1f).height(14.dp).background(palette.surfaceElevated)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(seconds.toFloat() / max).background(palette.engineJava))
                }
                Text(PlayStats.format(seconds), Modifier.width(96.dp), color = palette.textMuted,
                    style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
        Text("Cuenta solo el tiempo con el juego de Java en pantalla. Otras apps y frontends pueden leer estos datos desde Biomeria.",
            color = palette.textMuted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StatCard(label: String, value: String, tint: androidx.compose.ui.graphics.Color, modifier: Modifier, sub: String? = null) {
    val palette = Biomeria.colors
    Column(modifier.background(palette.surfaceCard, PanelShape).border(2.dp, palette.surfaceElevated, PanelShape)
        .padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(label, color = palette.textMuted, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        Text(value, color = tint, fontFamily = PixelFont, fontSize = 24.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sub != null) Text(sub, color = palette.textMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun DayChart(summary: PlaySummary) {
    val palette = Biomeria.colors
    val days = summary.perDay.takeLast(30)
    val max = (days.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)
    val fmt = remember { SimpleDateFormat("d", Locale.getDefault()) }
    Column(Modifier.fillMaxWidth().background(palette.surfaceCard, PanelShape).border(2.dp, palette.surfaceElevated, PanelShape)
        .padding(14.dp)) {
        Text("Tiempo por día", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(8.dp))
        if (days.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(90.dp), contentAlignment = Alignment.Center) {
                Text("Sin datos todavía", color = palette.textMuted)
            }
        } else {
            Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.Bottom) {
                days.forEach { (_, seconds) ->
                    Box(Modifier.weight(1f).fillMaxHeight(if (seconds == 0L) .02f else (seconds.toFloat() / max).coerceAtLeast(.06f))
                        .background(if (seconds == max) palette.gold else palette.engineJava))
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                days.forEach { (day, _) ->
                    Text(if (days.size <= 10) fmt.format(Date(day)) else "", Modifier.weight(1f),
                        color = palette.textMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}
