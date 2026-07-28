import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "refactor-simulator"

pluginManagement {
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.1.20"
        id("org.jetbrains.changelog") version "2.5.0"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.16.0"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        mavenCentral()

        // org.gradle:gradle-tooling-api modern releases (8.x/9.x) aren't
        // published to Maven Central -- confirmed 2026-07-28 while
        // spiking IsolatedTestRunner. Needed for the isolated test run
        // mechanism (see testimpact/IsolatedTestRunner.kt).
        maven("https://repo.gradle.org/gradle/libs-releases")

        intellijPlatform {
            defaultRepositories()
        }
    }
}
