package com.prissma.launcher.engine.imports

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit

internal data class PreparedArchive<T>(val name: String, val content: T, val file: File, val uri: Uri)

internal class LocalArchiveStorage(private val context: Context, cacheFolder: String) {
    private val directory = File(context.cacheDir, cacheFolder)

    suspend fun <T> prepare(
        source: Uri,
        extensionFor: (String) -> String,
        onVerify: (String) -> Unit,
        validate: (File, checkCancelled: () -> Unit) -> T
    ): PreparedArchive<T> {
        var staging: File? = null
        try {
            return withContext(Dispatchers.IO) {
                val resolver = context.contentResolver
                val name = resolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: source.lastPathSegment.orEmpty().substringAfterLast('/')
                val extension = extensionFor(name)

                if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
                    throw IOException("No se pudo preparar el archivo de importación.")
                }
                cleanExpired()
                val folder = Files.createTempDirectory(directory.toPath(), "import-").toFile()
                staging = folder
                val stem = name.substringBeforeLast('.').take(160).replace(Regex("[^a-zA-Z0-9 _.-]"), "_")
                    .trim(' ', '.').ifBlank { "Minecraft" }
                val file = File(folder, "$stem.$extension")
                val job = currentCoroutineContext()
                val sourceStream = resolver.openInputStream(source) ?: throw IOException("No se pudo leer el archivo seleccionado.")
                sourceStream.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            job.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
                withContext(Dispatchers.Main) { onVerify(name) }
                val content = validate(file) { job.ensureActive() }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                PreparedArchive(name, content, file, uri)
            }
        } catch (failure: Exception) {
            staging?.deleteRecursively()
            throw failure
        }
    }

    fun discard(prepared: PreparedArchive<*>) {
        prepared.file.parentFile?.deleteRecursively()
    }

    private fun cleanExpired() {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
        directory.listFiles()?.filter { it.name.startsWith("import-") && it.lastModified() < cutoff }
            ?.forEach { it.deleteRecursively() }
    }
}
