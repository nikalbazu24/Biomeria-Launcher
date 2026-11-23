package com.biomeria.launcher.data

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.InputDevice

/** What kind of device Biomeria runs on, so the defaults fit it: one or two screens, built-in controls or touch. */
data class DeviceProfile(val screens: Int, val hasGamepad: Boolean, val name: String) {
    val dual get() = screens >= 2

    val summary: String get() = buildString {
        append(name)
        append(if (dual) " · 2 pantallas" else " · 1 pantalla")
        append(if (hasGamepad) " · con mando" else " · táctil")
    }

    companion object {
        /** Two-screen handhelds whose lower screen may not be listed until an app uses it. */
        private val dualModels = listOf("thor", "flip ds")

        fun detect(context: Context): DeviceProfile {
            val manager = context.getSystemService(DisplayManager::class.java)
            val extra = manager?.displays.orEmpty().count { it.displayId != Display.DEFAULT_DISPLAY && it.isValid }
            val model = (Build.MANUFACTURER + " " + Build.MODEL).trim()
            val knownDual = model.lowercase().let { lower -> dualModels.any { lower.contains(it) } }
            val gamepad = InputDevice.getDeviceIds().any { id ->
                InputDevice.getDevice(id)?.let { device ->
                    !device.isVirtual && (device.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                } == true
            }
            val name = Build.MODEL.ifBlank { "Este equipo" }
            return DeviceProfile(if (extra > 0 || knownDual) 2 else 1, gamepad, name)
        }

        /** First run: leaves the defaults that suit the device. The player can still change them. */
        fun applyDefaults(context: Context, force: Boolean = false) {
            val settings = context.getSharedPreferences("biomeria_java", Context.MODE_PRIVATE)
            if (!force && settings.getBoolean("device-defaults-applied", false)) return
            val profile = detect(context)
            settings.edit()
                .putBoolean("dual-screen-default", profile.dual)
                .putBoolean("touch-default", !profile.hasGamepad)
                .putBoolean("device-defaults-applied", true)
                .apply()
        }
    }
}
