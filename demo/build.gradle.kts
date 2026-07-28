plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.20" apply false
}

subprojects {
    apply(plugin = "java")

    repositories {
        mavenCentral()
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
