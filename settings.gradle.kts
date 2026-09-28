pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net/") { name = "FabricMC" }
    }
}

rootProject.name = "modstreaming"

include("api", "core", "fabric", "gradle-plugin", "backend")

// The example mod is a Stonecutter/Loom build that downloads Minecraft.
// It is intentionally a separate build (see example-mod/README.md) so the
// framework itself builds without any Minecraft toolchain.
