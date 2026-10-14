plugins {
    id("com.android.application")
}

// Injected by the GitHub Actions workflow from the BRAIN_TOKEN repo secret.
// Empty when the secret does not exist yet - the app then shows an honest
// "no API key in this build" message instead of crashing.
val brainToken = providers.environmentVariable("BRAIN_TOKEN").orNull ?: ""

android {
    namespace = "com.prem.aios"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.prem.aios"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "BRAIN_TOKEN", "\"$brainToken\"")
    }

    buildFeatures {
        buildConfig = true
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
