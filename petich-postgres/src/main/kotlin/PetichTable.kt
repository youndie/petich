package io.github.youndie.petich.postgres

import io.github.youndie.petich.EnrichedPayload
import io.github.youndie.petich.PetichPayload
import io.github.youndie.petich.PetichPhase
import io.github.youndie.petich.PetichStatus
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.json.json

public class PetichTable(
    jsonFormat: Json,
) : Table("petiches") {
    public val id: Column<String> = varchar("id", 255)
    public val type: Column<String> = varchar("type", 100)

    public val currentPhase: Column<PetichPhase> = enumerationByName<PetichPhase>("current_phase", 50)
    public val currentInterceptorIndex: Column<Int> = integer("current_interceptor_index")
    public val status: Column<PetichStatus> = enumerationByName<PetichStatus>("status", 50)

    public val payload: Column<PetichPayload> = json<PetichPayload>("payload", jsonFormat)
    public val enrichedPayload: Column<EnrichedPayload> = json<EnrichedPayload>("enriched_payload", jsonFormat)

    public val version: Column<Long> = long("version")

    // The instant after which a suspended petich counts as expired (see
    // Petich.suspendedUntilEpochMs). Nullable: a petich with no TTL configured has no deadline.
    // The expiry query filters on this column, so a real database wants an index on
    // (status, suspended_until) — declared below rather than described here.
    public val suspendedUntil: Column<Long?> = long("suspended_until").nullable()

    // How many times a rollback of this saga has given up (see Petich.compensationAttempts).
    // Defaulted in the DDL rather than only in Kotlin: a table that already holds sagas takes this
    // column through an ALTER, and a NOT NULL column with no default cannot be added to a non-empty
    // table at all. petich ships no migrations, so the generated statement is what a consumer runs.
    public val compensationAttempts: Column<Int> = integer("compensation_attempts").default(0)

    // When this row was last written, from the clock the store was given. Not part of Petich: the
    // engine has no use for it and a domain field would have to be carried, compared and kept in
    // step by every caller — exactly as outbox_events.created_at is the store's business and not
    // an OutboxEvent's.
    //
    // NOT INDEXED, and that is the decision rather than an omission. It changes on every one of the
    // eleven writes a six-step saga makes, so an index containing it would make every one of them a
    // non-HOT update on the busiest table in the system, to serve a query that runs once per poll.
    // The sweeper reaches its rows through the leading `status` column of the index below and
    // rechecks this from the heap: in a healthy system the non-terminal rows are a handful.
    public val updatedAt: Column<Long> = long("updated_at").default(0L)

    // The fingerprint of the steps this saga has already run (see Petich.chainFingerprint).
    // Nullable, and that is what makes the upgrade harmless: a row written before this column
    // existed carries null, and a null is never refused.
    public val chainFingerprint: Column<String?> = varchar("chain_fingerprint", 64).nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)

    // Declared, not merely recommended in a comment. Exposed's tooling treats a Table as the whole
    // description of the schema, so an index that exists in the database and not here is an index
    // MigrationUtils.statementsRequiredForDatabaseMigration proposes DROPping — handing a consumer
    // who followed the comment a plausible, clean-applying migration that removes the index from
    // the busiest table in the system. The cost of that is a sequential scan per sweep and no
    // error anybody sees.
    //
    // The name is part of the module's contract for the same reason: two consumers writing the DDL
    // by hand would otherwise pick two names for one index, and neither would match what this
    // table now generates.
    init {
        index("idx_petiches_status_suspended_until", false, status, suspendedUntil)
    }

    /**
     * What a schema generator cannot say, as statements to run once, after the table exists.
     *
     * **Only the fill factor, and only on this table.** A saga row is updated at every step
     * boundary — about eleven times for a six-step saga — and Postgres can keep those updates off
     * the index chain only while the page they live on has room for the new row version. At the
     * default 100 there is none: each update lands on another page, the table and its index bloat,
     * and autovacuum is left to clean up after a workload that was avoidable. 80 leaves a fifth of
     * each page for the versions that follow, which is the range this shape of table wants.
     *
     * **This is the asymmetry with `petich-sqlx4k-postgres`, stated rather than smoothed over.**
     * That module hands the application SQL, so its `CREATE TABLE` carries `WITH (fillfactor = 80)`
     * and there is nothing else to run. Exposed's `Table` cannot express a storage parameter, so on
     * this side it is an `ALTER` beside the generated DDL. Same setting, two shapes, because the two
     * modules state their schema in two different ways.
     *
     * On an empty table the `ALTER` and the `WITH` are equivalent: the setting governs how pages are
     * filled from then on, and there are none yet. On a table that already holds sagas it applies to
     * new pages only, and the bloat already there needs a `VACUUM FULL` or a `pg_repack` — which is
     * the application's call and its downtime, not this library's.
     */
    public fun tuningStatements(): List<String> = listOf("ALTER TABLE $tableName SET (fillfactor = 80);")
}
