plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("petich.publishing")
}

group = "io.github.youndie"

repositories {
    mavenCentral()
}

dependencies {
    // api, not implementation: the classes here IMPLEMENT interfaces from the modules listed
    // (ExposedPetichRepository : OutboxAwarePetichRepository, ExposedScheduleRepository :
    // ScheduleRepository, and so on). With implementation the dependency lands in the POM as
    // runtime, and a project consuming the published petich-postgres would not see those
    // supertypes at compile time.
    api(projects.petichCore)
    // Schedule storage: the bridge between :petich-scheduler and SQL, as for outbox and idempotency.
    api(projects.petichScheduler)
    api(projects.petichOutboxCore)
    api(projects.petichIdempotency)

    api(libs.exposed.core)
    api(libs.exposed.jdbc)
    api(libs.exposed.json) // JSON column support

    // No drivers and no connection pool here, deliberately: the module works with an Exposed
    // Database that is handed to it and does not know a line about which DBMS sits underneath.
    // Choosing a driver is the host's decision and is declared by the host. Otherwise anyone
    // taking this module into their project would get H2, Postgres and HikariCP thrown in, quite
    // possibly needing none of them.

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    // The module had no tests at all, which is how a class with no package and an index described
    // only in a comment both shipped: neither is visible from inside the module's own package.
    testImplementation(libs.kotlin.testJunit)
}
