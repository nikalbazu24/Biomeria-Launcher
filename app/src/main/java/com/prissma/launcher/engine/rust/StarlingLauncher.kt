package com.prissma.launcher.engine.rust

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

internal const val STARLING_PACKAGE = "io.github.juanluci.starling"
internal const val STARLING_MINECRAFT_VERSION = "26.3"
internal const val STARLING_DIAGNOSTICS_ACTIVITY = "$STARLING_PACKAGE.DiagnosticsActivity"
internal const val STARLING_RELEASES_URL = "https://github.com/starling-client/starling/releases/latest"

internal data class StarlingInstallation(val version: String?, val canOpen: Boolean) {
    val label: String get() = listOfNotNull("Starling", version).joinToString(" · ")
}

internal fun StarlingInstallation?.launchProblem(): String? = when {
    this == null -> "Instala Starling ARM64 en este perfil de Android."
    !canOpen -> "Revisa que Starling esté habilitado en Android."
    else -> null
}

internal class StarlingLauncher(private val activity: Activity) {
    private val manager get() = activity.packageManager

    @Suppress("DEPRECATION") // The integer-flags overload is also available on Android 8.
    fun detect(): StarlingInstallation? {
        val launch = manager.getLaunchIntentForPackage(STARLING_PACKAGE)
        val info = try {
            manager.getPackageInfo(STARLING_PACKAGE, 0)
        } catch (error: PackageManager.NameNotFoundException) {
            null
        }
        if (info == null && launch == null) return null
        return StarlingInstallation(info?.versionName?.takeIf { it.isNotBlank() }, launch != null)
    }

    fun open(): String? {
        val launch = manager.getLaunchIntentForPackage(STARLING_PACKAGE)
            ?: return detect().launchProblem() ?: "Android no pudo abrir Starling."
        return if (start(launch)) null else "Android no pudo abrir Starling."
    }

    fun openDiagnostics(): String? {
        val launch = Intent(Intent.ACTION_VIEW).setClassName(STARLING_PACKAGE, STARLING_DIAGNOSTICS_ACTIVITY)
        return if (start(launch)) null else "Android no pudo abrir el diagnóstico. Comprueba que Starling esté instalado y actualizado."
    }

    fun openReleases(): String? =
        if (start(Intent(Intent.ACTION_VIEW, Uri.parse(STARLING_RELEASES_URL)))) null
        else "Android no pudo abrir la página oficial de Starling."

    private fun start(intent: Intent): Boolean = try {
        activity.startActivity(intent)
        true
    } catch (error: ActivityNotFoundException) {
        false
    } catch (error: SecurityException) {
        false
    }
}
