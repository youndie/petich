package io.github.youndie.petich

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// An optional extension of PetichRepository, on the same principle as OutboxAwarePetichRepository:
// putting the method on PetichRepository itself would force every existing test double to
// implement it, though exactly one consumer needs the expired-petiches query. A storage that
// cannot do this simply cannot be swept — visible in the type rather than discovered at runtime.
public interface ExpiringPetichRepository : PetichRepository {
    // Petiches in PENDING_SIGNATURE whose suspendedUntilEpochMs is non-null and already in the
    // past. Filtering happens in the storage: pulling every suspended petich into memory to sift
    // them client-side amounts to having no index at all.
    public suspend fun findExpired(
        nowEpochMs: Long,
        limit: Int,
    ): List<Petich>

    /**
     * Sagas in [status] that nothing has written since [notTouchedSinceEpochMs] — the ones a
     * process died in the middle of. One status per call rather than a set, because both stores
     * express that as a plain equality and a list parameter is the kind of thing one of them binds
     * differently from the other.
     *
     * **The stamp it filters on is the store's own**, written on every insert and update from the
     * clock the store was given; it is not part of [Petich], exactly as `outbox_events.created_at`
     * is not part of an outbox event. Several replicas write it from their own clocks, which is
     * youndie/petich#20 one table over: the skew is seconds and the threshold below is minutes, so
     * it changes nothing here — but it is the reason the threshold is a formula rather than a
     * constant.
     *
     * **Deliberately not indexed on that stamp.** A saga row is updated at every step boundary, and
     * an index containing a column that changes on every write makes every one of those writes a
     * non-HOT update — eleven per saga, on the busiest table in the system, to serve a query that
     * runs once per poll. The leading `status` column of the index the sweeper already needs is
     * enough to reach the handful of rows in a non-terminal state; the stamp is rechecked from the
     * heap.
     */
    public suspend fun findStuck(
        status: PetichStatus,
        notTouchedSinceEpochMs: Long,
        limit: Int,
    ): List<Petich>
}

