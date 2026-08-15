package ru.workinprogress.petich.scheduler

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// Wall clock as a parameter, for the same reason as PetichClock in :petich-core: commonMain
// cannot see java.*, and a test must move time rather than sleep through it.
fun interface SchedulerClock {
    fun nowEpochMs(): Long
}

interface ScheduleRepository {
    suspend fun save(job: ScheduledJob): ScheduledJob

    suspend fun findById(id: String): ScheduledJob?

    // Active jobs whose run time has already arrived.
    suspend fun findDue(
        nowEpochMs: Long,
        limit: Int,
    ): List<ScheduledJob>

    suspend fun findByOwner(ownerId: String): List<ScheduledJob>
}

// What a due job actually does is the application's decision. The scheduler knows only "it is
// time" and "here is the payload" — the same split of responsibility as OutboxPublisher in
// :petich-outbox-core, and precisely what lets this module stay independent of :petich-core. An
// implementation may assemble a petich and hand it to PetichEngine, so a saga can start with no
// HTTP initiator at all.
fun interface ScheduledJobRunner {
    // An exception means the run failed: the job gets +1 on its failure counter and is
    // rescheduled, or closed if it keeps failing.
    suspend fun run(job: ScheduledJob)
}

// Polling the schedule: findDue -> run -> reschedule. Same shape as OutboxRelayWorker: one job
// failing does not sink the batch, and a storage failure between polls does not sink the worker.
class SchedulerWorker(
    private val repository: ScheduleRepository,
    private val runner: ScheduledJobRunner,
    private val clock: SchedulerClock,
    // The zone in which "the same day next month" is computed (see nextRunAfter).
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
    private val pollInterval: Duration = 60.seconds,
    private val batchSize: Int = 50,
    // After this many consecutive failures the job is disabled instead of retried again: a
    // recurring item that systematically cannot go through — a deleted target, a revoked
    // permission — must not keep hammering silently forever.
    private val maxFailures: Int = 5,
    private val onFired: (ScheduledJob) -> Unit = {},
    private val onFailed: (ScheduledJob, Throwable) -> Unit = { _, _ -> },
    // The job was disabled after maxFailures in a row. The application needs to know so it can
    // tell the user that the recurring item is no longer running.
    private val onGaveUp: (ScheduledJob) -> Unit = {},
) {
    fun start(scope: CoroutineScope): Job =
        scope.launch {
            while (isActive) {
                try {
                    tick()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A transient storage failure; the next poll will try again.
                    // TODO: log this
                }
                delay(pollInterval)
            }
        }

    // One pass, exposed separately from start, so it can be called from a test or an admin
    // endpoint without spawning a coroutine or waiting out the interval.
    suspend fun tick(): Int {
        var fired = 0
        repository.findDue(clock.nowEpochMs(), batchSize).forEach { job ->
            val ranAt = clock.nowEpochMs()
            val outcome =
                try {
                    runner.run(job)
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e
                }

            try {
                if (outcome == null) {
                    fired++
                    repository.save(reschedule(job, ranAt, failures = 0))
                    onFired(job)
                } else {
                    val failures = job.consecutiveFailures + 1
                    val updated =
                        if (failures >= maxFailures) {
                            // Nothing left to reschedule: the job closes.
                            job.copy(active = false, lastRunAtEpochMs = ranAt, consecutiveFailures = failures)
                        } else {
                            reschedule(job, ranAt, failures)
                        }
                    repository.save(updated)
                    onFailed(job, outcome)
                    if (!updated.active) onGaveUp(updated)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Writing the job's new state failed: the next poll still sees it as due and
                // tries again. Running twice is safer here than silently losing the schedule —
                // and that is exactly why the runner must be idempotent, typically by deriving a
                // deterministic petich id from the job and its scheduled instant.
                // TODO: log this
            }
        }
        return fired
    }

    // ONCE closes after its run: by definition there is no next time.
    private fun reschedule(
        job: ScheduledJob,
        ranAtEpochMs: Long,
        failures: Int,
    ): ScheduledJob {
        // Counted from the SCHEDULED time, not the actual one: otherwise a job that fired late —
        // the worker was down, the batch was busy — would slowly drift, and a job due "on the 1st"
        // would run on the 5th a year later.
        val next = job.recurrence.nextRunAfter(job.nextRunAtEpochMs, timeZone)
        return if (next == null) {
            job.copy(active = false, lastRunAtEpochMs = ranAtEpochMs, consecutiveFailures = failures)
        } else {
            job.copy(
                // Missed periods are not caught up: if the worker was down for three months the
                // job runs once, not three times retroactively. For side-effecting work, catching
                // up is more dangerous than skipping, and that decision must be visible in the
                // code rather than emerge as a side effect.
                nextRunAtEpochMs = catchUp(next, job.recurrence),
                lastRunAtEpochMs = ranAtEpochMs,
                consecutiveFailures = failures,
            )
        }
    }

    private fun catchUp(
        candidate: Long,
        recurrence: Recurrence,
    ): Long {
        var next = candidate
        val now = clock.nowEpochMs()
        // Fast-forward through the missed periods to the first future one, without running them.
        while (next <= now) {
            next = recurrence.nextRunAfter(next, timeZone) ?: return next
        }
        return next
    }
}
