package io.github.youndie.petich

/**
 * Engine counters. They exist for one question that cannot be answered from the outside: WHY did
 * throughput drop.
 *
 * From outside you see only latency and error count, while a slowdown that looks identical has at
 * least three distinct causes inside: version conflicts with repeated saga passes, repeated state
 * writes, and compensations. Response time cannot tell them apart, and each is cured differently.
 *
 * The default implementation [NoOp] does nothing and the engine parameter has a default value, so
 * existing code neither changes nor pays anything. Counters are enabled deliberately: by a load
 * harness, or by an application that wants to expose these numbers.
 *
 * Calls arrive from different coroutines concurrently, so an implementation must be thread-safe.
 */
public interface PetichEngineMetrics {
    /** A saga pass began. Together with [onOptimisticRetry] this yields the average attempt count. */
    public fun onProcessAttempt(type: String): Unit = Unit

    /**
     * A saga pass restarts because of a version conflict: someone changed the same petich first.
     * A direct measure of contention — the one thing that separates "we hit the database ceiling"
     * from "we are fighting over a row".
     */
    public fun onOptimisticRetry(
        type: String,
        attempt: Int,
    ): Unit = Unit

    /** A state write retried within a single pass (see forceUpdateStateWithRetry). */
    public fun onStateUpdateRetry(type: String): Unit = Unit

    /** The saga went backwards. Compensation costs more than the forward pass, and a spike of
     *  rollbacks changes the load profile. */
    public fun onCompensation(
        type: String,
        reason: String,
    ): Unit = Unit

    /**
     * A rollback stopped without finishing. [attempt] counts from one and is kept on the saga, so
     * it survives a restart; [exhausted] is true on the attempt that gives up for good and leaves
     * the saga [PetichStatus.COMPENSATION_FAILED].
     *
     * Read it as two different questions. A non-zero rate with `exhausted = false` is a far side
     * that is briefly unavailable — the rollback will be tried again. Anything at all with
     * `exhausted = true` is a saga that is half undone and that nothing will touch again, which is
     * the only state in this engine with no automatic way out.
     */
    public fun onCompensationFailure(
        type: String,
        attempt: Int,
        exhausted: Boolean,
    ): Unit = Unit

    /**
     * The interceptor chain for a saga could not be assembled — a `supports()` that threw, or a
     * priority tie refused by [PetichEngineConfig.requireDistinctPriorities] — so the saga is
     * written WITHOUT a chain fingerprint and the guard that would refuse a moved step is off for
     * it from then on.
     *
     * Deliberately not a failure: computing the fingerprint happens on every write, including the
     * emergency transition to FAILED that exists for exactly the kind of interceptor that causes
     * this, and a guard must never be the reason a write does not happen. A counter is what is left,
     * and a non-zero one means sagas are being persisted unguarded.
     */
    public fun onChainUnavailable(
        type: String,
        reason: String,
    ): Unit = Unit

    /**
     * The saga is waiting on client action. A repeated wait (Resuspend) counts too: from outside
     * it is indistinguishable from the first, and it costs the engine the same.
     */
    public fun onSuspend(type: String): Unit = Unit

    /**
     * Outbox events were produced and thrown away, because the configured repository is not an
     * [OutboxAwarePetichRepository]. [count] is how many were lost in that one write.
     *
     * The odd one out among these counters: the rest answer "why did throughput drop", this one
     * answers a question nobody thinks to ask. The degradation is deliberate and documented — an
     * application that wants no events should not have to configure their absence — but it is
     * shaped like the worst kind of failure, in that the work happened and nobody was told. The
     * saga completes, its state is correct, and every assertion anyone naturally writes about it
     * passes; only the consumer on the far end of the event never runs. Nothing else in the system
     * is different, which is why a counter is the only thing that can say it happened.
     *
     * It is also reached by accident rather than by decision: :petich-postgres is outbox-aware,
     * while a test double or a hand-rolled repository is not. A flat non-zero line here is that
     * mistake, in production, and [PetichEngineConfig.requireOutbox] is the same mistake refused
     * at construction instead.
     */
    public fun onDroppedEvents(
        type: String,
        count: Int,
    ): Unit = Unit

    /**
     * Work an interceptor asked to have committed with the state change, thrown away because the
     * repository cannot store it.
     *
     * The counterpart of [onDroppedEvents], and it answers a question nobody thinks to ask for the
     * same reason: the write succeeds, the saga completes, its state is correct, and every
     * assertion anybody naturally makes about that run passes. What does not happen is the thing
     * nobody is waiting for right now — a timer three days out, most concretely.
     *
     * [PetichEngineConfig.requireSideEffects] is the same mistake refused at wiring time instead of
     * counted at runtime.
     */
    public fun onDroppedSideEffects(
        petichType: String,
        count: Int,
    ): Unit = Unit

    /**
     * What a member asked to have committed and lost by then refusing the saga (B-35).
     *
     * **Not the same question as [onDroppedEvents]**, which counts a repository that cannot store an
     * outbox at all — a wiring mistake, "reached by accident rather than by decision". This is the
     * decision: `reject` and `fail` both begin a rollback, and petich does not announce work it is
     * undoing. A rollback's own word belongs to the compensations, which may announce freely — but
     * the REFUSING member's compensation does not run, so what it emitted has no owner and nothing
     * else in the system would ever say so.
     *
     * A non-zero line here is a member written as though announcing and refusing could be done in
     * one breath. It names the step key, because which member it is, is the whole finding.
     */
    public fun onAnnouncementDiscarded(
        type: String,
        stepKey: String,
        count: Int,
    ): Unit = Unit

    public object NoOp : PetichEngineMetrics
}
