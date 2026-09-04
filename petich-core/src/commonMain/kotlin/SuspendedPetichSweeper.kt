package ru.workinprogress.petich

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
interface ExpiringPetichRepository : PetichRepository {
    // Petiches in PENDING_SIGNATURE whose suspendedUntilEpochMs is non-null and already in the
    // past. Filtering happens in the storage: pulling every suspended petich into memory to sift
    // them client-side amounts to having no index at all.
    suspend fun findExpired(
        nowEpochMs: Long,
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
class SuspendedPetichSweeper(
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
    // Called for every petich that actually expired — an application needs this to notify the
    // client ("the confirmation window has passed") or to record a metric. A failure in the
    // handler does not undo the rollback: by that point it has already happened.
    private val onExpired: (String) -> Unit = {},
    // An expired petich whose type has no engine. Skipping it silently is not acceptable: it
    // means someone introduced a new petich type and forgot to register it here, and such petiches
    // will pile up expired forever.
    private val onUnowned: (Petich) -> Unit = {},
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
    fun start(scope: CoroutineScope): Job =
        scope.launch {
            while (isActive) {
                try {
                    sweep()
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

    // Separate from start: a single pass can be invoked by hand — from a test or an admin
    // endpoint — without spawning a coroutine or waiting out the interval.
    suspend fun sweep(): Int {
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
                if (engine.expireSuspended(petich.id) is ExpireResult.Expired) {
                    expired++
                    onExpired(petich.id)
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
