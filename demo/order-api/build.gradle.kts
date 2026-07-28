plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    implementation(project(":order-core"))
    testImplementation(kotlin("test"))
}
