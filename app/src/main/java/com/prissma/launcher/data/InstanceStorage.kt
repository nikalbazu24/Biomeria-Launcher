package com.prissma.launcher.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import java.util.UUID
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

data class SavedProfile(
    val playerName: String,
    val accountType: String,
    val displayName: String = "",
    val bio: String = "",
    val playStyle: String = "",
    val language: String = "",
    val avatar: String? = null
) {
    val label: String get() = displayName.ifBlank { playerName }
}

class InstanceStorage(private val context: Context) {

    private val catalogFile = File(context.filesDir, "prissma_catalog.json")
    private val profileFile = File(context.filesDir, "prissma_profile.json")

    // Carpeta accesible para frontends (Cocoon / exploradores) sin pedir permisos especiales
    val exportInstancesDir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "PRISSMA/instances")
            .apply { mkdirs() }

    fun loadProfile(): SavedProfile {
        if (!profileFile.exists()) {
            return SavedProfile(playerName = "Jugador_Local", accountType = "Offline")
        }
        return runCatching {
            val obj = JSONObject(profileFile.readText(Charsets.UTF_8))
            SavedProfile(
                playerName = obj.optString("playerName", "Jugador_Local"),
                accountType = obj.optString("accountType", "Offline"),
                displayName = obj.optString("displayName"),
                bio = obj.optString("bio"),
                playStyle = obj.optString("playStyle"),
                language = obj.optString("language"),
                avatar = obj.optString("avatar").takeIf(avatarName::matches)
            )
        }.getOrDefault(SavedProfile(playerName = "Jugador_Local", accountType = "Offline"))
    }

    private val avatarName = Regex("avatar-[a-f0-9-]{36}\\.jpg")

    fun profileAvatar(profile: SavedProfile): File? =
        profile.avatar?.takeIf(avatarName::matches)?.let { File(context.filesDir, it) }?.takeIf { it.isFile }

    fun saveProfile(profile: SavedProfile, photo: Uri? = null): SavedProfile {
        val previous = loadProfile()
        val imported = photo?.let(::copyProfilePhoto)
        val saved = if (imported == null) profile else profile.copy(avatar = imported.name)
        val obj = JSONObject().apply {
            put("playerName", saved.playerName)
            put("accountType", saved.accountType)
            put("displayName", saved.displayName)
            put("bio", saved.bio)
            put("playStyle", saved.playStyle)
            put("language", saved.language)
            put("avatar", saved.avatar.orEmpty())
        }
        val atomic = AtomicFile(profileFile)
        try {
            val output = atomic.startWrite()
            try {
                output.write(obj.toString(2).toByteArray(Charsets.UTF_8))
                atomic.finishWrite(output)
            } catch (failure: Exception) {
                atomic.failWrite(output)
                throw failure
            }
        } catch (failure: Exception) {
            imported?.delete()
            throw failure
        }
        if (previous.avatar != saved.avatar) profileAvatar(previous)?.delete()
        return saved
    }

    private fun copyProfilePhoto(uri: Uri): File {
        val resolver = context.contentResolver
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        val side = maxOf(options.outWidth, options.outHeight)
        require(side > 0) { "No se pudo leer la foto elegida." }
        var sample = 1
        while (side / sample > 512) sample *= 2
        val bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("No se pudo abrir la foto elegida.")
        val file = File(context.filesDir, "avatar-" + UUID.randomUUID() + ".jpg")
        try {
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it)) }
            return file
        } catch (failure: Exception) {
            file.delete()
            throw failure
        } finally {
            bitmap.recycle()
        }
    }

    fun loadInstances(): List<GameInstance> {
        if (!catalogFile.exists()) {
            val defaults = defaultInstances()
            saveInstances(defaults)
            return defaults
        }
        return runCatching {
            val array = JSONArray(catalogFile.readText(Charsets.UTF_8))
            val list = mutableListOf<GameInstance>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val engineName = obj.optString("engine", "JAVA")
                val engine = runCatching { EngineType.valueOf(engineName) }
                    .getOrDefault(EngineType.JAVA)
                list.add(
                    GameInstance(
                        id = obj.getString("id"),
                        title = obj.getString("title"),
                        engine = engine,
                        version = obj.optString("version", "1.21.4"),
                        loader = obj.optString("loader", engine.defaultLoader),
                        playtimeHours = obj.optDouble("playtimeHours", 0.0).toFloat(),
                        friendsOnline = obj.optInt("friendsOnline", 0)
                    )
                )
            }
            list
        }.getOrElse { defaultInstances() }
    }

    fun saveInstances(instances: List<GameInstance>) {
        val array = JSONArray()
        instances.forEach { inst ->
            array.put(
                JSONObject().apply {
                    put("id", inst.id)
                    put("title", inst.title)
                    put("engine", inst.engine.name)
                    put("version", inst.version)
                    put("loader", inst.loader)
                    put("playtimeHours", inst.playtimeHours.toDouble())
                    put("friendsOnline", inst.friendsOnline)
                }
            )
        }
        catalogFile.writeText(array.toString(2), Charsets.UTF_8)
        syncExportedCartridges(instances)
    }

    /**
     * Genera archivos .mcinst ligeros para que Cocoon, iiSU o Díptico detecten cada instancia.
     */
    fun syncExportedCartridges(instances: List<GameInstance>) {
        runCatching {
            val dir = exportInstancesDir
            val validFiles = mutableSetOf<String>()

            instances.forEach { inst ->
                val slug = slugify(inst.title, inst.id)
                val fileName = "$slug.mcinst"
                validFiles.add(fileName)

                File(dir, fileName).writeText(cartridge(inst).toString(2), Charsets.UTF_8)
            }

            dir.listFiles()?.filter { it.extension == "mcinst" && it.name !in validFiles }
                ?.forEach { it.delete() }
        }
    }

    fun cartridgeName(instance: GameInstance) = "${slugify(instance.title, instance.id)}.mcinst"

    fun launchLink(instance: GameInstance): String = Uri.Builder()
        .scheme("prissma").authority("launch").appendQueryParameter("instance_id", instance.id)
        .build().toString()

    fun exportCartridge(instance: GameInstance, destination: Uri) {
        val output = context.contentResolver.openOutputStream(destination, "wt")
            ?: error("No se pudo abrir el destino del cartucho.")
        output.bufferedWriter(Charsets.UTF_8).use { it.write(cartridge(instance).toString(2)) }
    }

    private fun cartridge(instance: GameInstance) = JSONObject().apply {
        put("schema_version", 1)
        put("instance_id", instance.id)
        put("slug", slugify(instance.title, instance.id))
        put("title", instance.title)
        put("engine", instance.engine.name.lowercase(Locale.US))
        put("mc_version", instance.version)
        put("loader", instance.loader)
        put("deep_link", launchLink(instance))
    }

    /**
     * Extrae el ID de la instancia cuando un frontend abre un archivo .mcinst.
     */
    fun extractInstanceIdFromUri(uri: Uri): String? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                JSONObject(reader.readText()).optString("instance_id").takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    private fun slugify(title: String, id: String): String {
        val base = title.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "instancia" }
        return "$base-${id.take(6)}"
    }

    private fun defaultInstances(): List<GameInstance> = listOf(
        GameInstance(
            id = "survival-mods-java",
            title = "Survival Con Mods",
            engine = EngineType.JAVA,
            version = "1.21.4",
            loader = "Fabric 0.16.9",
            playtimeHours = 42.0f,
            friendsOnline = 1
        ),
        GameInstance(
            id = "rust-120fps",
            title = "Minecraft Rust 120FPS",
            engine = EngineType.RUST,
            version = "26.3",
            loader = "Starling wgpu",
            playtimeHours = 18.5f,
            friendsOnline = 2
        ),
        GameInstance(
            id = "crossplay-bedrock",
            title = "Crossplay Amigos",
            engine = EngineType.BEDROCK,
            version = "26.10",
            loader = "MCPE Oficial",
            playtimeHours = 11.2f,
            friendsOnline = 3
        )
    )
}
