package io.github.youndie.petich

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration

@Serializable
public abstract class PetichPayload

/**
 * What a step did, in its own words, kept beside the key of the step that did it.
 *
 * **It exists so that a compensation can tell "the step did not happen" from "the step happened and
 * produced nothing".** Until now the only channel from an action to its undo was [EnrichedPayload],
 * a map merged across every member of the saga — so a compensation asking for a charge id and
 * finding none could not tell which of the two it was looking at, and had to guess. One of those
 * guesses is a live money defect in a service built on this engine (youndie/shashki#13), where the
 * fallback taken when the id was absent refunded a hold belonging to a different settlement.
 *
 * It is the evidence [PetichStep.compensate] needs in order to be safe for a step that never ran —
 * which B-18 put into the contract and left every implementation to arrange for itself.
 *
 * `@SerialName` on every subclass, for the reason [SimpleEnrichedPayload] gives: the discriminator
 * is the storage format, and without a short name it is the fully qualified class name, so moving a
 * module renders already-persisted records unreadable.
 */
@Serializable
public abstract class PetichStepRecord

@Serializable
public abstract class ResumePayload

@Serializable
public abstract class EnrichedPayload {
    public abstract fun merge(other: EnrichedPayload): EnrichedPayload
}

// @SerialName is load-bearing, not cosmetic. Without it the polymorphic discriminator is the
// fully qualified class name, which makes the STORAGE format depend on where the package lives:
// moving a module silently renders already-persisted rows unreadable. That is exactly what the
// petition -> petich rename would have done. A short name pins the format for good and decouples
// it from refactorings.
@Serializable
@SerialName("simple_enriched")
public data class SimpleEnrichedPayload(
    val data: Map<String, String> = emptyMap(),
) : EnrichedPayload() {
    override fun merge(other: EnrichedPayload): EnrichedPayload =
        if (other is SimpleEnrichedPayload) {
            SimpleEnrichedPayload(this.data + other.data)
        } else {
            other
        }
}

public enum class PetichStatus {
    DRAFT,
    PENDING_SIGNATURE,
    PROCESSING,
    COMPLETED,
    REJECTED,
    FAILED,
    COMPENSATING,

    // The rollback ran out of attempts. FAILED means "undone"; this means "undone in part, and
    // nobody is going to try again" — the difference matters to whoever has to finish it by hand,
    // and collapsing the two is how a half-rolled-back saga hides among the ordinary failures.
    //
    // Rolling deploys: an instance built before this constant existed cannot decode a row carrying
    // it. Deploy the version that knows the name before anything starts writing it.
    COMPENSATION_FAILED,
}

public fun PetichStatus.isTerminal(): Boolean =
    this == PetichStatus.COMPLETED ||
        this == PetichStatus.REJECTED ||
        this == PetichStatus.FAILED ||
        this == PetichStatus.COMPENSATION_FAILED

public data class Petich(
    val id: String,
    val type: String,
    val currentPhase: PetichPhase = PetichPhase.ENRICHMENT,
    val currentInterceptorIndex: Int = 0,
    val status: PetichStatus,
    val payload: PetichPayload,
    val enrichedPayload: EnrichedPayload = SimpleEnrichedPayload(),
    val version: Long = 0L,
    val compensatingFromIndex: Int? = null,
    val resumePayload: ResumePayload? = null,
    // The instant after which a petich awaiting client action (PENDING_SIGNATURE) counts as
    // expired. Phase timeouts (see timeoutMs) bound the EXECUTION of an interceptor, not the wait
    // for a human: without this deadline a petich stuck awaiting a confirmation lives forever,
    // holding open the saga steps already performed — stock reserved, quota claimed.
    //
    // null means "no deadline", which is how every petich behaves until a TTL is configured (see
    // defaultSuspendTtl), so existing behaviour does not change by itself.
    val suspendedUntilEpochMs: Long? = null,
    // How many times a rollback of this saga has given up. Persisted, because the retries that
    // matter are separate passes — a re-drive after a crash, not a loop inside one call — and a
    // counter that lives in memory bounds nothing across them.
    //
    // It is what stops a deterministically failing compensate() from being retried for ever once
    // something starts re-driving abandoned sagas (the second half of B-19).
    val compensationAttempts: Int = 0,
    // A fingerprint of the steps this saga has already run, in order (see PetichEngine's
    // describeChain for what a chain is). Null on a saga written before this existed and on one
    // whose engine never computed it; null is never refused, so an upgrade does not stop the sagas
    // already in flight.
    val chainFingerprint: String? = null,
    // What each member recorded about what it did, by that member's key (see PetichStepRecord).
    // Empty until something is recorded, and a member reads only its own: the shared map this
    // replaces is exactly what made "absent" and "nothing to say" one observation.
    val stepRecords: Map<String, PetichStepRecord> = emptyMap(),
)

// A wall clock for deadlines. Passed in rather than System.currentTimeMillis(): commonMain of a
// KMP module cannot see java.* regardless of how many targets actually exist, and tests must be
// able to move time instead of depending on the real one.
//
// Wall clock specifically, not TimeSource.Monotonic (which OutboxRelayWorker uses for backoff): a
// deadline survives a process restart and is compared across rows in a database, and monotonic
// marks mean nothing outside a single process.
public fun interface PetichClock {
    public fun nowEpochMs(): Long
}

public enum class PetichPhase {
    ENRICHMENT,
    VALIDATION,
    AUTHORIZATION,
    EXECUTION,
    POST_PROCESSING,
}

// The default timeout table. DEFAULTS, not engine constants: they are overridden through
// PetichEngineConfig because they differ between environments — external services answer more
// slowly on dev than in production.
public val PetichPhase.timeoutMs: Long
    get() =
        when (this) {
            PetichPhase.ENRICHMENT -> 1000L
            PetichPhase.VALIDATION -> 2000L
            PetichPhase.AUTHORIZATION -> 30000L
            PetichPhase.EXECUTION -> 10000L
            PetichPhase.POST_PROCESSING -> 2000L
        }

// Engine settings. Every default equals what used to be hardcoded, so existing code that creates
// a PetichEngine without a config behaves exactly as before.
public data class PetichEngineConfig(
    val phaseTimeoutsMs: Map<PetichPhase, Long> = PetichPhase.entries.associateWith { it.timeoutMs },
    // How many times to retry the whole processing pass on a version conflict.
    val maxProcessAttempts: Int = 5,
    // How many times to retry a state write before giving up (see forceUpdateStateWithRetry).
    val maxStateUpdateAttempts: Int = 100,
    val retryBaseDelayMs: Long = 20,
    val retryJitterMs: Long = 50,
    // Separate from the forward-pass timeout: compensation usually takes longer, since it calls
    // the same external systems but in recovery mode. null means use the phase timeout.
    val compensationTimeoutsMs: Map<PetichPhase, Long>? = null,
    // The blanket deadline for suspended petiches awaiting client action. null (the default)
    // means no deadline, behaving exactly as before TTLs existed: defaults in this config never
    // change the behaviour of existing code. It is switched on deliberately, either by the
    // application or by a specific interceptor through Suspend(ttl = ...).
    val defaultSuspendTtl: Duration? = null,
    // Refuse to build an engine whose repository cannot store outbox events. false by default,
    // because the quiet degradation is the documented behaviour and an application that wants no
    // events must not have to configure their absence.
    //
    // Worth switching on by anything that wires the outbox to a message broker, and worth it at
    // construction rather than at the drop: by the time the first event is dropped the process is
    // in production, and the drop is invisible there. The mistake it refuses is not a typo — it is
    // a plain PetichRepository reaching a place that needed an outbox-aware one, which is easy
    // because :petich-postgres is outbox-aware while a test double or a hand-rolled repository is
    // not. Its counterpart in flight is PetichEngineMetrics.onDroppedEvents.
    val requireOutbox: Boolean = false,
    // The same refusal for side effects, and it matters more than the outbox one: an application
    // that schedules a durable timer from a saga step and gets a repository that cannot store it
    // has lost the timer, not a notification.
    val requireSideEffects: Boolean = false,
    // How many times a rollback may give up before the saga is marked COMPENSATION_FAILED and left
    // for a person. Counted across passes, not inside one.
    //
    // Three rather than one, because the cause is usually the far side being briefly unavailable
    // and the whole rollback is retried, not just the step that threw. Not unbounded, because the
    // other common cause is a compensate() that will never succeed — and retrying that one for ever
    // is the hot loop this bound exists to prevent.
    val maxCompensationAttempts: Int = 3,
    // Refuse to build an engine whose compensation failures go nowhere. false by default, like the
    // two above and for the same reason: an application that has deliberately chosen to ignore them
    // must not have to configure that.
    //
    // Worth switching on by anything that rolls back money. A failing compensation is the one state
    // this engine cannot leave on its own, and with the default handler it is also silent - so the
    // first anyone hears of it is a support ticket about a reservation nobody released.
    val requireCompensationHandler: Boolean = false,
) {
    init {
        require(maxProcessAttempts > 0) { "maxProcessAttempts must be positive" }
        require(maxStateUpdateAttempts > 0) { "maxStateUpdateAttempts must be positive" }
        require(retryJitterMs >= 0) { "retryJitterMs cannot be negative" }
        require(maxCompensationAttempts > 0) { "maxCompensationAttempts must be positive" }
        require(defaultSuspendTtl == null || defaultSuspendTtl > Duration.ZERO) {
            "defaultSuspendTtl must be positive"
        }
    }

    public fun timeoutMs(phase: PetichPhase): Long = phaseTimeoutsMs[phase] ?: phase.timeoutMs

    public fun compensationTimeoutMs(phase: PetichPhase): Long = compensationTimeoutsMs?.get(phase) ?: timeoutMs(phase)
}

