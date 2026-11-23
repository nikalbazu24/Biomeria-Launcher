package com.prissma.launcher.ui.hub

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.prissma.launcher.data.accounts.AccountError
import com.prissma.launcher.data.accounts.ElyLoginError
import com.prissma.launcher.data.accounts.JavaAccountIdentity
import com.prissma.launcher.data.accounts.JavaAccountMode
import com.prissma.launcher.data.accounts.MinecraftAccounts
import com.prissma.launcher.ui.components.LauncherButton
import com.prissma.launcher.ui.theme.Prissma
import kotlinx.coroutines.launch
import java.io.IOException

@Composable
internal fun ElyAccountPanel(enabled: Boolean) {
    val context = LocalContext.current
    val accounts = remember(context) { MinecraftAccounts(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var linked by remember { mutableStateOf<JavaAccountIdentity?>(null) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var needsCode by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(true) }
    var problem by remember { mutableStateOf<String?>(null) }
    val editable = enabled && !busy

    LaunchedEffect(accounts) {
        try {
            linked = accounts.linked(JavaAccountMode.ELY_BY)
        } catch (error: AccountError) {
            problem = error.message
        } finally {
            busy = false
        }
    }

    val account = linked
    if (account != null) {
        Text("Conectado como ${account.playerName}", color = Prissma.colors.brandAccent)
        LauncherButton("Cerrar sesión de Ely.by", {
            busy = true
            scope.launch {
                try {
                    val revoked = accounts.signOutEly()
                    linked = null
                    problem = if (revoked) null else "Se borró la sesión de Biomeria. Ely.by no confirmó la revocación remota."
                } catch (error: IOException) {
                    problem = "No se pudo cerrar la sesión de Ely.by."
                } finally {
                    busy = false
                }
            }
        }, enabled = editable)
    } else {
        OutlinedTextField(username, { username = it.take(254) }, Modifier.fillMaxWidth(),
            label = { Text("Usuario o correo de Ely.by") }, singleLine = true, enabled = editable)
        OutlinedTextField(password, { password = it.take(256) }, Modifier.fillMaxWidth(),
            label = { Text("Contraseña de Ely.by") }, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, enabled = editable)
        if (needsCode) {
            OutlinedTextField(code, { code = it.filter { digit -> digit in '0'..'9' }.take(6) }, Modifier.fillMaxWidth(),
                label = { Text("Código de verificación") }, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), singleLine = true, enabled = editable)
        }
        LauncherButton("Vincular Ely.by", {
            busy = true
            problem = null
            scope.launch {
                try {
                    linked = accounts.authorizeEly(username, password, code)
                    username = ""
                    password = ""
                    code = ""
                    needsCode = false
                } catch (error: ElyLoginError) {
                    needsCode = needsCode || error.needsCode
                    code = ""
                    problem = error.message
                } catch (error: AccountError) {
                    problem = error.message
                } catch (error: IOException) {
                    problem = "No se pudo conectar con Ely.by. Revisa tu conexión."
                } finally {
                    busy = false
                }
            }
        }, enabled = editable && username.isNotBlank() && password.isNotEmpty() && (!needsCode || code.length == 6))
    }
    if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    problem?.let { Text(it, color = Prissma.colors.error, style = MaterialTheme.typography.bodySmall) }
}
