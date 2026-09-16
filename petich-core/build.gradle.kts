plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    alias(libs.plugins.sborkaKmp)
    alias(libs.plugins.sborkaLint)
    alias(libs.plugins.sborkaPublish)
}

kotlin {
    jvm()

    // THE SECOND TARGET, AND THE REASON IS A CONSUMER RATHER THAN A ROADMAP.
    //
    // A Kotlin/Native consumer cannot take a jvm-only library at all. It is not a missing feature:
    // the build fails on resolution with "no matching variant", before its own first line compiles.
    // `tools/native-consumer-probe.py` reproduces exactly that against this repository (B-02), and
    // this line is what turns that run green.
    //
    // Nothing in the module changed to earn it. Every source file is in `commonMain`, the clock is a
    // parameter (`PetichClock`) rather than a call to the platform, and `java.*` appears nowhere —
    // the library was portable from the first day and did not say so.
    //
    // Only `linuxX64`, and that is a decision rather than a first instalment: it is where a server
    // built on Kotlin/Native runs. Apple and mingw targets would add test tasks nobody runs and
    // klibs nobody asked for. Adding one later is a line in this file, and the cost of leaving it
    // out today falls on nobody. docs/research/research-native-port.md, D2.
    linuxX64()

    sourceSets {
        commonMain {
            dependencies {
                // api, not implementation: both appear in public signatures, so a consumer
                // that only gets them at runtime cannot compile the call. Serialization through
                // the generated serializer() of the public @Serializable types (Petich and the
                // payload hierarchy), coroutines through SuspendedPetichSweeper.start, which
                // takes a CoroutineScope and returns a Job.
                api(libs.kotlinx.serialization.json)
                api(libs.kotlinx.coroutines.core)
            }
        }
        commonTest {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
