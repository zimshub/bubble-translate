plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zim.bubbletranslate"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zim.bubbletranslate"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        // Phones only (no emulator/x86 builds). Keeps the APK smaller.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // A fixed signing key kept in the repo, so every build can install
    // over the previous one without uninstalling first.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("debug") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false }
}

dependencies {
    // Reads text out of a picture of the screen (one model per writing system).
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    // Works out which language a piece of text is in.
    implementation("com.google.mlkit:language-id:17.0.6")
    // Translates on the phone itself after a one-time language pack download.
    implementation("com.google.mlkit:translate:17.0.3")
}
