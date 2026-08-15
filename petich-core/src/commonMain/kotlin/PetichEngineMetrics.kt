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

    object NoOp : PetichEngineMetrics
}
