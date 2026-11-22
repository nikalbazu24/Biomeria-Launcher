package com.prissma.launcher.ui.hub

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.prissma.launcher.BuildConfig
import com.prissma.launcher.data.accounts.DeviceCode
import com.prissma.launcher.data.accounts.AccountError
import com.prissma.launcher.data.accounts.JavaAccountIdentity
import com.prissma.launcher.data.accounts.JavaAccountMode
import com.prissma.launcher.data.accounts.MinecraftAccounts
import com.prissma.launcher.data.accounts.validMicrosoftApplicationId
import com.prissma.launcher.ui.components.LauncherButton
import com.prissma.launcher.ui.theme.Prissma
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException

@Composable
internal fun MicrosoftAccountPanel(enabled: Boolean) {
    val context = LocalContext.current
    val accounts = remember(context) { MinecraftAccounts(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var applicationId by rememberSaveable { mutableStateOf(BuildConfig.MICROSOFT_CLIENT_ID) }
    var linked by remember { mutableStateOf<JavaAccountIdentity?>(null) }
    var deviceCode by remember { mutableStateOf<DeviceCode?>(null) }
    var busy by remember { mutableStateOf(true) }
    var job by remember { mutableStateOf<Job?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(accounts) {
        try {
            linked = accounts.linked(JavaAccountMode.MICROSOFT)
        } catch (error: AccountError) {
            problem = error.message
        } finally {
            busy = false
        }
    }

    linked?.let { account ->
        Text("Conectado como ${account.playerName}", color = Prissma.colors.brandAccent)
        LauncherButton("Cerrar sesión de Microsoft", {
            busy = true
            scope.launch {
                try {
                    accounts.signOut()
                    linked = null
                    problem = null
                } catch (error: IOException) {
                    problem = "No se pudo cerrar la sesión de Microsoft."
                } finally {
                    busy = false
                }
            }
        }, enabled = enabled && !busy)
    }

    if (linked == null && deviceCode == null) {
        if (BuildConfig.MICROSOFT_CLIENT_ID.isEmpty()) {
            OutlinedTextField(applicationId, { applicationId = it.trim().take(36) }, Modifier.fillMaxWidth(),
                label = { Text("ID público de aplicación de Microsoft") }, singleLine = true, enabled = enabled && !busy)
            Text("Usa el ID de una aplicación autorizada para Minecraft Java.", color = Prissma.colors.textMuted,
                style = MaterialTheme.typography.bodySmall)
        }
        LauncherButton("Vincular Microsoft", {
            busy = true
            problem = null
            job = scope.launch {
                try {
                    val code = accounts.start(applicationId)
                    deviceCode = code
                    linked = accounts.authorize(code)
                } catch (error: AccountError) {
                    problem = error.message
                } catch (error: IOException) {
                    problem = "No se pudo conectar con el servicio de cuentas. Revisa tu conexión."
                } finally {
                    deviceCode = null
                    busy = false
                }
            }
        }, enabled = enabled && !busy && validMicrosoftApplicationId(applicationId))
    }

    deviceCode?.let { code ->
        Text("Introduce este código en Microsoft", color = Prissma.colors.textMuted)
        Text(code.userCode, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LauncherButton("Abrir Microsoft", {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(code.verificationUrl)))
                } catch (error: ActivityNotFoundException) {
                    problem = "Android no pudo abrir el navegador."
                } catch (error: SecurityException) {
                    problem = "Android bloqueó la apertura del navegador."
                }
            })
            LauncherButton("Copiar código", { context.copyText("Código de Microsoft", code.userCode) })
            LauncherButton("Cancelar código", { job?.cancel() })
        }
    }
    if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    problem?.let { Text(it, color = Prissma.colors.error, style = MaterialTheme.typography.bodySmall) }
}