internal sealed interface MemberOutcome {
    val enrichedPayload: EnrichedPayload? get() = null

    data class Proceed(
        override val enrichedPayload: EnrichedPayload? = null,
        val outboxEvents: List<OutboxEvent> = emptyList(),
        // Committed with the state change this result produces, or reported as dropped. See
        // PetichSideEffect.
        val sideEffects: List<PetichSideEffect> = emptyList(),
    ) : MemberOutcome

    data class Suspend(
        val requiredAction: String,
        override val enrichedPayload: EnrichedPayload? = null,
        // How long to await client action AT THIS PARTICULAR STEP. null takes the blanket
        // deadline from PetichEngineConfig.defaultSuspendTtl: typing a one-time code and
        // approving a long-running request live on different time scales, and the interceptor
        // knows that, not the engine.
        val ttl: Duration? = null,
        // A step that suspends could hand the engine nothing at all until now — not even an outbox
        // event. A durable timer belongs here more than anywhere else: "wait until this instant" is
        // precisely the step whose timer must not go missing while its state commits.
        val sideEffects: List<PetichSideEffect> = emptyList(),
        // AND THE ANNOUNCEMENT, which this half of the pair was missing until B-35. A suspension is
        // a real, committed write — the saga row goes to PENDING_SIGNATURE — so "we are holding
        // your funds, confirm within five minutes" is an announcement with a write to ride on. The
        // side effects got a field when somebody hit the gap; the events did not, and in the
        // definition model `ctx.emit` before `ctx.suspendFor` compiled, ran, and disappeared.
        val outboxEvents: List<OutboxEvent> = emptyList(),
    ) : MemberOutcome

    data class Resuspend(
        val requiredAction: String,
        override val enrichedPayload: EnrichedPayload? = null,
        val ttl: Duration? = null,
        val sideEffects: List<PetichSideEffect> = emptyList(),
    ) : MemberOutcome

    data class Reject(
        val reason: String,
    ) : MemberOutcome

    data class Compensate(
        val reason: String,
    ) : MemberOutcome
}

public interface PetichRepository {
    public suspend fun findById(id: String): Petich?

    public suspend fun saveOrGet(petich: Petich): Petich

    public suspend fun update(petich: Petich): Boolean
}

// An optional extension: an implementation able to write outbox events in the SAME SQL
// transaction as the petich update (see ExposedPetichRepository in :petich-postgres). PetichEngine
// checks `repository is OutboxAwarePetichRepository` at the persistence point and quietly degrades
// to a plain update(petich) when the repository does not support it, or when there are no events.
// Plain PetichRepository implementations therefore keep working without a single change.
public interface OutboxAwarePetichRepository : PetichRepository {
    public suspend fun update(
        petich: Petich,
        outboxEvents: List<OutboxEvent>,
    ): Boolean

    override suspend fun update(petich: Petich): Boolean = update(petich, emptyList())
}

// Work an interceptor needs committed TOGETHER WITH the state change it produced, and which the
// engine deliberately cannot interpret.
//
// The engine carries these from the interceptor to the repository and does nothing else with them:
// it does not know what a given one means, cannot execute it, and has no dependency on whatever
// library defines it. A repository that understands a type writes it; one that does not is told so
// (see PetichEngineMetrics.onDroppedSideEffects).
//
// WHY THIS EXISTS AT ALL. Until now an interceptor could hand the engine nothing but outbox events,
// and only from Proceed. Anything else it wanted committed alongside the state — a durable timer,
// most concretely — had to be written by calling its own storage from inside intercept(), which is
// a second transaction. If the process dies between the two, the state is committed and the other
// half is not: exactly the dual write OutboxAwarePetichRepository exists to make impossible, in a
// different place. This closes the same hole for anything that is not an event.
public interface PetichSideEffect

// The counterpart of OutboxAwarePetichRepository for side effects. Optional in the same way and for
// the same reason: existing implementations keep working untouched, and a storage that cannot do
// this says so in its type rather than at runtime.
public interface SideEffectAwarePetichRepository : PetichRepository {
    public suspend fun update(
        petich: Petich,
        outboxEvents: List<OutboxEvent>,
        sideEffects: List<PetichSideEffect>,
    ): Boolean
}

// What expireSuspended did. Not a Boolean: "not found", "no longer waiting" and "deadline not
// reached yet" are three different situations, and a worker benefits from telling them apart in
// its logs.
public sealed interface ExpireResult {
    public data class Expired(
        val petichId: String,
    ) : ExpireResult

    public data class NotSuspended(
        val status: PetichStatus,
    ) : ExpireResult

    public data object NotExpiredYet : ExpireResult

    /**
     * The saga's recorded prefix does not match the chain this process assembles, so nothing was
     * rolled back. Its own answer rather than [NotExpiredYet], because the two need opposite
     * reactions: one is a saga whose client still has time, the other is a saga that will sit here
     * expired for ever until somebody looks. A sweeper that folded them together would be silently
     * doing nothing, which is the state it is hardest to notice from outside.
     */
    public data class ChainChanged(
        val petichId: String,
        val details: String,
    ) : ExpireResult

    /**
     * Another sweeper got there first. Its own answer, and the reason this type exists at all: a
     * replica that loses the claim must SKIP the saga, not retry it — retrying is what turns two
     * sweepers into two rollbacks of one saga.
     */
    public data class Contended(
        val petichId: String,
    ) : ExpireResult

    public data object NotFound : ExpireResult
}

// The reason an expired petich goes to compensation. A constant rather than an inline literal:
// it is what distinguishes a deadline rollback from a member refusing during an incident
// review.
public const val EXPIRED_REASON: String = "Petich expired while waiting for the client"

