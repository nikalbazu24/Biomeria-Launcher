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

    external fun nativeInput(kind: Int, code: Int, action: Int, x: Double, y: Double)

    external fun nativeSetViewportSize(width: Int, height: Int)

    external fun nativeCursorCaptured(): Boolean

    external fun nativeHasPresentedFrame(): Boolean

    external fun nativePresentedFrames(): Long

    external fun nativeLaunchJvm(
        libJvmPath: String,
        javaHomePath: String,
        gameDirPath: String,
        cacheDirPath: String,
        nativeLibraryDir: String,
        classpath: String,
        mainClass: String,
        jvmArguments: Array<String>,
        gameArgs: Array<String>,
        javaHeapMb: Int
    ): Int

    external fun nativeTestAudio(): String?

    external fun nativePollLogs(): Array<String>
}
