plugins {
    `java-gradle-plugin`
    `maven-publish`
}

description = "ModStreaming Gradle plugin: splits @Streamed classes out of the public jar, packages, signs and publishes streamed artifacts"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
}

val functionalTest: SourceSet by sourceSets.creating

dependencies {
    implementation(project(":api"))
    implementation(project(":core"))
    implementation(libs.asm)
    implementation(libs.asm.commons)
    implementation(libs.gson)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)

    "functionalTestImplementation"(platform(libs.junit.bom))
    "functionalTestImplementation"(libs.junit.jupiter)
    "functionalTestImplementation"(gradleTestKit())
    "functionalTestImplementation"(project(":core"))
    "functionalTestImplementation"(project(":api"))
    "functionalTestImplementation"(libs.gson)
    "functionalTestRuntimeOnly"(libs.junit.launcher)
}

gradlePlugin {
    testSourceSets(functionalTest)
    plugins {
        create("modStreaming") {
            id = "dev.modstreaming"
            implementationClass = "dev.modstreaming.gradle.ModStreamingPlugin"
            displayName = "ModStreaming"
            description = "Moves @Streamed implementation classes out of the public mod jar into a signed, encrypted artifact that is streamed at runtime."
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

val apiJar = project(":api").tasks.named<Jar>("jar")

val functionalTestTask = tasks.register<Test>("functionalTest") {
    description = "Runs Gradle TestKit tests against a synthetic mod project."
    group = "verification"
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
    useJUnitPlatform()
    dependsOn(apiJar)
    val apiJarPath = apiJar.flatMap { it.archiveFile }.map { it.asFile.absolutePath }
    inputs.file(apiJar.flatMap { it.archiveFile })
    doFirst {
        systemProperty("modstreaming.apiJar", apiJarPath.get())
    }
    // TestKit spawns Gradle daemons; keep them short lived.
    systemProperty("org.gradle.testkit.dir", layout.buildDirectory.dir("testkit").get().asFile.absolutePath)
}

tasks.check {
    dependsOn(functionalTestTask)
}

tasks.jar {
    archiveBaseName.set("modstreaming-gradle-plugin")
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        if (name == "pluginMaven") {
            artifactId = "modstreaming-gradle-plugin"
        }
    }
}
