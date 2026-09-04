package ru.workinprogress.petich

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
interface PetichEngineMetrics {
    /** A saga pass began. Together with [onOptimisticRetry] this yields the average attempt count. */
    fun onProcessAttempt(type: String) = Unit

    /**
     * A saga pass restarts because of a version conflict: someone changed the same petich first.
     * A direct measure of contention — the one thing that separates "we hit the database ceiling"
     * from "we are fighting over a row".
     */
    fun onOptimisticRetry(
        type: String,
        attempt: Int,
    ) = Unit

    /** A state write retried within a single pass (see forceUpdateStateWithRetry). */
    fun onStateUpdateRetry(type: String) = Unit

    /** The saga went backwards. Compensation costs more than the forward pass, and a spike of
     *  rollbacks changes the load profile. */
    fun onCompensation(
        type: String,
        reason: String,
    ) = Unit

    /**
     * The saga is waiting on client action. A repeated wait (Resuspend) counts too: from outside
     * it is indistinguishable from the first, and it costs the engine the same.
     */
    fun onSuspend(type: String) = Unit

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
    fun onDroppedEvents(
        type: String,
        count: Int,
    ) = Unit

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
    fun onDroppedSideEffects(
        petichType: String,
        count: Int,
    ) = Unit

    object NoOp : PetichEngineMetrics
}
