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
     * A saga was refused because the chain it recorded is not the chain this process assembles, and
     * **the saga was left exactly as it was** (B-44).
     *
     * Refusing is right: the row's position is an index, and a deploy that moved a member re-points
     * it at a different one. Nothing is repaired, because repairing means guessing what the index
     * used to mean. What the refusal costs is that the saga keeps whatever it held — a hold, a
     * reservation, a driver — for as long as the deploy stands, and until this counter existed
     * nothing said so: the row still reads PROCESSING or PENDING_SIGNATURE, which is what a healthy
     * saga reads.
     *
     * **It fires on every pass over the same saga, and that is the intent rather than an oversight.**
     * The condition is not an event that happened once; it holds for as long as the two versions
     * disagree, and a signal that goes quiet after the first sweep would read as "resolved" when
     * nothing resolved. A stuck condition should keep alerting while it is stuck.
     *
     * So read the RATE, not the total: non-zero means sagas are refused right now. The remedy is a
     * deploy, not a repair — see the README's runbook.
     *
     * Deliberately no saga id: a counter is the wrong place for an unbounded dimension. The message
     * on the refusal names the saga and both fingerprints, and that is where the identity belongs.
     */
    public fun onChainRefused(
        type: String,
        phase: PetichPhase,
    ): Unit = Unit

    /**
     * The chain for a saga could not be assembled — a member whose construction threw, most
     * plainly — so the saga is written WITHOUT a chain fingerprint and the guard that would refuse
     * a moved member is off for it from then on.
     *
     * **Much harder to reach than it was**, and worth keeping for what is left. The two ways in
     * were a `supports()` that threw and a priority tie refused by configuration; both went with the
     * interceptor model, and a definition's chain is a list that cannot decline to be one.
     *
     * Deliberately not a failure: computing the fingerprint happens on every write, including the
     * emergency transition to FAILED that exists for exactly the kind of member that causes this,
     * and a guard must never be the reason a write does not happen. A counter is what is left, and a
     * non-zero one means sagas are being persisted unguarded.
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
     * A call into the application's own code failed and the engine carried on regardless (B-52).
     *
     * [callback] names which one — `compensationFailureHandler.handle`,
     * `announcementFailureHandler.failed`, and so on. Bounded, because it is a short list written
     * here rather than anything an application supplies.
     *
     * **A non-zero rate here means a handler is broken, not that a saga is.** The engine used to let
     * these decide outcomes: a throw from `handle` skipped the attempt's own bookkeeping, so
     * `maxCompensationAttempts` stopped bounding anything; a throw from `failed` rolled a finished
     * saga back. Both are impossible now, and this is what is left to notice that the handler needs
     * fixing.
     *
     * Metrics themselves are guarded too and report nowhere when they fail: there is nothing left to
     * report to, and a second channel for it would have the same problem.
     */
    public fun onHandlerFailed(
        type: String,
        callback: String,
        reason: String,
    ): Unit = Unit

    /**
     * An announcement threw, and the saga carried on (B-41).
     *
     * **Read it as a delivery problem, never as a saga problem.** By the time an announcement runs
     * the work is done, so petich does not roll the saga back over one — which means a broken
     * notification path shows up here and nowhere else: not in the failure rate, not in the
     * compensation rate. A non-zero rate with everything else flat is exactly the case this counter
     * exists for, and it is the one a status page would otherwise call healthy.
     *
     * Distinct from [onAnnouncementDiscarded], which counts an announcement petich REFUSED to make
     * because the member that asked then began a rollback. That one never left; this one tried.
     */
    public fun onAnnouncementFailed(
        type: String,
        key: String,
        reason: String,
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

    /**
     * A state write was refused because the row is already terminal (B-54).
     *
     * **The only outward sign that two passes are working the same saga.** Nothing else shows it:
     * the row is correct, no exception is thrown, the client is answered. A steady line here says
     * replicas are pausing longer than `stuckAfter` and the sweeper is picking up sagas that are
     * still alive elsewhere — which is a `stuckAfter` too short, not a bug in anyone's saga.
     *
     * [attempted] is the status that did not land, because "COMPENSATING refused" (a second
     * rollback averted) and "COMPLETED refused" (a duplicate finish) are different situations.
     */
    public fun onTerminalWriteRefused(
        type: String,
        attempted: PetichStatus,
    ): Unit = Unit

    public object NoOp : PetichEngineMetrics
}
