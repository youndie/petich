package ru.workinprogress.petich.scheduler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

private val UTC = TimeZone.UTC

private fun at(text: String): Long = LocalDateTime.parse(text).toInstant(UTC).toEpochMilliseconds()

private fun Long.asText(): String = Instant.fromEpochMilliseconds(this).toLocalDateTime(UTC).toString()

private class TestClock(
    var nowMs: Long,
) : SchedulerClock {
    override fun nowEpochMs(): Long = nowMs
}

private class InMemoryScheduleRepository : ScheduleRepository {
    val jobs = mutableMapOf<String, ScheduledJob>()

    // How many times a state write was attempted — this is what shows the worker actually
    // rescheduled the job rather than merely executing it.
    var saves = 0

    override suspend fun save(job: ScheduledJob): ScheduledJob {
        saves++
        jobs[job.id] = job
        return job
    }

    override suspend fun findById(id: String): ScheduledJob? = jobs[id]

    override suspend fun findDue(
        nowEpochMs: Long,
        limit: Int,
    ): List<ScheduledJob> = jobs.values.filter { it.active && it.nextRunAtEpochMs <= nowEpochMs }.take(limit)

    override suspend fun findByOwner(ownerId: String): List<ScheduledJob> = jobs.values.filter { it.ownerId == ownerId }
}

private fun job(
    id: String = "job-1",
    recurrence: Recurrence = Recurrence.MONTHLY,
    nextRunAt: Long = at("2026-01-15T09:00"),
    failures: Int = 0,
) = ScheduledJob(
    id = id,
    ownerId = "user1",
    type = "recurring_job",
    payload = """{"amount":100}""",
    recurrence = recurrence,
    nextRunAtEpochMs = nextRunAt,
    consecutiveFailures = failures,
)

class RecurrenceTest {
    @Test
    fun `daily moves one day forward keeping the time of day`() {
        assertEquals("2026-01-16T09:00", Recurrence.DAILY.nextRunAfter(at("2026-01-15T09:00"), UTC)!!.asText())
    }

    @Test
    fun `weekly moves seven days forward`() {
        assertEquals("2026-01-22T09:00", Recurrence.WEEKLY.nextRunAfter(at("2026-01-15T09:00"), UTC)!!.asText())
    }

    // The point of calendar arithmetic: "monthly" means the same DAY OF MONTH, not "plus 30 days".
    @Test
    fun `monthly keeps the day of month across months of different length`() {
        assertEquals("2026-03-15T09:00", Recurrence.MONTHLY.nextRunAfter(at("2026-02-15T09:00"), UTC)!!.asText())
        assertEquals("2026-05-01T09:00", Recurrence.MONTHLY.nextRunAfter(at("2026-04-01T09:00"), UTC)!!.asText())
    }

    // A job due "on the 31st" must land on the last day of February, not slide into March.
    @Test
    fun `monthly clamps the 31st to the length of the shorter month`() {
        assertEquals("2026-02-28T09:00", Recurrence.MONTHLY.nextRunAfter(at("2026-01-31T09:00"), UTC)!!.asText())
    }

    @Test
    fun `monthly on the 29th of a leap February lands correctly`() {
        assertEquals("2028-03-29T09:00", Recurrence.MONTHLY.nextRunAfter(at("2028-02-29T09:00"), UTC)!!.asText())
    }

    // A one-off job has no next time, by definition.
    @Test
    fun `once has no next run`() {
        assertEquals(null, Recurrence.ONCE.nextRunAfter(at("2026-01-15T09:00"), UTC))
    }
}

class SchedulerWorkerTest {
    private fun worker(
        repository: ScheduleRepository,
        clock: SchedulerClock,
        runner: ScheduledJobRunner,
        onGaveUp: (ScheduledJob) -> Unit = {},
    ) = SchedulerWorker(
        repository = repository,
        runner = runner,
        clock = clock,
        timeZone = UTC,
        onGaveUp = onGaveUp,
    )

    @Test
    fun `a job whose time has not come is not run`() =
        runTest {
            val repository = InMemoryScheduleRepository()
            repository.save(job(nextRunAt = at("2026-01-15T09:00")))
            var runs = 0

            val fired = worker(repository, TestClock(at("2026-01-14T09:00")), { runs++ }).tick()

            assertEquals(0, fired)
            assertEquals(0, runs)
        }

    @Test
    fun `a due job runs and is rescheduled to the next period`() =
        runTest {
            val repository = InMemoryScheduleRepository()
            repository.save(job(nextRunAt = at("2026-01-15T09:00")))
            val payloads = mutableListOf<String>()

            val fired = worker(repository, TestClock(at("2026-01-15T09:00")), { payloads += it.payload }).tick()

            assertEquals(1, fired)
            assertEquals(listOf("""{"amount":100}"""), payloads)
            assertEquals("2026-02-15T09:00", repository.jobs.getValue("job-1").nextRunAtEpochMs.asText())
        }

