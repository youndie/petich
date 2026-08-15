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
                implementation(libs.kotlinx.coroutines.core)
                // Calendar arithmetic: "the same day next month" is not a shift by a fixed number
                // of milliseconds — months differ in length and daylight saving transitions exist.
                api(libs.kotlinx.datetime)
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
