package io.github.youndie.petich.conformance

import io.github.youndie.petich.idempotency.IdempotencyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

public interface IdempotencyStoreSubject : ConformanceSubject {
    public val repository: IdempotencyRepository
}

/**
 * What `IdempotencyRepository` promises. The interesting half is the last case: `tryClaim` is
 * documented as atomic at the STORAGE level — a unique constraint and a caught conflict, not a
 * "find, then insert" — and a store that reads before it writes passes every other case here.
 *
 * That case needs real competition to mean anything. It runs its claims on `Dispatchers.Default`,
 * which is several threads on both targets; a corpus run under a single-threaded test dispatcher
 * would let a read-then-write implementation through, and would be reporting the dispatcher rather
 * than the store.
 */
public class IdempotencyStoreConformance {
    public val cases: List<Case<IdempotencyStoreSubject>> = corpus()

    public suspend fun run(subject: IdempotencyStoreSubject): List<Finding> = runCorpus(cases, subject)

    private fun case(
        rule: String,
        check: suspend (IdempotencyStoreSubject) -> String?,
    ) = Case(rule, check)

    private fun corpus(): List<Case<IdempotencyStoreSubject>> =
        listOf(
            case("an unknown key reads back as null") { subject ->
                expect(subject.repository.find("absent") == null) {
                    "find returned something for a key that was never claimed"
                }
            },
            case("a new key is claimed and reads back with its fingerprint") { subject ->
                val claimed = subject.repository.tryClaim("key", "fingerprint-a")
                val stored = subject.repository.find("key")
                expect(claimed && stored?.requestFingerprint == "fingerprint-a") {
                    "tryClaim returned $claimed and find returned $stored"
                }
            },
            case("a key claimed twice is refused the second time") { subject ->
                subject.repository.tryClaim("key", "fingerprint-a")
                val second = subject.repository.tryClaim("key", "fingerprint-a")
                expect(!second) { "the same key was claimed twice" }
            },
            case("a second claim does not overwrite the fingerprint of the first") { subject ->
                subject.repository.tryClaim("key", "fingerprint-a")
                subject.repository.tryClaim("key", "fingerprint-b")
                val stored = subject.repository.find("key")
                expect(stored?.requestFingerprint == "fingerprint-a") {
                    "the stored fingerprint became ${stored?.requestFingerprint}; " +
                        "the comparison the guard makes would then never see a mismatch"
                }
            },
            case("two callers racing for one new key produce exactly one winner") { subject ->
                val winners =
                    withContext(Dispatchers.Default) {
                        coroutineScope {
                            (1..8)
                                .map { async { subject.repository.tryClaim("raced", "fingerprint-$it") } }
                                .awaitAll()
                        }
                    }.count { it }
                expect(winners == 1) {
                    "$winners of 8 concurrent callers were told the key was theirs; " +
                        "tryClaim is reading before it writes instead of letting the storage refuse"
                }
            },
        )
}
