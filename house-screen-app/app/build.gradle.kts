plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.house.screen"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.house.screen"
        minSdk = 24
        targetSdk = 30
        versionCode = 1
        versionName = "1.0"
        // The worker address is baked in so you only type the pairing code on the TV.
        val worker = (project.findProperty("workerUrl") as String?) ?: ""
        buildConfigField("String", "DEFAULT_BASE", "\"$worker\"")
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false }
}
