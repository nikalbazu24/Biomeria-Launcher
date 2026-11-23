package com.biomeria.launcher.engine.java

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.zip.ZipFile

internal const val NEOFORGE_GAME_VERSION = "1.21.4"
internal const val NEOFORGE_VERSION = "21.4.161"
internal const val NEOFORGE_MAIN_CLASS = "com.biomeria.bootstrap.NeoForgeBootstrap"
internal const val NEOFORGE_INSTALLER_URL =
    "https://maven.neoforged.net/releases/net/neoforged/neoforge/$NEOFORGE_VERSION/neoforge-$NEOFORGE_VERSION-installer.jar"
internal const val NEOFORGE_INSTALLER_SHA256 = "3696ad5d47dee24b09a557c98289a82834ef539fee7634ebd26e1e6ea92b881b"

internal data class NeoForgeInstall(
    val downloads: List<Artifact>,
    val launchLibraries: List<Artifact>,
    val jvmArguments: List<String>,
    val gameArguments: List<String>,
    val planFile: File,
    val patch: File,
    private val processors: Properties
) {
    fun writePlan() = replaceFile(planFile) { file -> file.outputStream().use { processors.store(it, null) } }
}

internal fun neoForgeInstall(
    installer: File, root: File, client: File, plan: File, vanillaVersion: JSONObject
): NeoForgeInstall = ZipFile(installer).use { zip ->
    val profile = zip.json("install_profile.json")
    val version = zip.json("version.json")
    val versionName = "neoforge-$NEOFORGE_VERSION"
    check(profile.getString("minecraft") == NEOFORGE_GAME_VERSION && version.getString("inheritsFrom") == NEOFORGE_GAME_VERSION)
    check(version.getString("id") == versionName && version.getString("mainClass") == "cpw.mods.bootstraplauncher.BootstrapLauncher")

    val libraryRoot = File(root, "libraries")
    fun library(coordinate: String) = File(libraryRoot, mavenPath(coordinate))

    val data = profile.getJSONObject("data")
    val variables = mutableMapOf("MINECRAFT_JAR" to client.absolutePath, "SIDE" to "client")
    for (key in data.keys()) {
        val value = data.getJSONObject(key).getString("client")
        variables[key] = when {
            value.startsWith('[') && value.endsWith(']') -> library(value.substring(1, value.lastIndex)).absolutePath
            value.startsWith('\'') && value.endsWith('\'') -> value.substring(1, value.lastIndex)
            key == "BINPATCH" && value == "/data/client.lzma" -> {
                val patch = File(root, "client.lzma")
                replaceFile(patch) { file -> zip.getInputStream(zip.getEntry("data/client.lzma")).use { input ->
                    file.outputStream().use { input.copyTo(it) }
                } }
                patch.absolutePath
            }
            else -> error("Dato de instalación de NeoForge desconocido: $key")
        }
    }

    val processorList = profile.getJSONArray("processors").objects().filter { processor ->
        val sides = processor.optJSONArray("sides")
        val args = processor.getJSONArray("args").strings()
        (sides == null || "client" in sides.strings()) && "DOWNLOAD_MOJMAPS" !in args
    }
    val tools = Properties().apply {
        setProperty("cache", root.absolutePath)
        setProperty("count", processorList.size.toString())
        val generated = listOf("MAPPINGS", "MERGED_MAPPINGS", "MC_SLIM", "MC_EXTRA", "MC_SRG", "PATCHED")
        setProperty("generated", generated.joinToString(File.pathSeparator) { variables.getValue(it) })
        setProperty("runtime", listOf("MC_EXTRA", "MC_SRG", "PATCHED").joinToString(File.pathSeparator) { variables.getValue(it) })
        for ((index, processor) in processorList.withIndex()) {
            setProperty("$index.jar", library(processor.getString("jar")).absolutePath)
            setProperty("$index.classpath", processor.getJSONArray("classpath").strings()
                .joinToString(File.pathSeparator) { library(it).absolutePath })
            val args = processor.getJSONArray("args").strings().map { argument ->
                when {
                    argument.startsWith('{') && argument.endsWith('}') -> variables.getValue(argument.substring(1, argument.lastIndex))
                    argument.startsWith('[') && argument.endsWith(']') -> library(argument.substring(1, argument.lastIndex)).absolutePath
                    else -> argument
                }
            }
            setProperty("$index.argc", args.size.toString())
            args.forEachIndexed { position, argument -> setProperty("$index.arg.$position", argument) }
        }
    }

    fun artifacts(metadata: JSONObject) = metadata.getJSONArray("libraries").objects().map { entry ->
        val coordinate = entry.getString("name")
        val artifact = entry.getJSONObject("downloads").getJSONObject("artifact")
        val url = artifact.getString("url")
        require(url.startsWith("https://maven.neoforged.net/releases/") || url.startsWith("https://libraries.minecraft.net/"))
        check(artifact.getString("path") == mavenPath(coordinate))
        Artifact(url, library(coordinate), artifact.getString("sha1"), coordinate = coordinate)
    }

    val arguments = version.getJSONObject("arguments")
    val jvmArgs = neoForgeJvmOptions(arguments.getJSONArray("jvm").strings(), libraryRoot, versionName) +
        listOf("-Dbiomeria.neoforge.plan=${plan.absolutePath}", "-Djava.awt.headless=true")
    val mappings = vanillaVersion.getJSONObject("downloads").getJSONObject("client_mappings")
    val required = artifacts(profile) + Artifact(mappings.getString("url"), File(variables.getValue("MOJMAPS")), mappings.getString("sha1"))
    val runLibraries = artifacts(version)
    NeoForgeInstall((required + runLibraries).distinctBy { it.file }, runLibraries, jvmArgs,
        arguments.getJSONArray("game").strings(), plan, File(variables.getValue("BINPATCH")), tools)
}

internal fun neoForgeJvmOptions(arguments: List<String>, libraries: File, versionName: String): List<String> {
    val expanded = arguments.map { argument ->
        argument.replace("\${library_directory}", libraries.absolutePath)
            .replace("\${classpath_separator}", File.pathSeparator).replace("\${version_name}", versionName)
    }.iterator()
    return buildList {
        while (expanded.hasNext()) {
            val option = expanded.next()
            when {
                option == "-p" -> add("--module-path=${expanded.next()}")
                option in listOf("--module-path", "--add-modules", "--add-opens", "--add-exports") -> add("$option=${expanded.next()}")
                option.startsWith("-D") -> add(option)
                else -> error("Opción de JVM de NeoForge desconocida: $option")
            }
        }
    }
}

private fun ZipFile.json(name: String) = getInputStream(getEntry(name)).bufferedReader().use { JSONObject(it.readText()) }

private fun replaceFile(target: File, write: (File) -> Unit) {
    val parent = checkNotNull(target.parentFile).apply { mkdirs() }
    val staged = File.createTempFile("neoforge-", ".part", parent)
    try {
        write(staged)
        Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        staged.delete()
    }
}
