package com.prissma.launcher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prissma.launcher.data.LogLevel
import com.prissma.launcher.data.classifyLogLine
import com.prissma.launcher.ui.theme.Prissma

/** Which lines a log view shows. */
internal enum class LogFilter(val label: String) {
    ALL("Todo"), WARNINGS("Avisos y errores"), ERRORS("Solo errores");

    fun keeps(level: LogLevel) = when (this) {
        ALL -> true
        WARNINGS -> level != LogLevel.INFO
        ERRORS -> level == LogLevel.ERROR
    }
}

internal fun filterLog(lines: List<String>, filter: LogFilter, query: String): List<String> {
    val needle = query.trim()
    return lines.filter { filter.keeps(classifyLogLine(it)) && (needle.isEmpty() || it.contains(needle, ignoreCase = true)) }
}

/** Game log with a colour per level: errors in orange with a bar, warnings in gold. */
@Composable
internal fun LogView(
    lines: List<String>,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    emptyText: String = "Todavía no hay líneas en el registro."
) {
    Box(
        modifier.background(Color(0xFF07090B), PanelShape).border(2.dp, Prissma.colors.surfaceElevated, PanelShape)
            .padding(8.dp).semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        if (lines.isEmpty()) {
            Text(emptyText, color = Prissma.colors.textMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        } else {
            LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                itemsIndexed(lines) { _, line ->
                    val level = classifyLogLine(line)
                    val (text, bar, back) = when (level) {
                        LogLevel.ERROR -> Triple(Color(0xFFFFD2C4), Color(0xFFF07D4A), Color(0x24F07D4A))
                        LogLevel.WARN -> Triple(Color(0xFFF2EDC2), Color(0xFFC9B93A), Color(0x10F5E663))
                        LogLevel.INFO -> Triple(Color(0xFFC9D2D7), Color.Transparent, Color.Transparent)
                    }
                    Text(
                        line,
                        Modifier.fillMaxWidth().background(back).drawBehind {
                            drawRect(bar, size = Size(3.dp.toPx(), size.height))
                        }.padding(start = 8.dp, end = 4.dp, top = 1.dp, bottom = 1.dp),
                        color = text, fontSize = 11.sp, lineHeight = 15.sp, fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
