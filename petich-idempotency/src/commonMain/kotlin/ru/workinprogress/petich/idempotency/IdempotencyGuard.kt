package ru.workinprogress.petich.idempotency

// FirstUse/SameRequest mean the caller should CONTINUE processing as usual — replaying the result
// of a genuine retry is the engine's job, since the key is handed to it as the operation id (see
// the comment in IdempotencyRepository.kt). FingerprintMismatch means the caller must REJECT
// (409/422) rather than quietly process: the same key arrived with different request parameters,
// which is either a client bug (key reused for a different logical operation) or abuse.
sealed class IdempotencyCheck {
    data object FirstUse : IdempotencyCheck()

    data object SameRequest : IdempotencyCheck()

    data object FingerprintMismatch : IdempotencyCheck()
}

// This module has exactly one responsibility: detect that a key was reused with a DIFFERENT
// request. It deliberately does NOT store or replay the result itself — PetichEngine already
// does that (saveOrGet plus a short circuit on a terminal status) whenever the caller uses the
// idempotency key as Petich.id. Duplicating that here would create a second source of truth for
// one and the same fact.
object IdempotencyGuard {
    suspend fun check(
        repository: IdempotencyRepository,
        key: String,
        requestFingerprint: String,
    ): IdempotencyCheck {
        if (repository.tryClaim(key, requestFingerprint)) return IdempotencyCheck.FirstUse

        // tryClaim returned false — the key is already claimed by someone, possibly a concurrent
        // request with this same new key that got there a moment earlier. A null here would mean
        // a race between tryClaim and find at the storage level; degrade to FirstUse instead of
        // throwing, on the principle that quiet degradation beats a crash on a race we cannot win.
        val existing = repository.find(key) ?: return IdempotencyCheck.FirstUse
        return if (existing.requestFingerprint == requestFingerprint) {
            IdempotencyCheck.SameRequest
        } else {
            IdempotencyCheck.FingerprintMismatch
        }
    }
}
