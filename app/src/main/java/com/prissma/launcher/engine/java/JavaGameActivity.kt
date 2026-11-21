package com.prissma.launcher.engine.java

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.prissma.launcher.ui.theme.Prissma
import com.prissma.launcher.ui.theme.PrissmaTheme
import java.io.File

class JavaGameActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableImmersiveMode()

        val title = intent.getStringExtra("INSTANCE_TITLE") ?: "Minecraft Java"
        val version = intent.getStringExtra("RESOLVED_VERSION") ?: "1.21.4"
        val playerName = intent.getStringExtra("PLAYER_NAME") ?: "Jugador_Local"
        val playerUuid = intent.getStringExtra("PLAYER_UUID") ?: ""
        val accountType = intent.getStringExtra("ACCOUNT_TYPE") ?: "Offline"
        val mainClass = intent.getStringExtra("MAIN_CLASS") ?: "net.minecraft.client.main.Main"
        val clientJarPath = intent.getStringExtra("CLIENT_JAR_PATH") ?: ""
        val modsCount = intent.getIntExtra("MODS_COUNT", 0)

        val jarFile = File(clientJarPath)
        val jarSizeMb = if (jarFile.exists()) jarFile.length() / (1024.0 * 1024.0) else 0.0

        setContent {
            PrissmaTheme {
                JavaRuntimeViewport(
                    instanceTitle = title,
                    version = version,
                    playerName = playerName,
                    playerUuid = playerUuid,
                    accountType = accountType,
                    mainClass = mainClass,
                    jarSizeMb = jarSizeMb,
                    modsCount = modsCount,
                    onExitGame = { finish() }
                )
            }
        }
    }

    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

@Composable
private fun JavaRuntimeViewport(
    instanceTitle: String,
    version: String,
    playerName: String,
    playerUuid: String,
    accountType: String,
    mainClass: String,
    jarSizeMb: Double,
    modsCount: Int,
    onExitGame: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF05070A))
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.ButtonB || event.key == Key.Escape)
                ) {
                    onExitGame()
                    true
                } else {
                    false
                }
            }
            .padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Barra superior del motor Java
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "JAVA RUNTIME",
                    color = Prissma.colors.engineJava,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .background(Prissma.colors.engineJava.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                        .border(1.dp, Prissma.colors.engineJava, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
                Text(
                    text = "$instanceTitle · MC $version",
                    color = Prissma.colors.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Button(
                onClick = onExitGame,
                colors = ButtonDefaults.buttonColors(containerColor = Prissma.colors.surfaceElevated)
            ) {
                Text(
                    text = "Salir al Carrusel [B]",
                    color = Prissma.colors.textPrimary,
                    fontSize = 12.sp
                )
            }
        }

        // Panel central con los datos reales cargados en memoria
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Prissma.colors.surfaceCard, RoundedCornerShape(16.dp))
                .border(1.dp, Prissma.colors.engineJava, RoundedCornerShape(16.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Paquete Oficial Verificado y Listo",
                color = Prissma.colors.brandAccent,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "• Cliente oficial descargado: client.jar (${String.format("%.1f", jarSizeMb)} MB)",
                color = Prissma.colors.textPrimary,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "• Clase principal detectada: $mainClass",
                color = Prissma.colors.textMuted,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "• Perfil activo: $playerName ($accountType) · UUID: ${playerUuid.take(18)}...",
                color = Prissma.colors.netP2P,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "• Mods listos en carpeta /mods: $modsCount archivos .jar",
                color = Prissma.colors.textPrimary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Siguiente paso (v0.6): Conectar la librería nativa JRE 21 ARM64 + puente gráfico OpenGL/Vulkan para renderizar el mundo 3D dentro de esta ventana.",
                color = Prissma.colors.engineJava,
                fontSize = 12.sp
            )
        }

        // Pie de pantalla
        Text(
            text = "PRISSMA Java Engine Bridge · Modo Pantalla Completa Activo",
            color = Prissma.colors.textMuted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
