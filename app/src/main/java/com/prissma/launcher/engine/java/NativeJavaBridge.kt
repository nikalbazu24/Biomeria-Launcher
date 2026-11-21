package com.prissma.launcher.engine.java

import android.view.Surface

object NativeJavaBridge {

    init {
        System.loadLibrary("prissma_jvm_bridge")
    }

    external fun nativeInitGraphicsSurface(
        surface: Surface,
        previewOnly: Boolean
    ): String

    external fun nativeDestroyGraphicsSurface()

    external fun nativePointer(x: Double, y: Double, action: Int)

    external fun nativeHasPresentedFrame(): Boolean

    external fun nativeLaunchJvm(
        libJvmPath: String,
        javaHomePath: String,
        gameDirPath: String,
        cacheDirPath: String,
        nativeLibraryDir: String,
        classpath: String,
        mainClass: String,
        gameArgs: Array<String>,
        useVulkanZink: Boolean
    ): Int

    external fun nativePollLogs(): Array<String>
}
