// A BUILD OF ITS OWN, for the same reason `native-consumer-probe` is one: a module inside the root
// build would resolve `project(":petich-core")`, and the question here is whether the page's
// examples compile against the library a READER would take.
//
// It is never included from the root `settings.gradle.kts`, so `./gradlew build` does not see it;
// `tools/readme-examples.py` runs it with `./gradlew -p tools/readme-probe`.

rootProject.name = "readme-probe"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    // Passed in by the runner, which reads it from this repository's version catalogue — a number
    // written out here would be a second place to bump.
    plugins {
        id("org.jetbrains.kotlin.jvm") version
            settings.providers.gradleProperty("probe.kotlinVersion").get()
        id("org.jetbrains.kotlin.plugin.serialization") version
            settings.providers.gradleProperty("probe.kotlinVersion").get()
    }
}

dependencyResolutionManagement {
    repositories {
        // The group comes from exactly one place and Central is excluded from serving it. Without
        // that the probe has a way to pass by accident: Central still serves the previous release,
        // so a run that failed to see the publication under test would compile against last
        // month's artefacts and report success.
        val petichFrom = providers.gradleProperty("probe.repository").orNull
        if (petichFrom == null) {
            mavenLocal {
                content { includeGroup("io.github.youndie.petich") }
            }
        } else {
            maven(petichFrom) {
                name = "probe-subject"
                content { includeGroup("io.github.youndie.petich") }
            }
        }
        mavenCentral {
            content { excludeGroup("io.github.youndie.petich") }
        }
    }
}
