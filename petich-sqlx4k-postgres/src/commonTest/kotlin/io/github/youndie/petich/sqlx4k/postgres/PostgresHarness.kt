package io.github.youndie.petich.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.postgres.PostgreSQL

/** The environment, read the way each target can read it. */
internal expect fun testEnv(name: String): String?

/**
 * Which target this run is, as a table-name suffix.
 *
 * The two test tasks share one database — starting a second container for the second target would
 * double the wiring to answer the same question — so they must not share tables. A suffix is the
 * whole of that: `petiches_jvm` and `petiches_linuxx64` never see each other's rows, and a run of
 * one cannot turn the other red.
 */
internal expect val testTarget: String

/**
 * A real Postgres, started by Gradle and pointed at through the environment.
 *
 * **Not a fake, and not SQLite.** Half of what this store promises is SQL — the version predicate,
 * `ON CONFLICT DO NOTHING` as an atomic claim, the filters behind `findExpired` and `findDue` — and
 * none of it exists anywhere but in the database it was written for.
 *
 * **Not skipped when the database is missing, either.** A suite that quietly passes without one is
 * how a store ships untested; the message below says what to run instead.
 */
internal object PostgresHarness {
    /** Four writers compete in one test and each needs a connection of its own. */
    private const val POOL = 8

    fun open(): Driver =
        PostgreSQL(
            url = required("PETICH_TEST_POSTGRES_URL"),
            username = required("PETICH_TEST_POSTGRES_USER"),
            password = required("PETICH_TEST_POSTGRES_PASSWORD"),
            options =
                ConnectionPool.Options
                    .builder()
                    .maxConnections(POOL)
                    .build(),
        )

    /** Table names for this target, so the two test tasks never touch one another's rows. */
    fun tables(): Tables =
        Tables(
            petiches = "petiches_$testTarget",
            outbox = "outbox_events_$testTarget",
            idempotency = "idempotency_keys_$testTarget",
            schedule = "scheduled_jobs_$testTarget",
        )

    data class Tables(
        val petiches: String,
        val outbox: String,
        val idempotency: String,
        val schedule: String,
    )

    private fun required(name: String): String =
        testEnv(name)
            ?: error(
                "$name is not set. The store's tests need a real Postgres; Gradle starts one for " +
                    "them, so run `./gradlew :petich-sqlx4k-postgres:jvmTest` or `:linuxX64Test` " +
                    "rather than the test binary directly. Docker has to be available.",
            )
}

/** Apply the schema this module states, the way an application's own migration would. */
internal suspend fun Driver.createSchema(tables: PostgresHarness.Tables) {
    petichPostgresSchema(
        petiches = tables.petiches,
        outboxEvents = tables.outbox,
        idempotencyKeys = tables.idempotency,
        scheduledJobs = tables.schedule,
    ).forEach { execute(it).getOrThrow() }
}

/** Empty every table this module's tests write to. */
internal suspend fun Driver.truncate(tables: PostgresHarness.Tables) {
    execute(
        "TRUNCATE ${tables.petiches}, ${tables.outbox}, ${tables.idempotency}, ${tables.schedule}",
    ).getOrThrow()
}
