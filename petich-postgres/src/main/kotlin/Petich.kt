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
    // (status, suspended_until).
    val suspendedUntil = long("suspended_until").nullable()

    override val primaryKey = PrimaryKey(id)
}
