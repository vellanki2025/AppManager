import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.lucid.appstore.vehicle"
    compileSdk = 35
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

/** The car API is a compile-time stub shipped with the SDK platform (`optional/android.car.jar`). */
val androidSdkDir: String = rootProject.file("local.properties")
    .takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } }.getProperty("sdk.dir") }
    ?: System.getenv("ANDROID_HOME")
    ?: error("Set sdk.dir in local.properties or ANDROID_HOME")

dependencies {
    implementation(project(":download-core"))
    compileOnly(files("$androidSdkDir/platforms/android-35/optional/android.car.jar"))
}
