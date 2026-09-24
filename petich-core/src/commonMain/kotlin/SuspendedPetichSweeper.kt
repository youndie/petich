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
    // ONE ENGINE, and it answers for itself which sagas are its (B-31). This was
    // `engineFor: (Petich) -> PetichEngine?` — a mapping the application kept because several
    // engines shared one saga store and only it knew which owned which. `PetichDefinition` is the
    // value that says what an "order" saga is, so `engine.owns` answers the question the lambda
    // was asked, and there is no mapping left to forget an entry in.
    private val engine: PetichEngine,
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
    /**
     * A row whose type this engine has no definition for — and the cause has changed, which is why
     * the name did (B-31).
     *
     * It used to mean "somebody introduced a saga type and forgot to register an engine for it".
     * That cause is gone: there is one engine, and a type with no definition cannot be **started**
     * either — `process` refuses it by name, at the caller, the moment anyone tries. What is left
     * is the case the old name never described: a row written when a definition existed and read
     * after it stopped existing, which is a rollback or a decommission rather than a bug.
     *
     * **Skipped rather than failed, deliberately.** The engine's own answer to an unknown type is
     * to end the saga `FAILED`, which is right on the forward path and wrong here: a deploy that
     * drops a definition would have this worker walk every expired saga of that type and end them
     * all, irreversibly, for a reason that is fixed by deploying again. Skipping leaves them
     * exactly where they were. This callback is how anybody finds out, and a non-zero rate means a
     * definition is missing rather than a saga is broken.
     */
    private val onUnknownType: (Petich) -> Unit = {},
    // Called for every saga picked up after the process that was running it died. Worth a log line
    // and a counter: a rate that is normally zero and suddenly is not says that instances are
    // dying mid-saga, which nothing else in this library is in a position to notice.
    private val onRevived: (String) -> Unit = {},
    // A saga another replica claimed first, on either queue. Expected wherever more than one
    // instance runs, and worth counting for one reason: zero of these on a multi-instance
    // deployment means the claim is not doing anything, which is what an arbiter that quietly
    // stopped working looks like from outside.
    private val onContended: (String) -> Unit = {},
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
                // ONE TRY EACH (B-55). The two queues answer different questions of different
                // tables and share nothing but this loop, and they used to share a `try`: while
                // `findExpired` was failing — a query the database cannot serve, an index being
                // rebuilt — the stranded queue was not swept at all, for as long as that lasted.
                // The half that still worked was stopped by the half that did not, and nothing
                // said so: `onWorkerFailure("sweep", …)` named the pass, not the queue.
                pass("sweep") { sweep() }
                pass("stuck") { sweepStuck() }
                delay(pollInterval)
            }
        }

    private suspend fun pass(
        stage: String,
        body: suspend () -> Unit,
    ) {
        try {
            body()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A transient storage failure is no reason to stop the worker; the next pass
            // will try again — and somebody is told, because a sweeper that has failed
            // every pass looks exactly like one with nothing to sweep.
            report(stage, e)
        }
    }

    /**
     * The reporter, guarded, and it is the one callback that had to be (B-55).
     *
     * Every other callback here is called from inside a `try` whose `catch` reports it, so an
     * application whose handler throws costs one item its pass and no more. This one **is** that
     * `catch`: a throw from it left the `while` loop and ended the worker for the life of the
     * process, silently, and the sagas it was sweeping simply stopped being swept. Same rule as
     * every application callback the engine makes (B-52), for the same reason.
     *
     * **Its own failure goes nowhere**, exactly as `GuardedMetrics`: this is the reporting channel,
     * and a second one for when it fails would have the same problem.
     */
    private fun report(
        stage: String,
        cause: Throwable,
    ) {
        guarding(onFailure = { }) { onWorkerFailure(stage, cause) }
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
                    if (!engine.owns(petich)) {
                        onUnknownType(petich)
                        return@forEach
                    }

                    // A REFUSAL IS NOT A RESCUE (B-55), and the question is asked before the claim
                    // rather than read off what `process` returned. A chain that changed under a
                    // saga makes the engine run nothing and write nothing, so the row keeps
                    // matching the query that found it and comes back on EVERY pass — and this
                    // counted each of those as an instance dying mid-saga, for ever, beside the
                    // counter B-44 added to say the opposite.
                    //
                    // `process` cannot be asked afterwards: its refusal is a `SystemFailure` like
                    // any other, and a rollback that succeeded is one too. The engine answers the
                    // narrow question directly, next to `owns`.
                    //
                    // Reported through the same channel as the expiry queue's `ChainChanged`, which
                    // is the same condition one table over.
                    engine.chainRefusal(petich)?.let { details ->
                        report("stuck:${petich.id}", IllegalStateException(details))
                        return@forEach
                    }

                    // THE CLAIM, and it is one write. Bumping the version re-stamps `updated_at` —
                    // the store does that itself on every write — so the row stops matching the
                    // "not touched since" predicate that found it, and the next replica to query
                    // does not see it at all. A replica that already holds the row holds a stale
                    // version and is refused here, BEFORE it calls a single intercept().
                    //
                    // The lease is `stuckAfter` and there is no second parameter: whatever the
                    // winner does next writes the row again at every step boundary, and if the
                    // winner dies the row goes stale again on its own.
                    //
                    // The loser SKIPS. It does not retry, which is the whole rule this rests on:
                    // the engine's own retries exist to win against a live handler, and a sweeper
                    // that borrowed them would be two rollbacks of one saga.
                    if (!repository.update(petich.copy(version = petich.version + 1))) {
                        engine.trace { PetichTraceEvent.ClaimLost(petich.id, petich.type, SweepQueue.STUCK) }
                        onContended(petich.id)
                        return@forEach
                    }
                    // Through the engine's tracer: the event that says a pass died is this one,
                    // and the engine's own events for the pass that carries it on follow it (B-58).
                    engine.trace { PetichTraceEvent.ClaimWon(petich.id, petich.type, SweepQueue.STUCK) }

                    engine.process(petich)
                    revived++
                    onRevived(petich.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Same rule as the batch above: one saga that cannot be carried on must not
                    // cost the rest of the batch its sweep, and a saga that fails this way on every
                    // pass must not do it in silence.
                    report("stuck:${petich.id}", e)
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
                if (!engine.owns(petich)) {
                    onUnknownType(petich)
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
                    // The claim went to another replica. Ordinary on more than one instance, and
                    // counted rather than silent: a rate that is always high means the poll
                    // interval is shorter than the work, and every replica is paying for a query
                    // whose answer another one is already acting on.
                    is ExpireResult.Contended -> {
                        onContended(petich.id)
                    }

                    is ExpireResult.ChainChanged -> {
                        report("expire:${petich.id}", IllegalStateException(outcome.details))
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
                report("expire:${petich.id}", e)
            }
        }
        return expired
    }
}
