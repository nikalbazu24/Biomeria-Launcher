import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.tools.ToolProvider

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val buildVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
val buildVersionName = (project.findProperty("versionName") as String?) ?: "1.0.0-beta.1"

val jnaAndroidVersions = listOf("5.12.1", "5.13.0", "5.14.0", "5.15.0", "5.17.0")
val jnaAndroid = jnaAndroidVersions.associateWith { version ->
    configurations.create("jnaAndroid" + version.replace(".", ""))
}
val jnaJniLibs = layout.buildDirectory.dir("generated/jna/jniLibs")
val unpackJnaAndroid by tasks.registering(Sync::class) {
    jnaAndroid.forEach { (version, archive) ->
        from({ zipTree(archive.singleFile) }) {
            include("jni/arm64-v8a/libjnidispatch.so")
            eachFile {
                path = if (version == "5.15.0") "arm64-v8a/libjnidispatch.so"
                else "arm64-v8a/libjnidispatch_" + version.replace('.', '_') + ".so"
            }
            includeEmptyDirs = false
        }
    }
    into(jnaJniLibs)
}
val lwjglRevision = "166af615dbdca1a507b8c2d379822cca02795132"
val lwjglSha256 = "3ec756806be63e5446964464777146d7c87660d3d4e238d5b74c76d68d6070d7"
val lwjglArchive = layout.buildDirectory.file("downloads/lwjgl-3.3.3-natives.aar")
val lwjglJniLibs = layout.buildDirectory.dir("generated/lwjgl/jniLibs")
val lwjglAssets = layout.buildDirectory.dir("generated/lwjgl/assets")
val lwjglSource = "https://raw.githubusercontent.com/AngelAuraMC/Amethyst-Android/$lwjglRevision"
val lwjglAnnotations by configurations.creating
val rendererArchive = layout.buildDirectory.file("downloads/mobileglues-2.0.0.apk")
val rendererJniLibs = layout.buildDirectory.dir("generated/mobileglues/jniLibs")

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
        include(listOf("liblwjgl.so", "liblwjgl_opengl.so", "liblwjgl_stb.so",
            "libfreetype.so", "liblwjgl_tinyfd.so").map { prefix + it })
        eachFile { path = "arm64-v8a/" + name }
        includeEmptyDirs = false
    }
    into(lwjglJniLibs)
}
val downloadMobileGlues by tasks.registering {
    val expected = "a7e1eb29731fdece7ab3af7fb00692b856f1808a7544436e7b3eb6c08355581a"
    inputs.property("sha256", expected)
    outputs.file(rendererArchive)
    doLast {
        val bytes = verifiedDownload(
            "https://github.com/MobileGL-Dev/MobileGlues-release/releases/download/V2.0.0/MobileGlues_2.0.0.apk",
            expected
        )
        rendererArchive.get().asFile.apply { parentFile.mkdirs() }.writeBytes(bytes)
    }
}
val unpackMobileGlues by tasks.registering(Sync::class) {
    dependsOn(downloadMobileGlues)
    from({ zipTree(rendererArchive.get().asFile) }) {
        include("lib/arm64-v8a/libmobileglues.so")
        eachFile { path = path.removePrefix("lib/") }
        includeEmptyDirs = false
    }
    into(rendererJniLibs)
}
val prepareLwjglJars by tasks.registering {
    val jars = mapOf(
        "lwjgl" to "c26bc7db30a3414d907530eb3128c582612c10674328408e560feb5444ceefd1",
        "lwjgl-opengl" to "941bd9e9d1818f732b614e104a885d313e88368eab348b8541832ae12a414114",
        "lwjgl-stb" to "7a8145cadeee92d682515c8a37d561ed594026fa9d83a6b033eeaec4a587a010",
        "lwjgl-freetype" to "a9c05ef1df10e6a5b539a64bff5532fcf1d95c699e62978ae645ccc674de6864",
        "lwjgl-tinyfd" to "b1a62102ad86361fb3f68738cf13ef37d481a2e3b39bd04ce24de910dec7b79b"
    )
    inputs.property("revision", lwjglRevision)
    inputs.property("jars", jars)
    inputs.files(lwjglAnnotations)
    outputs.dir(lwjglAssets)
    doLast {
        val directory = lwjglAssets.get().dir("lwjgl/3.3.3").asFile.apply { mkdirs() }
        jars.forEach { (module, sha256) ->
            val source = "$lwjglSource/jre_lwjgl3glfw/lwjgl-3.3.3/libs/lwjgl-3.3.3/$module.jar"
            directory.resolve("$module-3.3.3.jar").writeBytes(verifiedDownload(source, sha256))
        }
        val core = directory.resolve("lwjgl-3.3.3.jar")
        val source = temporaryDir.resolve("Library.java")
        val upstream = verifiedDownload(
            "https://raw.githubusercontent.com/AngelAuraMC/lwjgl3/6a564a8b4b1438699e9d7ccb42548e4ba454fd30/" +
                "modules/lwjgl/core/src/main/java/org/lwjgl/system/Library.java",
            "b4681775003f6e47971b89ac77f7c716fc0bfb9abab3f90f25a8b79c02c95fc6"
        ).toString(Charsets.UTF_8)
        val start = upstream.indexOf("        try {\n            // pojavexec")
        val end = upstream.indexOf("        loadSystem(\"org.lwjgl\", JNI_LIBRARY_NAME);", start.coerceAtLeast(0))
        check(start >= 0 && end > start) { "No se encontró el bootstrap de Amethyst en Library.java." }
        source.writeText(upstream.removeRange(start, end))
        val classes = temporaryDir.resolve("classes").apply { mkdirs() }
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "Se requiere un JDK para compilar LWJGL." }
        val classpath = files(core, lwjglAnnotations).asPath
        check(compiler.run(null, null, null, "--release", "8", "-cp", classpath,
            "-d", classes.absolutePath, source.absolutePath) == 0) { "No se pudo recompilar Library.java." }
        val patched = temporaryDir.resolve("lwjgl-3.3.3.jar")
        ZipFile(core).use { input ->
            ZipOutputStream(patched.outputStream()).use { output ->
                input.entries().asSequence().forEach { entry ->
                    output.putNextEntry(ZipEntry(entry.name))
                    val replacement = classes.resolve(entry.name)
                    if (entry.name == "org/lwjgl/system/Library.class") {
                        replacement.inputStream().use { it.copyTo(output) }
                    } else {
                        input.getInputStream(entry).use { it.copyTo(output) }
                    }
                    output.closeEntry()
                }
            }
        }
        patched.copyTo(core, overwrite = true)
    }
}
tasks.named("preBuild") {
    dependsOn(unpackJnaAndroid, unpackLwjglAndroid, unpackMobileGlues, prepareLwjglJars)
}

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
    sourceSets.getByName("main").jniLibs.srcDir(rendererJniLibs)
    sourceSets.getByName("main").assets.srcDir(lwjglAssets)
    // La JVM de Minecraft carga las bibliotecas desde applicationInfo.nativeLibraryDir.
    packaging.jniLibs.useLegacyPackaging = true
    packaging.jniLibs.keepDebugSymbols.addAll(
        setOf("**/liblwjgl.so", "**/liblwjgl_opengl.so", "**/liblwjgl_stb.so", "**/liblwjgl_tinyfd.so")
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
    jnaAndroid.forEach { (version, archive) -> add(archive.name, "net.java.dev.jna:jna:$version@aar") }
    lwjglAnnotations("com.google.code.findbugs:jsr305:3.0.2")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material3:material3")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("org.tukaani:xz:1.9")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
