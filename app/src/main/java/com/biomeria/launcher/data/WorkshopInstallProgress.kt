package com.biomeria.launcher.data

internal enum class WorkshopInstallStage(val label: String) {
    CHECKING("Comprobando compatibilidad y dependencias"),
    DOWNLOADING("Descargando"),
    VERIFYING("Verificando archivo"),
    INSTALLING("Instalando")
}

internal data class WorkshopInstallProgress(
    val stage: WorkshopInstallStage,
    val fileName: String? = null,
    val fileNumber: Int = 0,
    val fileCount: Int = 0,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0
) {
    val percent: Int? get() = if (stage == WorkshopInstallStage.DOWNLOADING && totalBytes > 0) {
        (downloadedBytes.coerceIn(0, totalBytes).toDouble() / totalBytes * 100).toInt()
    } else null
}
