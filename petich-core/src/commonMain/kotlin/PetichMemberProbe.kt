package io.github.youndie.petich

import kotlin.time.Duration

/**
 * A context for one member, which records what the member did with it.
 *
 * **The engine's own, and that is the point** (B-38). A test that wants to ask a member the question
 * the engine is about to ask it — call `compensate` directly, with no saga, no engine and no
 * database — needs a `PetichStepContext`, and before this both consumers wrote one by hand. Eleven
 * methods each, written independently, identical in shape, and broken twice: once when `stepKey`
 * arrived and once when `resuspendFor` did, each time as a compile error in the consumer at its next
 * version bump rather than where the method was added.
 *
 * Shipping a second implementation would have moved that problem rather than removed it, because a
 * double can disagree with the thing it doubles. So this **is** the implementation the engine runs
 * members through: a test asserting against it is asserting against what production does, and a
 * method added to [PetichMemberContext] is implemented once, here.
 *
 * ```kotlin
 * val probe = PetichMemberProbe(saga, stepKey = "capture")
 * CaptureStep(payments).compensate(probe, payload)
 * assertEquals(emptyList(), payments.refunded)
 * ```
 *
 * What the member asked for is readable afterwards: [enrichment], [record], [events], [effects] and
 * [decision]. Construct one per call — it accumulates, exactly as it does inside the engine, where
 * its lifetime is one member.
 */
public class PetichMemberProbe(
    override val petich: Petich,
    /**
     * What the member reads back as its own key.
     *
     * Defaulted because most direct calls do not care; give the real one where the member names a
     * span or a log line after it, since that string is what a test about the name would assert.
     */
    override val stepKey: String = "probe",
) : PetichCheckContext,
    PetichStepContext {
    private var enriched: EnrichedPayload? = null
    private var decided: MemberOutcome? = null
    private var written: PetichStepRecord? = null
    private val emitted = mutableListOf<OutboxEvent>()
    private val attached = mutableListOf<PetichSideEffect>()
    private var discardedAnnouncements = 0

    /** What the member merged into the payload the saga carries forward, or `null`. */
    public val enrichment: EnrichedPayload? get() = enriched

    /** What the member recorded about what it did, or `null` — which is "it recorded nothing". */
    public val record: PetichStepRecord? get() = written

    /** What the member asked to have announced, in the order it asked. */
    public val events: List<OutboxEvent> get() = emitted.toList()

    /** What the member asked to have committed beside the write — a durable timer, most plainly. */
    public val effects: List<PetichSideEffect> get() = attached.toList()

    /**
     * What the member decided, or [Decision.Proceeded] when it decided nothing.
     *
     * A separate vocabulary from the engine's own outcome type, which is internal on purpose: a
     * consumer asserting on a decision should not be reading the type the engine dispatches on.
     */
    public val decision: Decision
        get() =
            when (val outcome = decided) {
                null -> Decision.Proceeded
                is MemberOutcome.Suspend -> Decision.Suspended(outcome.requiredAction, outcome.ttl, again = false)
                is MemberOutcome.Resuspend -> Decision.Suspended(outcome.requiredAction, outcome.ttl, again = true)
                is MemberOutcome.Reject -> Decision.Refused(outcome.reason)
                is MemberOutcome.Compensate -> Decision.Failed(outcome.reason)
                else -> Decision.Proceeded
            }

    /** What a member can decide, as a test reads it. */
    public sealed interface Decision {
        /** It decided nothing, which is how a member says the saga carries on. */
        public data object Proceeded : Decision

        /**
         * It stopped to wait.
         *
         * [again] distinguishes `resuspendFor` from `suspendFor`, which is the difference between
         * the next answer belonging to this member and belonging to whatever comes after it — and
         * therefore between a hold taken once and a hold taken per answer (B-37).
         */
        public data class Suspended(
            val action: String,
            val ttl: Duration?,
            val again: Boolean,
        ) : Decision

        /** It refused on business grounds: the saga rolls back and ends `REJECTED`. */
        public data class Refused(
            val reason: String,
        ) : Decision

        /** It reported a fault: the saga rolls back and ends `FAILED`. */
        public data class Failed(
            val reason: String,
        ) : Decision
    }

    override fun emit(event: OutboxEvent) {
        emitted += event
    }

    override fun attach(effect: PetichSideEffect) {
        attached += effect
    }

    override fun record(value: PetichStepRecord) {
        written = value
    }

    override fun recordedValue(): PetichStepRecord? = written ?: petich.stepRecords[stepKey]

    override fun enrich(payload: EnrichedPayload) {
        enriched = enriched?.merge(payload) ?: payload
    }

    override fun suspendFor(
        action: String,
        ttl: Duration?,
    ) {
        decided = MemberOutcome.Suspend(requiredAction = action, enrichedPayload = enriched, ttl = ttl)
    }

    override fun resuspendFor(
        action: String,
        ttl: Duration?,
    ) {
        decided = MemberOutcome.Resuspend(requiredAction = action, enrichedPayload = enriched, ttl = ttl)
    }

    override fun reject(reason: String) {
        decided = MemberOutcome.Reject(reason)
    }

    override fun fail(reason: String) {
        decided = MemberOutcome.Compensate(reason)
    }

    internal fun written(): PetichStepRecord? = written

    internal fun emitted(): List<OutboxEvent> = emitted.toList()

    /**
     * What the member decided, carrying whatever it asked to have committed alongside.
     *
     * The events and effects ride on the outcomes that produce a write of their own. A refusal or a
     * fault leads to a rollback whose writes are the compensations', so anything announced there
     * belongs to the member that did the undoing rather than to this one.
     */
    internal fun outcome(): MemberOutcome =
        when (val outcome = decided) {
            null -> {
                MemberOutcome.Proceed(enriched, emitted.toList(), attached.toList())
            }

            is MemberOutcome.Suspend -> {
                outcome.copy(sideEffects = attached.toList(), outboxEvents = emitted.toList())
            }

            is MemberOutcome.Resuspend -> {
                // The same rule as Suspend, and for the same reason: a re-ask commits a write of its
                // own, so what the member asked to have committed rides with it. Resuspend has no
                // field for outbox events either, which is B-35's other half — an announcement from
                // a member that then re-asks is still dropped, and still counted rather than silent.
                if (emitted.isNotEmpty()) discardedAnnouncements = emitted.size
                outcome.copy(sideEffects = attached.toList())
            }

            else -> {
                // A REFUSAL CARRIES NOTHING, and it is counted rather than silent. `reject` and
                // `fail` both begin a rollback, and petich will not announce work it is in the
                // middle of undoing. A rollback's own word belongs to the compensations, which may
                // announce freely — but the REFUSING member's compensation does not run, so anything
                // it emitted has no owner at all. That is what this counts.
                discardedAnnouncements = emitted.size + attached.size
                outcome
            }
        }

    /**
     * How much this member asked to have committed and lost by then refusing.
     *
     * Zero on every other outcome. Deliberately not folded into
     * `PetichEngineMetrics.onDroppedEvents`, whose own documentation calls that one a mistake
     * "reached by accident rather than by decision" — a repository with no outbox at all. This is
     * the decision, and one counter meaning both would answer neither question.
     */
    internal fun discarded(): Int = discardedAnnouncements
}
