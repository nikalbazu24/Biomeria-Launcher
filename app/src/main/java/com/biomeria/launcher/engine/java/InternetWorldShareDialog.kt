package com.biomeria.launcher.engine.java

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.biomeria.launcher.ui.components.LauncherButton

@Composable
internal fun InternetWorldShareDialog(room: InternetWorldShare, onDismiss: () -> Unit) {
    val state by room.state.collectAsState()
    val current = state
    var copied by remember(current) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sala por internet") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (current) {
                    InternetRoomState.Idle -> {
                        Text("Comparte tu mundo Java a través de $INTERNET_RELAY_HOST. Los jugadores que tengan la dirección podrán intentar entrar mientras el enlace esté activo.")
                    }
                    InternetRoomState.Starting -> Text("Preparando el mundo y solicitando una dirección…")
                    InternetRoomState.Stopping -> Text("Cerrando la invitación…")
                    is InternetRoomState.Open -> {
                        SelectionContainer { Text(current.address) }
                        Text("Tus amigos pueden usar esta dirección en Multijugador → Conexión directa. La invitación termina al salir del mundo.")
                        LauncherButton("Volver a la partida", onDismiss)
                    }
                    is InternetRoomState.Closed -> Text(current.message)
                }
            }
        },
        confirmButton = {
            when (current) {
                InternetRoomState.Idle, is InternetRoomState.Closed ->
                    LauncherButton("Crear invitación", room::start, primary = true)
                is InternetRoomState.Open -> LauncherButton(if (copied) "Dirección copiada" else "Copiar dirección", {
                    clipboard.setText(AnnotatedString(current.address))
                    copied = true
                }, primary = true)
                InternetRoomState.Starting, InternetRoomState.Stopping -> Unit
            }
        },
        dismissButton = {
            if (current is InternetRoomState.Open || current == InternetRoomState.Starting) {
                LauncherButton(if (current == InternetRoomState.Starting) "Cancelar" else "Cerrar enlace", room::close)
            } else {
                LauncherButton("Volver", onDismiss)
            }
        }
    )
}
