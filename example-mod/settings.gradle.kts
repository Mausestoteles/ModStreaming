pluginManagement {
    repositories {
        mavenLocal() // ModStreaming artifacts published with `./gradlew publishToMavenLocal` in the parent project
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/") { name = "FabricMC" }
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie" }
    }
}

plugins {
    // Stonecutter 0.7.x is the last line supporting Gradle 8.14; 0.8+ requires Gradle 9.
    id("dev.kikugie.stonecutter") version "0.7.11"
}

stonecutter {
    create(rootProject) {
        versions("1.21.1", "1.21.4")
        vcsVersion = "1.21.1"
    }
}

rootProject.name = "streamdemo"
