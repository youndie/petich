package io.github.youndie.petich.conformance

import io.github.youndie.petich.scheduler.Recurrence
import io.github.youndie.petich.scheduler.ScheduleRepository
import io.github.youndie.petich.scheduler.ScheduledJob

public interface ScheduleStoreSubject : ConformanceSubject {
    public val repository: ScheduleRepository
}

/**
 * What `ScheduleRepository` promises: a job survives a round trip, the worker's own writes are
 * durable, and `findDue` selects on both conditions rather than on the clock alone.
 *
 * NOT A RULE HERE: what happens when `save` is given an id that exists with a DIFFERENT owner or
 * type. The worker never does that — it saves back the job it was handed, with the run state
 * changed — so the two implementations are free to differ until a consumer needs one answer. A
 * corpus rule invented for an operation nobody performs is a constraint on the next store for no
 * reason.
 */
public class ScheduleStoreConformance {
    public val cases: List<Case<ScheduleStoreSubject>> = corpus()

    public suspend fun run(subject: ScheduleStoreSubject): List<Finding> = runCorpus(cases, subject)

    private fun case(
        rule: String,
        check: suspend (ScheduleStoreSubject) -> String?,
    ) = Case(rule, check)

    private fun corpus(): List<Case<ScheduleStoreSubject>> =
        listOf(
            case("an unknown id reads back as null") { subject ->
                expect(subject.repository.findById("absent") == null) {
                    "findById returned something for a job that was never saved"
                }
            },
            case("what save stored is what findById returns") { subject ->
                val job = job(id = "saved")
                subject.repository.save(job)
                val read = subject.repository.findById("saved")
                expect(read == job) { "saved $job, read back $read" }
            },
            case("saving the same id again keeps the run state the worker wrote") { subject ->
                subject.repository.save(job(id = "ran"))
                val after =
                    job(id = "ran").copy(
                        nextRunAtEpochMs = 5_000L,
                        lastRunAtEpochMs = 1_000L,
                        consecutiveFailures = 2,
                        active = false,
                    )
                subject.repository.save(after)
                val read = subject.repository.findById("ran")
                expect(read == after) { "after the second save the row is $read, expected $after" }
            },
            case("a job whose time has come is due") { subject ->
                subject.repository.save(job(id = "now", nextRunAtEpochMs = 1_000L))
                val due = subject.repository.findDue(nowEpochMs = 1_000L, limit = 10)
                expect(due.map { it.id } == listOf("now")) {
                    "findDue at exactly the run time returned ${due.map { it.id }}"
                }
            },
            case("a job whose time has not come is not due") { subject ->
                subject.repository.save(job(id = "later", nextRunAtEpochMs = 9_000L))
                val due = subject.repository.findDue(nowEpochMs = 1_000L, limit = 10)
                expect(due.isEmpty()) { "findDue(1000) returned ${due.map { it.id }} for a run at 9000" }
            },
            case("a disabled job is never due, however long it has been waiting") { subject ->
                subject.repository.save(job(id = "cancelled", nextRunAtEpochMs = 1L).copy(active = false))
                val due = subject.repository.findDue(nowEpochMs = 9_000L, limit = 10)
                expect(due.isEmpty()) { "findDue returned a disabled job: ${due.map { it.id }}" }
            },
            case("findDue returns no more than the limit asked for") { subject ->
                repeat(5) { subject.repository.save(job(id = "due-$it", nextRunAtEpochMs = 1_000L)) }
                val due = subject.repository.findDue(nowEpochMs = 2_000L, limit = 2)
                expect(due.size == 2) { "findDue(limit = 2) returned ${due.size} jobs" }
            },
            case("findByOwner returns that owner's jobs and nobody else's") { subject ->
                subject.repository.save(job(id = "mine-1", ownerId = "owner-a"))
                subject.repository.save(job(id = "mine-2", ownerId = "owner-a"))
                subject.repository.save(job(id = "theirs", ownerId = "owner-b"))
                val mine = subject.repository.findByOwner("owner-a")
                expect(mine.map { it.id }.sorted() == listOf("mine-1", "mine-2")) {
                    "findByOwner(owner-a) returned ${mine.map { it.id }}"
                }
            },
        )

    private fun job(
        id: String,
        ownerId: String = "owner",
        nextRunAtEpochMs: Long = 1_000L,
    ) = ScheduledJob(
        id = id,
        ownerId = ownerId,
        type = "conformance",
        payload = """{"marker":"$id"}""",
        recurrence = Recurrence.ONCE,
        nextRunAtEpochMs = nextRunAtEpochMs,
    )
}
