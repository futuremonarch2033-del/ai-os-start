plugins {
    id("com.android.application")
}

android {
    namespace = "com.prem.aios"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.prem.aios"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        named("debug") {
            // Standard debug-only keystore committed to the repo so every
            // cloud build signs with the same key and APKs install as updates.
            // Not a secret: it cannot sign release/Play builds.
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
}
