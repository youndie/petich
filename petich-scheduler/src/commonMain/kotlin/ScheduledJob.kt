package ru.workinprogress.petich.scheduler

import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

// Recurrence. Deliberately NOT cron: a cron expression needs a parser, does incomparably more
// than a recurring job requires, and "0 0 3 * *" cannot be explained to an end user. The list
// is closed and grows only as real needs appear.
enum class Recurrence {
    // A single run at the appointed instant, after which the job closes.
    ONCE,
    DAILY,
    WEEKLY,
    MONTHLY,
}

// One job in the schedule.
//
// payload is already-serialised JSON, as with OutboxRecord in :petich-outbox-core: the scheduler
// must not know which business object is inside, or it stops being a portable mechanism and
// becomes part of one particular feature. Whoever executes the job parses the payload (see
// ScheduledJobRunner).
data class ScheduledJob(
    val id: String,
    // Who owns the job. The scheduler does not care, but every application needs a "show me my
    // scheduled items" query, and putting the owner in the payload would mean searching JSON.
    val ownerId: String,
    val type: String,
    val payload: String,
    val recurrence: Recurrence,
    val nextRunAtEpochMs: Long,
    val lastRunAtEpochMs: Long? = null,
    // A disabled job is never selected: this is how a user cancels a recurring item without
    // losing the history of its runs.
    val active: Boolean = true,
    // How many times execution failed in a row. Persisted rather than kept in the worker's memory
    // because this is what sends a job to "gave up" (see maxFailures in SchedulerWorker): the
    // counter must survive a restart, or a systematically failing job retries forever.
    val consecutiveFailures: Int = 0,
)

// The next run after the instant `from`. Calendar arithmetic, not adding a fixed number of
// milliseconds: months differ in length and time zones have transitions, so "monthly" means the
// same DAY OF MONTH next month, not "plus 30 days".
//
// The time zone is a parameter: "monthly on the 1st" is meaningless without one, and the scheduler
// has no business choosing it on the application's behalf.
fun Recurrence.nextRunAfter(
    fromEpochMs: Long,
    timeZone: TimeZone,
): Long? {
    if (this == Recurrence.ONCE) return null

    val local = Instant.fromEpochMilliseconds(fromEpochMs).toLocalDateTime(timeZone)
    val next =
        when (this) {
            Recurrence.DAILY -> local.date.plus(1, DateTimeUnit.DAY)
            Recurrence.WEEKLY -> local.date.plus(1, DateTimeUnit.WEEK)
            // 31 January plus a month is 28/29 February: kotlinx-datetime clamps the day to the
            // month's length by itself, which is exactly what "run on the 31st" should mean.
            Recurrence.MONTHLY -> local.date.plus(1, DateTimeUnit.MONTH)
            Recurrence.ONCE -> return null
        }
    return next.atTime(local.time).toInstant(timeZone).toEpochMilliseconds()
}
