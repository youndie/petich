rootProject.name = "petich"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        // Written out by hand, and it has to be: `pluginManagement` is evaluated before any settings
        // plugin is applied — including the sborka one, which is fetched through it.
        maven("https://reposilite.kotlin.website/snapshots") {
            name = "wip-snapshots"
            content { includeGroupByRegex("ru\\.workinprogress.*") }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    // Repositories with content filters, the shared `wip` catalog, and the check that this
    // repository's `.editorconfig` is the one the rest of them use.
    id("ru.workinprogress.sborka.settings") version "0.1.0.4"
}

// PROVISIONAL, AND THIS BRANCH MUST NOT MERGE WITH IT.
//
// chronik has no published coordinate yet — it exists in a sibling repository and is installed to
// the local Maven cache by hand. A build that resolves from mavenLocal is a build that works on one
// machine and fails everywhere else, including CI, which is exactly the failure this repository's
// content filters exist to prevent. It is here so the bridge can be written and tested at all.
//
// Filtered to one group, in the same spirit as the filters the settings plugin installs: an
// unfiltered mavenLocal poisons resolution for everything else in the build.
dependencyResolutionManagement {
    repositories {
        mavenLocal {
            content { includeGroup("io.github.youndie") }
        }
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

// The bridge to chronik: a fired timer wakes a suspended saga. A module of its own so that the
// engine does not gain a dependency on a timer library that most applications will not use.
include(":petich-chronik")
