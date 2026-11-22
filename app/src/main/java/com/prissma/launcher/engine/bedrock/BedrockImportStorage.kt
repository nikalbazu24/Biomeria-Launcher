package com.prissma.launcher.engine.bedrock

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

internal data class PreparedBedrockImport(val name: String, val type: BedrockArchive, val file: File, val uri: Uri)

internal class BedrockImportStorage(private val context: Context) {
    private val directory = File(context.cacheDir, "bedrock-imports")

    suspend fun prepare(source: Uri, onVerify: (String) -> Unit): PreparedBedrockImport {
        var staging: File? = null
        try {
            return withContext(Dispatchers.IO) {
                val resolver = context.contentResolver
                val name = resolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: source.lastPathSegment.orEmpty().substringAfterLast('/')
                val type = BedrockArchive.fromName(name)
                    ?: throw InvalidBedrockArchive("Elige un archivo .mcpack, .mcaddon o .mcworld.")

                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("No se pudo preparar el archivo de importación.")
                cleanExpired()
                val folder = Files.createTempDirectory(directory.toPath(), "import-").toFile()
                staging = folder
                val stem = name.substringBeforeLast('.').take(160).replace(Regex("[^a-zA-Z0-9 _.-]"), "_")
                    .trim(' ', '.').ifBlank { "Minecraft" }
                val file = File(folder, "$stem.${type.extension}")
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
                validateBedrockArchive(file, type) { job.ensureActive() }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                PreparedBedrockImport(name, type, file, uri)
            }
        } catch (failure: Exception) {
            staging?.deleteRecursively()
            throw failure
        }
    }

    fun discard(prepared: PreparedBedrockImport) {
        prepared.file.parentFile?.deleteRecursively()
    }

    private fun cleanExpired() {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
        directory.listFiles()?.filter { it.name.startsWith("import-") && it.lastModified() < cutoff }
            ?.forEach { it.deleteRecursively() }
    }
}
