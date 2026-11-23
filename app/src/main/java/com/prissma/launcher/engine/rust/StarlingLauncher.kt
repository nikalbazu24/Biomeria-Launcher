package com.prissma.launcher.engine.rust

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import com.prissma.launcher.data.GameInstance
import io.github.juanluci.starling.DiagnosticsActivity
import io.github.juanluci.starling.LauncherActivity
import io.github.juanluci.starling.PrissmaProfiles
import java.io.File

internal const val STARLING_MINECRAFT_VERSION = "26.3"
private const val STARLING_VERSION = "0.9.0"

internal data class StarlingInstallation(val version: String?, val canOpen: Boolean) {
    val label: String get() = listOfNotNull("Rust integrado", version?.let { "Starling $it" }).joinToString(" · ")
}

internal fun StarlingInstallation?.launchProblem(): String? = when {
    this == null || !canOpen -> "Falta el motor Rust en esta compilación de Biomeria."
    else -> null
}

internal class StarlingLauncher(private val activity: Activity) {
    fun detect(): StarlingInstallation = StarlingInstallation(STARLING_VERSION,
        File(activity.applicationInfo.nativeLibraryDir, "libhorizon_android.so").isFile)

    fun open(instance: GameInstance, mod: Uri? = null): String? {
        detect().launchProblem()?.let { return it }
        val launch = Intent(activity, LauncherActivity::class.java).forInstance(instance)
        if (mod != null) {
            launch.action = Intent.ACTION_VIEW
            launch.setDataAndType(mod, "application/zip")
            launch.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return if (start(launch)) null else "Android no pudo abrir el motor Rust."
    }

    fun openDiagnostics(instance: GameInstance?): String? = if (start(Intent(activity, DiagnosticsActivity::class.java)
        .apply { instance?.let { forInstance(it) } })) null
        else "Android no pudo abrir el diagnóstico del motor Rust."

    private fun Intent.forInstance(instance: GameInstance): Intent =
        putExtra(PrissmaProfiles.EXTRA_SLOT, PrissmaProfiles.slotForInstance(instance.id))
            .putExtra(PrissmaProfiles.EXTRA_TITLE, instance.title.take(120))

    private fun start(intent: Intent): Boolean = try {
        activity.startActivity(intent)
        true
    } catch (error: ActivityNotFoundException) {
        false
    } catch (error: SecurityException) {
        false
    }
}
