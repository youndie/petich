package ru.workinprogress.petich.outbox

// The concrete transport — a message queue, a webhook, a log — is the application's to implement.
// The contract lives in the library; the wire does not.
fun interface OutboxPublisher {
    suspend fun publish(event: OutboxRecord)
}
