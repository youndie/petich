plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    alias(libs.plugins.sborkaKmp)
    alias(libs.plugins.sborkaLint)
    alias(libs.plugins.sborkaPublish)
}

kotlin {
    jvm()

    // The HTTP surface follows the engine onto the second target. Everything this module touches —
    // the plugin API, routing, receive/respond, StatusPages, the JSON negotiation bridge — is common
    // in Ktor and publishes a linuxX64 klib at the version this catalogue pins; the engine a
    // consumer mounts these routes on is CIO there, and this module depends on no engine.
    //
    // The change that made it possible is in the catalogue rather than here: the five Ktor
    // coordinates no longer end in `-jvm`.
    linuxX64()

    sourceSets {
        commonMain {
            dependencies {
                // api, not implementation: PetichPayload and PetichEngine appear in public
                // signatures (CreatePetichRequest.payload, PetichFeature). Without api a consuming
                // project gets petich-core at runtime only and cannot compile the call.
                api(projects.petichCore)
                // api: PetichFeature is an ApplicationPlugin and petichRouting is an extension
                // on Route, both from ktor-server-core. Installing the plugin or wiring the
                // routes is the entire point of this module, and neither compiles without them.
                api(libs.ktor.serverCore)
                implementation(libs.ktor.serverContentNegotiation)
                implementation(libs.ktor.serializationJson)
                implementation(libs.ktor.serverStatusPages)

                // api: the request and response types are @Serializable, so their generated
                // serializer() is part of the published surface.
                api(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        commonTest {
            dependencies {
                implementation(libs.ktor.serverTestHost)
                implementation(libs.ktor.serverContentNegotiation)
                implementation(libs.ktor.serializationJson)
            }
        }
    }
}
