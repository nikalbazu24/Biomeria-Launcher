package com.prissma.launcher.data

import android.content.Context
import android.net.Uri
import com.prissma.launcher.ui.hub.EngineType
import com.prissma.launcher.ui.hub.GameInstance
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

data class SavedProfile(
    val playerName: String,
    val accountType: String
)

class InstanceStorage(private val context: Context) {

    private val catalogFile = File(context.filesDir, "prissma_catalog.json")
    private val profileFile = File(context.filesDir, "prissma_profile.json")

    // Carpeta accesible para frontends (Cocoon / exploradores) sin pedir permisos especiales
    val exportInstancesDir: File
        get() = File(context.getExternalFilesDir(null), "PRISSMA/instances").apply { mkdirs() }

    fun loadProfile(): SavedProfile {
        if (!profileFile.exists()) {
            return SavedProfile(playerName = "Jugador_Local", accountType = "Offline")
        }
        return runCatching {
            val obj = JSONObject(profileFile.readText(Charsets.UTF_8))
            SavedProfile(
                playerName = obj.optString("playerName", "Jugador_Local"),
                accountType = obj.optString("accountType", "Offline")
            )
        }.getOrDefault(SavedProfile(playerName = "Jugador_Local", accountType = "Offline"))
    }

    fun saveProfile(playerName: String, accountType: String) {
        val obj = JSONObject().apply {
            put("playerName", playerName)
            put("accountType", accountType)
        }
        profileFile.writeText(obj.toString(2), Charsets.UTF_8)
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
            if (list.isEmpty()) defaultInstances() else list
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

                val cartridge = JSONObject().apply {
                    put("schema_version", 1)
                    put("instance_id", inst.id)
                    put("slug", slug)
                    put("title", inst.title)
                    put("engine", inst.engine.name.lowercase(Locale.US))
                    put("mc_version", inst.version)
                    put("loader", inst.loader)
                    put("deep_link", "prissma://launch?instance_id=${inst.id}")
                }
                File(dir, fileName).writeText(cartridge.toString(2), Charsets.UTF_8)
            }

            dir.listFiles()?.filter { it.extension == "mcinst" && it.name !in validFiles }
                ?.forEach { it.delete() }
        }
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
