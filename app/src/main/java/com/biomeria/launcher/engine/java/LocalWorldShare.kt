package com.biomeria.launcher.engine.java

import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.biomeria.launcher.ui.components.LauncherButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface

internal fun DualScreenBridge.openLocalWorld(): Boolean {
    return try {
        File(directory, "lan-result").delete()
        File(directory, "lan-open").createNewFile()
    } catch (_: IOException) {
        false
    }
}

private fun lanAddress(context: Context): String? {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val local = manager?.getLinkProperties(manager.activeNetwork)?.linkAddresses.orEmpty()
        .map { it.address }.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
    return local?.hostAddress ?: runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()
}

@Composable
internal fun LocalWorldShareDialog(context: Context, bridge: DualScreenBridge, onDismiss: () -> Unit) {
    var started by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Int?>(null) }
    var address by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(started) {
        if (!started) return@LaunchedEffect
        if (!withContext(Dispatchers.IO) { bridge.openLocalWorld() }) {
            result = -1
            return@LaunchedEffect
        }
        repeat(40) {
            val port = withContext(Dispatchers.IO) {
                runCatching { File(bridge.directory, "lan-result").readText().toIntOrNull() }.getOrNull()
            }
            if (port != null) {
                result = port
                if (port in 1..65535) address = withContext(Dispatchers.IO) { lanAddress(context)?.let { "$it:$port" } }
                return@LaunchedEffect
            }
            delay(125)
        }
        withContext(Dispatchers.IO) { File(bridge.directory, "lan-open").delete() }
        result = -1
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sala LAN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val port = result
                when {
                    !started -> Text("Abre tu mundo a jugadores de Minecraft Java conectados a la misma red. La sala se cierra al salir del mundo.")
                    port == null -> Text("Abriendo el mundo a la red local…")
                    port == 0 -> Text("Entra a un mundo de un jugador para compartirlo.")
                    port in 1..65535 -> {
                        Text(address ?: "Puerto $port")
                        Text("Comparte la dirección con tus amigos para que la usen en Conexión directa.")
                    }
                    else -> Text("Minecraft no pudo abrir la sala LAN.")
                }
            }
        },
        confirmButton = {
            val link = address
            when {
                !started -> LauncherButton("Abrir sala", { started = true }, primary = true)
                link != null -> LauncherButton(if (copied) "Dirección copiada" else "Copiar dirección", {
                    clipboard.setText(AnnotatedString(link))
                    copied = true
                }, primary = true)
            }
        },
        dismissButton = { LauncherButton("Cerrar", onDismiss) }
    )
}
