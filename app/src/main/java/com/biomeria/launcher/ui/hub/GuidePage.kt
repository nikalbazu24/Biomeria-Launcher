package com.biomeria.launcher.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.biomeria.launcher.ui.components.BlockButton
import com.biomeria.launcher.ui.components.BlockButtons
import com.biomeria.launcher.ui.components.PanelShape
import com.biomeria.launcher.ui.theme.Biomeria
import com.biomeria.launcher.ui.theme.PixelFont

private class GuidePlace(val label: String, val detail: String, val rows: List<Pair<String, String>>)

private val guidePlaces = listOf(
    GuidePlace("En el juego", "Una vez dentro del mundo", listOf(
        "Stick izq." to "Moverte", "Stick der." to "Mirar", "A" to "Saltar", "B" to "Agacharte",
        "X" to "Abrir tu inventario", "Y" to "Cambiar de mano", "LB / RB" to "Cambiar de objeto",
        "L2" to "Usar o colocar", "R2" to "Romper o atacar", "L3" to "Correr", "R3" to "Agacharte",
        "Start" to "Pausa de Minecraft", "Select" to "Mapa grande con una pantalla; menú con dos",
        "Select (largo)" to "Menú, o mando a la pantalla de abajo")),
    GuidePlace("Pantalla de abajo", "Inventario, crafteo y mapa", listOf(
        "LB / RB" to "Cambiar de pestaña", "A" to "Tomar o elegir", "B" to "Volver",
        "X" to "Pasar a la barra rápida o guardar", "Y" to "Tomar la mitad", "L2" to "Tirar uno al suelo",
        "R3" to "Marcar en el mapa", "Start" to "Buscar objetos", "Cruceta" to "Moverte por las casillas")),
    GuidePlace("Mapa grande", "Con una sola pantalla", listOf(
        "Sticks" to "Mover el mapa", "R2" to "Acercar", "L2" to "Alejar", "X" to "Volver a centrarte",
        "A" to "Marcar dónde estás", "Y" to "Editar marcas", "LB / RB" to "Mapa u opciones del mapa", "B" to "Volver al juego")),
    GuidePlace("Launcher", "Antes de entrar al mundo", listOf(
        "A" to "Jugar o elegir", "X" to "Abrir el taller", "Y" to "Marcar favorito", "B" to "Volver",
        "L1 / R1" to "Cambiar de sección", "Cruceta" to "Moverte por la lista"))
)

private class GuideCard(val title: String, val color: Color, val short: String, val more: String)

private val guideCards = listOf(
    GuideCard("Mis mundos", Color(0xFF7BC74D), "Cada mundo es una instancia con su versión y su motor.",
        "Elige uno y toca Jugar. Con la estrella lo marcas favorito y con el filtro ves solo Java, Bedrock o Starling."),
    GuideCard("Taller", Color(0xFF4FB3D9), "Instala mods, shaders y paquetes de recursos.",
        "Busca en Modrinth y te muestra qué versión y qué dependencias se instalarán antes de descargar. También importa modpacks .mrpack de Modrinth y .zip de CurseForge."),
    GuideCard("Jugar juntos", Color(0xFFF5E663), "Abre tu mundo a tus amigos.",
        "En tu misma red aparece en Multijugador. Por internet creas una sala temporal y compartes su dirección."),
    GuideCard("Dos pantallas", Color(0xFFF07D4A), "En la AYN Thor, la de abajo muestra inventario, crafteo y mapa.",
        "Cambias de pestaña con LB y RB. Mantén Select para pasarle el mando a esa pantalla y B para devolverlo."),
    GuideCard("Una pantalla", Color(0xFF4FB3D9), "En la Odin 2 Portal hay un mapa flotante sobre el juego.",
        "Toca el mapa o pulsa Select para abrirlo en grande. Sus opciones están en la pestaña Opciones del mapa."),
    GuideCard("Cuentas", Color(0xFF7BC74D), "Juega sin conexión, con Microsoft o con Ely.by.",
        "Se elige en Ajustes. Con Microsoft puedes entrar a servidores oficiales; sin conexión, a tu mundo y a tu red.")
)

private fun keyColors(key: String): Pair<Color, Color> = when (key) {
    "A" -> Color(0xFF7BC74D) to Color(0xFF0E1A08)
    "B" -> Color(0xFFF07D4A) to Color(0xFF2B0E05)
    "X" -> Color(0xFF4FB3D9) to Color(0xFF08202B)
    "Y" -> Color(0xFFF5E663) to Color(0xFF2A2508)
    else -> Color(0xFF34404A) to Color(0xFFF2F5EF)
}

/** What each button does, and a short tour of the app. Shows the factory buttons. */
@Composable
internal fun GuidePage(modifier: Modifier = Modifier) {
    val palette = Biomeria.colors
    var tab by remember { mutableStateOf(0) }
    var place by remember { mutableStateOf(0) }
    var open by remember { mutableStateOf(0) }
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Guía", fontFamily = PixelFont, fontSize = 34.sp)
            Spacer(Modifier.weight(1f))
            listOf("Controles", "La app").forEachIndexed { index, label ->
                BlockButton({ tab = index }, colors = if (tab == index) BlockButtons.primary else BlockButtons.neutral,
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
                    Text(label, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                }
            }
        }
        Text("Qué hace cada botón y cómo se usa cada parte de Biomeria.", color = palette.textMuted)
        if (tab == 0) {
            Text("Estos son los botones de fábrica. Si cambiaste alguno en Controles del mando, aquí sigue saliendo el original.",
                color = palette.textMuted, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                guidePlaces.forEachIndexed { index, item ->
                    BlockButton({ place = index }, Modifier.weight(1f).heightIn(min = 56.dp),
                        if (place == index) BlockButtons.primary else BlockButtons.neutral,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                        Column {
                            Text(item.label, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                            Text(item.detail, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    }
                }
            }
            val current = guidePlaces[place]
            Column(Modifier.fillMaxWidth().background(palette.surfaceCard, PanelShape).border(2.dp, palette.surfaceElevated, PanelShape)
                .padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                current.rows.forEach { (key, what) ->
                    val (bg, fg) = keyColors(key)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(key, Modifier.widthIn(min = 56.dp).background(bg, RoundedCornerShape(14.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp), color = fg, fontWeight = FontWeight.ExtraBold,
                            style = MaterialTheme.typography.labelLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Text(what, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        } else {
            guideCards.chunked(2).forEachIndexed { rowIndex, pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEachIndexed { col, card ->
                        val index = rowIndex * 2 + col
                        val on = open == index
                        BlockButton({ open = if (on) -1 else index }, Modifier.weight(1f), BlockButtons.neutral,
                            contentPadding = PaddingValues(14.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(card.title, color = card.color, fontFamily = PixelFont, fontSize = 20.sp)
                                Text(card.short, style = MaterialTheme.typography.bodyMedium)
                                if (on) Text(card.more, color = palette.textMuted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
