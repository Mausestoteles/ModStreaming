plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

description = "ModStreaming reference backend (Kotlin + Ktor): artifact upload, storage, verification and download with a pluggable AuthorizationProvider"

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("dev.modstreaming.backend.MainKt")
}

dependencies {
    implementation(project(":api"))
    implementation(project(":core"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveBaseName.set("modstreaming-backend")
}
