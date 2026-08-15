plugins {
    `maven-publish`
}

// The default version comes from gradle.properties so that a local build and publishToMavenLocal
// work without extra parameters. CI overrides it through -PVERSION (see afterEvaluate below),
// appending the run number.
version = findProperty("petich.version")?.toString() ?: "0.1.0"

plugins.withId("java") {
    extensions.configure<JavaPluginExtension> {
        withSourcesJar()
    }
}

// The KMP plugin registers publications on its own; the plain Kotlin/JVM plugin does not. Without
// this block a kotlin("jvm") module (here, petich-postgres) builds fine, its publish task reports
// success and uploads NOTHING — there is simply nothing to upload. It surfaced only by checking
// that the artifact resolves from the server: Gradle's exit code cannot tell that apart from a
// successful publish.
plugins.withId("org.jetbrains.kotlin.jvm") {
    afterEvaluate {
        publishing.publications.create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

publishing {
    repositories {
        maven {
            name = "wip"
            url = uri("https://reposilite.kotlin.website/snapshots")
            // /snapshots is readable anonymously; credentials are needed only for writing.
            credentials {
                username = findProperty("REPOSILITE_USER")?.toString()
                password = findProperty("REPOSILITE_SECRET")?.toString()
            }
        }
    }
}

afterEvaluate {
    findProperty("VERSION")?.toString()?.let { publishVersion ->
        publishing.publications.withType<MavenPublication>().configureEach {
            version = publishVersion
        }
    }
}
