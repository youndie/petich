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
