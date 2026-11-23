package com.biomeria.launcher.engine.java

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun GameKeyboardDialog(onDismiss: () -> Unit, onSubmit: (String, Boolean) -> Unit) {
    val focus = remember { FocusRequester() }
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Teclado de Minecraft") },
        text = {
            val keyboard = LocalSoftwareKeyboardController.current
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= 256) text = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                label = { Text("Texto para el chat o campo activo") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit(text, true) })
            )
            LaunchedEffect(Unit) {
                focus.requestFocus()
                withFrameNanos { }
                keyboard?.show()
            }
        },
        dismissButton = { TextButton(onClick = { onSubmit(text, false) }, enabled = text.isNotEmpty()) { Text("Insertar") } },
        confirmButton = { TextButton(onClick = { onSubmit(text, true) }) { Text("Enviar con Intro") } }
    )
}
