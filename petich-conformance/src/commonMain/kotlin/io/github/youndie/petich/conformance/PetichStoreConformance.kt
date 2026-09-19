package io.github.youndie.petich.conformance

import io.github.youndie.petich.ExpiringPetichRepository
import io.github.youndie.petich.OutboxAwarePetichRepository
import io.github.youndie.petich.OutboxEvent
import io.github.youndie.petich.Petich
import io.github.youndie.petich.PetichClock
import io.github.youndie.petich.PetichPhase
import io.github.youndie.petich.PetichRepository
import io.github.youndie.petich.PetichStatus
import io.github.youndie.petich.outbox.OutboxRecord

/**
 * The clock a subject's store stamps its rows from, which the corpus can move.
 *
 * One rule below cannot be written without it: whether an UPDATE re-stamps the row is invisible
 * while time stands still, because the stamp the INSERT wrote is already in the past. A store that
 * forgets the column in its update then answers every question correctly until a process dies — and
 * then hands a live instance's saga to the sweeper.
 */
public class MovableClock(
    private var now: Long = 1_000L,
) : PetichClock {
    override fun nowEpochMs(): Long = now

    public fun advanceBy(ms: Long) {
        now += ms
    }
}

/** What the corpus is run against: one store, plus a way to look at the outbox it may own. */
public interface PetichStoreSubject : ConformanceSubject {
    public val repository: PetichRepository

    /**
     * The clock the store under test was built with. Not defaulted: a subject that cannot supply
     * one would silently skip the rule that needs it, and a check that cannot find its subject
     * scores as a pass.
     */
    public val clock: MovableClock

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
                // The marker is unchanged: the payload is the one field an update must NOT move,
                // and the rule for that is the next one.
                val next =
                    petich(id = "moved")
                        .copy(
                            status = PetichStatus.PENDING_SIGNATURE,
                            currentPhase = PetichPhase.EXECUTION,
                            currentInterceptorIndex = 3,
                            suspendedUntilEpochMs = 4_000L,
                            // Non-zero on purpose. This case is the corpus's "every field lands"
                            // rule, and a field left at its default cannot tell a store that
                            // writes the column from one that forgot it exists.
                            compensationAttempts = 2,
                            // Non-default for the same reason as the line above: a null here would
                            // pass against a store that does not write the column at all, and a
                            // fingerprint that never comes back is a guard that never fires.
                            chainFingerprint = "deadbeef",
                            version = 1L,
                        )
                val applied = subject.repository.update(next)
                val stored = subject.repository.findById("moved")
                expect(applied && stored == next) {
                    "update returned $applied and left $stored, expected $next"
                }
            },
            case("an update does not change the payload") { subject ->
                // A saga does not change what it was asked to do, and the engine never tries to:
                // the payload is written once, by the insert. A store that sends it in every update
                // rewrites the largest column in the row on all eleven writes a six-step saga makes
                // — and past the TOAST threshold that is eleven full rewrites out of line, plus the
                // dead chunks they leave behind, for a value that was identical every time.
                val initial = petich(id = "immutable", marker = "as-asked")
                subject.repository.saveOrGet(initial)
                subject.repository.update(
                    initial.copy(version = 1L, currentInterceptorIndex = 1, payload = ConformancePayload("rewritten")),
                )
                val stored = subject.repository.findById("immutable")
                expect(stored?.payload == ConformancePayload("as-asked")) {
                    "the update moved the payload to ${(stored?.payload as? ConformancePayload)?.marker}"
                }
            },
            case("a rollback that gave up keeps its status and its attempt count") { subject ->
                // Two things at once, and both are about the INSERT rather than the update above:
                // COMPENSATION_FAILED is a longer name than any status that existed before, so a
                // store whose column is too narrow fails here; and the counter has to survive the
                // first write, not only a later one.
                val gaveUp =
                    petich(id = "gave-up", status = PetichStatus.COMPENSATION_FAILED)
                        .copy(compensationAttempts = 3)
                subject.repository.saveOrGet(gaveUp)
                val stored = subject.repository.findById("gave-up")
                expect(stored == gaveUp) {
                    "stored $gaveUp, read back $stored"
                }
            },
            case("an update carrying a stale version is refused and changes nothing") { subject ->
                // The witness is the step index, not the payload: an update does not move the
                // payload any more (see the rule above), so a store that ignored the version
                // predicate entirely would leave it unchanged too and pass this by accident.
                val initial = petich(id = "raced")
                subject.repository.saveOrGet(initial)
                subject.repository.update(initial.copy(version = 1L, currentInterceptorIndex = 1))
                val stale = initial.copy(version = 1L, currentInterceptorIndex = 9)
                val applied = subject.repository.update(stale)
                val stored = subject.repository.findById("raced")
                expect(!applied && stored?.currentInterceptorIndex == 1) {
                    "update with a stale version returned $applied and left index ${stored?.currentInterceptorIndex}"
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
            case("a saga in the status asked for is found stuck") { subject ->
                val repository = subject.repository as? ExpiringPetichRepository ?: return@case null
                repository.saveOrGet(petich(id = "stuck-1"))
                // Long.MAX_VALUE means "whenever it was written, it was before now", which is the
                // one threshold that holds whatever clock the store was given.
                val found = repository.findStuck(PetichStatus.PROCESSING, Long.MAX_VALUE, limit = 10)
                expect(found.map { it.id } == listOf("stuck-1")) {
                    "findStuck(PROCESSING) returned ${found.map { it.id }}"
                }
            },
            case("a saga in another status is not stuck in this one") { subject ->
                val repository = subject.repository as? ExpiringPetichRepository ?: return@case null
                repository.saveOrGet(petich(id = "done-1", status = PetichStatus.COMPLETED))
                val found = repository.findStuck(PetichStatus.PROCESSING, Long.MAX_VALUE, limit = 10)
                expect(found.none { it.id == "done-1" }) {
                    "findStuck(PROCESSING) returned ${found.map { it.id }}"
                }
            },
            case("an update re-stamps the row, so a saga being worked on is not called stuck") { subject ->
                val repository = subject.repository as? ExpiringPetichRepository ?: return@case null
                // The rule the other three cannot express. A store that stamps on INSERT and forgets
                // the column in its UPDATE answers all of them correctly — the stamp it wrote is
                // already in the past — and then, in production, hands the sweeper a saga a live
                // instance is in the middle of. Only moving the clock between the two writes tells
                // them apart.
                val initial = petich(id = "stamped")
                repository.saveOrGet(initial)
                val afterInsert = subject.clock.nowEpochMs()
                subject.clock.advanceBy(10_000L)
                repository.update(initial.copy(version = 1L, currentInterceptorIndex = 1))
                val found =
                    repository.findStuck(
                        PetichStatus.PROCESSING,
                        notTouchedSinceEpochMs = afterInsert + 1,
                        limit = 10,
                    )
                expect(found.none { it.id == "stamped" }) {
                    "findStuck returned ${found.map { it.id }}: the update left the stamp where the insert put it"
                }
            },
            case("findStuck returns no more than the limit asked for") { subject ->
                val repository = subject.repository as? ExpiringPetichRepository ?: return@case null
                repeat(3) { repository.saveOrGet(petich(id = "many-stuck-$it")) }
                val found = repository.findStuck(PetichStatus.PROCESSING, Long.MAX_VALUE, limit = 2)
                expect(found.size == 2) { "findStuck(limit = 2) returned ${found.size} rows" }
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
