package com.prissma.launcher.engine.bedrock

import android.content.Context
import android.net.Uri
import com.prissma.launcher.engine.imports.LocalArchiveStorage
import com.prissma.launcher.engine.imports.PreparedArchive

internal typealias PreparedBedrockImport = PreparedArchive<BedrockArchive>

internal class BedrockImportStorage(context: Context) {
    private val storage = LocalArchiveStorage(context, "bedrock-imports")

    suspend fun prepare(source: Uri, onVerify: (String) -> Unit): PreparedBedrockImport = storage.prepare(
        source,
        extensionFor = { name ->
            BedrockArchive.fromName(name)?.extension ?: throw InvalidBedrockArchive("Elige un archivo .mcpack, .mcaddon o .mcworld.")
        },
        onVerify = onVerify
    ) { file, checkCancelled ->
        val type = checkNotNull(BedrockArchive.fromName(file.name))
        validateBedrockArchive(file, type, checkCancelled)
        type
    }

    fun discard(prepared: PreparedBedrockImport) = storage.discard(prepared)
}
