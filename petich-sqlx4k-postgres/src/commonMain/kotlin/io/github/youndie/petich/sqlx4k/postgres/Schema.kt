package io.github.youndie.petich.sqlx4k.postgres

/**
 * The statements that create the four tables this store reads and writes, as text the application
 * runs itself.
 *
 * **petich still ships no DDL, and this is not a departure from that.** The rule is that petich does
 * not own the schema's lifecycle: it opens no connection, runs no migration and decides no version.
 * What it must do is *state* what the queries need, because an index that lives only in prose is one
 * a migration does not create and a benchmark does not have.
 *
 * On the JVM side that statement is the Exposed `Table` object, which a schema generator can read
 * (`PetichTable`, `OutboxEventsTable`, and their declared indexes). Kotlin/Native has no such
 * generator, so here it is the SQL itself: append these to your own migration list, next to your own
 * tables, and keep the version numbering you already have. This module never executes them.
 *
 * **The names match `petich-postgres` column for column, on purpose.** A service moving from the JVM
 * to Kotlin/Native — or running both while it moves — points the two stores at one database, and a
 * saga written by either is read by the other. A schema that differed by a column name would make
 * that migration a data migration.
 *
 * **The types do not match, and that sentence was careful to say *names*.** Every JSON-shaped column
 * is `TEXT` here and `json()` in `PetichTable`, which Postgres creates as `json`. The promise above
 * is about behaviour rather than spelling, and it now has a test rather than an argument:
 * `NativeSchemaCompatibilityTest` runs the Exposed store's whole conformance corpus against a
 * database these statements built, step records included, and it is green. The divergence stays
 * because changing a shipped column's type rewrites a consumer's busiest table for tidiness; it is
 * stated in the README's upgrade notes so a consumer on the Exposed store writes `json` instead, and
 * `tools/schema-notes-audit.py` fails if a JSON column ever acquires a third spelling (B-34).
 */
public fun petichPostgresSchema(
    petiches: String = DEFAULT_PETICHES_TABLE,
    outboxEvents: String = DEFAULT_OUTBOX_TABLE,
    idempotencyKeys: String = DEFAULT_IDEMPOTENCY_TABLE,
    scheduledJobs: String = DEFAULT_SCHEDULE_TABLE,
): List<String> {
    listOf(petiches, outboxEvents, idempotencyKeys, scheduledJobs).forEach(::requireIdentifier)
    return listOf(
        """
        CREATE TABLE IF NOT EXISTS $petiches (
            id VARCHAR(255) PRIMARY KEY,
            type VARCHAR(100) NOT NULL,
            current_phase VARCHAR(50) NOT NULL,
            current_interceptor_index INT NOT NULL,
            status VARCHAR(50) NOT NULL,
            payload TEXT NOT NULL,
            enriched_payload TEXT NOT NULL,
            version BIGINT NOT NULL,
            suspended_until BIGINT,
            -- How many times a rollback of this saga has given up (Petich.compensationAttempts).
            -- DEFAULT 0 so the same column can be added by ALTER to a table that already holds
            -- sagas: petich ships no migrations, and a NOT NULL column with no default cannot be
            -- added to a non-empty table at all.
            compensation_attempts INT NOT NULL DEFAULT 0,
            -- When the row was last written, from the clock the store was given. DEFAULT 0 so the
            -- column can be added by ALTER to a table that already holds sagas; every write from
            -- this store sets it.
            --
            -- There is deliberately NO index on it. It changes on every one of the eleven writes a
            -- six-step saga makes, and an index containing it would turn each of those into a
            -- non-HOT update on the busiest table here, to serve a query that runs once per poll.
            -- The sweeper reaches its rows through the leading status column of the index below.
            updated_at BIGINT NOT NULL DEFAULT 0,
            -- The fingerprint of the steps this saga has already run (Petich.chainFingerprint).
            -- Nullable on purpose: a row written before the column existed carries NULL, and NULL
            -- is never refused, so an upgrade does not stop the sagas already in flight.
            chain_fingerprint VARCHAR(64),
            -- What each member recorded about what it did, by that member's key
            -- (Petich.stepRecords). DEFAULT '{}' so the column can be added by ALTER to a table
            -- that already holds sagas, and so a row written before it existed reads back as
            -- "nobody recorded anything" rather than as NULL.
            step_records TEXT NOT NULL DEFAULT '{}'
        ) WITH (fillfactor = 80);
        """.trimIndent(),
        // The sweeper's query is "status = PENDING_SIGNATURE and suspended_until <= now", run on
        // every tick against the busiest table in the system. Declared here for the same reason
        // PetichTable declares it: an index described in a comment is one a migration does not make.
        "CREATE INDEX IF NOT EXISTS idx_${petiches}_status_suspended_until " +
            "ON $petiches (status, suspended_until);",
        """
        CREATE TABLE IF NOT EXISTS $outboxEvents (
            id VARCHAR(255) PRIMARY KEY,
            type VARCHAR(100) NOT NULL,
            payload TEXT NOT NULL,
            status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
            retry_count INT NOT NULL DEFAULT 0,
            created_at BIGINT NOT NULL
        );
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_${outboxEvents}_status_created_at " +
            "ON $outboxEvents (status, created_at);",
        """
        CREATE TABLE IF NOT EXISTS $idempotencyKeys (
            key VARCHAR(255) PRIMARY KEY,
            request_fingerprint VARCHAR(64) NOT NULL,
            created_at BIGINT NOT NULL
        );
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS $scheduledJobs (
            id VARCHAR(64) PRIMARY KEY,
            owner_id VARCHAR(64) NOT NULL,
            type VARCHAR(64) NOT NULL,
            payload TEXT NOT NULL,
            recurrence VARCHAR(16) NOT NULL,
            next_run_at BIGINT NOT NULL,
            last_run_at BIGINT,
            active BOOLEAN NOT NULL DEFAULT TRUE,
            consecutive_failures INT NOT NULL DEFAULT 0
        );
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_${scheduledJobs}_active_next_run_at " +
            "ON $scheduledJobs (active, next_run_at);",
    )
}

/** The tables these stores read and write unless they are told other names. */
public const val DEFAULT_PETICHES_TABLE: String = "petiches"

/** @see DEFAULT_PETICHES_TABLE */
public const val DEFAULT_OUTBOX_TABLE: String = "outbox_events"

/** @see DEFAULT_PETICHES_TABLE */
public const val DEFAULT_IDEMPOTENCY_TABLE: String = "idempotency_keys"

/** @see DEFAULT_PETICHES_TABLE */
public const val DEFAULT_SCHEDULE_TABLE: String = "scheduled_jobs"
