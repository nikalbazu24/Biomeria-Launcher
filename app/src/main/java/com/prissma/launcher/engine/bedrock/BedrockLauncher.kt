package com.prissma.launcher.engine.bedrock

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager

internal enum class BedrockEdition(val packageName: String, val title: String) {
    MINECRAFT("com.mojang.minecraftpe", "Minecraft Bedrock"),
    TRIAL("com.mojang.minecrafttrialpe", "Minecraft Trial")
}

internal data class BedrockInstallation(
    val edition: BedrockEdition,
    val versionName: String?,
    val canOpen: Boolean
) {
    val label: String get() = listOfNotNull(edition.title, versionName).joinToString(" · ")
}

internal fun findBedrock(probe: (BedrockEdition) -> BedrockInstallation?): BedrockInstallation? {
    var unavailable: BedrockInstallation? = null
    for (edition in BedrockEdition.entries) {
        val installed = probe(edition) ?: continue
        if (installed.canOpen) return installed
        if (unavailable == null) unavailable = installed
    }
    return unavailable
}

internal fun BedrockInstallation?.launchProblem(): String? = when {
    this == null -> "Instala Minecraft Bedrock en este dispositivo."
    !canOpen -> "Revisa que ${edition.title} esté habilitado en Android."
    else -> null
}

internal class BedrockLauncher(private val activity: Activity) {
    private val packages get() = activity.packageManager

    fun detect(): BedrockInstallation? = findBedrock(::probe)

    fun open(): String? {
        val installed = detect()
        installed.launchProblem()?.let { return it }
        val edition = checkNotNull(installed).edition
        val unavailable = "Android no pudo abrir ${edition.title}."
        val intent = packages.getLaunchIntentForPackage(edition.packageName) ?: return unavailable
        return try {
            // Keep the Hub underneath Minecraft when the game's activity allows sharing a task.
            intent.removeFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(intent)
            null
        } catch (error: ActivityNotFoundException) {
            unavailable
        } catch (error: SecurityException) {
            unavailable
        }
    }

    @Suppress("DEPRECATION") // The flag-free PackageManager overload also supports API 26–32.
    private fun probe(edition: BedrockEdition): BedrockInstallation? {
        val info = try {
            packages.getPackageInfo(edition.packageName, 0)
        } catch (error: PackageManager.NameNotFoundException) {
            return null
        }
        return BedrockInstallation(
            edition,
            info.versionName?.takeIf { it.isNotBlank() },
            info.applicationInfo?.enabled != false && packages.getLaunchIntentForPackage(edition.packageName) != null
        )
    }
}
