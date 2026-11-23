package com.biomeria.launcher.data

import org.json.JSONArray
import org.json.JSONObject

internal data class WorkshopArtifact(
    val projectId: String,
    val versionId: String,
    val versionLabel: String,
    val filename: String,
    val url: String,
    val sha512: String,
    val size: Long,
    val metadata: JSONObject
)

internal data class WorkshopInstallPlan(val artifacts: List<WorkshopArtifact>) {
    val root: WorkshopArtifact get() = artifacts.first()
    val pins: String get() = artifacts.joinToString(",") { "${it.projectId}:${it.versionId}" }
}

internal class WorkshopIncompatibility(message: String) : IllegalStateException(message)

private data class RequiredMod(val projectId: String?, val versionId: String?)

internal val modrinthId = Regex("[A-Za-z0-9]{1,64}")

internal fun workshopModLoaderName(loader: String?): String? = when (loader) {
    "fabric" -> "Fabric"
    "neoforge" -> "NeoForge"
    else -> null
}

internal class ModrinthInstallPlanner(
    private val category: WorkshopCategory,
    private val gameVersion: String,
    private val loader: String?,
    confirmedPins: String = "",
    private val get: (String) -> String
) {
    private val versions = mutableMapOf<String, List<JSONObject>>()
    private val exactVersions = mutableMapOf<String, JSONObject>()
    private val loaderLabel = workshopModLoaderName(loader)
    private val pins = if (confirmedPins.isBlank()) emptyMap() else confirmedPins.split(',').associate { pin ->
        val (project, version) = pin.split(':', limit = 2)
        require(modrinthId.matches(project) && modrinthId.matches(version))
        project to version
    }
    private var attempts = 0

    fun plan(projectId: String): WorkshopInstallPlan {
        if (category == WorkshopCategory.MODS && loaderLabel == null) {
            throw WorkshopIncompatibility("Selecciona Fabric o NeoForge en la instancia para instalar mods de este Taller.")
        }
        val selected = resolve(listOf(RequiredMod(projectId, pins[projectId])), emptyMap())
        if (pins.isNotEmpty() && selected.mapValues { it.value.versionId } != pins) {
            throw WorkshopIncompatibility("El proyecto cambió desde la comprobación. Vuelve a comprobarlo en el Taller.")
        }
        return WorkshopInstallPlan(selected.values.toList())
    }

    private fun resolve(
        pending: List<RequiredMod>,
        selected: Map<String, WorkshopArtifact>
    ): Map<String, WorkshopArtifact> {
        val remaining = pending.toMutableList()
        while (remaining.isNotEmpty()) {
            // Resolve known pins first, then choose from the newest compatible releases.
            val pinIndex = remaining.indexOfFirst { it.versionId != null }.coerceAtLeast(0)
            val requirement = remaining.removeAt(pinIndex)
            val existing = selected[requirement.projectId]
            if (existing != null) {
                verifyPin(existing, requirement)
                continue
            }

            var firstConflict: WorkshopIncompatibility? = null
            for (version in candidates(requirement)) {
                check(++attempts <= 240) { "La instalación tiene demasiadas combinaciones de dependencias." }
                try {
                    val project = version.getString("project_id")
                    val versionId = version.getString("id")
                    check(modrinthId.matches(project) && modrinthId.matches(versionId))
                    check(requirement.projectId == null || requirement.projectId == project)
                    check(requirement.versionId == null || requirement.versionId == versionId)
                    val previouslySelected = selected[project]
                    if (previouslySelected != null) {
                        verifyPin(previouslySelected, RequiredMod(project, versionId))
                        return resolve(remaining, selected)
                    }
                    val artifact = version.artifact(project, versionId)
                    check(selected.size < 60) { "La instalación tiene demasiadas dependencias." }
                    if (selected.values.any { it.filename == artifact.filename }) {
                        throw WorkshopIncompatibility("Dos mods usan el mismo nombre de archivo (${artifact.filename}).")
                    }
                    return resolve(remaining + version.requiredMods(), selected + (project to artifact))
                } catch (conflict: WorkshopIncompatibility) {
                    if (firstConflict == null) firstConflict = conflict
                }
            }
            throw checkNotNull(firstConflict)
        }
        return selected
    }

    private fun verifyPin(artifact: WorkshopArtifact, requirement: RequiredMod) {
        if (requirement.versionId != null && requirement.versionId != artifact.versionId) {
            throw WorkshopIncompatibility("El mod «${artifact.filename}» se necesita en dos versiones distintas.")
        }
    }

    private fun candidates(requirement: RequiredMod): List<JSONObject> {
        val (project, version) = requirement
        require(project != null || version != null)
        require(listOfNotNull(project, version).all(modrinthId::matches))
        val published = if (version != null) {
            listOf(exactVersions.getOrPut(version) { JSONObject(get("/version/$version")) })
        } else {
            versions.getOrPut(checkNotNull(project)) {
                JSONArray(get("/project/$project/version")).objects()
            }
        }
        val matching = published.filter { it.matchesGame(gameVersion, category, loader) }
        if (matching.isNotEmpty()) return matching

        val forGame = published.any { it.optJSONArray("game_versions").containsValue(gameVersion) }
        val subject = if (version != null) "La versión requerida ($version)" else "El proyecto ($project)"
        val reason = when {
            !forGame -> "$subject no tiene una publicación para Minecraft $gameVersion."
            else -> "$subject no tiene una publicación para $loaderLabel en Minecraft $gameVersion."
        }
        throw WorkshopIncompatibility(reason)
    }

    private fun JSONObject.requiredMods(): List<RequiredMod> {
        if (category != WorkshopCategory.MODS) return emptyList()
        return optJSONArray("dependencies").objects().mapNotNull { dependency ->
            if (dependency.optString("dependency_type") != "required") return@mapNotNull null
            val project = dependency.optionalId("project_id")
            val version = dependency.optionalId("version_id")
            if (project == null && version == null) {
                val filename = dependency.optString("file_name").ifBlank { "sin identificar" }
                throw WorkshopIncompatibility("Una dependencia obligatoria ($filename) no se puede descargar desde Modrinth.")
            }
            RequiredMod(project, version ?: pins[project])
        }
    }

    private fun JSONObject.artifact(project: String, version: String): WorkshopArtifact {
        val files = getJSONArray("files").objects()
        val file = files.firstOrNull { it.optBoolean("primary") } ?: files.firstOrNull()
            ?: throw WorkshopIncompatibility("La versión $version no tiene un archivo para descargar.")
        val name = file.getString("filename")
        if (category == WorkshopCategory.MODS && !name.endsWith(".jar", ignoreCase = true)) {
            throw WorkshopIncompatibility("La versión $version no ofrece un archivo JAR para $loaderLabel.")
        }
        require(safeWorkshopFilename(name)) { "Modrinth devolvió un nombre de archivo inválido." }
        val url = file.getString("url")
        require(url.startsWith("https://cdn.modrinth.com/")) { "Origen del archivo inválido." }
        val hash = file.getJSONObject("hashes").getString("sha512").lowercase()
        require(hash.matches(Regex("[a-f0-9]{128}"))) { "Modrinth devolvió una suma de verificación inválida." }
        val metadata = JSONObject().put("file", name).put("game_versions", getJSONArray("game_versions"))
            .put("loaders", getJSONArray("loaders"))
        return WorkshopArtifact(project, version, optString("version_number", version), name, url, hash, file.optLong("size"), metadata)
    }
}

private fun JSONObject.optionalId(name: String) = optString(name).takeUnless { it.isBlank() || it == "null" }

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else List(length(), ::getJSONObject)

internal fun JSONObject.matchesGame(version: String, category: WorkshopCategory, loader: String?): Boolean =
    optJSONArray("game_versions").containsValue(version) &&
        (category != WorkshopCategory.MODS || optJSONArray("loaders").containsValue(loader))

private fun JSONArray?.containsValue(value: String?): Boolean =
    this != null && value != null && (0 until length()).any { optString(it) == value }

internal fun safeWorkshopFilename(name: String) =
    name.isNotBlank() && name.length <= 255 && name.none { it == '/' || it == '\\' || it.isISOControl() } &&
        name != "." && name != ".."
