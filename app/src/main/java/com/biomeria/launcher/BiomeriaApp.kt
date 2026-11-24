package com.biomeria.launcher

import android.app.Application
import com.biomeria.launcher.report.CrashReporter

/** Starts in every process of Biomeria (launcher, game, Rust), so a crash in any of them is reported. */
class BiomeriaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        CrashReporter.sendPendingAsync(this)
    }
}
