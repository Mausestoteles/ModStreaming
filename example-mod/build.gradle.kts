plugins {
    id("fabric-loom") version "1.13.6"
    id("dev.modstreaming") version "0.1.0"
}

val mcVersion: String = stonecutter.current.version
val streamModVersion: String = property("mod.version").toString()

group = "com.example"
version = "$streamModVersion+$mcVersion"
base.archivesName.set(property("mod.id").toString())

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://maven.fabricmc.net/") { name = "FabricMC" }
}

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    mappings("net.fabricmc:yarn:${property("deps.yarn")}:v2")
    modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")

    // ModStreaming runtime (bundles api + core); nested into the public jar via jar-in-jar.
    modImplementation("dev.modstreaming:modstreaming-fabric:${property("deps.modstreaming")}")
    include("dev.modstreaming:modstreaming-fabric:${property("deps.modstreaming")}")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

loom {
    runConfigs.all {
        ideConfigGenerated(false)
        runDir = "../../run"
    }
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("minecraft", mcVersion)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version, "minecraft" to mcVersion)
    }
}

/*
 * ModStreaming: every @Streamed class is removed from the public jar (build/libs/streamdemo-*.jar)
 * and packaged into a signed, encrypted artifact by `packageStreamed`. `build` never uploads;
 * `publishStreamed` does, using MODSTREAMING_PUBLISH_TOKEN.
 */
modStreaming {
    endpoint.set(property("streaming.endpoint").toString())
    projectId.set(property("streaming.project_id").toString())
    publicKey.set(property("streaming.public_key").toString())
    modVersion.set(streamModVersion)
    minecraftVersion.set(mcVersion)
    loader.set("fabric")
    // Private key: MODSTREAMING_SIGNING_KEY(_FILE) env var, or this git-ignored file created by `generateStreamedKeyPair`.
    signingKeyFile.set(rootProject.file("modstreaming-keys/modstreaming.private.key"))
}

tasks.named("generateStreamedKeyPair", dev.modstreaming.gradle.tasks.GenerateStreamedKeyPairTask::class) {
    outputDirectory.set(rootProject.layout.projectDirectory.dir("modstreaming-keys"))
}