    // Counted from the SCHEDULED time, not the actual one: otherwise a job due "on the 15th" that
    // fired late would slowly drift through the calendar.
    @Test
    fun `a late run does not drift the schedule`() =
        runTest {
            val repository = InMemoryScheduleRepository()
            repository.save(job(nextRunAt = at("2026-01-15T09:00")))

            worker(repository, TestClock(at("2026-01-15T23:47")), {}).tick()

            assertEquals("2026-02-15T09:00", repository.jobs.getValue("job-1").nextRunAtEpochMs.asText())
        }

    // Missed periods are not caught up: for side-effecting work, three back-dated runs are more
    // dangerous than skipping them.
    @Test
    fun `periods missed while the worker was down are skipped, not replayed`() =
        runTest {
            val repository = InMemoryScheduleRepository()
            repository.save(job(nextRunAt = at("2026-01-15T09:00")))
            var runs = 0

            // The worker was down for three months.
            worker(repository, TestClock(at("2026-04-20T10:00")), { runs++ }).tick()

            assertEquals(1, runs, "missed periods must not be executed retroactively")
            assertEquals("2026-05-15T09:00", repository.jobs.getValue("job-1").nextRunAtEpochMs.asText())
        }

    @Test
    fun `a ONCE job is closed after its single run`() =
        runTest {
            val repository = InMemoryScheduleRepository()
            repository.save(job(recurrence = Recurrence.ONCE, nextRunAt = at("2026-01-15T09:00")))

            worker(repository, TestClock(at("2026-01-15T09:00")), {}).tick()

            val stored = repository.jobs.getValue("job-1")
            assertFalse(stored.active)
            assertEquals(at("2026-01-15T09:00"), stored.lastRunAtEpochMs)
        }

    // One job failing must not deprive the rest of the batch of their run.
    @Test
    fun `one failing job does not abort the batch`() =
        runTest {
            val repository = InMemoryScheduleRepository()
            repository.save(job(id = "job-1", nextRunAt = at("2026-01-15T09:00")))
            repository.save(job(id = "job-2", nextRunAt = at("2026-01-15T09:00")))
            val ran = mutableListOf<String>()

            val fired =
                worker(repository, TestClock(at("2026-01-15T09:00")), { job ->
                    ran += job.id
                    if (job.id == "job-1") error("boom")
                }).tick()

            assertEquals(setOf("job-1", "job-2"), ran.toSet())
            assertEquals(1, fired, "only the second job counts as successful")
        }

    @Test
    fun `a failed job is retried at the next period with its failure counted`() =
        runTest {
            val repository = InMemoryScheduleRepository()
            repository.save(job(nextRunAt = at("2026-01-15T09:00")))

            worker(repository, TestClock(at("2026-01-15T09:00")), { error("boom") }).tick()

            val stored = repository.jobs.getValue("job-1")
            assertTrue(stored.active)
            assertEquals(1, stored.consecutiveFailures)
            assertEquals("2026-02-15T09:00", stored.nextRunAtEpochMs.asText())
        }

    @Test
    fun `a successful run resets the failure counter`() =
        runTest {
            val repository = InMemoryScheduleRepository()
            repository.save(job(nextRunAt = at("2026-01-15T09:00"), failures = 3))

            worker(repository, TestClock(at("2026-01-15T09:00")), {}).tick()

            assertEquals(0, repository.jobs.getValue("job-1").consecutiveFailures)
        }

    // A systematically failing item — a deleted target, a revoked permission — must not retry
    // forever.
    @Test
    fun `a job that keeps failing is switched off and reported`() =
        runTest {
            val repository = InMemoryScheduleRepository()
            repository.save(job(nextRunAt = at("2026-01-15T09:00"), failures = 4))
            val gaveUp = mutableListOf<String>()

            worker(repository, TestClock(at("2026-01-15T09:00")), { error("boom") }, onGaveUp = { gaveUp += it.id }).tick()

            val stored = repository.jobs.getValue("job-1")
            assertFalse(stored.active, "a job failing repeatedly must be disabled")
            assertEquals(listOf("job-1"), gaveUp)
        }

    @Test
    fun `a switched-off job is never selected again`() =
        runTest {
            val repository = InMemoryScheduleRepository()
            repository.save(job(nextRunAt = at("2026-01-15T09:00")).copy(active = false))
            var runs = 0

            worker(repository, TestClock(at("2026-06-15T09:00")), { runs++ }).tick()

            assertEquals(0, runs)
        }
}
