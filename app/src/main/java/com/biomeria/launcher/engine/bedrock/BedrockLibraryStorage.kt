package com.biomeria.launcher.engine.bedrock

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

internal class BedrockLibraryStorage(private val context: Context, instanceId: String) {
    private val resolver = context.contentResolver
    private val library = BedrockLibrary(File(context.filesDir, "bedrock-content"),
        File(context.cacheDir, "bedrock-staging"), instanceId)

    suspend fun list() = withContext(Dispatchers.IO) { library.list() }

    suspend fun importArchive(source: Uri, onVerify: () -> Unit): BedrockLibraryImport = withContext(Dispatchers.IO) {
        val name = resolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: source.lastPathSegment.orEmpty().substringAfterLast('/')
        val job = currentCoroutineContext()
        val input = resolver.openInputStream(source) ?: throw IOException("No se pudo leer el archivo seleccionado.")
        input.use { library.importArchive(name, it, { job.ensureActive() }, onVerify) }
    }

    suspend fun export(id: String, destination: Uri) = withContext(Dispatchers.IO) {
        try {
            val item = library.item(id) ?: throw IOException("El archivo ya no está en la biblioteca.")
            val job = currentCoroutineContext()
            item.file.inputStream().use { input ->
                val output = resolver.openOutputStream(destination, "wt") ?: throw IOException("No se pudo abrir el destino.")
                output.use { input.copyArchiveTo(it) { job.ensureActive() } }
            }
            job.ensureActive()
        } catch (failure: Exception) {
            // CreateDocument provided a new file. Remove its partial copy when the export fails.
            runCatching { DocumentsContract.deleteDocument(resolver, destination) }
            throw failure
        }
    }

    suspend fun sharedUri(id: String): Uri = withContext(Dispatchers.IO) {
        val item = library.item(id) ?: throw IOException("El archivo ya no está en la biblioteca.")
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", item.file)
    }

    suspend fun remove(id: String): Boolean = withContext(Dispatchers.IO) { library.remove(id) }
}
