import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    // Gradle Tooling API for IsolatedTestRunner -- runs `test` against a
    // temp-directory copy of the affected modules. Confirmed working
    // (not ProjectTaskManager) via a disposable spike, 2026-07-28. Not on
    // Maven Central -- see the `gradle-releases` repository below.
    implementation("org.gradle:gradle-tooling-api:9.5.1")

    intellijPlatform {
        intellijIdea("2025.2.6.2")

        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")

        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            // 243 = 2024.3, so as not to exclude the real installed base.
            sinceBuild = "243"
            untilBuild = provider { null }
        }
    }

    // Same tooling bug as the other Gap Hunter Labs plugins (Gradle 9.5 +
    // IntelliJ Platform Gradle Plugin 2.16 + IDE 2025.2.6.2): the
    // bytecode instrumenter fails with "instrumentIdeaExtensions
    // doesn't support the nested element". Not required for
    // build/test/verifyPlugin.
    instrumentCode = false

    // Catch experimental/internal API usage locally, before Marketplace's
    // own verifier flags it post-upload.
    pluginVerification {
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.INTERNAL_API_USAGES,
            VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
            VerifyPluginTask.FailureLevel.EXPERIMENTAL_API_USAGES,
            VerifyPluginTask.FailureLevel.SCHEDULED_FOR_REMOVAL_API_USAGES,
        )
    }
}

// As of the 0.3.0 Freemium product-descriptor, buildSearchableOptions can
// no longer run headless (the platform requires presenting a real license
// dialog it can't show without a display) -- it hangs indefinitely rather
// than failing fast, confirmed by a real buildPlugin run. This plugin adds
// no Settings/Configurable searchable strings that task would ever cover,
// so disabling it costs nothing. Same fix the IntelliJ Platform Gradle
// Plugin docs recommend for paid plugins.
tasks.named("buildSearchableOptions") {
    enabled = false
}
