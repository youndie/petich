package ru.workinprogress.petich.idempotency

// A persistent record that "this key has already been used with this request fingerprint". It
// deliberately does not store the result of the processing itself (see IdempotencyGuard):
// PetichEngine already replays a result on its own (saveOrGet plus a short circuit on a terminal
// status, when the key is used as Petich.id). This module guards against a DIFFERENT and equally
// common bug: the client reuses the same key for what is in fact a different request, either by
// mistake or by reusing one key across distinct logical operations.
data class IdempotencyRecord(
    val key: String,
    val requestFingerprint: String,
)

interface IdempotencyRepository {
    // Atomically tries to claim the key with this fingerprint. True means the key was new and is
    // now held by this call; false means it already existed — possibly claimed by a concurrent
    // request with the same key — and the caller must then consult find(key) to compare
    // fingerprints. This MUST be atomic at the storage level (a unique constraint plus catching
    // the insert conflict, not a separate "find, then insert"), otherwise two concurrent requests
    // carrying ONE new key both see "no such key yet" and both claim it.
    suspend fun tryClaim(
        key: String,
        requestFingerprint: String,
    ): Boolean

    suspend fun find(key: String): IdempotencyRecord?
}
