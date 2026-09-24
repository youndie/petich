package io.github.youndie.petich

/**
 * What ONE saga did, event by event (B-58).
 *
 * Every other channel this engine has is keyed by saga **type**: [PetichEngineMetrics] says so on
 * purpose, and the row keeps the latest position and nothing about how it got there. The defects of
 * the last three review rounds — an announcement sent six times, a hung announcement rolling a
 * finished saga back, a refusal coming back as `FAILED` after a crash — were all found by reading
 * code and by doubles that count calls, and none of them was visible in a running system. This is
 * the channel that would have shown them, and it is deliberately only a channel: no exporter, no
 * storage, no surface. `docs/research/research-petich-tracer.md` is why.
 *
 * **Best-effort and outside every transaction.** An event is delivered at the moment the engine
 * knows the thing happened, synchronously, from whatever coroutine the engine is running in. A
 * process that dies loses the events of the pass it was running, and the sweeper's
 * [PetichTraceEvent.ClaimWon] on the stuck queue is the event that says so afterwards.
 *
 * **An implementation must return immediately.** The call is not suspending and nothing buffers it:
 * a tracer that blocks makes every saga slower by exactly as long as it blocks, and the engine does
 * not hide that. Anything slow — a network exporter, a file — buffers on its own side of this call.
 *
 * **An implementation that throws changes nothing about the saga.** The engine wraps whatever it is
 * handed once, at construction, exactly as it wraps metrics (B-52), and counts the failure through
 * [PetichEngineMetrics.onHandlerFailed] as `tracer.onEvent`.
 *
 * **No timestamp and no replica label in the event, and the reason is the same for both:** the
 * engine does not have them. Its clock is optional and throws when a TTL was never configured, and
 * nothing in it knows which replica it is. A tracer instance lives in one process and is called at
 * the moment of the event, so it stamps both itself — correctly, and without the engine inventing a
 * value it cannot vouch for.
 *
 * Calls arrive from different coroutines concurrently, so an implementation must be thread-safe.
 */
public fun interface PetichTracer {
    public fun onEvent(event: PetichTraceEvent)

    public companion object {
        /** The default: no events are built into anything and nothing is paid for. */
        public val NoOp: PetichTracer = PetichTracer { }
    }
}

/** Which of the sweeper's two queues a claim was made on. */
public enum class SweepQueue {
    /** A suspended saga whose client did not answer in time: `findExpired`. */
    EXPIRED,

    /** A saga left in `PROCESSING` or `COMPENSATING` by a process that went away: `findStuck`. */
    STUCK,
}

/**
 * One thing that happened to one saga.
 *
 * **Keys, phases, indices and outcomes — never a payload.** A `reason` is the only free text, and it
 * is cut to [TRACE_REASON_LIMIT] characters with a visible marker: it comes from `e.message`, which
 * has already carried an address once (B-57), and a trace goes wherever its sink sends it.
 *
 * A member is named by its phase, its index in that phase's chain and its key — the three things
 * `describeChain` prints and the row's position is made of, so an event can be laid over the chain
 * without a lookup.
 */
public sealed interface PetichTraceEvent {
    public val sagaId: String
    public val type: String

    /**
     * A pass over the saga began, [attempt] counting from one. [status], [phase] and [index] are the
     * row as this pass read it — a `PENDING_SIGNATURE` here is a resume, a `PROCESSING` one a pass
     * picking up where another stopped.
     */
    public data class PassStarted(
        override val sagaId: String,
        override val type: String,
        val attempt: Int,
        val status: PetichStatus,
        val phase: PetichPhase,
        val index: Int,
    ) : PetichTraceEvent

    /**
     * A pass lost a version conflict and the whole pass will run again — every member since the
     * last committed position included. This is the event that explains an announcement entered
     * more than once.
     */
    public data class PassRetried(
        override val sagaId: String,
        override val type: String,
        val attempt: Int,
    ) : PetichTraceEvent

    /** A member is about to run. */
    public data class MemberEntered(
        override val sagaId: String,
        override val type: String,
        val phase: PetichPhase,
        val index: Int,
        val key: String,
    ) : PetichTraceEvent

