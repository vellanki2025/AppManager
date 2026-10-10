plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.lucid.appstore.download"
    compileSdk = 35
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(libs.coroutines.android)
    api(libs.work.runtime)
    implementation(libs.datastore.preferences)
    implementation(libs.core.ktx)
}