public class PetichEngine(
    private val repository: PetichRepository,
    private val compensationFailureHandler: CompensationFailureHandler = NoOpCompensationFailureHandler(),
    private val config: PetichEngineConfig = PetichEngineConfig(),
    // Needed only for the deadlines of suspended petiches. The default throws: the clock is
    // consulted exactly when a TTL is actually configured, so existing code that never enabled a
    // TTL need not touch its constructor and will never hit this stub.
    private val clock: PetichClock = PetichClock { error("PetichClock is not set, yet a suspend TTL is enabled") },
    // Counters. A no-op by default: existing code pays nothing and changes nothing (see
    // PetichEngineMetrics on why they exist at all).
    private val metrics: PetichEngineMetrics = PetichEngineMetrics.NoOp,
    /**
     * Sagas described as definitions, by type. Last in the list so that every existing positional
     * call still compiles: the two models live side by side until B-33 removes the older one.
     *
     * A saga whose `type` has a definition here is walked from it; anything else falls back to the
     * interceptor list. That is the whole of the switch — the walk below is shared, because B-21
     * had already reduced "which members run in this phase" to a single function.
     */
    private val definitions: List<PetichDefinition<*>> = emptyList(),
    /**
     * Members that apply to every saga, declared once — limits, audit, anti-fraud (B-30).
     *
     * Last, and after [definitions], so that every positional call written before this still
     * compiles. They are not a second chain: [chainFor] puts them in front of the declared members
     * of their phase, which is why [describeChain] shows them inline and the fingerprint covers
     * them without either having to know they exist.
     */
    private val globals: List<PetichGlobal> = emptyList(),
) {
    init {
        // Deliberately a construction failure and not a warning. A warning about events that will
        // be dropped is read, if at all, in the logs of a process that is already serving traffic,
        // and it competes with everything else printed at startup; the whole difficulty with this
        // mistake is that nothing downstream of it looks wrong.
        // ONE NAMESPACE FOR EVERY KEY IN A CHAIN, refused at construction rather than discovered
        // from a record that overwrote another. A key is the member's identity in the saga's row —
        // `stepRecords` is keyed by it and the fingerprint is built from it — so two members
        // sharing one in the same chain make both ambiguous, and a global shares its chain with
        // every definition there is.
        globals.groupBy { it.key }.forEach { (key, sharing) ->
            require(sharing.size == 1) { "two globals are declared under the key `$key`" }
        }
        globals.forEach { global ->
            definitions.forEach { definition ->
                require(definition.members.none { it.key == global.key }) {
                    "the global `${global.key}` collides with a member of `${definition.type}`: a key " +
                        "is what identifies a member in the saga's row, and a chain cannot hold two"
                }
            }
        }

        require(!config.requireSideEffects || repository is SideEffectAwarePetichRepository) {
            "requireSideEffects is set, but ${repository::class.simpleName} is not a " +
                "SideEffectAwarePetichRepository: work an interceptor asks to have committed with " +
                "the state change would be dropped, and the saga would complete looking correct."
        }
        require(!config.requireCompensationHandler || compensationFailureHandler !is NoOpCompensationFailureHandler) {
            "requireCompensationHandler is set, but the engine was built with the no-op handler: a " +
                "rollback that gives up would leave the saga half undone and say nothing, and that " +
                "is the one state it cannot leave on its own."
        }
        require(!config.requireOutbox || repository is OutboxAwarePetichRepository) {
            "requireOutbox is set, but ${repository::class.simpleName} is not an " +
                "OutboxAwarePetichRepository: outbox events produced by members would be " +
                "dropped and the sagas would still report success"
        }
    }

    // The per-petich lock is needed only while processing. We count references, because dropping
    // it "when done" is not enough: while one call holds the mutex, a second may already have
    // taken it from the map and be waiting on it, and removing the entry at that moment would hand
    // a third call a NEW mutex — letting two calls process one petich at the same time.
    private class LockEntry(
        val mutex: Mutex = Mutex(),
        var holders: Int = 0,
    )

    private val lockMapMutex = Mutex()
    private val petichLocks = mutableMapOf<String, LockEntry>()

    // Observability for tests: how many locks the engine holds right now. Not visible outside the
    // module (internal) — this is not part of the contract, only a way to prove locks get released.
    internal val activeLockCount: Int get() = petichLocks.size

    // The single persistence point that outbox events pass through. Degrades quietly to
    // repository.update(petich) when there are no events, or when the repository is not an
    // OutboxAwarePetichRepository (see the comment on the interface itself).
    //
    // The two ways of reaching the plain update are NOT the same event and are deliberately not
    // written as one condition. No events is nothing happening. Events with a repository that
    // cannot store them is a loss, and the only trace it leaves anywhere, since the write succeeds
    // and the saga completes exactly as it would have.
    private suspend fun updatePetich(
        petich: Petich,
        outboxEvents: List<OutboxEvent> = emptyList(),
        sideEffects: List<PetichSideEffect> = emptyList(),
    ): Boolean {
        // SIDE EFFECTS FIRST, because a repository that can take them takes the events with them:
        // the whole point is one transaction, and asking twice would be two.
        if (sideEffects.isNotEmpty()) {
            if (repository is SideEffectAwarePetichRepository) {
                return repository.update(petich, outboxEvents, sideEffects)
            }
            // The same shape as a dropped event and for the same reason: the write succeeds, the
            // saga completes, its state is correct, and only the thing nobody is waiting for right
            // now never happens. A counter is the only trace it leaves.
            metrics.onDroppedSideEffects(petich.type, sideEffects.size)
        }

        return when {
            outboxEvents.isEmpty() -> {
                repository.update(petich)
            }

            repository is OutboxAwarePetichRepository -> {
                repository.update(petich, outboxEvents)
            }

            else -> {
                metrics.onDroppedEvents(petich.type, outboxEvents.size)
                repository.update(petich)
            }
        }
    }

    /**
     * One member of a phase, whichever model described it.
     *
     * The walk below asks four things of a member, and both models can answer them: what it is
     * called, how it reads in a dump, what running it produced, and what undoing it produced. Two
     * models meeting at one point is the price of not rewriting three hundred tests in the same
     * change as the model — and the point disappears with the older arm (B-33).
     */
    private interface PetichMemberRun {
        val stepKey: String
        val label: String

        suspend fun run(
            petich: Petich,
            payload: PetichPayload,
        ): MemberOutcome?

        suspend fun undo(
            petich: Petich,
            payload: PetichPayload,
        ): List<OutboxEvent>

        /** What the last [run] recorded, if anything. Always null for the older model. */
        fun lastRecord(): PetichStepRecord?

        /**
         * How much the last [run] asked to have committed and lost by then refusing (B-35).
         *
         * Always zero for the older model, where an interceptor returning `Reject` had no channel
         * to ask through in the first place — which is exactly how this was introduced without
         * anybody noticing: the new model accepts what the old one could not express.
         */
        fun discardedAnnouncements(): Int = 0
    }

    /**
     * A member that applies to every saga, run the same way a declared check is.
     *
     * No cast, unlike [DefinitionRun]: a global's check is declared over `PetichPayload` because it
     * has to accept whatever saga it lands in, so there is no narrower type to assert.
     */
    private class GlobalRun(
        private val global: PetichGlobal,
    ) : PetichMemberRun {
        override val stepKey: String get() = global.key

        // NAMED AS A GLOBAL WHERE THE CHAIN IS READ. The point of rendering these inline is that a
        // reader of one saga sees what will run; the point of saying "global" is that they can tell
        // which lines are not in the definition they are holding.
        override val label: String get() = "${global.key} (global check)"

        override suspend fun run(
            petich: Petich,
            payload: PetichPayload,
        ): MemberOutcome {
            val context = RecordingContext(petich, global.key)
            global.check.check(context, payload)
            return context.outcome()
        }

        /** Nothing, and that is the type's whole argument (D9): a global leaves no rollback behind. */
        override suspend fun undo(
            petich: Petich,
            payload: PetichPayload,
        ): List<OutboxEvent> = emptyList()

        override fun lastRecord(): PetichStepRecord? = null
    }

    /**
     * A member of a definition, run through a context that RECORDS its outcome.
     *
     * `ctx.reject(...)` maps onto the engine's existing `Reject` and `ctx.fail(...)` onto
     * `Compensate` — which is the post-B-20 vocabulary read back: both roll back what ran, and they
     * differ in the name the saga ends under. A member that sets nothing proceeds.
     */
    private class DefinitionRun<P : PetichPayload>(
        private val member: PetichMember<P>,
    ) : PetichMemberRun {
        private var recorded: PetichStepRecord? = null
        private var discarded = 0

        override fun lastRecord(): PetichStepRecord? = recorded

        override fun discardedAnnouncements(): Int = discarded

        /**
         * ONE cast, in one place, for the forward pass and the rollback both.
         *
         * It is at the boundary where a row meets the definition its type names — rather than one
         * per member behind a `supports()` that could lie about anyone's payload. It cannot be
         * removed while a stored payload is polymorphic and a definition is generic; what this
         * stage changed is that there is a single declared place for it to be wrong.
         *
         * **It cannot announce itself here**, which is what "unchecked" means: `as P` on an erased
         * type does not throw on this line. The ClassCastException arrives later, when the member is
         * actually handed the value — so the diagnostic sits around the dispatch, exactly where
         * `withPayloadDiagnostics` sat for the older model.
         */
        @Suppress("UNCHECKED_CAST")
        private fun typed(payload: PetichPayload): P = payload as P

        override val stepKey: String get() = member.key
        override val label: String get() = member.key + if (member.undoes) "" else " (check)"

        override suspend fun run(
            petich: Petich,
            payload: PetichPayload,
        ): MemberOutcome {
            val context = RecordingContext(petich, member.key)
            // ONE cast, at the boundary where a row meets the definition its type names — rather
            // than one per member behind a `supports()` that could lie about anyone's payload. It
            // cannot be removed while a stored payload is polymorphic and a definition is generic;
            // what changed is that there is a single declared place for it to be wrong.
            val typed = typed(payload)
            // IN A FINALLY, because the case that matters most is the one where `execute` does not
            // return. A member that records what it did and then throws is the ambiguous failure
            // B-18 exists for: its own compensation is called, and without the record it concludes
            // "the step did not happen" about a step that may well have. Read after the fact, the
            // record survives however the member left.
            try {
                try {
                    member.step?.execute(context, typed) ?: member.check?.check(context, typed)
                } catch (e: ClassCastException) {
                    // The unchecked cast above, failing where it actually shows: a definition
                    // declared for one payload and registered under a saga type whose rows carry
                    // another. Bare, this is two class names and nothing about the saga.
                    //
                    // It can mis-attribute — a ClassCastException from inside the member's own body
                    // is relabelled too — and that trade was already made for the older model, which
                    // wrapped its call the same way. A message naming the wrong cause is cheaper
                    // than one naming no cause at all, and the original is kept as the cause.
                    throw IllegalStateException(
                        "member `${member.key}` of saga type `${petich.type}` was handed a " +
                            "${payload::class.simpleName}, which its definition is not declared for",
                        e,
                    )
                }
            } finally {
                recorded = context.written()
            }
            val outcome = context.outcome()
            // AFTER outcome(), which is where the decision and what was asked for meet. Read before
            // it, this would always answer zero.
            discarded = context.discarded()
            return outcome
        }

        override suspend fun undo(
            petich: Petich,
            payload: PetichPayload,
        ): List<OutboxEvent> {
            val step = member.step ?: return emptyList()

            val typed = typed(payload)
            // The context a compensation gets is built from the SAGA AS STORED, so `recorded()`
            // answers with what this member wrote when it ran — or null, which is what a step that
            // never ran looks like from inside its own undo.
            val context = RecordingContext(petich, member.key)
            step.compensate(context, typed)
            // A compensation may announce what it undid, in the write that records the undoing —
            // which is what `compensateWithEvents` was for in the model this replaces.
            return context.emitted()
        }
    }

    /** What a member said, collected rather than thrown — see PetichMemberContext.suspendFor. */
    private class RecordingContext(
        override val petich: Petich,
        override val stepKey: String,
    ) : PetichCheckContext,
        PetichStepContext {
        private var enriched: EnrichedPayload? = null
        private var decided: MemberOutcome? = null
        private var written: PetichStepRecord? = null
        private val events = mutableListOf<OutboxEvent>()
        private val effects = mutableListOf<PetichSideEffect>()
        private var discardedAnnouncements = 0

        override fun emit(event: OutboxEvent) {
            events += event
        }

        override fun attach(effect: PetichSideEffect) {
            effects += effect
        }

        fun emitted(): List<OutboxEvent> = events.toList()

        override fun record(value: PetichStepRecord) {
            written = value
        }

        override fun recordedValue(): PetichStepRecord? = written ?: petich.stepRecords[stepKey]

        fun written(): PetichStepRecord? = written

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

        /**
         * What the member decided, carrying whatever it asked to have committed alongside.
         *
         * The events and effects ride on the outcomes that produce a write of their own. A refusal
         * or a fault leads to a rollback whose writes are the compensations', so anything announced
         * there belongs to the member that did the undoing rather than to this one.
         */
        fun outcome(): MemberOutcome =
            when (val decision = decided) {
                null -> {
                    MemberOutcome.Proceed(enriched, events.toList(), effects.toList())
                }

                is MemberOutcome.Suspend -> {
                    decision.copy(sideEffects = effects.toList(), outboxEvents = events.toList())
                }

                is MemberOutcome.Resuspend -> {
                    // The same rule as Suspend, and for the same reason: a re-ask commits a write of
                    // its own, so what the member asked to have committed rides with it. Resuspend
                    // has no field for outbox events either, which is B-35's other half — an
                    // announcement from a member that then re-asks is still dropped, and still
                    // counted rather than silent.
                    if (events.isNotEmpty()) discardedAnnouncements = events.size
                    decision.copy(sideEffects = effects.toList())
                }

                else -> {
                    // A REFUSAL CARRIES NOTHING, and it is now counted rather than silent. `reject`
                    // and `fail` both begin a rollback, and petich will not announce work it is in
                    // the middle of undoing. A rollback's own word belongs to the compensations,
                    // which may announce freely — but the REFUSING member's compensation does not
                    // run, so anything it emitted has no owner at all. That is what this counts.
                    discardedAnnouncements = events.size + effects.size
                    decision
                }
            }

        /**
         * How much this member asked to have committed and lost by then refusing.
         *
         * Zero on every other outcome. Deliberately not folded into
         * `PetichEngineMetrics.onDroppedEvents`, whose own documentation calls that one a mistake
         * "reached by accident rather than by decision" — a repository with no outbox at all. This
         * is the decision, and one counter meaning both would answer neither question.
         */
        fun discarded(): Int = discardedAnnouncements
    }

    /**
     * Whether this engine has a definition for [petich]'s type — the question an application used
     * to answer with a mapping of its own (B-31).
     *
     * `SuspendedPetichSweeper` and `SagaTimerSink` took an `engineFor: (Petich) -> PetichEngine?`
     * because several engines shared one saga store and only the application knew which owned
     * which. `Petich.type` carried that identity all along; what was missing was a value on the
     * other side of it, and `PetichDefinition` is now that value. The mapping was a thing to
     * maintain, and the way it failed was silence: a type introduced and not registered produced
     * sagas that piled up expired forever, unless somebody had wired the optional callback.
     *
     * **An engine with no definitions owns everything it is given.** That is the interceptor
     * model, where the engine is a fixed list and the application's lambda was the only thing that
     * ever decided ownership — so this answers exactly as before for it, and B-33 removes the
     * branch along with the model.
     */
    public fun owns(petich: Petich): Boolean = definitions.isEmpty() || definitionFor(petich.type) != null

    private fun definitionFor(type: String?): PetichDefinition<*>? =
        type?.let { wanted -> definitions.firstOrNull { it.type == wanted } }

    private fun chainFor(
        phase: PetichPhase,
        payload: PetichPayload,
        type: String? = null,
    ): List<PetichMemberRun> {
        // IN FRONT, AND THROUGH THE SAME SEAM as everything else. Every question anybody asks about
        // a chain — what runs, in what order, what the fingerprint covers, what a mismatch prints —
        // is answered by this one function, so a global added here is inline everywhere by
        // construction rather than by four places remembering to include it (B-30).
        val cross = globals.filter { it.phase == phase }.map { GlobalRun(it) }
        definitionFor(type)?.let { definition ->
            return cross + definition.members.filter { it.phase == phase }.map { DefinitionRun(it) }
        }
        // NO FALLBACK ANY MORE. A saga whose type has no definition used to walk the interceptor
        // list; there is no list, so it walks nothing — and `doProcess` refuses a saga no member of
        // any phase applies to rather than completing it (#78), which is what that refusal is for.
        return cross
    }

    /**
     * The resolved chain for one payload, phase by phase, as text.
     *
     * The flow of a single saga is written down nowhere else: to see it, someone has to collect
     * every interceptor whose `supports()` accepts the payload and sort them, in their head. Numeric
     * priorities behave like `z-index` as they grow, and the order compensations run in is the
     * reverse of this — which is a thing to review with eyes, in a payments codebase.
     *
     * Print it at startup, or snapshot it in a test: then a chain that changed shows up in a diff
     * rather than in a saga.
     */
    public fun describeChain(
        payload: PetichPayload,
        type: String? = null,
    ): String =
        PetichPhase.entries.joinToString("\n") { phase ->
            // WITH THE TYPE, which it never had and could not work without. `chainFor` resolves a
            // definition BY TYPE, so every call that omitted it described the interceptor chain —
            // for a consumer on definitions, the empty one. The diagnostic that exists to say "the
            // chain here is" printed five dashes to exactly the people this stage is built for
            // (B-30). Defaulted, so every existing call still compiles and the interceptor model
            // keeps the behaviour it had.
            val steps = chainFor(phase, payload, type)
            val body = if (steps.isEmpty()) "-" else steps.joinToString(" -> ") { it.label }
            "$phase: $body"
        }

    /**
     * A fingerprint of the steps this saga has ALREADY RUN, in order.
     *
     * **The prefix, not the whole chain, and that is the whole design.** A fingerprint over every
     * step would refuse every saga in flight after any legitimate deploy that appends one, and a
     * guard that fires on a normal release is switched off in its first week — leaving the silent
     * case back where it was, with a disabled check in front of it.
     *
     * Its own hash rather than [String.hashCode]: this value is written to a database by one process
     * and compared by another, possibly on a different target, and a hash whose algorithm is not
     * promised across those is a comparison that fails for a reason nobody can see. FNV-1a is four
     * lines and the same everywhere.
     */
    private fun prefixFingerprint(petich: Petich): String? {
        val names = mutableListOf<String>()
        try {
            for (phase in PetichPhase.entries) {
                if (phase.ordinal > petich.currentPhase.ordinal) break
                val steps = chainFor(phase, petich.payload, petich.type).map { it.stepKey }
                names += if (phase == petich.currentPhase) steps.take(petich.currentInterceptorIndex) else steps
            }
        } catch (e: Exception) {
            // The chain could not be assembled — a supports() that throws, or a tie refused by
            // configuration. NO FINGERPRINT, rather than a failure: this is a guard, and a guard
            // must never be the reason a write does not happen. Every write path goes through here,
            // including the emergency transition to FAILED that exists precisely for an interceptor
            // that misbehaves; throwing from here turned that path into an exception escaping
            // process() (caught by EngineDefectsTest, which is what it is for).
            //
            // Nothing is hidden by this: the counter says it happened, and a non-zero rate means
            // sagas are being persisted with the guard off. A saga left without a fingerprint is
            // one that will not be refused later — the same position as every saga written before
            // this existed.
            metrics.onChainUnavailable(petich.type, e.message ?: e::class.simpleName ?: "unknown")
            return null
        }

        var hash = 2166136261u
        for (char in names.joinToString("|")) {
            hash = hash xor char.code.toUInt()
            hash *= 16777619u
        }
        return hash.toString(16)
    }

    /**
     * The saga's recorded prefix against the chain this process assembles today, or null when they
     * agree — and null whenever the saga carries no fingerprint, which is how an upgrade leaves
     * everything already in flight alone.
     *
     * Refusing is the whole point. A saga's position is an index into a list filtered by
     * `supports()` and sorted by priority, and the row stores nothing else: a deploy that adds,
     * removes or re-prioritises a step in the same or an earlier phase silently re-points every
     * suspended saga at a DIFFERENT step, and the rollback with it. Stopping loudly is worth far
     * more than continuing plausibly, and nothing here tries to repair it — repairing would mean
     * guessing which step the index used to mean.
     */
    private fun chainMismatch(petich: Petich): PetichResult? {
        val recorded = petich.chainFingerprint ?: return null
        // Null means the chain could not be assembled at all, which is not the same as "it changed"
        // and must not be reported as it: refusing here would turn a broken supports() into a saga
        // nobody can touch, on top of the failure it already causes.
        val current = prefixFingerprint(petich) ?: return null
        if (recorded == current) return null
        return PetichResult.SystemFailure(
            "the interceptor chain changed under saga ${petich.id}: it recorded $recorded for the steps " +
                "it had run and this process computes $current, so ${petich.currentPhase} index " +
                "${petich.currentInterceptorIndex} no longer means the same step. Nothing was run. " +
                "The chain here is:\n${describeChain(petich.payload, petich.type)}",
        )
    }

    /** The saga with whatever [member] wrote folded in, or unchanged when it wrote nothing. */
    private fun withRecordOf(
        member: PetichMemberRun,
        petich: Petich,
    ): Petich {
        val written = member.lastRecord() ?: return petich
        return petich.copy(stepRecords = petich.stepRecords + (member.stepKey to written))
    }

    private suspend fun triggerCompensation(
        petich: Petich,
        reason: String,
        isSystemFailure: Boolean = false,
        // The step at currentInterceptorIndex was entered and never reported an outcome: it threw
        // or it timed out. Then it is part of the rollback, and this is the whole of B-18.
        //
        // The index only advances after a committed Proceed, so without this flag the rollback
        // starts one below the step that failed and that step compensates nothing. That is correct
        // only if a failed intercept() means nothing happened, which for a remote call it does not:
        // the reservation reaching the far side while the answer is lost is the ordinary failure of
        // a distributed system, and the engine sees exactly what it sees when the call never landed.
        // It cannot tell the two apart, so it rolls back the one that might have happened — and the
        // price is paid in the contract instead: compensate() may be called for a step that did not
        // happen (see PetichStep.compensate).
        //
        // NOT set for MemberOutcome.Compensate. That is a reported outcome — the step is alive
        // and said what it wants; a step that did work and then decided to roll back has its own
        // body to undo it in. Ambiguity is what this flag is about, and there is none there.
        //
        // NOT set for an expired suspension either, where currentInterceptorIndex already points
        // PAST the step that suspended (the Suspend branch stores index + 1). Adding to it there
        // would compensate a step that was never entered.
        stepOutcomeUnknown: Boolean = false,
        // What the saga becomes once the rollback finishes. FAILED for a fault; REJECTED when the
        // rollback is there because a step refused the saga on business grounds.
        //
        // The two are told apart on a replay under the same id, so they cannot be collapsed: a
        // client repeating a request that was refused must be told it was refused, not that the
        // server broke. Undoing the work and naming the outcome are separate questions, and only
        // the first one was ever missing.
        terminalStatus: PetichStatus = PetichStatus.FAILED,
    ): PetichResult {
        metrics.onCompensation(petich.type, reason)
        // The field means "one past the next step to compensate", and it means that for every
        // writer and for the reader below — including a rollback resumed from storage, which takes
        // this branch's `?:` never, because its value was persisted by the loop further down. That
        // is what keeps a resumed rollback from compensating the same step twice: the meaning of
        // the number does not depend on who wrote it.
        val compensateFromIdx =
            petich.compensatingFromIndex
                ?: (petich.currentInterceptorIndex + if (stepOutcomeUnknown) 1 else 0)
        // Via forceUpdateStateWithRetry rather than a plain update with the result discarded:
        // the "rollback started" mark is the most important write of the whole scenario. Losing it
        // to a version conflict would leave the engine compensating a petich that the database
        // still shows as executing.
        var currentPetich =
            forceUpdateStateWithRetry(
                petich,
                PetichStatus.COMPENSATING,
                petich.enrichedPayload,
                { it.copy(currentPhase = petich.currentPhase, compensatingFromIndex = compensateFromIdx) },
            )

        var compensationFailed = false
        var failedOn: String? = null

        withContext(NonCancellable) {
            val startPhaseOrdinal = currentPetich.currentPhase.ordinal

            for (phaseOrdinal in startPhaseOrdinal downTo 0) {
                val phase = PetichPhase.entries[phaseOrdinal]
                val phaseInterceptors = chainFor(phase, currentPetich.payload, currentPetich.type)

                if (phaseInterceptors.isEmpty()) continue

                val startIndex =
                    if (phaseOrdinal == startPhaseOrdinal) {
                        // Coerced, because this number can now equal the phase's size — the last
                        // step of a phase failing is the ordinary way to get there — and because a
                        // number read back from storage was computed against the chain as it was
                        // assembled then. A deploy that shortens a phase would otherwise index past
                        // the end and crash the rollback, which is the one pass that must not throw.
                        // Landing on the wrong step is a separate defect with its own item (B-21).
                        (compensateFromIdx - 1).coerceAtMost(phaseInterceptors.size - 1)
                    } else {
                        phaseInterceptors.size - 1
                    }

                var rollbackIndex = startIndex
                while (rollbackIndex >= 0) {
                    val interceptor = phaseInterceptors[rollbackIndex]
                    try {
                        // Compensation is time-bounded too. It used to be called directly inside
                        // NonCancellable, so a hung network call in a compensation held the caller
                        // forever, and by construction nothing outside could cancel it.
                        // withTimeout inside NonCancellable does work: it is the outer job that is
                        // non-cancellable, while the timeout cancels its own child coroutine.
                        val compensationEvents =
                            withTimeout(config.compensationTimeoutMs(phase)) {
                                interceptor.undo(
                                    currentPetich,
                                    currentPetich.payload,
                                )
                            }
                        rollbackIndex--
                        currentPetich =
                            forceUpdateStateWithRetry(
                                currentPetich,
                                PetichStatus.COMPENSATING,
                                currentPetich.enrichedPayload,
                                { it.copy(compensatingFromIndex = rollbackIndex + 1, currentPhase = phase) },
                                outboxEvents = compensationEvents,
                            )
                    } catch (e: Exception) {
                        compensationFailureHandler.handle(e, currentPetich, interceptor.stepKey)
                        compensationFailed = true
                        failedOn = interceptor.stepKey
                        break
                    }
                }

                if (compensationFailed) break
            }

            if (!compensationFailed) {
                forceUpdateStateWithRetry(
                    currentPetich,
                    terminalStatus,
                    currentPetich.enrichedPayload,
                    { it.copy(currentInterceptorIndex = 0, compensatingFromIndex = null) },
                )
            } else {
                recordGivingUp(currentPetich, failedOn)
            }
        }

        return if (isSystemFailure) {
            PetichResult.SystemFailure(reason)
        } else {
            PetichResult.Error(reason)
        }
    }

    /**
     * A rollback stopped without finishing. Count the attempt, and at the bound give up for good.
     *
     * Before this the engine wrote nothing at all here: the saga stayed COMPENSATING with whatever
     * position the last successful step had left, no record that a rollback had been tried, and — on
     * the default handler — in silence. It is the one state this engine cannot leave on its own, so
     * it is the one that most needs to be countable and, eventually, final.
     */
    private suspend fun recordGivingUp(
        petich: Petich,
        failedOn: String?,
    ) {
        val attempt = petich.compensationAttempts + 1
        val exhausted = attempt >= config.maxCompensationAttempts
        metrics.onCompensationFailure(petich.type, attempt, exhausted)

        // Asked before the write, and only at the bound: an application that announces this is
        // announcing something final, and the event has to be committed with the status rather
        // than after it.
        val events =
            if (exhausted && failedOn != null) {
                compensationFailureHandler.exhausted(petich, failedOn, attempt)
            } else {
                emptyList()
            }

        try {
            forceUpdateStateWithRetry(
                petich,
                if (exhausted) PetichStatus.COMPENSATION_FAILED else PetichStatus.COMPENSATING,
                petich.enrichedPayload,
                { it.copy(compensationAttempts = attempt) },
                outboxEvents = events,
            )
        } catch (e: OptimisticLockException) {
            // Swallowed, and deliberately. This is bookkeeping about a rollback that has already
            // failed; letting it out would turn "the rollback gave up" into an exception thrown from
            // process(), which is not what the caller is told anywhere else on this path. The saga
            // stays COMPENSATING — exactly where it was before this method existed — and the counter
            // above has already fired, so the event is not invisible.
            metrics.onStateUpdateRetry(petich.type)
        }
    }

    // null when no TTL is configured either globally or at this step, in which case the petich
    // waits indefinitely, as it did before TTLs existed.
    private fun suspendDeadline(ttl: Duration?): Long? {
        val effective = ttl ?: config.defaultSuspendTtl ?: return null
        return clock.nowEpochMs() + effective.inWholeMilliseconds
    }

    // The entry point for the background sweeper of expired petiches (see
    // SuspendedPetichSweeper). A separate method rather than "just call process": process would
    // carry the saga forward, whereas an expired petich must be rolled back.
    //
    // It goes through the same mutex as ordinary processing. The race "the client confirms at the
    // exact moment the deadline passes" is resolved by one of the two arriving second and
    // seeing state that has already changed. That is why the state is re-read inside the lock and
    // every condition re-checked: the decision the worker made from its query results may be stale
    // by now.
    public suspend fun expireSuspended(petichId: String): ExpireResult {
        val entry =
            lockMapMutex.withLock {
                petichLocks.getOrPut(petichId) { LockEntry() }.also { it.holders++ }
            }

        try {
            return entry.mutex.withLock {
                val petich = repository.findById(petichId) ?: return@withLock ExpireResult.NotFound
                val deadline = petich.suspendedUntilEpochMs
                when {
                    petich.status != PetichStatus.PENDING_SIGNATURE -> {
                        ExpireResult.NotSuspended(petich.status)
                    }

                    // The client answered in time and the petich went round again with a new
                    // deadline.
                    deadline == null || clock.nowEpochMs() < deadline -> {
                        ExpireResult.NotExpiredYet
                    }

                    // The same refusal as on the forward path, and it matters more here: an
                    // expiry rolls back without anyone watching, and a rollback walking a chain
                    // that has changed under it compensates steps that never ran.
                    else -> {
                        val mismatch = chainMismatch(petich)
                        if (mismatch is PetichResult.SystemFailure) {
                            ExpireResult.ChainChanged(petichId, mismatch.details)
                        } else {
                            expireClaimed(petich, petichId)
                        }
                    }
                }
            }
        } finally {
            lockMapMutex.withLock {
                if (--entry.holders == 0) petichLocks.remove(petichId)
            }
        }
    }

    /**
     * Take the saga, then roll it back — in that order, and the order is the whole point.
     *
     * **The claim IS the `PENDING_SIGNATURE -> COMPENSATING` transition**, written with a plain
     * `update` whose `false` is obeyed. The per-saga mutex above is per PROCESS: it keeps two
     * coroutines here apart and says nothing about the replica next to it, and the optimistic lock
     * on the row is the only thing both of them can see.
     *
     * **It has to be this write and not a touch of the version**, because a touch leaves the row
     * matching `findExpired` and leaves the outcome to whoever writes `COMPENSATING` first — which
     * is nobody, since the write that follows goes through [forceUpdateStateWithRetry] and that one
     * re-reads and writes again until it wins. Its retry is right where it lives (it competes with a
     * live handler, and losing the rollback mark would leave a saga the database shows as executing
     * with nothing rolling it back) and wrong as an arbiter, so the arbitration happens before it.
     *
     * The cost is one extra write per expiry: the rollback's own first write records `COMPENSATING`
     * a second time. That is the price of the loser being stopped before it calls a single
     * `compensate()`, and it is paid only on the expiry path.
     */
    private suspend fun expireClaimed(
        petich: Petich,
        petichId: String,
    ): ExpireResult {
        val claimed =
            petich.copy(
                status = PetichStatus.COMPENSATING,
                // Written here rather than left null so the rollback that follows computes the same
                // starting point it would have computed for itself.
                compensatingFromIndex = petich.currentInterceptorIndex,
                suspendedUntilEpochMs = null,
                version = petich.version + 1,
            )
        if (!repository.update(claimed)) return ExpireResult.Contended(petichId)

        triggerCompensation(claimed, EXPIRED_REASON)
        return ExpireResult.Expired(petichId)
    }

    public suspend fun process(petich: Petich): PetichResult {
        val entry =
            lockMapMutex.withLock {
                petichLocks.getOrPut(petich.id) { LockEntry() }.also { it.holders++ }
            }

        try {
            return entry.mutex.withLock {
                processWithRetry(petich)
            }
        } finally {
            lockMapMutex.withLock {
                if (--entry.holders == 0) petichLocks.remove(petich.id)
            }
        }
    }

    private suspend fun processWithRetry(petich: Petich): PetichResult {
        var currentAttempt = 0

        while (currentAttempt < config.maxProcessAttempts) {
            try {
                metrics.onProcessAttempt(petich.type)
                return doProcess(petich)
            } catch (e: OptimisticLockException) {
                currentAttempt++
                metrics.onOptimisticRetry(petich.type, currentAttempt)
                if (currentAttempt >= config.maxProcessAttempts) throw e

                val backoff =
                    (2.0.pow(currentAttempt) * config.retryBaseDelayMs).toLong() +
                        Random.nextLong(config.retryJitterMs + 1)
                delay(backoff)
            }
        }
        return PetichResult.SystemFailure("Max retries exceeded")
    }

    private suspend fun forceUpdateStateWithRetry(
        petich: Petich,
        status: PetichStatus,
        enrichedPayload: EnrichedPayload,
        additionalUpdates: (Petich) -> Petich = { it },
        outboxEvents: List<OutboxEvent> = emptyList(),
        sideEffects: List<PetichSideEffect> = emptyList(),
    ): Petich {
        // There is no "nothing to change" branch here, and cannot be: version is always
        // latest.version + 1 and no caller winds it back, so the former `updated == latest` check
        // was unreachable.
        repeat(config.maxStateUpdateAttempts) { attempt ->
            if (attempt > 0) metrics.onStateUpdateRetry(petich.type)
            val latest = repository.saveOrGet(petich)
            val updated =
                additionalUpdates(
                    latest.copy(
                        status = status,
                        enrichedPayload = enrichedPayload,
                        // From the petich the caller holds rather than from the row just read: a
                        // member records in memory and the write that carries its position is the
                        // one that must carry the record too. Taking it from `latest` would drop
                        // what the member just wrote, which is the whole point of the channel.
                        stepRecords = petich.stepRecords,
                        version = latest.version + 1,
                        // The deadline lives exactly as long as the petich waits for the client.
                        // Clearing it only in the Proceed branch is not enough: an interceptor that
                        // returned Suspend is deliberately NOT re-executed on resume (see
                        // ResumeInterceptorTest), so a petich that ran to completion would carry a
                        // stale deadline into a terminal status. additionalUpdates is applied
                        // afterwards and so remains the way to set a new deadline (see the Suspend
                        // and Resuspend branches).
                        suspendedUntilEpochMs =
                            latest.suspendedUntilEpochMs.takeIf {
                                status ==
                                    PetichStatus.PENDING_SIGNATURE
                            },
                    ),
                )
            val stamped = updated.copy(chainFingerprint = prefixFingerprint(updated))
            if (updatePetich(stamped, outboxEvents, sideEffects)) return stamped
        }
        throw OptimisticLockException()
    }

    // The emergency transition to a terminal status. A separate method because this used to be a
    // direct repository.update(...) with the result discarded: on a version conflict the write was
    // silently lost, the client got a SystemFailure, and the petich stayed in an intermediate
    // status forever with nobody to pick it up.
    private suspend fun failTerminally(
        petich: Petich,
        details: String,
    ): PetichResult =
        try {
            forceUpdateStateWithRetry(petich, PetichStatus.FAILED, petich.enrichedPayload)
            PetichResult.SystemFailure(details)
        } catch (e: OptimisticLockException) {
            // The write failed even with retries. Staying silent about it would leave the
            // divergence between what the client was told and what the database holds unnoticed.
            PetichResult.SystemFailure("$details (could not persist FAILED status: version conflict)")
        }

    private suspend fun doProcess(petich: Petich): PetichResult {
        var currentPetich =
            repository
                .saveOrGet(petich)
                .copy(resumePayload = petich.resumePayload)
        var currentEnrichedPayload = currentPetich.enrichedPayload

        chainMismatch(currentPetich)?.let { return it }

        if (currentPetich.status == PetichStatus.COMPENSATING) {
            return triggerCompensation(currentPetich, "Resuming compensation")
        }

        if (currentPetich.status.isTerminal()) {
            return when (currentPetich.status) {
                PetichStatus.COMPLETED -> {
                    PetichResult.Success(currentPetich)
                }

                PetichStatus.REJECTED -> {
                    PetichResult.Error("Petich was already rejected")
                }

                // Its own sentence rather than the one below: a repeat under the same id must not
                // tell the caller the saga was rolled back when part of it was not.
                PetichStatus.COMPENSATION_FAILED -> {
                    PetichResult.Error("Petich was rolled back only in part and needs a person")
                }

                // FAILED is as finished an outcome as REJECTED, and a repeat under the same id
                // must return what the pass that failed it returned. A saga rolled back through
                // Compensate returned Error, while its repeat returned SystemFailure — the same
                // outcome looking like a business rejection or a server fault depending on which
                // request number it was. A load test hit this first: a client repeating a request
                // with the same idempotency key after a rollback got a 500.
                //
                // SystemFailure keeps its meaning — "this call could not do the work". Here there
                // is no work by construction: the petich is terminal and the engine has nothing to
                // do.
                else -> {
                    PetichResult.Error("Petich has already failed")
                }
            }
        }

        try {
            // A SAGA THAT MATCHES NOTHING IS NOT A COMPLETED SAGA.
            //
            // Every phase empty means the engine was handed a saga it has no members for: a definition
            // whose `type` is spelled differently from the row's, an interceptor list that is not this
            // saga's, or a registration nobody made. Without this the walk finds nothing to do in five
            // phases and writes COMPLETED — the caller is told the work succeeded, the money never
            // moved, and every assertion anyone naturally writes about the RESULT passes.
            //
            // Found by migrating a consumer whose type constant reads `top_up` against a definition
            // declared as `topup`. Four of its tests failed on the balance rather than on the cause, and
            // the saga itself reported Success.
            if (PetichPhase.entries.all { chainFor(it, currentPetich.payload, currentPetich.type).isEmpty() }) {
                val known = definitions.joinToString { it.type }
                return failTerminally(
                    currentPetich,
                    "no member of any phase applies to a saga of type `${currentPetich.type}`: " +
                        if (definitions.isEmpty()) {
                            "the engine has no definitions and no interceptor accepted its payload"
                        } else {
                            "the engine knows [$known] and none of them is it"
                        },
                )
            }

            val startingPhaseIndex = currentPetich.currentPhase.ordinal
            val remainingPhases = PetichPhase.entries.drop(startingPhaseIndex)
            val initialPhase = currentPetich.currentPhase

            for (phase in remainingPhases) {
                if (currentPetich.currentPhase != phase) {
                    currentPetich = currentPetich.copy(currentPhase = phase, currentInterceptorIndex = 0)
                }

                val phaseInterceptors = chainFor(phase, currentPetich.payload, currentPetich.type)

                val runPhase =
                    suspend {
                        var result: PetichResult? = null

                        val startingInterceptorIndex =
                            if (phase == initialPhase) {
                                currentPetich.currentInterceptorIndex
                            } else {
                                0
                            }

                        for ((index, interceptor) in phaseInterceptors.withIndex()) {
                            if (index < startingInterceptorIndex) continue

                            val interceptorResult =
                                try {
                                    withTimeout(config.timeoutMs(phase)) {
                                        interceptor.run(
                                            petich = currentPetich,
                                            payload = currentPetich.payload,
                                        )
                                    }
                                } catch (e: TimeoutCancellationException) {
                                    currentPetich = withRecordOf(interceptor, currentPetich)
                                    result =
                                        triggerCompensation(
                                            currentPetich,
                                            e.message ?: "Timeout",
                                            isSystemFailure = true,
                                            stepOutcomeUnknown = true,
                                        )
                                    break
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    currentPetich = withRecordOf(interceptor, currentPetich)
                                    result =
                                        triggerCompensation(
                                            currentPetich,
                                            e.message ?: "System Error",
                                            isSystemFailure = true,
                                            stepOutcomeUnknown = true,
                                        )
                                    break
                                }

                            interceptorResult?.enrichedPayload?.let {
                                currentEnrichedPayload = currentEnrichedPayload.merge(it)
                            }

                            // Folded in BEFORE the outcome is acted on, so that every write below
                            // carries it — including the rollback mark. A member that records and
                            // then fails needs its own record on the way back, and a process that
                            // dies between the two would otherwise compensate blind. The two catch
                            // blocks above do the same, because a member that THREW after recording
                            // is the case this channel was built for.
                            currentPetich = withRecordOf(interceptor, currentPetich)

                            // Beside the record fold and for the same reason: this is the one point
                            // where the member's decision and everything it asked to have committed
                            // are both in hand. A member that announced and then refused is counted
                            // here rather than disappearing (B-35). The two catch blocks above do
                            // not need it: a member that THREW never reported an outcome, so it
                            // never reached the fold that discards.
                            val discarded = interceptor.discardedAnnouncements()
                            if (discarded > 0) {
                                metrics.onAnnouncementDiscarded(
                                    currentPetich.type,
                                    interceptor.stepKey,
                                    discarded,
                                )
                            }

                            when (interceptorResult) {
                                null -> {
                                    continue
                                }

                                is MemberOutcome.Reject -> {
                                    // A refusal UNDOES WHAT RAN, and still ends REJECTED.
                                    //
                                    // It used to write REJECTED and stop, compensating nothing —
                                    // correct for a validation that refuses before anything has
                                    // happened, and silent theft after a step has touched the
                                    // outside world. Choosing correctly required the interceptor to
                                    // know whether an EARLIER step had an effect, which is
                                    // knowledge about other people's steps that it does not have:
                                    // it declares a phase and a supports(), and the chain it lands
                                    // in is assembled elsewhere. The engine does know the position,
                                    // so the engine decides.
                                    //
                                    // The rejecting step itself is not undone: unlike a step that
                                    // threw, it reported its outcome, and what it reports is that
                                    // it declined to act.
                                    result =
                                        triggerCompensation(
                                            currentPetich,
                                            interceptorResult.reason,
                                            terminalStatus = PetichStatus.REJECTED,
                                        )
                                    break
                                }

                                is MemberOutcome.Compensate -> {
                                    result =
                                        triggerCompensation(
                                            currentPetich,
                                            interceptorResult.reason,
                                        )
                                    break
                                }

                                is MemberOutcome.Suspend -> {
                                    metrics.onSuspend(currentPetich.type)
                                    val deadline = suspendDeadline(interceptorResult.ttl)
                                    val updated =
                                        forceUpdateStateWithRetry(
                                            currentPetich,
                                            PetichStatus.PENDING_SIGNATURE,
                                            currentEnrichedPayload,
                                            {
                                                it.copy(
                                                    currentInterceptorIndex = index + 1,
                                                    currentPhase = phase,
                                                    suspendedUntilEpochMs = deadline,
                                                )
                                            },
                                            sideEffects = interceptorResult.sideEffects,
                                            outboxEvents = interceptorResult.outboxEvents,
                                        )
                                    result =
                                        PetichResult.ActionRequired(
                                            interceptorResult.requiredAction,
                                            updated,
                                        )
                                    break
                                }

                                is MemberOutcome.Resuspend -> {
                                    metrics.onSuspend(currentPetich.type)
                                    // A bug found while integrating a wizard on top of the engine:
                                    // capturing `val updated` and using it below. Previously — and
                                    // unlike the Suspend branch above — the return value of
                                    // forceUpdateStateWithRetry was discarded here, and the OLD
                                    // currentPetich went into ActionRequired.petich. Persistence
                                    // was correct, but a caller reading result.petich.enrichedPayload
                                    // right after a Resuspend, as a wizard route needing the current
                                    // step does, saw a stale value.
                                    //
                                    // The deadline is counted afresh from EVERY resume: a client
                                    // who mistyped the code and enters it again is waiting on a new
                                    // step, not living out the remainder of the previous one.
                                    val deadline = suspendDeadline(interceptorResult.ttl)
                                    val updated =
                                        forceUpdateStateWithRetry(
                                            currentPetich,
                                            PetichStatus.PENDING_SIGNATURE,
                                            currentEnrichedPayload,
                                            {
                                                it.copy(
                                                    currentInterceptorIndex = index,
                                                    suspendedUntilEpochMs = deadline,
                                                )
                                            },
                                            sideEffects = interceptorResult.sideEffects,
                                        )
                                    result =
                                        PetichResult.ActionRequired(
                                            interceptorResult.requiredAction,
                                            updated,
                                        )
                                    break
                                }

                                is MemberOutcome.Proceed -> {
                                    val moved =
                                        currentPetich.copy(
                                            currentInterceptorIndex = index + 1,
                                            enrichedPayload = currentEnrichedPayload,
                                            version = currentPetich.version + 1,
                                            // The petich has moved on and no longer awaits the
                                            // client; otherwise the sweeper would pick it up on a
                                            // stale deadline and roll back a saga already in
                                            // motion.
                                            suspendedUntilEpochMs = null,
                                        )
                                    val updated = moved.copy(chainFingerprint = prefixFingerprint(moved))
                                    if (!updatePetich(
                                            updated,
                                            interceptorResult.outboxEvents,
                                            interceptorResult.sideEffects,
                                        )
                                    ) {
                                        throw OptimisticLockException()
                                    }
                                    currentPetich = updated
                                }
                            }
                        }
                        result
                    }

                val result = runPhase()
                if (result != null) return result
            }

            // We return exactly what was written. Previously the result carried a copy taken
            // BEFORE the version increment, so a caller reading result.petich saw something other
            // than what the database holds — the same class of defect already fixed in the
            // Resuspend branch.
            val finished =
                currentPetich.copy(
                    status = PetichStatus.COMPLETED,
                    enrichedPayload = currentEnrichedPayload,
                    version = currentPetich.version + 1,
                    // A finished petich is not waiting for the client. This is the only status
                    // write that bypasses forceUpdateStateWithRetry (which applies the same rule),
                    // so the deadline is cleared here too: otherwise a completed petich would carry
                    // a stale deadline into the database.
                    suspendedUntilEpochMs = null,
                )
            val completed = finished.copy(chainFingerprint = prefixFingerprint(finished))
            if (!repository.update(completed)) throw OptimisticLockException()
            return PetichResult.Success(completed)
        } catch (e: TimeoutCancellationException) {
            return failTerminally(currentPetich, "Timeout")
        } catch (e: CancellationException) {
            throw e
        } catch (e: OptimisticLockException) {
            throw e
        } catch (e: Exception) {
            return failTerminally(currentPetich, e.message ?: "Unknown error")
        }
    }
}