// Background sweeping of suspended petiches: poll -> expireSuspended, once per pollInterval.
//
// Why it exists: the engine's phase timeouts (withTimeout around an interceptor) bound the
// EXECUTION of a step, not the wait for a human's answer. Without this worker a petich suspended
// awaiting a confirmation hangs forever — and the already-executed saga steps hang with it: the
// reserved stock, the allocated slot, the claimed quota. Compensation on expiry is how the saga
// ends when the client never came back.
//
// Resilience is the same as OutboxRelayWorker's: a failure on ONE petich does not sink the batch,
// and a storage failure between polls does not sink the worker. An expired petich is not going
// anywhere and will be picked up by the next pass, so "log and carry on" loses nothing here.
public class SuspendedPetichSweeper(
    private val repository: ExpiringPetichRepository,
    // Which engine owns the petich. Not one engine for everything: an application usually keeps
    // several, sharing ONE petich storage but each with its own interceptor list. Rolling back a
    // saga of one type with another type's engine would run the wrong compensations, or none, and
    // the expired-petiches query is common to all of them.
    //
    // null means "no owner": such a petich is skipped (see onUnowned) rather than rolled back at
    // random.
    private val engineFor: (Petich) -> PetichEngine?,
    private val clock: PetichClock,
    private val pollInterval: Duration = 30.seconds,
    private val batchSize: Int = 50,
    /**
     * How long a saga must sit untouched in PROCESSING or COMPENSATING before this worker re-drives
     * it. `null` — the default — leaves that half switched off, so an application that adopts this
     * version changes nothing by upgrading.
     *
     * **The number is a formula, not a taste.** There is no lease and no owner column: nothing
     * distinguishes a saga whose process died from one a live instance is slowly working on, and
     * the optimistic version protects the row rather than the effects — two instances re-driving
     * one saga both call `intercept()`, and only one of them loses the write. So this must exceed
     * the longest a healthy pass can take:
     *
     *     stuckAfter > max(PetichEngineConfig.phaseTimeoutsMs ∪ compensationTimeoutsMs)
     *
     * Both tables are per-application, which is why no default here could be right. A minute
     * chosen by feel is wrong in somebody's configuration, and wrong in the direction that runs a
     * payment twice.
     */
    private val stuckAfter: Duration? = null,
    // Called for every petich that actually expired — an application needs this to notify the
    // client ("the confirmation window has passed") or to record a metric. A failure in the
    // handler does not undo the rollback: by that point it has already happened.
    private val onExpired: (String) -> Unit = {},
    // An expired petich whose type has no engine. Skipping it silently is not acceptable: it
    // means someone introduced a new petich type and forgot to register it here, and such petiches
    // will pile up expired forever.
    private val onUnowned: (Petich) -> Unit = {},
    // Called for every saga picked up after the process that was running it died. Worth a log line
    // and a counter: a rate that is normally zero and suddenly is not says that instances are
    // dying mid-saga, which nothing else in this library is in a position to notice.
    private val onRevived: (String) -> Unit = {},
    // A saga the query offered and the engine then declined to expire: the client answered while
    // the batch was in flight, or the row had already moved on. Ordinary, and worth counting only
    // because a rate that is always high means the poll interval is fighting the deadline.
    private val onNotExpired: (String) -> Unit = {},
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
) {
    public fun start(scope: CoroutineScope): Job =
        scope.launch {
            while (isActive) {
                try {
                    sweep()
                    sweepStuck()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A transient storage failure is no reason to stop the worker; the next pass
                    // will try again — and somebody is told, because a sweeper that has failed
                    // every pass looks exactly like one with nothing to sweep.
                    onWorkerFailure("sweep", e)
                }
                delay(pollInterval)
            }
        }

    /**
     * Re-drive sagas a process died in the middle of. The engine already resumes both an
     * interrupted pass and an interrupted rollback correctly the moment [PetichEngine.process] is
     * called with that id; until this existed, nothing called it.
     *
     * It does not decide anything itself — it hands the saga back to its engine and lets the engine
     * re-read, re-lock and continue. A rollback that keeps failing is bounded by
     * `PetichEngineConfig.maxCompensationAttempts` and ends in `COMPENSATION_FAILED`, which is
     * terminal and therefore never returned here again; without that bound this method would be a
     * loop around a `compensate()` that cannot succeed, which is why it was written second.
     */
    public suspend fun sweepStuck(): Int {
        val after = stuckAfter ?: return 0
        val threshold = clock.nowEpochMs() - after.inWholeMilliseconds
        var revived = 0
        for (status in listOf(PetichStatus.PROCESSING, PetichStatus.COMPENSATING)) {
            repository.findStuck(status, threshold, batchSize).forEach { petich ->
                try {
                    val engine = engineFor(petich)
                    if (engine == null) {
                        onUnowned(petich)
                        return@forEach
                    }
                    engine.process(petich)
                    revived++
                    onRevived(petich.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Same rule as the batch above: one saga that cannot be carried on must not
                    // cost the rest of the batch its sweep, and a saga that fails this way on every
                    // pass must not do it in silence.
                    onWorkerFailure("stuck:${petich.id}", e)
                }
            }
        }
        return revived
    }

    // Separate from start: a single pass can be invoked by hand — from a test or an admin
    // endpoint — without spawning a coroutine or waiting out the interval.
    public suspend fun sweep(): Int {
        var expired = 0
        repository.findExpired(clock.nowEpochMs(), batchSize).forEach { petich ->
            try {
                val engine = engineFor(petich)
                if (engine == null) {
                    onUnowned(petich)
                    return@forEach
                }
                // The engine makes the decision under its own lock: by now the client may have
                // answered, making the query results stale (see expireSuspended).
                when (val outcome = engine.expireSuspended(petich.id)) {
                    is ExpireResult.Expired -> {
                        expired++
                        onExpired(petich.id)
                    }

                    // Not folded in with "nothing to do here". This saga is past its deadline and
                    // cannot be rolled back by this build, so it will come back on every pass for
                    // ever - and a worker returning quietly every time is exactly what an idle one
                    // looks like.
                    is ExpireResult.ChainChanged -> {
                        onWorkerFailure("expire:${petich.id}", IllegalStateException(outcome.details))
                    }

                    // NotFound, NotSuspended and NotExpiredYet: the query's answer was stale by
                    // the time the lock was taken, which is the race this path exists to lose
                    // safely.
                    else -> {
                        onNotExpired(petich.id)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // One petich failing to roll back must not deprive the rest of the batch of
                // their sweep — but a petich that can never be rolled back would otherwise fail
                // silently on every pass for ever.
                onWorkerFailure("expire:${petich.id}", e)
            }
        }
        return expired
    }
}
