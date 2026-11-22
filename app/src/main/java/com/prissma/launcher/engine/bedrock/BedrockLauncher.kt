package com.prissma.launcher.engine.bedrock

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

internal enum class BedrockEdition(val packageName: String, val title: String) {
    MINECRAFT("com.mojang.minecraftpe", "Minecraft Bedrock"),
    TRIAL("com.mojang.minecrafttrialpe", "Minecraft Trial")
}

internal data class BedrockInstallation(
    val packageName: String,
    val title: String,
    val versionName: String?,
    val canOpen: Boolean
) {
    val label: String get() = listOfNotNull(title, versionName).joinToString(" · ")
}

internal fun findBedrock(preferredPackage: String?, probe: (String) -> BedrockInstallation?): BedrockInstallation? {
    val candidates = (listOfNotNull(preferredPackage) + BedrockEdition.entries.map { it.packageName }).distinct()
    var unavailable: BedrockInstallation? = null
    for (name in candidates) {
        val installed = probe(name) ?: continue
        if (installed.canOpen) return installed
        if (unavailable == null) unavailable = installed
    }
    return unavailable
}

private val minecraftLabel = Regex("^minecraft(?:[^\\p{L}\\p{N}]|$)", RegexOption.IGNORE_CASE)

internal fun isBedrockCandidate(packageName: String, label: String): Boolean =
    BedrockEdition.entries.any { it.packageName == packageName } || minecraftLabel.containsMatchIn(label.trim())

internal fun BedrockInstallation?.launchProblem(): String? = when {
    this == null -> "PRISSMA no encontró Minecraft en este perfil de Android."
    !canOpen -> "Revisa que $title esté habilitado en Android."
    else -> null
}

internal class BedrockLauncher(private val activity: Activity) {
    private val packages get() = activity.packageManager
    private val preferences = activity.getSharedPreferences("prissma_bedrock", Context.MODE_PRIVATE)
    val preferredPackage: String? get() = preferences.getString("selected_package", null)

    fun detect(): BedrockInstallation? = findBedrock(preferredPackage, ::probe)

    fun choosePackage(packageName: String?) {
        preferences.edit().putString("selected_package", packageName).apply()
    }

    @Suppress("DEPRECATION") // Android 8 also uses the integer-flags overload.
    fun installedGames(): List<BedrockInstallation> {
        val matches = sequenceOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER)
            .flatMap { category -> packages.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0).asSequence() }
            .mapNotNull { entry ->
                val activity = entry.activityInfo ?: return@mapNotNull null
                val id = activity.packageName
                val matches = isBedrockCandidate(id, entry.loadLabel(packages).toString()) ||
                    isBedrockCandidate(id, activity.applicationInfo?.loadLabel(packages)?.toString().orEmpty())
                id.takeIf { matches }
            }
            .distinct()
            .mapNotNull(::probe)
            .filter { it.canOpen }
            .toList()
        return matches.sortedWith(compareBy<BedrockInstallation> {
            BedrockEdition.entries.indexOfFirst { edition -> edition.packageName == it.packageName }.let { rank ->
                if (rank < 0) BedrockEdition.entries.size else rank
            }
        }.thenBy { it.title }.thenBy { it.packageName })
    }

    fun open(): String? {
        val installed = detect()
        installed.launchProblem()?.let { return it }
        val target = checkNotNull(installed)
        val unavailable = "Android no pudo abrir ${target.title}."
        val intent = launchIntent(target.packageName) ?: return unavailable
        return if (start(intent.apply {
            // Keep the Hub underneath Minecraft when the game's activity allows sharing a task.
            removeFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })) null else unavailable
    }

    fun openStore(): String? {
        val packageName = BedrockEdition.MINECRAFT.packageName
        val store = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).setPackage("com.android.vending")
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName"))
        return if (start(store) || start(web)) null else "No se pudo abrir Google Play en este dispositivo."
    }

    fun openAppSettings(packageName: String): String? =
        if (start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))) null
        else "No se pudieron abrir los ajustes de esa aplicación."

    private fun start(intent: Intent): Boolean = try {
        activity.startActivity(intent)
        true
    } catch (error: ActivityNotFoundException) {
        false
    } catch (error: SecurityException) {
        false
    }

    private fun launchIntent(packageName: String): Intent? = packages.getLaunchIntentForPackage(packageName)
        ?: packages.getLeanbackLaunchIntentForPackage(packageName)

    @Suppress("DEPRECATION") // The flag-free PackageManager overload also supports API 26–32.
    private fun probe(packageName: String): BedrockInstallation? {
        val intent = launchIntent(packageName)
        val info = try {
            packages.getPackageInfo(packageName, 0)
        } catch (error: PackageManager.NameNotFoundException) {
            null
        }
        if (info == null && intent == null) return null
        val title = BedrockEdition.entries.firstOrNull { it.packageName == packageName }?.title
            ?: info?.applicationInfo?.loadLabel(packages)?.toString()?.takeIf { it.isNotBlank() }
            ?: packageName
        return BedrockInstallation(
            packageName,
            title,
            info?.versionName?.takeIf { it.isNotBlank() },
            intent != null
        )
    }
}
