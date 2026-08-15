package ru.workinprogress.petich.outbox

// The persistent form of an event ready for delivery — what the relay reads from storage.
// Deliberately independent of :petich-core (ru.workinprogress.petich.OutboxEvent): this module
// knows nothing about petiches or interceptors, only about "a row with id/type/payload that must
// be delivered at least once". Bridging the two types is the storage layer's job (see
// ExposedPetichRepository and ExposedOutboxRepository in :petich-postgres).
data class OutboxRecord(
    val id: String,
    val type: String,
    val payload: String,
    val retryCount: Int,
)
