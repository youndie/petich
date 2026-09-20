package io.github.youndie.petich

import kotlinx.coroutines.CancellationException

/**
 * Wrappers that stop an application's own code from deciding a saga's fate (B-52).
 *
 * The engine calls into code it did not write in three places — metrics, the compensation failure
 * handler, the announcement failure handler — and every one of them ran where an exception changes
 * the outcome:
 *
 * - a throw from `AnnouncementFailureHandler.failed` reached the phase loop and rolled the saga back;
 * - a throw from `CompensationFailureHandler.handle` escaped `triggerCompensation` **before** the
 *   attempt was counted, so `maxCompensationAttempts` stopped bounding anything and the sweeper
 *   re-drove the saga for ever;
 * - `exhausted` was called outside the `try` at all;
 * - and a metrics implementation could do any of the above from a dozen call sites.
 *
 * **Wrapped once rather than guarded at each call**, which is the difference between a rule and a
 * property. A `try` at every site is a list somebody keeps in step; a decorator is a thing a call
 * cannot get past. The engine wraps whatever it is handed, at construction, so no site inside it
 * has to remember.
 *
 * `CancellationException` is rethrown everywhere: it is the caller going away, not a handler
 * failing, and swallowing it would turn a cancelled process into one that keeps working.
 *
 * **[GuardedMetrics] has to name every method of [PetichEngineMetrics], and that is its weakness.**
 * A method added to the interface and not added here falls through to the interface's own default,
 * which is a no-op — so the counter is silently not forwarded. It happened while this file was being
 * written: `onHandlerFailed` was added to the interface afterwards and the decorator swallowed it,
 * and a test caught it only because it asserted on that counter. A list beside a growing set is a
 * list that is one behind; what stands in for a guard here is that every counter this engine relies
 * on is asserted somewhere.
 */
internal inline fun <T> guarding(
    onFailure: (Throwable) -> Unit,
    block: () -> T,
): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onFailure(e)
        null
    }

/**
 * Metrics that cannot throw.
 *
 * **Its own failures go nowhere, and that is not an oversight.** Every other guarded call reports
 * through the counters; a counter that throws has nothing left to report to, and inventing a second
 * channel for it would be inventing a reporting path with the same problem. The engine's contract
 * says an implementation must be thread-safe and quick; this says what happens when it is not, which
 * is nothing.
 */
internal class GuardedMetrics(
    private val delegate: PetichEngineMetrics,
) : PetichEngineMetrics {
    private inline fun quietly(block: () -> Unit) {
        guarding(onFailure = { }, block = block)
    }

    override fun onProcessAttempt(type: String): Unit = quietly { delegate.onProcessAttempt(type) }

    override fun onOptimisticRetry(
        type: String,
        attempt: Int,
    ): Unit = quietly { delegate.onOptimisticRetry(type, attempt) }

    override fun onStateUpdateRetry(type: String): Unit = quietly { delegate.onStateUpdateRetry(type) }

    override fun onCompensation(
        type: String,
        reason: String,
    ): Unit = quietly { delegate.onCompensation(type, reason) }

    override fun onCompensationFailure(
        type: String,
        attempt: Int,
        exhausted: Boolean,
    ): Unit = quietly { delegate.onCompensationFailure(type, attempt, exhausted) }

    override fun onChainRefused(
        type: String,
        phase: PetichPhase,
    ): Unit = quietly { delegate.onChainRefused(type, phase) }

    override fun onChainUnavailable(
        type: String,
        reason: String,
    ): Unit = quietly { delegate.onChainUnavailable(type, reason) }

    override fun onSuspend(type: String): Unit = quietly { delegate.onSuspend(type) }

    override fun onDroppedEvents(
        type: String,
        count: Int,
    ): Unit = quietly { delegate.onDroppedEvents(type, count) }

    override fun onDroppedSideEffects(
        petichType: String,
        count: Int,
    ): Unit = quietly { delegate.onDroppedSideEffects(petichType, count) }

    override fun onHandlerFailed(
        type: String,
        callback: String,
        reason: String,
    ): Unit = quietly { delegate.onHandlerFailed(type, callback, reason) }

    override fun onAnnouncementFailed(
        type: String,
        key: String,
        reason: String,
    ): Unit = quietly { delegate.onAnnouncementFailed(type, key, reason) }

    override fun onAnnouncementDiscarded(
        type: String,
        stepKey: String,
        count: Int,
    ): Unit = quietly { delegate.onAnnouncementDiscarded(type, stepKey, count) }
}

/**
 * A compensation failure handler that cannot decide the saga's fate.
 *
 * `handle` throwing used to skip the attempt's own bookkeeping, which is the worst of the three:
 * the saga was re-driven for ever because nothing counted the attempts that were failing.
 */
internal class GuardedCompensationFailureHandler(
    private val delegate: CompensationFailureHandler,
    private val metrics: PetichEngineMetrics,
) : CompensationFailureHandler {
    override suspend fun handle(
        e: Exception,
        petich: Petich,
        stepKey: String,
    ) {
        guarding(
            onFailure = { metrics.onHandlerFailed(petich.type, "compensationFailureHandler.handle", it.reason()) },
        ) {
            delegate.handle(e, petich, stepKey)
        }
    }

    override suspend fun exhausted(
        petich: Petich,
        stepKey: String,
        attempts: Int,
    ): List<OutboxEvent> =
        guarding(
            onFailure = { metrics.onHandlerFailed(petich.type, "compensationFailureHandler.exhausted", it.reason()) },
        ) {
            delegate.exhausted(petich, stepKey, attempts)
        } ?: emptyList()
}

/** An announcement failure handler that cannot roll a finished saga back. */
internal class GuardedAnnouncementFailureHandler(
    private val delegate: AnnouncementFailureHandler,
    private val metrics: PetichEngineMetrics,
) : AnnouncementFailureHandler {
    override suspend fun failed(
        petich: Petich,
        stepKey: String,
        reason: String,
    ): List<OutboxEvent> =
        guarding(
            onFailure = { metrics.onHandlerFailed(petich.type, "announcementFailureHandler.failed", it.reason()) },
        ) {
            delegate.failed(petich, stepKey, reason)
        } ?: emptyList()
}

internal fun Throwable.reason(): String = message ?: this::class.simpleName ?: "unknown"
