plugins {
    `java-library`
    `maven-publish`
}

description = "ModStreaming core: config, crypto, artifact container, HTTP client, in-memory class loader and runtime"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
}

dependencies {
    api(project(":api"))
    // Gson is shipped by Minecraft itself; the Fabric runtime jar therefore does not bundle it.
    implementation(libs.gson)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
    // The class loader tests define classes from test-compiled bytes.
    systemProperty("modstreaming.test.classes", sourceSets.test.get().output.classesDirs.asPath)
}

tasks.jar {
    archiveBaseName.set("modstreaming-core")
    manifest {
        attributes("Implementation-Title" to "ModStreaming Core", "Implementation-Version" to project.version)
    }
}

tasks.processResources {
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("modstreaming-core.properties") {
        expand("version" to version)
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "modstreaming-core"
            from(components["java"])
        }
    }
}
