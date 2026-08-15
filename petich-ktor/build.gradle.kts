plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("petich.publishing")
}

group = "ru.workinprogress"

repositories {
    mavenCentral()
}

kotlin {
    jvm()

    sourceSets {
        commonMain {
            dependencies {
                // api, not implementation: PetichPayload and PetichEngine appear in public
                // signatures (CreatePetichRequest.payload, PetichFeature). Without api a consuming
                // project gets petich-core at runtime only and cannot compile the call.
                api(projects.petichCore)
                implementation(libs.ktor.serverCore)
                implementation(libs.ktor.serverContentNegotiation)
                implementation(libs.ktor.serializationJson)
                implementation(libs.ktor.serverStatusPages)

                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.ktor.serverTestHost)
                implementation(libs.ktor.serverContentNegotiation)
                implementation(libs.ktor.serializationJson)
            }
        }
    }
}
