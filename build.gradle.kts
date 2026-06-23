import io.gitlab.arturbosch.detekt.Detekt
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.spotless)
    alias(libs.plugins.cyclonedx) apply false
}

allprojects {
    apply(plugin = rootProject.libs.plugins.detekt.get().pluginId)
    apply(plugin = rootProject.libs.plugins.ktlint.get().pluginId)
    apply(plugin = rootProject.libs.plugins.spotless.get().pluginId)

    detekt {
        toolVersion = rootProject.libs.versions.detekt.get()
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        baseline = rootProject.file("config/detekt/baseline.xml")
        buildUponDefaultConfig = true
        autoCorrect = false
        parallel = true
    }

    tasks.withType<Detekt>().configureEach {
        jvmTarget = rootProject.libs.versions.javaTarget.get()
        reports {
            html.required.set(true)
            xml.required.set(true)
            sarif.required.set(true)
            md.required.set(false)
        }
    }

    extensions.configure<KtlintExtension> {
        version.set("1.3.1")
        android.set(true)
        // V1: ktlint findings are reported but don't fail the build.
        // Style cleanup is tracked for V2; CI gate is currently functional correctness.
        ignoreFailures.set(true)
        reporters {
            reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.HTML)
            reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
        }
        filter {
            exclude { it.file.path.contains("/build/") }
        }
    }

    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            target("**/*.kt")
            targetExclude("**/build/**/*.kt", "**/generated/**/*.kt")
            ktlint("1.3.1").editorConfigOverride(
                mapOf(
                    "android" to "true",
                    "ktlint_standard_filename" to "disabled",
                ),
            )
            licenseHeader(
                """
                // All rights reserved (private)
                // Copyright (C) ${'$'}YEAR Labushuya
                """.trimIndent(),
            )
        }
        kotlinGradle {
            target("**/*.gradle.kts")
            targetExclude("**/build/**/*.gradle.kts")
            ktlint("1.3.1")
        }
        format("misc") {
            target("**/*.md", "**/.gitignore")
            targetExclude("**/build/**")
            trimTrailingWhitespace()
            endWithNewline()
        }
    }
}

// Aggregator-Task for CI quality gate
tasks.register("staticAnalysis") {
    group = "verification"
    description = "Runs detekt, ktlint, spotless across all modules"
    dependsOn(
        subprojects.flatMap { sub ->
            listOf("detekt", "ktlintCheck", "spotlessCheck").map { task -> "${sub.path}:$task" }
        },
    )
}

tasks.register<Delete>("cleanAll") {
    delete(rootProject.layout.buildDirectory)
    subprojects.forEach { delete(it.layout.buildDirectory) }
}

// Reproducible-build hardening at archive level (applies to every module)
subprojects {
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        dirPermissions { unix("rwxr-xr-x") }
        filePermissions { unix("rw-r--r--") }
    }
}
