plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

// JVM only, and that is not a gap. Every type the page's examples name lives in `commonMain`, so a
// JVM compilation asks the whole question the examples raise; what a native consumer can RESOLVE is
// a different question and `native-consumer-probe` already asks it.
kotlin {
    // 21, and not the 25 the library itself now builds with: a reader on the floor
    // `sborka.jvmFloor` promises is the one whose compile this probe stands in for.
    jvmToolchain(21)
}

dependencies {
    val version = providers.gradleProperty("probe.petichVersion").get()
    implementation("io.github.youndie.petich:petich-core:$version")
    implementation(kotlin("test"))
}
