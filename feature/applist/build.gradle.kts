plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.labushuya.hushd.feature.applist"
    compileSdk = 34
    defaultConfig {
        minSdk = 30
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions { jvmTarget = "21" }
    buildFeatures { compose = true; buildConfig = false }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // hiltViewModel() in Compose requires hilt-navigation-compose
    implementation(libs.hilt.navigation.compose)
    // collectAsStateWithLifecycle() + lifecycle-aware ViewModel access
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)
    // App icon async loading via PackageManager (coil-compose 2.7.0, defined in libs.versions.toml)
    implementation(libs.coil.compose)
    // BulkAutostopEngine + selection wiring
    implementation(projects.core.automation)
}
