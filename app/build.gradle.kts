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
tasks.named("preBuild") { dependsOn(unpackJnaAndroid) }

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
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "prissma2026"
                keyAlias = System.getenv("KEY_ALIAS") ?: "prissma"
                keyPassword = System.getenv("KEY_PASSWORD") ?: "prissma2026"
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
    // La JVM de Minecraft carga libjnidispatch desde applicationInfo.nativeLibraryDir.
    packaging.jniLibs.useLegacyPackaging = true

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
