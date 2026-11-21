package com.prissma.launcher.engine.java

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.prissma.launcher.R
import com.prissma.launcher.data.GameInstance
import com.prissma.launcher.data.ModrinthRepository
import com.prissma.launcher.data.WorkshopCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.roundToInt

internal const val RUNTIME_WORK = "download-java-21"
internal const val DOWNLOAD_MESSAGE = "message"
internal const val DOWNLOAD_PERCENT = "percent"

internal class GameDownloads(private val context: Context) {
    private val work = WorkManager.getInstance(context)
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun observe(key: String) = work.getWorkInfosForUniqueWorkFlow(key)

    fun runtime() {
        enqueue(RUNTIME_WORK, "Java 21", "runtime")
    }

    fun game(instance: GameInstance) {
        enqueue(key(instance), "Minecraft " + instance.version, "game", instance.id, instance.version, instance.loader)
    }

    fun mod(instance: GameInstance, projectId: String, title: String, category: WorkshopCategory) {
        enqueue(modKey(instance, projectId), title, "content", instance.id, instance.version,
            instance.configuredModLoader.orEmpty(), projectId, category.name, tag = modTag(instance))
    }

    fun mods(instance: GameInstance) = work.getWorkInfosByTagFlow(modTag(instance))

    fun key(instance: GameInstance) = "download-game-" + instance.id + "-" + instance.version + "-" + instance.loader
    private fun modKey(instance: GameInstance, id: String) = modTag(instance) + "-$id"
    private fun modTag(instance: GameInstance) = "workshop-" + instance.id

    private fun enqueue(
        key: String, title: String, kind: String, id: String = "", version: String = "", loader: String = "",
        projectId: String = "", category: String = "", tag: String = key
    ) {
        val data = workDataOf("kind" to kind, "title" to title, "id" to id, "version" to version, "loader" to loader,
            "projectId" to projectId, "category" to category)
        work.enqueueUniqueWork(key, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<GameDownloadWorker>().setInputData(data)
                .setConstraints(network).addTag(tag)
                .apply { if (projectId.isNotEmpty()) addTag(projectId) }.build())
    }
}

class GameDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = coroutineScope {
        val updates = Channel<Pair<Int, String>>(Channel.CONFLATED)
        val title = inputData.getString("title").orEmpty()
        val background = try {
            setForeground(foreground(title, "Preparando descarga", 0))
            true
        } catch (error: IllegalStateException) {
            // If Android blocks foreground startup, keep short jobs eligible for retry.
            false
        }
        val observer = launch {
            var lastPercent = -1
            for ((percent, message) in updates) {
                if (lastPercent == percent) continue
                lastPercent = percent
                setProgress(workDataOf(DOWNLOAD_PERCENT to percent, DOWNLOAD_MESSAGE to message))
                if (background) setForeground(foreground(title, message, percent))
            }
        }
        val progress: (Float, String) -> Unit = { fraction, message ->
            updates.trySend((fraction.coerceIn(0f, 1f) * 100).roundToInt() to message)
        }
        val context = applicationContext
        try {
            val id = inputData.getString("id").orEmpty()
            val version = inputData.getString("version").orEmpty()
            val loader = inputData.getString("loader").orEmpty()
            val message = when (inputData.getString("kind")) {
                "runtime" -> {
                    val runtimes = JavaRuntimeManager(context)
                    runtimes.installFromUrl(JavaRuntimeManager.DEFAULT_JRE21_URL, progress).getOrThrow()
                    "Java 21 listo"
                }
                "game" -> {
                    MojangDownloader(context).prepare(id, version, loader, progress)
                    "Minecraft $version listo"
                }
                "content" -> {
                    val project = inputData.getString("projectId").orEmpty()
                    val category = WorkshopCategory.valueOf(inputData.getString("category").orEmpty())
                    progress(.10f, "Comprobando compatibilidad")
                    val items = ModrinthRepository(context).install(project, id, category, version, loader.ifBlank { null })
                    if (items == 1) "Archivo listo" else "$items archivos listos, incluidas las dependencias"
                }
                else -> error("Tarea de descarga inválida.")
            }
            Result.success(workDataOf(DOWNLOAD_MESSAGE to message, "title" to title))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (error is IOException && runAttemptCount < 2) Result.retry()
            else Result.failure(workDataOf(DOWNLOAD_MESSAGE to (error.message ?: "Falló la descarga.").take(500), "title" to title))
        } finally {
            updates.close()
            observer.join()
        }
    }

    private suspend fun foreground(title: String, message: String, percent: Int): ForegroundInfo = withContext(Dispatchers.IO) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("game_downloads", "Descargas", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, "game_downloads")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setProgress(100, percent, percent == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        ForegroundInfo(id.hashCode(), notification,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
    }
}
