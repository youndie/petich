plugins {
    kotlin("multiplatform")
    alias(libs.plugins.sborkaKmp)
    alias(libs.plugins.sborkaLint)
    alias(libs.plugins.sborkaPublish)
}

kotlin {
    jvm()

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
