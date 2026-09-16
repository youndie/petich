package io.github.youndie.petich.conformance

import io.github.youndie.petich.ExpiringPetichRepository
import io.github.youndie.petich.OutboxAwarePetichRepository
import io.github.youndie.petich.OutboxEvent
import io.github.youndie.petich.Petich
import io.github.youndie.petich.PetichPhase
import io.github.youndie.petich.PetichRepository
import io.github.youndie.petich.PetichStatus
import io.github.youndie.petich.outbox.OutboxRecord

/** What the corpus is run against: one store, plus a way to look at the outbox it may own. */
public interface PetichStoreSubject : ConformanceSubject {
    public val repository: PetichRepository

    /**
     * Every outbox row the store currently holds, or `null` when this store has no outbox at all.
     *
     * Read directly rather than through `OutboxRepository.fetchPending`, because two of the rules
     * below are about rows that must NOT exist, and "fetchPending returns nothing" is also what a
     * store with a broken fetch says.
     */
    public suspend fun outboxRows(): List<OutboxRecord>?
}

/**
 * Every rule `PetichRepository` and its two optional extensions promise, as runnable cases.
 *
 * WHY THIS EXISTS WHILE THERE IS ONE IMPLEMENTATION. A corpus written after the second store
 * describes the intersection of the two — whatever both happen to do becomes the rule, including
 * whatever both happen to get wrong. Written first, it is a statement of what petich promises, and
 * the second implementation is measured against it rather than consulted.
 *
 * WHAT IS DELIBERATELY NOT A RULE HERE:
 *  * the order `fetchPending` returns events in. The engine promises at-least-once delivery, not
 *    order, and the one implementation orders by a stamp several replicas write (youndie/petich#20);
 *  * anything about DDL. petich ships no migrations, so a store is free to lay out its tables as it
 *    likes as long as the behaviour below holds;
 *  * concurrency between two stores of the same rows. The corpus runs one caller at a time, and a
 *    store that passes it can still hand one row to two workers — that needs its own test, next to
 *    the store, with real competition (see the note in B-07).
 */
public class PetichStoreConformance {
    /** Every case, in the order they run. Public so a reader can see the corpus without running it. */
    public val cases: List<Case<PetichStoreSubject>> = corpus()

    public suspend fun run(subject: PetichStoreSubject): List<Finding> = runCorpus(cases, subject)

    private fun case(
        rule: String,
        check: suspend (PetichStoreSubject) -> String?,
    ) = Case(rule, check)

