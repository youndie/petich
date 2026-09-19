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
}
