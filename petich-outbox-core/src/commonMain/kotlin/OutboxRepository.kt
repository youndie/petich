package io.github.youndie.petich.outbox

public interface OutboxRepository {
    public suspend fun fetchPending(limit: Int = 50): List<OutboxRecord>

    public suspend fun markDelivered(id: String)

    // Increments retryCount and leaves the record PENDING, so the relay's next poll picks it up
    // again once its own backoff timer expires (see OutboxRelayWorker, which calls this only for
    // an event that has NOT yet exhausted maxAttempts).
    public suspend fun markFailed(id: String)

    // A terminal status: the record is no longer returned by fetchPending, unlike markFailed,
    // which leaves it PENDING. Called by OutboxRelayWorker once delivery attempts are exhausted
    // (see maxAttempts). Such an event needs a human to look at it, but it must not burn CPU and
    // log space forever on attempts that will keep failing.
    public suspend fun markDeadLettered(id: String)
}
