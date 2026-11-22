package com.prissma.launcher.ui.workshop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import com.prissma.launcher.data.GameInstance
import com.prissma.launcher.engine.java.DOWNLOAD_DETAIL
import com.prissma.launcher.engine.java.DOWNLOAD_MESSAGE
import com.prissma.launcher.engine.java.DOWNLOAD_PERCENT
import com.prissma.launcher.engine.java.WORKSHOP_INSTANCE_TAG
import com.prissma.launcher.engine.java.WORKSHOP_TITLE_TAG
import com.prissma.launcher.ui.components.LauncherButton
import com.prissma.launcher.ui.components.PanelShape
import com.prissma.launcher.ui.theme.Prissma

internal fun workshopDownloadsLabel(jobs: List<WorkInfo>): String {
    val active = jobs.count { !it.state.isFinished }
    return if (active == 0) "Descargas" else "Descargas · $active"
}

@Composable
internal fun WorkshopDownloadsDialog(jobs: List<WorkInfo>, instances: List<GameInstance>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Descargas del Taller") },
        text = {
            if (jobs.isEmpty()) Text("Las descargas que inicies en el Taller aparecerán aquí.")
            else LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 340.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(jobs.sortedBy { it.state.isFinished }, key = { it.id }) { job ->
                    val title = job.tags.firstOrNull { it.startsWith(WORKSHOP_TITLE_TAG) }?.removePrefix(WORKSHOP_TITLE_TAG)
                        ?: job.outputData.getString("title") ?: job.progress.getString("title") ?: "Contenido del Taller"
                    val instance = instances.firstOrNull { WORKSHOP_INSTANCE_TAG + it.id in job.tags }
                    Column(
                        Modifier.fillMaxWidth().background(Prissma.colors.surfaceElevated, PanelShape)
                            .border(1.dp, Prissma.colors.borderSubtle, PanelShape).padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (instance != null) {
                            Text(instance.title, color = Prissma.colors.textMuted, style = MaterialTheme.typography.bodySmall)
                        }
                        WorkshopJobProgress(job)
                    }
                }
            }
        },
        confirmButton = { LauncherButton("Cerrar", onDismiss, primary = true) }
    )
}

@Composable
internal fun WorkshopJobProgress(job: WorkInfo) {
    val running = job.state == WorkInfo.State.RUNNING
    val percent = if (running) job.progress.getInt(DOWNLOAD_PERCENT, -1) else -1
    val message = when (job.state) {
        WorkInfo.State.ENQUEUED -> if (job.runAttemptCount > 0) "Reintentando cuando haya conexión" else "En cola · esperando conexión o turno"
        WorkInfo.State.BLOCKED -> "En cola"
        WorkInfo.State.RUNNING -> job.progress.getString(DOWNLOAD_MESSAGE) ?: "Preparando descarga"
        WorkInfo.State.SUCCEEDED -> "Instalación completada"
        WorkInfo.State.FAILED -> "Error de instalación"
        WorkInfo.State.CANCELLED -> "Instalación cancelada"
    }
    val detail = when (job.state) {
        WorkInfo.State.RUNNING -> job.progress.getString(DOWNLOAD_DETAIL)
        WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED -> job.outputData.getString(DOWNLOAD_MESSAGE)
        else -> null
    }
    Text(
        if (percent < 0) message else "$message · $percent %",
        color = when (job.state) {
            WorkInfo.State.FAILED -> Prissma.colors.error
            WorkInfo.State.SUCCEEDED -> Prissma.colors.brandAccent
            else -> Prissma.colors.textPrimary
        },
        style = MaterialTheme.typography.bodySmall
    )
    if (!detail.isNullOrBlank()) {
        Text(detail, color = Prissma.colors.textMuted, style = MaterialTheme.typography.bodySmall,
            maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
    if (running) {
        if (percent < 0) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(progress = { percent.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
    }
}
