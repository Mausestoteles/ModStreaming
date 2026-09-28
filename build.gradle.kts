plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.21" apply false
}

allprojects {
    group = "dev.modstreaming"
    version = property("modstreaming.version") as String
}

tasks.wrapper {
    gradleVersion = "8.14.3"
    distributionType = Wrapper.DistributionType.BIN
}
