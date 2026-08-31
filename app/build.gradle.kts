plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.siroha.flashtool"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.siroha.flashtool"
        // Android 10 (Q) through Android 16
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Only ship the ABIs we actually bundle qdl binaries for.
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    signingConfigs {
        // Production note: for a real Play Store release, replace this with a proper
        // upload keystore kept OUTSIDE the repo. This debug-signed config exists so
        // CI can build a release-shaped APK without requiring any repo secrets.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // No repo secrets: sign release builds with the checked-in debug key so
            // CI can produce an installable, testable artifact. Swap in a real
            // keystore + signing config before publishing to the Play Store.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Never compress the qdl binaries / firehose blobs so they can be read directly.
    androidResources {
        noCompress += listOf("mbn", "so")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi"
        )
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // Keep the raw qdl executables uncompressed inside the APK/native lib dir.
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Structured local logging shown in-app and exportable as a .log file
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Custom seed-color -> Material3 ColorScheme generation for Settings >
    // Appearance > "Custom color" (ported from FolkPatch's ColorSchemeGenerator).
    // Pinned to 2.0.0 (released Oct 2024, same era as this project's Kotlin
    // 2.0.21) rather than the newest release — anything from the 3.x/4.x
    // line is compiled with a much newer Kotlin (metadata version 2.3.0)
    // that this project's 2.0.21 compiler can't read, and fails with
    // "Module was compiled with an incompatible version of Kotlin" at
    // compileDebugKotlin. The dynamicColorScheme()/PaletteStyle API this
    // app uses has been stable since well before this version.
    implementation("com.materialkolor:material-kolor:2.0.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Script bypass untuk mengatasi bug folder desugar di on-device build
afterEvaluate {
    tasks.named("mergeExtDexDebug") {
        doFirst {
            val desugarDir = File(layout.buildDirectory.get().asFile, "intermediates/external_file_lib_dex_archives/debug/desugarDebugFileDependencies")
            if (!desugarDir.exists()) {
                desugarDir.mkdirs()
                println("=> Bypass: Folder desugar otomatis dibuat!")
            }
        }
    }
}