    private fun corpus(): List<Case<PetichStoreSubject>> =
        listOf(
            case("an unknown id reads back as null") { subject ->
                expect(subject.repository.findById("absent") == null) {
                    "findById returned something for an id that was never saved"
                }
            },
            case("what saveOrGet stored is what findById returns") { subject ->
                val petich = petich(id = "saved")
                subject.repository.saveOrGet(petich)
                val read = subject.repository.findById("saved")
                expect(read == petich) {
                    "stored $petich, read back $read"
                }
            },
            case("saveOrGet on an id that exists returns the stored one and overwrites nothing") { subject ->
                val first = petich(id = "twice", marker = "first")
                subject.repository.saveOrGet(first)
                val second = petich(id = "twice", marker = "second", status = PetichStatus.COMPLETED)
                val returned = subject.repository.saveOrGet(second)
                val stored = subject.repository.findById("twice")
                expect(returned == first && stored == first) {
                    "saveOrGet returned $returned and left $stored; the row saved first was $first"
                }
            },
            case("an update whose version follows the stored one applies, and every field lands") { subject ->
                subject.repository.saveOrGet(petich(id = "moved"))
                val next =
                    petich(id = "moved", marker = "moved-on")
                        .copy(
                            status = PetichStatus.PENDING_SIGNATURE,
                            currentPhase = PetichPhase.EXECUTION,
                            currentInterceptorIndex = 3,
                            suspendedUntilEpochMs = 4_000L,
                            version = 1L,
                        )
                val applied = subject.repository.update(next)
                val stored = subject.repository.findById("moved")
                expect(applied && stored == next) {
                    "update returned $applied and left $stored, expected $next"
                }
            },
            case("an update carrying a stale version is refused and changes nothing") { subject ->
                val initial = petich(id = "raced")
                subject.repository.saveOrGet(initial)
                subject.repository.update(initial.copy(version = 1L, payload = ConformancePayload("first-writer")))
                val stale = initial.copy(version = 1L, payload = ConformancePayload("second-writer"))
                val applied = subject.repository.update(stale)
                val stored = subject.repository.findById("raced")
                expect(!applied && (stored?.payload as? ConformancePayload)?.marker == "first-writer") {
                    "update with a stale version returned $applied and left ${stored?.payload}"
                }
            },
            case("an update of an id that does not exist is refused") { subject ->
                expect(!subject.repository.update(petich(id = "ghost").copy(version = 1L))) {
                    "update returned true for a petich that was never saved"
                }
            },
            case("outbox events land in the same call that applies the update") { subject ->
                val repository = subject.repository as? OutboxAwarePetichRepository ?: return@case null
                subject.outboxRows() ?: return@case null
                repository.saveOrGet(petich(id = "emitting"))
                repository.update(
                    petich(id = "emitting").copy(version = 1L),
                    listOf(event("evt-1"), event("evt-2")),
                )
                val rows = subject.outboxRows().orEmpty()
                expect(rows.map { it.id }.sorted() == listOf("evt-1", "evt-2")) {
                    "after an applied update the outbox holds ${rows.map { it.id }}"
                }
            },
            case("an update that is refused writes no events") { subject ->
                val repository = subject.repository as? OutboxAwarePetichRepository ?: return@case null
                subject.outboxRows() ?: return@case null
                repository.saveOrGet(petich(id = "refused"))
                repository.update(petich(id = "refused").copy(version = 1L))
                // Stale now: the stored version is already 1.
                repository.update(
                    petich(id = "refused").copy(version = 1L),
                    listOf(event("must-not-exist")),
                )
                val rows = subject.outboxRows().orEmpty()
                expect(rows.none { it.id == "must-not-exist" }) {
                    "an update that did not apply left ${rows.map { it.id }} in the outbox"
                }
            },
            case("a suspended petich past its deadline is found expired") { subject ->
                val repository = subject.repository as? ExpiringPetichRepository ?: return@case null
                repository.saveOrGet(
                    petich(id = "overdue").copy(
                        status = PetichStatus.PENDING_SIGNATURE,
                        suspendedUntilEpochMs = 1_000L,
                    ),
                )
                val found = repository.findExpired(nowEpochMs = 2_000L, limit = 10)
                expect(found.map { it.id } == listOf("overdue")) {
                    "findExpired(2000) returned ${found.map { it.id }}"
                }
            },
            case("a deadline in the future is not expired yet") { subject ->
                val repository = subject.repository as? ExpiringPetichRepository ?: return@case null
                repository.saveOrGet(
                    petich(id = "waiting").copy(
                        status = PetichStatus.PENDING_SIGNATURE,
                        suspendedUntilEpochMs = 9_000L,
                    ),
                )
                val found = repository.findExpired(nowEpochMs = 2_000L, limit = 10)
                expect(found.isEmpty()) { "findExpired(2000) returned ${found.map { it.id }} for a deadline at 9000" }
            },
            case("a petich that is not awaiting a signature is never expired, deadline or not") { subject ->
                val repository = subject.repository as? ExpiringPetichRepository ?: return@case null
                repository.saveOrGet(
                    petich(id = "processing").copy(
                        status = PetichStatus.PROCESSING,
                        suspendedUntilEpochMs = 1_000L,
                    ),
                )
                repository.saveOrGet(
                    petich(id = "no-deadline").copy(status = PetichStatus.PENDING_SIGNATURE),
                )
                val found = repository.findExpired(nowEpochMs = 2_000L, limit = 10)
                expect(found.isEmpty()) { "findExpired(2000) returned ${found.map { it.id }}" }
            },
            case("findExpired returns no more than the limit asked for") { subject ->
                val repository = subject.repository as? ExpiringPetichRepository ?: return@case null
                repeat(5) { index ->
                    repository.saveOrGet(
                        petich(id = "overdue-$index").copy(
                            status = PetichStatus.PENDING_SIGNATURE,
                            suspendedUntilEpochMs = 1_000L,
                        ),
                    )
                }
                val found = repository.findExpired(nowEpochMs = 2_000L, limit = 2)
                expect(found.size == 2) { "findExpired(limit = 2) returned ${found.size} rows" }
            },
        )

    private fun petich(
        id: String,
        marker: String = id,
        status: PetichStatus = PetichStatus.PROCESSING,
    ) = Petich(
        id = id,
        type = "conformance",
        status = status,
        payload = ConformancePayload(marker),
    )

    private fun event(id: String) =
        object : OutboxEvent {
            override val id: String = id
            override val type: String = "conformance.event"
            override val payload: String = """{"marker":"$id"}"""
        }
}
