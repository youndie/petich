plugins {
    kotlin("multiplatform")
    alias(libs.plugins.sborkaKmp)
    alias(libs.plugins.sborkaLint)
    alias(libs.plugins.sborkaPublish)
}

kotlin {
    jvm()

    // The second target: a Kotlin/Native consumer cannot resolve a jvm-only library at all ("no
    // matching variant"). linuxX64 alone, for the reasons in docs/research/research-native-port.md
    // (D2) and in petich-core/build.gradle.kts, which carries the long version.
    linuxX64()

    sourceSets {
        commonMain {
            dependencies {
                // api: OutboxRelayWorker.start takes a CoroutineScope and returns a Job, so a
                // consumer needs to be able to name both to start the relay at all.
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