    /** A member proceeded, and the position past it was committed. */
    public data class MemberProceeded(
        override val sagaId: String,
        override val type: String,
        val phase: PetichPhase,
        val index: Int,
        val key: String,
    ) : PetichTraceEvent

    /** A member refused the saga on business grounds; a rollback towards `REJECTED` follows. */
    public data class MemberRejected(
        override val sagaId: String,
        override val type: String,
        val phase: PetichPhase,
        val index: Int,
        val key: String,
        val reason: String,
    ) : PetichTraceEvent

    /**
     * A member failed: it said so ([thrown] false — `ctx.fail`) or it threw ([thrown] true, and then
     * whether it had an effect is unknown). A rollback towards `FAILED` follows either way.
     */
    public data class MemberFailed(
        override val sagaId: String,
        override val type: String,
        val phase: PetichPhase,
        val index: Int,
        val key: String,
        val reason: String,
        val thrown: Boolean,
    ) : PetichTraceEvent

    /** A member outran a deadline and was cancelled; a rollback towards `FAILED` follows. */
    public data class MemberTimedOut(
        override val sagaId: String,
        override val type: String,
        val phase: PetichPhase,
        val index: Int,
        val key: String,
        val reason: String,
    ) : PetichTraceEvent

    /** A member parked the saga to wait for [requiredAction]; the saga resumes after it. */
    public data class MemberSuspended(
        override val sagaId: String,
        override val type: String,
        val phase: PetichPhase,
        val index: Int,
        val key: String,
        val requiredAction: String,
    ) : PetichTraceEvent

    /** A member parked the saga to ask again; the saga resumes AT it. */
    public data class MemberResuspended(
        override val sagaId: String,
        override val type: String,
        val phase: PetichPhase,
        val index: Int,
        val key: String,
        val requiredAction: String,
    ) : PetichTraceEvent

    /**
     * An announcement could not be made — it threw or outran its own deadline. The saga carries on:
     * this is the event that says so, next to the member's own [MemberProceeded].
     */
    public data class AnnouncementFailed(
        override val sagaId: String,
        override val type: String,
        val key: String,
        val reason: String,
    ) : PetichTraceEvent

    /** A sweeper claimed the saga on [queue]; what it does next follows in the same trace. */
    public data class ClaimWon(
        override val sagaId: String,
        override val type: String,
        val queue: SweepQueue,
    ) : PetichTraceEvent

    /** Another replica claimed the saga first on [queue], and this one skipped it. */
    public data class ClaimLost(
        override val sagaId: String,
        override val type: String,
        val queue: SweepQueue,
    ) : PetichTraceEvent

    /**
     * A rollback began — or resumed — from [fromIndex] in [phase], and will end as [towards] if it
     * finishes. The mark that says so is committed before this event is sent.
     */
    public data class RollbackStarted(
        override val sagaId: String,
        override val type: String,
        val phase: PetichPhase,
        val fromIndex: Int,
        val towards: PetichStatus,
        val reason: String,
    ) : PetichTraceEvent

    /** A step's compensation ran and its position was committed. Members with nothing to undo send nothing. */
    public data class StepUndone(
        override val sagaId: String,
        override val type: String,
        val phase: PetichPhase,
        val index: Int,
        val key: String,
    ) : PetichTraceEvent

    /**
     * A rollback stopped without finishing, at [key]. [exhausted] is the attempt that gives up for
     * good; otherwise the sweeper will try again.
     */
    public data class RollbackGaveUp(
        override val sagaId: String,
        override val type: String,
        val key: String?,
        val attempt: Int,
        val exhausted: Boolean,
    ) : PetichTraceEvent

    /** The chain the saga recorded is not the chain this process assembles; nothing was run or written. */
    public data class ChainRefused(
        override val sagaId: String,
        override val type: String,
        val phase: PetichPhase,
        val index: Int,
    ) : PetichTraceEvent

    /** The saga was written in a terminal [status]. */
    public data class Finished(
        override val sagaId: String,
        override val type: String,
        val status: PetichStatus,
    ) : PetichTraceEvent
}

/** How much of a free-text reason reaches a trace. */
public const val TRACE_REASON_LIMIT: Int = 160

internal fun String.forTrace(): String =
    if (length <= TRACE_REASON_LIMIT) {
        this
    } else {
        take(TRACE_REASON_LIMIT) + "… [truncated]"
    }
