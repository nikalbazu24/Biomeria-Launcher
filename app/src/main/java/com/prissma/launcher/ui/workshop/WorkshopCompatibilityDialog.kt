package com.prissma.launcher.ui.workshop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.prissma.launcher.data.GameInstance
import com.prissma.launcher.data.ModrinthItem
import com.prissma.launcher.data.ModrinthRepository
import com.prissma.launcher.data.WorkshopIncompatibility
import com.prissma.launcher.data.WorkshopInstallPlan
import com.prissma.launcher.ui.components.LauncherButton
import com.prissma.launcher.ui.theme.Prissma
import kotlinx.coroutines.CancellationException

@Composable
internal fun WorkshopCompatibilityDialog(
    item: ModrinthItem,
    instance: GameInstance,
    repository: ModrinthRepository,
    onDismiss: () -> Unit,
    onInstall: (WorkshopInstallPlan) -> Unit
) {
    var result by remember(item.projectId, instance.version, instance.loader) {
        mutableStateOf<Result<WorkshopInstallPlan>?>(null)
    }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(item.projectId, instance, attempt) {
        result = try {
            Result.success(repository.checkCompatibility(item.projectId, item.category, instance.version, instance.configuredModLoader))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    val plan = result?.getOrNull()
    val failure = result?.exceptionOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.title) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    plan != null -> {
                        Text("Compatible con Minecraft ${instance.version} · Fabric", color = Prissma.colors.brandAccent)
                        Text("Versión ${plan.root.versionLabel}", style = MaterialTheme.typography.titleSmall)
                        Text(plan.root.filename)
                        val dependencies = plan.artifacts.drop(1)
                        if (dependencies.isNotEmpty()) {
                            Text("Dependencias obligatorias (${dependencies.size})", style = MaterialTheme.typography.titleSmall)
                            dependencies.forEach { Text(it.filename, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    failure != null -> {
                        Text(if (failure is WorkshopIncompatibility) "Incompatible" else "No se pudo comprobar",
                            color = Prissma.colors.error)
                        Text(failure.message ?: "Vuelve a intentarlo en unos instantes.")
                    }
                    else -> {
                        Text("Comprobando la versión y sus dependencias…")
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        dismissButton = { LauncherButton("Cerrar", onDismiss) },
        confirmButton = {
            if (plan != null) LauncherButton("Instalar en segundo plano", { onInstall(plan) }, primary = true)
            else if (failure != null && failure !is WorkshopIncompatibility) {
                LauncherButton("Comprobar de nuevo", { result = null; attempt += 1 }, primary = true)
            }
        }
    )
}
