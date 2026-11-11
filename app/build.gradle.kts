
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.mathai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.mathai"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }
}

dependencies {
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
