package com.prissma.launcher.engine.java

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

data class InstalledJavaRuntime(
    val javaHomeDir: File,
    val libJvmFile: File
)

class JavaRuntimeManager(private val context: Context) {

    companion object {
        const val DEFAULT_JRE21_URL =
            "https://github.com/QuestCraftPlusPlus/android-openjdk-build-multiarch/releases/download/JRE-21/JRE-21.zip"
    }

    private val runtimeRootDir: File
        get() = File(context.filesDir, "runtimes/jre21").apply { mkdirs() }

    /**
     * Busca libjvm.so dentro de la carpeta del runtime para localizar JAVA_HOME automáticamente.
     */
    fun getInstalledRuntime(): InstalledJavaRuntime? {
        val root = runtimeRootDir
        if (!root.exists()) return null

        val libJvm = root.walkTopDown()
            .firstOrNull { it.isFile && it.name == "libjvm.so" }
            ?: return null

        // Normalmente libjvm.so está en <JAVA_HOME>/lib/server/libjvm.so
        val serverDir = libJvm.parentFile ?: return null
        val libDir = serverDir.parentFile ?: return null
        val javaHome = libDir.parentFile ?: root
        return InstalledJavaRuntime(javaHomeDir = javaHome, libJvmFile = libJvm)
    }

    suspend fun installFromUrl(
        downloadUrl: String,
        onProgress: (Float, String) -> Unit
    ): Result<InstalledJavaRuntime> = withContext(Dispatchers.IO) {
        runCatching {
            onProgress(0.05f, "Conectando para descargar JRE 21 ARM64...")
            val conn = (URL(downloadUrl.trim()).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "PRISSMA-Launcher/0.6")
            }

            try {
                if (conn.responseCode !in 200..299) {
                    error("No se pudo descargar JRE 21 (HTTP ${conn.responseCode}). Prueba importarlo como .zip.")
                }
                val totalBytes = conn.contentLengthLong
                conn.inputStream.use { rawIn ->
                    extractZipStream(rawIn, totalBytes, onProgress)
                }
            } finally {
                conn.disconnect()
            }

            getInstalledRuntime()
                ?: error("El paquete descargado no contiene libjvm.so para ARM64.")
        }
    }

    suspend fun installFromLocalUri(
        uri: Uri,
        onProgress: (Float, String) -> Unit
    ): Result<InstalledJavaRuntime> = withContext(Dispatchers.IO) {
        runCatching {
            onProgress(0.05f, "Abriendo paquete JRE 21 seleccionado...")
            val stream = context.contentResolver.openInputStream(uri)
                ?: error("No se pudo leer el archivo seleccionado.")
            stream.use { rawIn ->
                extractZipStream(rawIn, -1L, onProgress)
            }

            getInstalledRuntime()
                ?: error("El archivo .zip seleccionado no contiene libjvm.so.")
        }
    }

    private fun extractZipStream(
        inputStream: InputStream,
        totalBytes: Long,
        onProgress: (Float, String) -> Unit
    ) {
        val targetDir = runtimeRootDir
        targetDir.deleteRecursively()
        targetDir.mkdirs()

        val canonicalTarget = targetDir.canonicalPath
        var extractedFiles = 0

        ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(targetDir, entry.name)
                if (!outFile.canonicalPath.startsWith(canonicalTarget)) {
                    error("Entrada inválida dentro del archivo ZIP.")
                }

                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        zis.copyTo(fos)
                    }
                    // Permisos de ejecución necesarios en Android para librerías .so y binarios
                    outFile.setReadable(true, false)
                    outFile.setExecutable(true, false)
                    extractedFiles++

                    if (extractedFiles % 25 == 0) {
                        val pct = if (totalBytes > 0) 0.50f else (0.15f + (extractedFiles % 70) / 100f)
                        onProgress(pct.coerceIn(0.1f, 0.92f), "Extrayendo runtime JRE 21 ($extractedFiles archivos)...")
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        onProgress(1.0f, "Runtime JRE 21 ARM64 listo ($extractedFiles archivos).")
    }

    /**
     * Construye el Classpath completo (librerías + client.jar) para arrancar Minecraft.
     */
    fun buildClasspath(instanceId: String, clientJarFile: File): String {
        val instanceRoot = File(context.filesDir, "instances/$instanceId")
        val librariesDir = File(instanceRoot, "libraries")
        val jars = mutableListOf<String>()

        if (librariesDir.exists()) {
            librariesDir.walkTopDown()
                .filter { it.isFile && it.extension == "jar" }
                .forEach { jars.add(it.absolutePath) }
        }
        jars.add(clientJarFile.absolutePath)
        return jars.joinToString(File.pathSeparator)
    }

    /**
     * Prepara los argumentos estándar de lanzamiento para Minecraft Java Edition.
     */
    fun buildMinecraftArgs(
        instanceId: String,
        resolvedVersion: String,
        playerName: String,
        playerUuid: String
    ): Array<String> {
        val instanceRoot = File(context.filesDir, "instances/$instanceId")
        val assetsDir = File(instanceRoot, "assets").apply { mkdirs() }
        val assetIndexId = File(assetsDir, "indexes")
            .listFiles()
            ?.firstOrNull { it.extension == "json" }
            ?.nameWithoutExtension
            ?: resolvedVersion

        return arrayOf(
            "--username", playerName,
            "--version", resolvedVersion,
            "--gameDir", instanceRoot.absolutePath,
            "--assetsDir", assetsDir.absolutePath,
            "--assetIndex", assetIndexId,
            "--uuid", playerUuid,
            "--accessToken", "0",
            "--userType", "legacy",
            "--versionType", "PRISSMA"
        )
    }
}
