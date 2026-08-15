package ru.workinprogress.petich.outbox

interface OutboxRepository {
    suspend fun fetchPending(limit: Int = 50): List<OutboxRecord>

    suspend fun markDelivered(id: String)

    // Increments retryCount and leaves the record PENDING, so the relay's next poll picks it up
    // again once its own backoff timer expires (see OutboxRelayWorker, which calls this only for
    // an event that has NOT yet exhausted maxAttempts).
    suspend fun markFailed(id: String)

    // A terminal status: the record is no longer returned by fetchPending, unlike markFailed,
    // which leaves it PENDING. Called by OutboxRelayWorker once delivery attempts are exhausted
    // (see maxAttempts). Such an event needs a human to look at it, but it must not burn CPU and
    // log space forever on attempts that will keep failing.
    suspend fun markDeadLettered(id: String)
}
