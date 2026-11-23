package com.biomeria.launcher.engine.java

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.biomeria.launcher.R
import com.biomeria.launcher.data.EngineType
import com.biomeria.launcher.data.GameInstance
import com.biomeria.launcher.data.InstanceStorage
import com.biomeria.launcher.data.ModrinthRepository
import com.biomeria.launcher.data.ModpackImports
import com.biomeria.launcher.data.WorkshopCategory
import com.biomeria.launcher.data.WorkshopInstallStage
import com.biomeria.launcher.data.shouldRetryWorkshopDownload
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
internal const val DOWNLOAD_DETAIL = "detail"
internal const val WORKSHOP_PROJECT_TAG = "workshop-project:"
internal const val WORKSHOP_TITLE_TAG = "workshop-title:"
internal const val WORKSHOP_INSTANCE_TAG = "workshop-"
internal const val WORKSHOP_CATEGORY_TAG = "workshop-category:"
internal const val WORKSHOP_PLAN_TAG = "workshop-plan:"
internal const val WORKSHOP_REQUEST_TAG = "workshop-request:"
internal const val MODPACK_IMPORT_TAG = "modpack-import"
private const val ALL_WORKSHOP_DOWNLOADS = "workshop-content-downloads"

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

    fun importModpack(instance: GameInstance) {
        work.enqueueUniqueWork("$MODPACK_IMPORT_TAG-${instance.id}", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<GameDownloadWorker>()
                .setInputData(workDataOf(
                    "kind" to MODPACK_IMPORT_TAG, "id" to instance.id, "title" to instance.title,
                    "version" to instance.version, "loader" to instance.loader
                ))
                .setConstraints(network)
                .addTag(ALL_WORKSHOP_DOWNLOADS)
                .addTag(MODPACK_IMPORT_TAG)
                .addTag(WORKSHOP_TITLE_TAG + instance.title)
                .build())
    }

    fun mod(instance: GameInstance, projectId: String, title: String, category: WorkshopCategory, pins: String = "") {
        enqueue(modKey(instance, projectId), title, "content", instance.id, instance.version,
            instance.configuredModLoader.orEmpty(), projectId, category.name, pins, tag = modTag(instance))
    }

    fun mods(instance: GameInstance) = work.getWorkInfosByTagFlow(modTag(instance))

    fun workshopDownloads(instances: List<GameInstance>) = work.getWorkInfosFlow(
        WorkQuery.Builder.fromTags(listOf(ALL_WORKSHOP_DOWNLOADS) + instances.map(::modTag)).build()
    )

    fun key(instance: GameInstance) = "download-game-" + instance.id + "-" + instance.version + "-" + instance.loader
    private fun modKey(instance: GameInstance, id: String) = modTag(instance) + "-$id"
    private fun modTag(instance: GameInstance) = WORKSHOP_INSTANCE_TAG + instance.id

    private fun enqueue(
        key: String, title: String, kind: String, id: String = "", version: String = "", loader: String = "",
        projectId: String = "", category: String = "", pins: String = "", tag: String = key
    ) {
        val data = workDataOf("kind" to kind, "title" to title, "id" to id, "version" to version, "loader" to loader,
            "projectId" to projectId, "category" to category, "pins" to pins)
        work.enqueueUniqueWork(key, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<GameDownloadWorker>().setInputData(data)
                .setConstraints(network).addTag(tag)
                .apply {
                    if (projectId.isNotEmpty()) {
                        addTag(ALL_WORKSHOP_DOWNLOADS)
                        addTag(WORKSHOP_PROJECT_TAG + projectId)
                        addTag(WORKSHOP_TITLE_TAG + title.take(120))
                        addTag(WORKSHOP_CATEGORY_TAG + category)
                        if (pins.isNotEmpty()) addTag(WORKSHOP_PLAN_TAG + pins)
                        addTag(WORKSHOP_REQUEST_TAG + System.currentTimeMillis())
                    }
                }.build())
    }
}

private data class DownloadUpdate(val percent: Int, val message: String, val detail: String = "")

class GameDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = coroutineScope {
        val updates = Channel<DownloadUpdate>(Channel.CONFLATED)
        val title = inputData.getString("title").orEmpty()
        val kind = inputData.getString("kind")
        val isWorkshop = kind == "content"
        val id = inputData.getString("id").orEmpty()
        val version = inputData.getString("version").orEmpty()
        val loader = inputData.getString("loader").orEmpty()
        val background = try {
            setForeground(foreground(title, DownloadUpdate(-1,
                if (isWorkshop) "Esperando turno de instalación" else "Preparando descarga")))
            true
        } catch (error: IllegalStateException) {
            // If Android blocks foreground startup, keep short jobs eligible for retry.
            false
        }
        val observer = launch {
            var lastUpdate: DownloadUpdate? = null
            for (update in updates) {
                if (lastUpdate == update) continue
                lastUpdate = update
                setProgress(workDataOf(DOWNLOAD_PERCENT to update.percent, DOWNLOAD_MESSAGE to update.message,
                    DOWNLOAD_DETAIL to update.detail, "title" to title))
                if (background) setForeground(foreground(title, update))
            }
        }
        val progress: (Float, String) -> Unit = { fraction, message ->
            updates.trySend(DownloadUpdate((fraction.coerceIn(0f, 1f) * 100).roundToInt(), message))
        }
        val context = applicationContext
        try {
            val message = when (kind) {
                "runtime" -> {
                    val runtimes = JavaRuntimeManager(context)
                    runtimes.installFromUrl(JavaRuntimeManager.DEFAULT_JRE21_URL, progress).getOrThrow()
                    "Java 21 listo"
                }
                "game" -> {
                    MojangDownloader(context).prepare(id, version, loader, progress)
                    "Minecraft $version listo"
                }
                MODPACK_IMPORT_TAG -> {
                    val imports = ModpackImports(context)
                    imports.install(GameInstance(id, title, EngineType.JAVA, version, loader), progress)
                    imports.removeArchive(id)
                    "Modpack listo en la biblioteca"
                }
                "content" -> {
                    val project = inputData.getString("projectId").orEmpty()
                    val category = WorkshopCategory.valueOf(inputData.getString("category").orEmpty())
                    updates.trySend(DownloadUpdate(-1, "Esperando turno de instalación"))
                    val configuredLoader = loader.ifBlank { null }
                    val storage = InstanceStorage(context)
                    val items = ModrinthRepository(context).install(
                        project, id, category, version, configuredLoader, inputData.getString("pins").orEmpty(),
                        validateInstance = {
                            val instance = storage.loadInstances().firstOrNull { it.id == id }
                            check(instance != null && instance.engine == EngineType.JAVA && instance.version == version &&
                                instance.configuredModLoader == configuredLoader) {
                                "La instancia cambió. Revisa qué mods son compatibles en el Taller."
                            }
                        }
                    ) { status ->
                        val detail = buildList {
                            if (status.fileNumber > 0) add("Archivo ${status.fileNumber} de ${status.fileCount}")
                            status.fileName?.let { add(it.take(120)) }
                            if (status.stage == WorkshopInstallStage.DOWNLOADING) {
                                val done = Formatter.formatShortFileSize(context, status.downloadedBytes)
                                val total = status.totalBytes.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) }
                                add(if (total == null) done else "$done / $total")
                            }
                        }.joinToString(" · ")
                        updates.trySend(DownloadUpdate(status.percent ?: -1, status.stage.label, detail))
                    }
                    if (items == 1) "1 archivo instalado" else "$items archivos instalados, incluidas las dependencias"
                }
                else -> error("Tarea de descarga inválida.")
            }
            Result.success(workDataOf(DOWNLOAD_MESSAGE to message, "title" to title, "id" to id,
                "version" to version, "loader" to loader))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val retry = if (isWorkshop) shouldRetryWorkshopDownload(error, runAttemptCount)
                else error is IOException && runAttemptCount < 2
            if (retry) Result.retry()
            else {
                if (kind == MODPACK_IMPORT_TAG) runCatching { ModpackImports(context).removeArchive(id) }
                Result.failure(workDataOf(DOWNLOAD_MESSAGE to (error.message ?: "Falló la descarga.").take(500), "title" to title))
            }
        } finally {
            updates.close()
            observer.join()
        }
    }

    private suspend fun foreground(title: String, update: DownloadUpdate): ForegroundInfo = withContext(Dispatchers.IO) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("game_downloads", "Descargas", NotificationManager.IMPORTANCE_LOW))
        val summary = if (update.percent < 0) update.message else update.message + " · " + update.percent + " %"
        val notification = NotificationCompat.Builder(applicationContext, "game_downloads")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(summary)
            .setProgress(100, update.percent.coerceAtLeast(0), update.percent < 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (update.detail.isNotBlank()) {
            notification.setStyle(NotificationCompat.BigTextStyle().bigText("$summary\n" + update.detail))
        }
        ForegroundInfo(id.hashCode(), notification.build(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
    }
}
