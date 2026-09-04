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
                // api on both: the engine appears in the sink's constructor and chronik's types in
                // its signature, so a consumer that only got them at runtime could not wire it up.
                api(project(":petich-core"))
                api("io.github.youndie:chronik-core:0.1.0-SNAPSHOT")
            }
        }
        commonTest {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
