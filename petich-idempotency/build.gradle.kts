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
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        commonTest {
            dependencies {
            }
        }
    }
}
