plugins {
    `java-library`
    `maven-publish`
}

description = "ModStreaming API: loader-independent annotations and the Bridge used by public hooks"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveBaseName.set("modstreaming-api")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "modstreaming-api"
            from(components["java"])
        }
    }
}
