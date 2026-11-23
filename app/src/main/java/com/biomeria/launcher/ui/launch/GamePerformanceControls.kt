package com.biomeria.launcher.ui.launch

import com.biomeria.launcher.ui.components.BiomeriaDialog
import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.biomeria.launcher.engine.java.JavaRenderer
import com.biomeria.launcher.engine.java.NativeJavaBridge
import com.biomeria.launcher.engine.java.RenderScale
import com.biomeria.launcher.engine.java.availableHeapSizes
import com.biomeria.launcher.ui.components.LauncherButton
import com.biomeria.launcher.ui.components.gamepadClick
import com.biomeria.launcher.ui.theme.Biomeria
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun totalDeviceRam(context: Context): Long {
    val memory = ActivityManager.MemoryInfo()
    context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
    return memory.totalMem
}

@Composable
internal fun GamePerformanceDialog(
    heapMb: Int,
    deviceRam: Long,
    scale: RenderScale,
    renderer: JavaRenderer,
    onHeap: (Int) -> Unit,
    onScale: (RenderScale) -> Unit,
    onRenderer: (JavaRenderer) -> Unit,
    onDismiss: () -> Unit
) {
    var audioTest by remember { mutableStateOf<String?>(null) }
    var testingAudio by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    BiomeriaDialog(
        width = 620.dp,
        accent = Biomeria.colors.engineBedrock,
        onDismissRequest = onDismiss,
        title = { Text("Rendimiento y vídeo") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("RAM máxima de Minecraft", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    availableHeapSizes(deviceRam).forEach { size ->
                        val select = { onHeap(size) }
                        FilterChip(heapMb == size, select, { Text("$size MiB") }, modifier = Modifier.gamepadClick(onClick = select))
                    }
                }
                Text("Escala de resolución", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RenderScale.entries.forEach { option ->
                        val select = { onScale(option) }
                        FilterChip(scale == option, select, { Text("${option.percent} %") },
                            modifier = Modifier.gamepadClick(onClick = select))
                    }
                }
                Text("Renderizador", style = MaterialTheme.typography.titleSmall)
                JavaRenderer.entries.forEach { option ->
                    val select = { onRenderer(option) }
                    FilterChip(renderer == option, select, { Text(option.label) }, modifier = Modifier.gamepadClick(onClick = select))
                }
                Text("Vulkan/Zink requiere integrar el controlador Mesa para Android.", color = Biomeria.colors.textMuted,
                    style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                LauncherButton(if (testingAudio) "Reproduciendo…" else "Probar audio", {
                    testingAudio = true
                    audioTest = null
                    scope.launch {
                        audioTest = withContext(Dispatchers.IO) { NativeJavaBridge.nativeTestAudio() }
                            ?: "OpenAL inició la reproducción. Comprueba si oíste el tono."
                        testingAudio = false
                    }
                }, enabled = !testingAudio)
                audioTest?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { LauncherButton("Listo", onDismiss, primary = true) }
    )
}
