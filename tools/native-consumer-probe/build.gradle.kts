plugins {
    kotlin("multiplatform")
}

// The consumer under test: a Kotlin/Native binary that declares petich coordinates and links.
//
// `linuxX64` and one executable, because that is the shape of the thing that cannot resolve petich
// today — a server built on Kotlin/Native. The `jvm()` half of the question is already answered by
// every other consumer in the portfolio and by the proba job in the publish workflow.
kotlin {
    linuxX64 {
        binaries.executable {
            entryPoint = "main"
        }
    }

    sourceSets {
        linuxX64Main {
            dependencies {
                val version = providers.gradleProperty("probe.petichVersion").get()
                // The list is a property so that the probe grows with the port: petich-core today,
                // petich-ktor once it has the target, the native store once it exists. A list
                // written out here would have to be edited by every item that adds a module, and
                // the runner is where the port's items already point.
                providers
                    .gradleProperty("probe.modules")
                    .get()
                    .split(",")
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .forEach { module ->
                        implementation("io.github.youndie.petich:$module:$version")
                    }
            }
        }
    }
}
