plugins {
    kotlin("multiplatform")
    // The corpus stores a petich, and a petich carries a payload the storage serialises. That
    // payload has to exist somewhere, and it has to be @Serializable — see ConformancePayload.
    kotlin("plugin.serialization")
    alias(libs.plugins.sborkaKmp)
    alias(libs.plugins.sborkaLint)
    alias(libs.plugins.sborkaPublish)
}

kotlin {
    jvm()

    // The reason this module has the target before there is a second store: a corpus that only
    // runs on the JVM cannot accept an implementation written for Kotlin/Native, which is the one
    // thing it is being written for (B-07, and chronik's B-16 for the same mistake made once).
    linuxX64()

    sourceSets {
        commonMain {
            dependencies {
                // api on all four: every one of them appears in the subject interfaces a consumer
                // has to implement, so getting them at runtime only would not let them compile it.
                api(projects.petichCore)
                api(projects.petichOutboxCore)
                api(projects.petichIdempotency)
                api(projects.petichScheduler)

                // Used inside the corpus rather than in its signatures: the idempotency case that
                // means anything runs its claims on a real multi-threaded dispatcher.
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}
