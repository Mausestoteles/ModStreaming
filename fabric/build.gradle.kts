plugins {
    `java-library`
    `maven-publish`
}

description = "ModStreaming Fabric bootstrap: discovers embedded stream configs and loads streamed artifacts at client start"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
}

// api + core are bundled (flattened) into the mod jar; Gson comes from Minecraft itself.
val bundled: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    // Compile against api + core, but bundle them instead of declaring them as POM dependencies.
    compileOnly(project(":api"))
    compileOnly(project(":core"))
    bundled(project(":api"))
    bundled(project(":core"))
    testImplementation(project(":api"))
    testImplementation(project(":core"))

    compileOnly(libs.fabric.loader)
    compileOnly(libs.slf4j.api)
    // Provided by Minecraft at runtime; not bundled.
    compileOnly(libs.gson)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.gson)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.processResources {
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("fabric.mod.json") {
        expand("version" to version)
    }
}

tasks.jar {
    archiveBaseName.set("modstreaming-fabric")
    dependsOn(bundled)
    from(bundled.elements.map { files -> files.map { zipTree(it) } }) {
        exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes("Implementation-Title" to "ModStreaming Fabric", "Implementation-Version" to project.version)
    }
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "modstreaming-fabric"
            from(components["java"])
        }
    }
}
