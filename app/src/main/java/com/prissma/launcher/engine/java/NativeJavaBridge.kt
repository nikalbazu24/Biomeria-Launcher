package com.prissma.launcher.engine.java

import android.view.Surface

object NativeJavaBridge {

    init {
        System.loadLibrary("prissma_jvm_bridge")
    }

    external fun nativeInitGraphicsSurface(
        surface: Surface,
        useVulkanZink: Boolean
    ): String

    external fun nativeDestroyGraphicsSurface()

    external fun nativeLaunchJvm(
        libJvmPath: String,
        javaHomePath: String,
        gameDirPath: String,
        classpath: String,
        mainClass: String,
        gameArgs: Array<String>,
        useVulkanZink: Boolean
    ): Int

    external fun nativePollLogs(): Array<String>
}
