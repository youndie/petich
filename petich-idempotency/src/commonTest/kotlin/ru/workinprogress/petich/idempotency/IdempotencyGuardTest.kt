package ru.workinprogress.petich.idempotency

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.test.Test
import kotlin.test.assertEquals

// In-memory implementation, but with a real Mutex around tryClaim, so that the concurrency test
// below exercises the actual "claim atomically" contract rather than two functions called one
// after the other.
private class FakeRepository : IdempotencyRepository {
    private val mutex = Mutex()
    private val records = mutableMapOf<String, IdempotencyRecord>()

    override suspend fun tryClaim(
        key: String,
        requestFingerprint: String,
    ): Boolean =
        mutex.withLock {
            if (records.containsKey(key)) {
                false
            } else {
                records[key] = IdempotencyRecord(key, requestFingerprint)
                true
            }
        }

    override suspend fun find(key: String): IdempotencyRecord? = records[key]
}

class IdempotencyGuardTest {
    @Test
    fun `a brand new key is FirstUse`() =
        runBlocking {
            val repository = FakeRepository()

            val result = IdempotencyGuard.check(repository, "key-1", "fingerprint-a")

            assertEquals(IdempotencyCheck.FirstUse, result)
        }

    @Test
    fun `reusing the same key with the same fingerprint is SameRequest`() =
        runBlocking {
            val repository = FakeRepository()
            IdempotencyGuard.check(repository, "key-1", "fingerprint-a")

            val result = IdempotencyGuard.check(repository, "key-1", "fingerprint-a")

            assertEquals(IdempotencyCheck.SameRequest, result)
        }

    @Test
    fun `reusing the same key with a different fingerprint is FingerprintMismatch`() =
        runBlocking {
            val repository = FakeRepository()
            IdempotencyGuard.check(repository, "key-1", "fingerprint-a")

            val result = IdempotencyGuard.check(repository, "key-1", "fingerprint-b")

            assertEquals(IdempotencyCheck.FingerprintMismatch, result)
        }

    @Test
    fun `different keys are independent`() =
        runBlocking {
            val repository = FakeRepository()

            val first = IdempotencyGuard.check(repository, "key-1", "fingerprint-a")
            val second = IdempotencyGuard.check(repository, "key-2", "fingerprint-a")

            assertEquals(IdempotencyCheck.FirstUse, first)
            assertEquals(IdempotencyCheck.FirstUse, second)
        }
}
