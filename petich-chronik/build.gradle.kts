plugins {
    kotlin("multiplatform")
    // Test-only need: the saga table stores its payload as polymorphic JSON, so a payload used
    // in a test has to be @Serializable and registered.
    kotlin("plugin.serialization")
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
                api(libs.chronik.core)
            }
        }
        commonTest {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        jvmTest {
            dependencies {
                // A REAL POSTGRES, because the claim under test is that a timer row and a saga row
                // become visible together or not at all. Nothing smaller can be asked that: a fake
                // repository commits when the test tells it to, which is the answer being checked.
                implementation(project(":petich-postgres"))
                implementation(libs.chronik.postgres)
                implementation(libs.testcontainers.postgresql)
                implementation(libs.postgres.driver)
            }
        }
    }
}