public class OptimisticLockException : RuntimeException("Version conflict")

public sealed interface PetichResult {
    public data class Success(
        val petich: Petich,
    ) : PetichResult

    public data class ActionRequired(
        val actionType: String,
        val petich: Petich,
    ) : PetichResult

    public data class Error(
        val reason: String,
    ) : PetichResult

    public data class SystemFailure(
        val details: String,
    ) : PetichResult
}

public interface CompensationFailureHandler {
    /**
     * [stepKey] rather than the member itself: under a definition a member is identified by the key
     * declared at its call site, and a handler wants to say WHICH step could not be undone rather
     * than hold the object that failed to do it. It is also the identity the saga's row carries.
     */
    public suspend fun handle(
        e: Exception,
        petich: Petich,
        stepKey: String,
    )

    /**
     * The rollback has given up for the last time and the saga is about to become
     * [PetichStatus.COMPENSATION_FAILED]: nothing will try again, and what was already undone stays
     * undone while the rest stays done.
     *
     * Whatever this returns is committed in the SAME transaction as that status, through the outbox
     * (see [OutboxAwarePetichRepository]) — which is the only way to announce this without a dual
     * write, since the announcement matters precisely when the process is unreliable. The event's
     * shape is the application's: petich does not know what a half-rolled-back saga means to it, and
     * a library that invented a payload here would be inventing a wire format for somebody else's
     * relay.
     *
     * Defaulted to nothing, so no existing implementation has to change.
     */
    public suspend fun exhausted(
        petich: Petich,
        stepKey: String,
        attempts: Int,
    ): List<OutboxEvent> = emptyList()
}

public class NoOpCompensationFailureHandler : CompensationFailureHandler {
    override suspend fun handle(
        e: Exception,
        petich: Petich,
        stepKey: String,
    ) {
    }
}
