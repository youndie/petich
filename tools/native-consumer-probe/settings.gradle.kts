// A BUILD OF ITS OWN, deliberately not a module of the root build.
//
// A module inside the root build would resolve `project(":petich-core")` — the very thing a
// consumer cannot do. The question this probe exists to ask is whether a Kotlin/Native build can
// resolve the PUBLISHED coordinate, and only a separate build asks it.
//
// It is never included from the root `settings.gradle.kts`, so `./gradlew build` does not see it;
// `tools/native-consumer-probe.py` runs it with `./gradlew -p tools/native-consumer-probe`.

rootProject.name = "native-consumer-probe"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    // The Kotlin version is passed in by the runner, which reads it from this repository's version
    // catalogue. A number written out here would be a second place to bump, and the day the two
    // disagreed the probe would be testing a compiler the library is not built with.
    plugins {
        id("org.jetbrains.kotlin.multiplatform") version
            settings.providers.gradleProperty("probe.kotlinVersion").get()
        // A consumer that STORES sagas needs this, and finding that out is part of what the probe
        // is for: the payload is written as polymorphic JSON, so `@Serializable` without the plugin
        // is an annotation nothing reads — and the failure arrives at the first write, as
        // "Serializer for class 'OrderPayload' is not found", rather than at compile time.
        id("org.jetbrains.kotlin.plugin.serialization") version
            settings.providers.gradleProperty("probe.kotlinVersion").get()
    }
}

dependencyResolutionManagement {
    repositories {
        // Only petich comes from the local publication, and petich comes ONLY from there. Without
        // the second half the probe has a way to pass by accident: Maven Central still serves
        // 0.1.0, so a build that failed to see the local publication would resolve the released
        // one instead and answer a question about last month's artefacts.
        mavenLocal {
            content { includeGroup("io.github.youndie.petich") }
        }
        mavenCentral {
            content { excludeGroup("io.github.youndie.petich") }
        }
    }
}
