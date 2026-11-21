package com.prissma.launcher.ui.launch

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prissma.launcher.engine.java.JavaGameActivity
import com.prissma.launcher.engine.java.JavaRuntimeManager
import com.prissma.launcher.engine.java.MojangDownloader
import com.prissma.launcher.engine.java.PreparedJavaSession
import com.prissma.launcher.ui.hub.GameInstance
import com.prissma.launcher.ui.theme.Prissma
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

@Composable
fun JavaLaunchScreen(
    instance: GameInstance,
    playerName: String,
    accountType: String,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloader = remember { MojangDownloader(context) }
    val runtimeManager = remember { JavaRuntimeManager(context) }

    var progress by remember { mutableFloatStateOf(0.02f) }
    var statusText by remember { mutableStateOf("Preparando archivos oficiales de Minecraft...") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var readySession by remember { mutableStateOf<PreparedJavaSession?>(null) }
    var installedRuntime by remember { mutableStateOf(runtimeManager.getInstalledRuntime()) }
    var useVulkanZink by remember { mutableStateOf(true) }
    var isInstallingRuntime by remember { mutableStateOf(false) }
    var isOpeningGame by remember { mutableStateOf(false) }
    var retryCounter by remember { mutableIntStateOf(0) }

    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        label = "DownloadProgress"
    )

    // Selector de archivos para importar JRE-21.zip desde la carpeta Descargas de la consola
    val zipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isInstallingRuntime = true
                errorText = null
                runtimeManager.installFromLocalUri(uri) { pct, msg ->
                    progress = pct
                    statusText = msg
                }.onSuccess { rt ->
                    installedRuntime = rt
                    statusText = "JRE 21 ARM64 importado correctamente."
                }.onFailure { err ->
                    errorText = err.message
                }
                isInstallingRuntime = false
            }
        }
    }

    suspend fun openGameViewport(session: PreparedJavaSession) {
        val instanceDir = File(context.filesDir, "instances/${session.instanceId}")
        val classpath = runtimeManager.buildClasspath(session.instanceId, session.clientJarFile)
        val gameArgs = runtimeManager.buildMinecraftArgs(
            instanceId = session.instanceId,
            resolvedVersion = session.resolvedVersion,
            playerName = session.playerName,
            playerUuid = session.playerUuid
        )

        val intent = Intent(context, JavaGameActivity::class.java).apply {
            putExtra("INSTANCE_TITLE", session.instanceTitle)
            putExtra("RESOLVED_VERSION", session.resolvedVersion)
            putExtra("MAIN_CLASS", session.mainClass)
            putExtra("GAME_DIR_PATH", instanceDir.absolutePath)
            putExtra("CLASSPATH", classpath)
            putExtra("GAME_ARGS", gameArgs)
            putExtra("LIBJVM_PATH", installedRuntime?.libJvmFile?.absolutePath ?: "")
            putExtra("JAVA_HOME_PATH", installedRuntime?.javaHomeDir?.absolutePath ?: "")
            putExtra("USE_VULKAN_ZINK", useVulkanZink)
        }
        context.startActivity(intent)
    }

    LaunchedEffect(instance.id, retryCounter) {
        errorText = null
        readySession = null
        downloader.prepareJavaInstance(
            instanceId = instance.id,
            instanceTitle = instance.title,
            requestedVersion = instance.version,
            playerName = playerName,
            accountType = accountType,
            onProgress = { pct, msg ->
                progress = pct
                statusText = msg
            }
        ).onSuccess { session ->
            readySession = session
            statusText = "Archivos del juego verificados (${session.resolvedVersion})."
        }.onFailure { err ->
            errorText = err.message ?: "Error descargando archivos de Mojang"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Prissma.colors.surfaceVoid)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.ButtonB || event.key == Key.Escape)
                ) {
                    onBack()
                    true
                } else {
                    false
                }
            }
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "MOTOR JAVA ARM64 · OPENGL / VULKAN",
            color = Prissma.colors.engineJava,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .background(Prissma.colors.engineJava.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                .border(1.dp, Prissma.colors.engineJava, RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = instance.title,
            color = Prissma.colors.textPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Selector de puente gráfico: Vulkan (Zink) vs OpenGL ES 3.2
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(
                selected = useVulkanZink,
                onClick = { useVulkanZink = true },
                label = { Text("Vulkan (Zink) · Recomendado") }
            )
            FilterChip(
                selected = !useVulkanZink,
                onClick = { useVulkanZink = false },
                label = { Text("OpenGL ES 3.2 · Clásico") }
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Estado del paquete JRE 21 ARM64
        Box(
            modifier = Modifier
                .fillMaxWidth(0.80f)
                .background(Prissma.colors.surfaceCard, RoundedCornerShape(12.dp))
                .border(
                    1.dp,
                    if (installedRuntime != null) Prissma.colors.brandAccent else Prissma.colors.borderSubtle,
                    RoundedCornerShape(12.dp)
                )
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (installedRuntime != null) {
                            "Runtime JRE 21 ARM64: Instalado ✓"
                        } else {
                            "Runtime JRE 21 ARM64: No instalado"
                        },
                        color = if (installedRuntime != null) Prissma.colors.brandAccent else Prissma.colors.textPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (installedRuntime != null) {
                            "Motor nativo libjvm.so listo en memoria interna."
                        } else {
                            "Descárgalo directo o importa un archivo JRE-21.zip."
                        },
                        color = Prissma.colors.textMuted,
                        fontSize = 11.sp
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !isInstallingRuntime,
                        onClick = {
                            scope.launch {
                                isInstallingRuntime = true
                                errorText = null
                                runtimeManager.installFromUrl(
                                    JavaRuntimeManager.DEFAULT_JRE21_URL
                                ) { pct, msg ->
                                    progress = pct
                                    statusText = msg
                                }.onSuccess { rt ->
                                    installedRuntime = rt
                                }.onFailure { err ->
                                    errorText = err.message
                                }
                                isInstallingRuntime = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Prissma.colors.engineJava)
                    ) {
                        Text(
                            text = if (installedRuntime != null) "Reinstalar JRE" else "Descargar JRE 21",
                            color = Prissma.colors.surfaceVoid,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedButton(
                        enabled = !isInstallingRuntime,
                        onClick = { zipPickerLauncher.launch(arrayOf("application/zip", "*/*")) }
                    ) {
                        Text("Importar .zip", color = Prissma.colors.textPrimary, fontSize = 11.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (errorText != null) {
            Text(
                text = errorText!!,
                color = Color(0xFFF87171),
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        LinearProgressIndicator(
            progress = { animatedProgress },
            color = Prissma.colors.engineJava,
            trackColor = Prissma.colors.surfaceElevated,
            modifier = Modifier
                .fillMaxWidth(0.80f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "${(animatedProgress * 100).toInt()}% · $statusText",
            color = Prissma.colors.textPrimary,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )

        Spacer(modifier = Modifier.height(18.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            readySession?.let { session ->
                Button(
                    enabled = !isInstallingRuntime && !isOpeningGame,
                    onClick = {
                        isOpeningGame = true
                        errorText = null
                        scope.launch {
                            try {
                                openGameViewport(session)
                            } catch (error: IOException) {
                                errorText = error.message ?: "No se pudo preparar el classpath de LWJGL."
                            } finally {
                                isOpeningGame = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Prissma.colors.brandAccent)
                ) {
                    Text(
                        text = if (installedRuntime != null) {
                            "Arrancar Juego 3D"
                        } else {
                            "Probar Lienzo GPU (Sin JRE)"
                        },
                        color = Prissma.colors.surfaceVoid,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (errorText != null) {
                OutlinedButton(onClick = { retryCounter += 1 }) {
                    Text("Reintentar", color = Prissma.colors.textPrimary)
                }
            }

            OutlinedButton(onClick = onBack) {
                Text("Volver [B]", color = Prissma.colors.textMuted)
            }
        }
    }
}
