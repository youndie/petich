rootProject.name = "petich"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// The engine core: the saga itself, the interceptor pipeline, compensation, suspend/resume.
// Depends on nothing else in this repository.
include(":petich-core")

// The Ktor bridge: REST endpoints for creating and resuming a saga.
include(":petich-ktor")

// Storage on Exposed. The only module that knows about SQL, and the only one that depends on
// all the others at once.
include(":petich-postgres")

// The outbox mechanism. Deliberately does NOT depend on :petich-core: it knows only about a row
// of id/type/payload that must be delivered at least once.
include(":petich-outbox-core")

// Protection against reusing a key with a different request. Also independent of :petich-core:
// replaying the result of a genuine retry is the engine's own job.
include(":petich-idempotency")

// Scheduling: a saga that starts with no HTTP initiator. It knows only "it is time" and
// "here is the payload".
include(":petich-scheduler")
