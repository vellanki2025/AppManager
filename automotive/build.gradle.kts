plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.lucid.appmanager"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.lucid.appmanager"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        // Feature flag default per build. Runtime value lives in DataStore and can be toggled from the status screen.
        buildConfigField("boolean", "DOWNLOAD_SERVICE_ENABLED", "true")
        // Install path default (FR-52, C5). Runtime value lives in DataStore; PACKAGE_MANAGER on both
        // build types because APK_OVERRIDE needs a writable partition that only userdebug images have.
        buildConfigField("String", "INSTALL_MODE", "\"PACKAGE_MANAGER\"")
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.coroutines.android)
    implementation(libs.work.runtime)
    implementation(libs.datastore.preferences)
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
}
