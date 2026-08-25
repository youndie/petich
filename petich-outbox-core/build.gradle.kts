plugins {
    kotlin("multiplatform")
    id("petich.publishing")
}

group = "io.github.youndie"

repositories {
    mavenCentral()
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
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
