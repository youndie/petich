package ru.workinprogress.petich.outbox

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

// At-least-once delivery: poll -> publish -> markDelivered/markFailed/markDeadLettered, once per
// pollInterval. Failing to publish ONE event kills neither the batch (the rest still attempt
// delivery) nor the worker itself — a transient storage failure between polls is likewise no
// reason to stop, see the outer catch. Log and carry on, rather than fall over.
//
// The backoff lives only in this worker's memory (nextEligibleAt), not in storage. retryCount in
// the repository stays the source of truth for how many attempts an event has had, so a process
// restart at worst forgets the current timer and retries early — a redundant but harmless repeat
// under at-least-once — instead of losing the attempt count itself.
class OutboxRelayWorker(
    private val repository: OutboxRepository,
    private val publisher: OutboxPublisher,
    private val pollInterval: Duration = 1.seconds,
    private val batchSize: Int = 50,
    // After maxAttempts failures — retryCount having reached this number — the event goes to the
    // dead letter instead of getting another backoff. A systematic failure, a misconfigured
    // transport say, must not keep an event burning forever.
    private val maxAttempts: Int = 5,
    private val baseBackoff: Duration = 1.seconds,
    private val maxBackoff: Duration = 5.minutes,
    /**
     * Something failed that is not one item's own work: the storage refused a pass, or writing an
     * outcome back did not go through.
     *
     * These were swallowed with a `TODO: log this`. The worker surviving them is deliberate — the
     * work is not going anywhere and the next pass picks it up — but surviving is not the same as
     * being invisible: a worker whose storage has been refusing every pass for an hour looks
     * EXACTLY like an idle one from outside, and that is the only state in which it is silently
     * doing nothing. petich has no logger of its own; whoever wires it up has one.
     */
    private val onWorkerFailure: (stage: String, cause: Throwable) -> Unit = { _, _ -> },
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {
    // Event -> the instant before which no retry should be made. Cleared on success or on dead
    // lettering; a missing entry means "may be attempted right now" — the first attempt at an
    // event this worker has not yet seen fail.
    private val nextEligibleAt = mutableMapOf<String, ComparableTimeMark>()

    fun start(scope: CoroutineScope): Job =
        scope.launch {
            while (isActive) {
                try {
                    tick()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A transient failure of the storage itself, not of any one event, is no
                    // reason to stop the worker for good; the next poll will try again — and it is
                    // reported, because a relay failing every poll delivers nothing and looks idle.
                    onWorkerFailure("poll", e)
                }
                delay(pollInterval)
            }
        }

    // One pass, exposed separately from start for the same reason as SchedulerWorker.tick(): it
    // can be called from a test or an admin endpoint without spawning a coroutine or waiting out
    // the interval. For tests this is decisive — delivery is checked by invoking a pass and
    // advancing timeSource, not by sleeping on the real clock and hoping the worker wakes the
    // required number of times. On a loaded machine that hope does not come true.
    suspend fun tick() {
        val now = timeSource.markNow()
        repository
            .fetchPending(batchSize)
            .filter { event -> nextEligibleAt[event.id]?.let { now >= it } ?: true }
            .forEach { event ->
                try {
                    publisher.publish(event)
                    repository.markDelivered(event.id)
                    nextEligibleAt.remove(event.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The event's own failure is handled below — but the CAUSE was thrown away,
                    // and it is the only thing that explains a dead letter. An operator looking at
                    // an event that gave up after five attempts otherwise has five identical
                    // nothings to go on.
                    onWorkerFailure("publish:${event.id}", e)
                    val attemptsSoFar = event.retryCount + 1
                    if (attemptsSoFar >= maxAttempts) {
                        repository.markDeadLettered(event.id)
                        nextEligibleAt.remove(event.id)
                    } else {
                        repository.markFailed(event.id)
                        val backoff = backoffFor(attemptsSoFar)
                        nextEligibleAt[event.id] = timeSource.markNow() + backoff
                    }
                }
            }
    }

    // Exponential growth from baseBackoff (2^(attempt-1) * base), capped at maxBackoff. Without
    // the cap the delay would grow without bound for events that fail long and systematically.
    private fun backoffFor(attempt: Int): Duration {
        val exponent = (attempt - 1).coerceAtMost(30) // 2^30 * base is far past maxBackoff already
        val scaled = baseBackoff * (1 shl exponent)
        return if (scaled > maxBackoff) maxBackoff else scaled
    }
}
