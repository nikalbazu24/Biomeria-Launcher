package com.prissma.launcher.ui.launch

import android.content.Intent
import androidx.activity.compose.BackHandler
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
import com.prissma.launcher.engine.java.MojangDownloader
import com.prissma.launcher.engine.java.PreparedJavaSession
import com.prissma.launcher.ui.hub.GameInstance
import com.prissma.launcher.ui.theme.Prissma
import kotlinx.coroutines.delay

@Composable
fun JavaLaunchScreen(
    instance: GameInstance,
    playerName: String,
    accountType: String,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val downloader = remember { MojangDownloader(context) }

    var progress by remember { mutableFloatStateOf(0.02f) }
    var statusText by remember { mutableStateOf("Preparando descarga de Minecraft Java...") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var readySession by remember { mutableStateOf<PreparedJavaSession?>(null) }
    var retryCounter by remember { mutableIntStateOf(0) }

    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        label = "DownloadProgress"
    )

    fun launchActivity(session: PreparedJavaSession) {
        val intent = Intent(context, JavaGameActivity::class.java).apply {
            putExtra("INSTANCE_TITLE", session.instanceTitle)
            putExtra("RESOLVED_VERSION", session.resolvedVersion)
            putExtra("PLAYER_NAME", session.playerName)
            putExtra("PLAYER_UUID", session.playerUuid)
            putExtra("ACCOUNT_TYPE", session.accountType)
            putExtra("MAIN_CLASS", session.mainClass)
            putExtra("CLIENT_JAR_PATH", session.clientJarFile.absolutePath)
            putExtra("MODS_COUNT", session.installedModsCount)
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
            delay(500L)
            launchActivity(session)
        }.onFailure { err ->
            errorText = err.message ?: "Error desconocido descargando archivos de Mojang"
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
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "MOTOR JAVA EDITION",
            color = Prissma.colors.engineJava,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .background(Prissma.colors.engineJava.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                .border(1.dp, Prissma.colors.engineJava, RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = instance.title,
            color = Prissma.colors.textPrimary,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Jugador: $playerName ($accountType) · Versión objetivo: ${instance.version}",
            color = Prissma.colors.textMuted,
            fontSize = 13.sp
        )

        Spacer(modifier = Modifier.height(28.dp))

        if (errorText != null) {
            Text(
                text = errorText!!,
                color = Color(0xFFF87171),
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { retryCounter += 1 },
                    colors = ButtonDefaults.buttonColors(containerColor = Prissma.colors.engineJava)
                ) {
                    Text("Reintentar Descarga", color = Prissma.colors.surfaceVoid, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = onBack) {
                    Text("Volver [B]", color = Prissma.colors.textPrimary)
                }
            }
        } else {
            // Barra de progreso real
            LinearProgressIndicator(
                progress = { animatedProgress },
                color = Prissma.colors.engineJava,
                trackColor = Prissma.colors.surfaceElevated,
                modifier = Modifier
                    .fillMaxWidth(0.72f)
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "${(animatedProgress * 100).toInt()}% · $statusText",
                color = Prissma.colors.textPrimary,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                readySession?.let { session ->
                    Button(
                        onClick = { launchActivity(session) },
                        colors = ButtonDefaults.buttonColors(containerColor = Prissma.colors.brandAccent)
                    ) {
                        Text(
                            text = "Abrir Ventana de Juego",
                            color = Prissma.colors.surfaceVoid,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                OutlinedButton(onClick = onBack) {
                    Text("Cancelar / Volver [B]", color = Prissma.colors.textMuted)
                }
            }
        }
    }
}
