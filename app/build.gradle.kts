plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    // TODO(v2): re-enable cyclonedx plugin once SBOM generation is wired into the release workflow.
    // The configuration DSL changed in cyclonedx-gradle-plugin 1.10+, the old block in this file is incompatible.
    // alias(libs.plugins.cyclonedx)
}

// ---------------------------------------------------------------------------
// Signing config from env / gradle.properties. NEVER hard-code or commit.
// CI sets these via Decoded keystore in $RUNNER_TEMP (see release.yml).
// Local devs can put values in ~/.gradle/gradle.properties (NOT in repo).
// ---------------------------------------------------------------------------
val signingKeystorePath: String? =
    providers.environmentVariable("SIGNING_KEYSTORE_PATH").orNull
        ?: (project.findProperty("signing.keystorePath") as String?)
val signingKeystorePassword: String? =
    providers.environmentVariable("SIGNING_KEYSTORE_PASSWORD").orNull
        ?: (project.findProperty("signing.keystorePassword") as String?)
val signingKeyAlias: String? =
    providers.environmentVariable("SIGNING_KEY_ALIAS").orNull
        ?: (project.findProperty("signing.keyAlias") as String?)
val signingKeyPassword: String? =
    providers.environmentVariable("SIGNING_KEY_PASSWORD").orNull
        ?: (project.findProperty("signing.keyPassword") as String?)
val signingLineagePath: String? =
    providers.environmentVariable("SIGNING_LINEAGE_PATH").orNull
        ?: (project.findProperty("signing.lineagePath") as String?)

val hasReleaseSigning: Boolean = listOf(
    signingKeystorePath, signingKeystorePassword, signingKeyAlias, signingKeyPassword,
).all { !it.isNullOrBlank() }

// versionName / versionCode are injected by CI via -PversionName / -PversionCode.
// Local builds default to a clearly-marked dev version.
val resolvedVersionName: String =
    (project.findProperty("versionName") as String?) ?: "0.0.0-local"
val resolvedVersionCode: Int =
    (project.findProperty("versionCode") as String?)?.toInt() ?: 1

android {
    namespace = "dev.labushuya.hushd"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "dev.labushuya.hushd"
        minSdk = 30
        targetSdk = 34
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        resourceConfigurations += listOf("en", "de")

        // Locked-down BuildConfig — only what we need at runtime.
        buildConfigField("String", "GIT_SHA", "\"${project.findProperty("gitSha") ?: "local"}\"")
        buildConfigField("boolean", "REPRODUCIBLE", "true")
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(signingKeystorePath!!)
                storePassword = signingKeystorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
                // APK Signature Schemes — v1 disabled (legacy, broken),
                // v2/v3/v4 enabled for forward-compat + key rotation lineage.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            isProfileable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
            ndk { debugSymbolLevel = "NONE" }
            vcsInfo { include = false } // reproducibility — strip git metadata from APK
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        viewBinding = false
        aidl = false
        renderScript = false
        shaders = false
        resValues = false
    }

    composeOptions {
        // Kotlin 2.x uses the kotlin-compose plugin — kotlinCompilerExtensionVersion is not consumed
        // for K2, but kept here defensively for tooling that still reads it.
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        isCoreLibraryDesugaringEnabled = false
    }

    kotlinOptions {
        jvmTarget = libs.versions.javaTarget.get()
        freeCompilerArgs += listOf(
            "-Xjvm-default=all",
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "/META-INF/DEPENDENCIES",
                "/META-INF/*.kotlin_module",
                "/META-INF/versions/**",
                "/META-INF/proguard/**",
                "**/kotlin/**",
                "**/*.txt",
                "**/*.version",
                "DebugProbesKt.bin",
            )
            // Reproducibility: deterministic merge order
            pickFirsts += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
        }
        jniLibs {
            useLegacyPackaging = false
            excludes += setOf("**/libdatastore_shared_counter.so")
        }
    }

    androidResources {
        // We ship a hand-written res/xml/locales_config.xml referenced by the manifest.
        // Setting this to true would require resources.properties files in every values-* dir
        // (see https://developer.android.com/r/studio-ui/build/automatic-per-app-languages).
        generateLocaleConfig = false
    }

    // Reproducible-build levers (defense-in-depth; root build.gradle.kts also handles archive tasks)
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    bundle {
        language { enableSplit = false }
        density { enableSplit = false }
        abi { enableSplit = false }
    }

    lint {
        baseline = file("lint-baseline.xml")
        // V1: lint reports findings but doesn't gate CI. Hardened back in V2 after a
        // baseline-record run (./gradlew updateLintBaseline) is committed.
        warningsAsErrors = false
        abortOnError = false
        checkDependencies = true
        checkReleaseBuilds = false
        sarifReport = true
        disable += setOf("ObsoleteLintCustomCheck", "OldTargetApi")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = false
        }
        animationsDisabled = true
    }

    // TODO(v2): Room schema directory once Room is actually wired up (currently only a stub).
    // room {
    //     schemaDirectory("$projectDir/schemas")
    // }
}

// TODO(v2): SBOM (CycloneDX) — re-enable when SBOM generation is part of the release workflow.
// The block below is incompatible with cyclonedx-gradle-plugin 1.10+ — needs migration.
// cyclonedxBom {
//     includeConfigs.set(listOf("releaseRuntimeClasspath"))
//     skipConfigs.set(listOf("testCompileClasspath", "androidTestCompileClasspath"))
//     projectType.set("application")
//     schemaVersion.set("1.5")
//     destination.set(file("$buildDir/reports"))
//     outputName.set("bom")
//     outputFormat.set("json")
//     includeBomSerialNumber.set(true)
//     includeLicenseText.set(true)
// }

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.data)
    implementation(projects.core.ui)
    implementation(projects.core.automation)
    implementation(projects.service.accessibility)
    implementation(projects.service.overlay)
    implementation(projects.feature.applist)
    implementation(projects.feature.automation)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.startup)
    implementation(libs.androidx.savedstate)
    implementation(libs.bundles.lifecycle)

    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.compose.navigation)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler)
    ksp(libs.hilt.work.compiler)

    implementation(libs.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.timber)

    testImplementation(libs.bundles.test.unit)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(libs.bundles.test.android)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}

// JUnit 5 platform for unit tests
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
    // Reproducibility: stable test execution order
    systemProperty("junit.jupiter.testinstance.lifecycle.default", "per_class")
}

// CI-friendly fail-fast gate: refuse release build when not signed
tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    doFirst {
        check(hasReleaseSigning) {
            "Release build requires SIGNING_KEYSTORE_PATH/PASSWORD/KEY_ALIAS/KEY_PASSWORD " +
                "to be set in environment or ~/.gradle/gradle.properties. " +
                "See docs/KEYSTORE_SETUP.md."
        }
        check(resolvedVersionName != "0.0.0-local") {
            "Release build requires -PversionName=<x.y.z> -PversionCode=<n> on the command line. " +
                "CI passes these via ORG_GRADLE_PROJECT_versionName / ORG_GRADLE_PROJECT_versionCode."
        }
    }
}
