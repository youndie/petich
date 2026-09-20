plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

// JVM only, and that is not a gap. Every type the page's examples name lives in `commonMain`, so a
// JVM compilation asks the whole question the examples raise; what a native consumer can RESOLVE is
// a different question and `native-consumer-probe` already asks it.
kotlin {
    jvmToolchain(21)
}

dependencies {
    val version = providers.gradleProperty("probe.petichVersion").get()
    implementation("io.github.youndie.petich:petich-core:$version")
    implementation(kotlin("test"))
}
