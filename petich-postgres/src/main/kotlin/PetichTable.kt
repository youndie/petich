package ru.workinprogress.petich.postgres

import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.json.json
import ru.workinprogress.petich.EnrichedPayload
import ru.workinprogress.petich.PetichPayload
import ru.workinprogress.petich.PetichPhase
import ru.workinprogress.petich.PetichStatus

class PetichTable(
    jsonFormat: Json,
) : Table("petiches") {
    val id = varchar("id", 255)
    val type = varchar("type", 100)

    val currentPhase = enumerationByName<PetichPhase>("current_phase", 50)
    val currentInterceptorIndex = integer("current_interceptor_index")
    val status = enumerationByName<PetichStatus>("status", 50)

    val payload = json<PetichPayload>("payload", jsonFormat)
    val enrichedPayload = json<EnrichedPayload>("enriched_payload", jsonFormat)

    val version = long("version")

    // The instant after which a suspended petich counts as expired (see
    // Petich.suspendedUntilEpochMs). Nullable: a petich with no TTL configured has no deadline.
    // The expiry query filters on this column, so a real database wants an index on
    // (status, suspended_until) — declared below rather than described here.
    val suspendedUntil = long("suspended_until").nullable()

    override val primaryKey = PrimaryKey(id)

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
