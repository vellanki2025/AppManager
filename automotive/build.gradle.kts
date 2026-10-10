plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.lucid.appstore"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.lucid.appstore"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        // Feature flag default per build. Runtime value lives in DataStore and can be toggled from the status screen.
        buildConfigField("boolean", "DOWNLOAD_SERVICE_ENABLED", "true")
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
