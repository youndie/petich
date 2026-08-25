plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
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
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
