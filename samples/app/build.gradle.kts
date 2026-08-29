/*
 * Three tiny, real, installable APKs used as test fixtures for LucidAppStore.
 *
 * Each flavour is a different managed package. Pass -PversionBump=<n> to raise every flavour's
 * versionCode, which is how you simulate "a newer version is available on the server":
 *
 *     ./gradlew :samples:app:assembleDebug -PversionBump=5
 */
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val versionBump = (project.findProperty("versionBump") as String?)?.toInt() ?: 0

android {
    namespace = "com.lucid.samples"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
        targetSdk = 35
    }

    flavorDimensions += "sample"
    productFlavors {
        create("media") {
            dimension = "sample"
            applicationId = "com.lucid.auto.media"
            versionCode = 10100 + versionBump
            versionName = "1.1.$versionBump"
            resValue("string", "sample_label", "Lucid Media")
        }
        create("radio") {
            dimension = "sample"
            applicationId = "com.lucid.auto.radio"
            versionCode = 20000 + versionBump
            versionName = "2.0.$versionBump"
            resValue("string", "sample_label", "Lucid Radio")
        }
        create("spotify") {
            dimension = "sample"
            // A stand-in for the real Spotify package so the third row is exercisable.
            // Install this on an emulator only — never on a device with real Spotify.
            applicationId = "com.spotify.music"
            versionCode = 89050000 + versionBump
            versionName = "8.9.5$versionBump"
            resValue("string", "sample_label", "Spotify (sample)")
        }
    }

    buildTypes {
        debug { isMinifyEnabled = false }
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets { getByName("main").java.srcDirs("src/main/kotlin") }
}

dependencies {
    implementation(libs.androidx.core.ktx)
}
