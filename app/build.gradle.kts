import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val buildVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
val buildVersionName = (project.findProperty("versionName") as String?) ?: "1.0.0-beta.1"

val jnaAndroid by configurations.creating
val jnaJniLibs = layout.buildDirectory.dir("generated/jna/jniLibs")
val unpackJnaAndroid by tasks.registering(Sync::class) {
    from({ zipTree(jnaAndroid.singleFile) }) {
        include("jni/arm64-v8a/libjnidispatch.so")
        eachFile { path = path.removePrefix("jni/") }
        includeEmptyDirs = false
    }
    into(jnaJniLibs)
}
val lwjglRevision = "166af615dbdca1a507b8c2d379822cca02795132"
val lwjglSha256 = "3ec756806be63e5446964464777146d7c87660d3d4e238d5b74c76d68d6070d7"
val lwjglArchive = layout.buildDirectory.file("downloads/lwjgl-3.3.3-natives.aar")
val lwjglJniLibs = layout.buildDirectory.dir("generated/lwjgl/jniLibs")
val lwjglAssets = layout.buildDirectory.dir("generated/lwjgl/assets")
val lwjglSource = "https://raw.githubusercontent.com/AngelAuraMC/Amethyst-Android/$lwjglRevision"

fun verifiedDownload(url: String, expectedSha256: String): ByteArray {
    val connection = URI(url).toURL().openConnection().apply {
        connectTimeout = 15_000
        readTimeout = 30_000
    }
    val bytes = connection.getInputStream().use { it.readBytes() }
    val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    check(sha256 == expectedSha256) { "SHA-256 inesperado para $url." }
    return bytes
}

val downloadLwjglAndroid by tasks.registering {
    inputs.property("revision", lwjglRevision)
    inputs.property("sha256", lwjglSha256)
    outputs.file(lwjglArchive)
    doLast {
        val bytes = verifiedDownload(
            "$lwjglSource/app_pojavlauncher/libs/lwjgl-3.3.3-natives-release.aar", lwjglSha256
        )
        lwjglArchive.get().asFile.apply { parentFile.mkdirs() }.writeBytes(bytes)
    }
}
val unpackLwjglAndroid by tasks.registering(Sync::class) {
    dependsOn(downloadLwjglAndroid)
    val prefix = "assets/components/lwjgl-3.3.3-natives/arm64-v8a/"
    from({ zipTree(lwjglArchive.get().asFile) }) {
        include(listOf("liblwjgl.so", "liblwjgl_opengl.so", "liblwjgl_stb.so").map { prefix + it })
        eachFile { path = "arm64-v8a/" + name }
        includeEmptyDirs = false
    }
    into(lwjglJniLibs)
}
val prepareLwjglJars by tasks.registering {
    val jars = mapOf(
        "lwjgl" to "c26bc7db30a3414d907530eb3128c582612c10674328408e560feb5444ceefd1",
        "lwjgl-opengl" to "941bd9e9d1818f732b614e104a885d313e88368eab348b8541832ae12a414114",
        "lwjgl-stb" to "7a8145cadeee92d682515c8a37d561ed594026fa9d83a6b033eeaec4a587a010"
    )
    inputs.property("revision", lwjglRevision)
    inputs.property("jars", jars)
    outputs.dir(lwjglAssets)
    doLast {
        val directory = lwjglAssets.get().dir("lwjgl/3.3.3").asFile.apply { mkdirs() }
        jars.forEach { (module, sha256) ->
            val source = "$lwjglSource/jre_lwjgl3glfw/lwjgl-3.3.3/libs/lwjgl-3.3.3/$module.jar"
            directory.resolve("$module-3.3.3.jar").writeBytes(verifiedDownload(source, sha256))
        }
    }
}
tasks.named("preBuild") { dependsOn(unpackJnaAndroid, unpackLwjglAndroid, prepareLwjglJars) }

android {
    namespace = "com.prissma.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.prissma.launcher"
        minSdk = 26
        targetSdk = 35
        versionCode = buildVersionCode
        versionName = buildVersionName

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            val keystoreFile = file("prissma.jks")
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (file("prissma.jks").exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets.getByName("main").jniLibs.srcDir(jnaJniLibs)
    sourceSets.getByName("main").jniLibs.srcDir(lwjglJniLibs)
    sourceSets.getByName("main").assets.srcDir(lwjglAssets)
    // La JVM de Minecraft carga las bibliotecas desde applicationInfo.nativeLibraryDir.
    packaging.jniLibs.useLegacyPackaging = true
    packaging.jniLibs.keepDebugSymbols.addAll(
        setOf("**/liblwjgl.so", "**/liblwjgl_opengl.so", "**/liblwjgl_stb.so")
    )

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    jnaAndroid("net.java.dev.jna:jna:5.15.0@aar")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("org.tukaani:xz:1.9")
}
