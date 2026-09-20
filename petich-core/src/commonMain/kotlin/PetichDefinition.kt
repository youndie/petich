package io.github.youndie.petich

import kotlin.time.Duration

/**
 * What one saga is: its type, its payload, and its members in the order they run.
 *
 * The order is the list's order. Nothing sorts it at runtime and nothing filters it by payload, so
 * the flow of a saga is readable in the place it is written — which is the whole of B-28. In the
 * model this replaces, a saga's flow existed nowhere: to read it, a person collected every
 * interceptor whose `supports()` accepted the payload and sorted them by priority in their head.
 */
public class PetichDefinition<P : PetichPayload> internal constructor(
    public val type: String,
    public val members: List<PetichMember<P>>,
) {
    /**
     * The definition read back, phase by phase, as text — for a log line at startup or a snapshot in
     * a test, so a chain that moved shows up in a diff rather than in a saga.
     *
     * It renders from the list rather than by re-deriving anything, which is the point: there is no
     * second answer for it to disagree with.
     */
    public fun describeChain(): String =
        PetichPhase.entries.joinToString("\n") { phase ->
            val here = members.filter { it.phase == phase }
            val body =
                if (here.isEmpty()) {
                    "-"
                } else {
                    here.joinToString(" -> ") { "${it.key}${if (it.undoes) "" else " (check)"}" }
                }
            "$phase: $body"
        }
}

/** One member of a definition: its key, where it sits, and whether it has anything to undo. */
public class PetichMember<P : PetichPayload> internal constructor(
    public val key: String,
    public val phase: PetichPhase,
    public val step: PetichStep<P>?,
    public val check: PetichCheck<P>?,
) {
    /** A step undoes; a check has nothing to undo, which is why it has no `compensate` to write. */
    public val undoes: Boolean get() = step != null
}

/**
 * A member that acts and can undo what it did.
 *
 * It declares no phase, no priority and no `supports`: the definition places it, the list orders it,
 * and the payload type is named once on the definition. The unchecked `payload as T` the engine used
 * to need — and the `ClassCastException` it had to rename to say which interceptor lied — has no
 * reason to exist here.
 */
public interface PetichStep<P : PetichPayload> {
    public suspend fun execute(
        ctx: PetichStepContext,
        payload: P,
    )

    /**
     * Undo what [execute] did.
     *
     * **It may be called for a step that did not happen.** When [execute] throws or times out the
     * engine cannot tell an effect that reached the far side from a call that never landed, so it
     * rolls that step back as well (B-18). Until a step can record what it did (B-29), the guard is
     * whatever evidence the step itself left behind.
     */
    public suspend fun compensate(
        ctx: PetichStepContext,
        payload: P,
    )
}

/**
 * A member that decides and has nothing to undo.
 *
 * **No `compensate`, and that is the type's whole job.** Of the 27 compensations written against the
 * interceptor model in the two services built on it, 12 are empty — written to satisfy a type rather
 * than to reverse anything. Those are validations and enrichments, and this is what they are.
 *
 * A check cannot be placed after a step: [PetichDefinitionBuilder] refuses it. That ordering rule,
 * not the type, is what keeps a refusal that cannot roll back from sitting after an effect.
 */
public interface PetichCheck<P : PetichPayload> {
    public suspend fun check(
        ctx: PetichCheckContext,
        payload: P,
    )
}

/**
 * A member that applies to **every** saga, declared once instead of in each definition.
 *
 * Limits, audit and anti-fraud are the cases, and they are the one thing the interceptor model was
 * genuinely good at: a list filtered by `supports` attaches them without editing anything. A model
 * where each saga spells its own order loses that unless something replaces it — and the obvious
 * replacement reintroduces the defect this stage removes, because a member mixed in at a phase
 * boundary is one that is **not written where the saga is read**.
 *
 * So globals go through the same seam as everything else: [PetichEngine.describeChain] renders them
 * inline, in the position they run, and the chain fingerprint covers them. A deploy that adds a
 * global is caught by the mechanism that catches a deploy which moves a step (B-21), which matters
 * most for the case that is easy to miss — a global inserted before the current position re-points
 * every saga in flight.
 *
 * **A global is a [PetichCheck] and cannot be a [PetichStep]** (D9). A member that acts has to be
 * undone, and a global's undo would run inside every saga's rollback at a position no saga's author
 * wrote. The author could not reason about their own rollback, which is the defect this stage
 * exists to remove, arriving from the other side. A check has nothing petich must undo, so a global
 * lengthens the forward pass and no rollback.
 *
 * That does **not** make a global pure: it may act through its own ports. What it may not do is act
 * in a way that leaves petich owing somebody an undo — or announce, since [emit] belongs to a step
 * for the same reason (B-35).
 *
 * Globals of a phase run **before** that phase's declared members, in the order this list gives.
 * A limit that runs after the saga already acted in that phase is a limit that arrived too late.
 */
public class PetichGlobal(
    /**
     * Its identity in the chain and in the fingerprint, exactly as a member's key is.
     *
     * It shares one namespace with every definition's member keys, and the engine refuses a
     * collision at construction: two members under one key in a saga's chain would write one
     * record over the other and make the fingerprint ambiguous.
     */
    public val key: String,
    public val phase: PetichPhase,
    public val check: PetichCheck<PetichPayload>,
)

/** What every member may do, whichever kind it is. */
public interface PetichMemberContext {
    /** The saga as it stands, for a member that needs its id or its enriched payload. */
    public val petich: Petich

    /**
     * This member's key — the same string the definition declares it under (B-36).
     *
     * **It is the member's address, and it is the only one.** A step carries no `phase` and no
     * `priority` any more; both moved into the definition, which is the point of this model. What
     * moved with them was the member's ability to say where it is, and observability had been
     * hanging off exactly that: shashki names a tracing span after the step's phase and asserts the
     * string in a test, because a version of it once shipped an unexpanded template to a collector.
     *
     * **The same value in a compensation as on the forward pass**, which is what makes it usable for
     * naming: the engine builds this context from the member it is running, going either way.
     * `petich.currentPhase` is a different fact — the saga's position, which during a rollback is
     * where the rollback has reached rather than where this member ran.
     *
     * The engine has had this all along: it constructs the context with the key, and uses it to find
     * this member's [recordedValue] and to build the chain fingerprint. Nothing here is computed;
     * it was private.
     */
    public val stepKey: String

    /** Merge into the payload the saga carries forward. */
    public fun enrich(payload: EnrichedPayload)

    /**
     * Write down what this member did, to be committed with the position it advances to and read
     * back by this member's own compensation.
     *
     * Scoped to the member on purpose: a record is evidence about one action. What the saga carries
     * FORWARD is [enrich], a different channel with a different lifetime and a different reader —
     * and conflating the two is what left a compensation unable to tell "it did not happen" from
     * "it happened and had nothing to say".
     */
    public fun record(value: PetichStepRecord)

    /**
     * What this member recorded when it ran, or `null` if it recorded nothing — which, inside a
     * compensation, is what "this step did not happen" looks like.
     *
     * Prefer the reified [recorded] below at a call site; this is what it reads.
     */
    public fun recordedValue(): PetichStepRecord?

    /**
     * Stop and wait for a separate `resume` call, for [ttl] or for the engine's blanket deadline.
     *
     * **It records the intent and returns**; it does not throw. A control-flow exception here would
     * be swallowed by the first member that wraps its own work in `catch (Exception)` — and by four
     * `catch (e: Exception)` sites in the engine itself. `return ctx.suspendFor(...)` reads as the
     * end of the member because it is one.
     *
     * A member may act and then suspend: konekt holds a subscriber's money and waits for their
     * confirmation in one step, deliberately, so that the money is held exactly once.
     */
    public fun suspendFor(
        action: String,
        ttl: Duration? = null,
    )

    /**
     * Wait for another answer **at this member**, rather than for the one that moves past it.
     *
     * [suspendFor] stores the position one past this member, so a resume runs whatever comes next
     * and the money it moved is moved exactly once. This stores the position unchanged, so the next
     * resume re-enters *here*.
     *
     * **The case is a cascade, and shashki is where it was settled** (B-37). Its order saga offers a
     * ride to the nearest driver and waits; a decline releases that driver, offers the ride to the
     * next one, and waits again — and the next answer has to land in the same member, because the
     * member is the cascade. A wizard re-asking a question is the same shape.
     *
     * Two verbs rather than a flag on one, because what differs is not a detail of the waiting: it
     * is whether this member runs again, which is the difference between a hold taken once and a
     * hold taken per answer.
     */
    public fun resuspendFor(
        action: String,
        ttl: Duration? = null,
    )

    /**
     * Refuse the saga on business grounds. Whatever ran is rolled back, and the saga ends
     * `REJECTED` rather than `FAILED`.
     *
     * **Rolling back and naming the outcome are separate questions** (B-20). A client repeating a
     * request that was refused has to be told it was refused; that it also got its money back is not
     * the same fact.
     */
    public fun reject(reason: String)
}

/**
 * What a check may do: decide, wait, or refuse. It cannot fail the saga, having nothing to undo —
 * and it cannot announce, for the same reason.
 *
 * **A check has no compensation to carry its word** (B-35). Announcing belongs to a member that
 * acted: what rides on a write is what that member did, and a check's whole contribution is whether
 * the saga continues. A check that refuses leaves nothing behind that could carry an announcement,
 * so the model does not let it produce one — rather than accepting it and dropping it, which is what
 * it used to do.
 */
public interface PetichCheckContext : PetichMemberContext

/** What a step may do, and the things a check may not: announce, attach, and report a fault. */
public interface PetichStepContext : PetichMemberContext {
    /**
     * Announce something, committed in the same write as the state change this member produces —
     * the outbox, and the reason "the work happened but the notification never went out" is
     * structurally impossible here.
     *
     * **On `Proceed` and on `suspendFor`, and not on a refusal** (B-35). Both of those commit
     * forward progress and the announcement rides with it. `reject` and `fail` begin a rollback, and
     * petich will not announce work it is in the middle of undoing — what it does instead is count
     * the announcement through `PetichEngineMetrics.onAnnouncementDiscarded`, so a member that
     * announces and then refuses is a line on a graph rather than an event nobody ever sees.
     *
     * Available from a compensation too, and that is where a rollback's word belongs: "the
     * reservation was released" is announced by the member that did the releasing, in the write that
     * records it.
     */
    public fun emit(event: OutboxEvent)

    /**
     * Work that must be committed with this member's state change and which the engine deliberately
     * cannot interpret — a durable timer, most concretely. See [PetichSideEffect].
     *
     * Carried on the same outcomes as [emit].
     */
    public fun attach(effect: PetichSideEffect)

    /**
     * Something went wrong that is not a business decision. Whatever ran is rolled back and the saga
     * ends `FAILED`.
     *
     * Unavailable to a check by construction: a fault in something that has nothing to undo is an
     * exception, and the engine already turns one of those into a rollback.
     */
    public fun fail(reason: String)
}

/** The builder behind [petich]. Its refusals are the model's rules, stated where they are broken. */
public class PetichDefinitionBuilder<P : PetichPayload> internal constructor(
    private val type: String,
) {
    private val members = mutableListOf<PetichMember<P>>()
    private var sawStep = false

    public fun enrich(
        key: String,
        check: PetichCheck<P>,
    ): Unit = add(key, PetichPhase.ENRICHMENT, check = check)

    public fun validate(
        key: String,
        check: PetichCheck<P>,
    ): Unit = add(key, PetichPhase.VALIDATION, check = check)

    /**
     * `AUTHORIZATION` takes a check and nothing else — which it did not, until B-39.
     *
     * It used to take either kind, on D3's argument that konekt's authorisation holds money and then
     * waits for a confirmation as one member, and that a verb implying the type would force it in
     * two. The argument was about the wrong axis. **In payments, "authorization" IS the hold; in
     * these phases it means "is this allowed, and has the human agreed"** — one word, two senses,
     * and the overload let the second phase hold members of the first.
     *
     * What that cost is the only thing a phase is for. If a member here may act, then "before
     * effects" is a sentence the author remembers rather than a property the type carries, and every
     * reader of a definition has to check each member to learn what the boundary means. That is the
     * shape of defect this model removed from an empty `compensate`, arriving at the phases instead.
     *
     * It was never about losing money: a refusal rolls back what ran (B-20), so the hold came back
     * either way. It was about a reader being unable to use the phase at all.
     *
     * **A member that acts goes in [step], above the members that depend on it** — which also gives
     * the rollback the order it should have had: what was reserved is released before what was held.
     * Both consumers' holds are there now, and both still suspend from it: waiting was never the
     * property this phase guarded.
     */
    public fun authorize(
        key: String,
        check: PetichCheck<P>,
    ): Unit = add(key, PetichPhase.AUTHORIZATION, check = check)

    public fun step(
        key: String,
        step: PetichStep<P>,
    ): Unit = add(key, PetichPhase.EXECUTION, step = step)

    public fun announce(
        key: String,
        step: PetichStep<P>,
    ): Unit = add(key, PetichPhase.POST_PROCESSING, step = step)

    private fun add(
        key: String,
        phase: PetichPhase,
        step: PetichStep<P>? = null,
        check: PetichCheck<P>? = null,
    ) {
        require(key.isNotBlank()) { "a member of $type was declared with a blank key" }
        require(members.none { it.key == key }) {
            "$type declares `$key` twice: the key is the member's identity in the saga's row, so two " +
                "members cannot share one"
        }
        // THE ORDERING RULE, and it is what makes the type split worth having. A check has no
        // compensate, so a refusal from one after an effect would keep what that effect did — which
        // is the defect B-20 had to fix in the engine because the author could not be asked to know
        // whether an EARLIER member had acted. Here the builder knows, and says so at the line that
        // is wrong.
        if (step != null) sawStep = true
        require(!(check != null && sawStep)) {
            "$type places the check `$key` after a step that has already acted. A check has nothing " +
                "to undo, so refusing there would keep what ran: make it a PetichStep, or move it " +
                "above the first step"
        }
        members += PetichMember(key, phase, step, check)
    }

    internal fun build(): PetichDefinition<P> {
        require(members.isNotEmpty()) { "$type declares no members" }
        return PetichDefinition(type, members.toList())
    }
}

/**
 * Declare what a saga of [type] is.
 *
 * ```kotlin
 * val orderPetich = petich<OrderPayload>("order") {
 *     validate("limits", CheckLimits(limits))
 *     authorize("confirm", RequireConfirmation(ttl = 5.minutes))
 *     step("reserve-stock", ReserveStock(stock))
 *     step("charge", ChargeCard(psp))
 * }
 * ```
 *
 * The keys are stored identities rather than labels: a saga's row records the key of the member it
 * stopped at, so renaming one is a migration and the fingerprint refuses a saga whose recorded
 * prefix no longer matches (B-21).
 */
public fun <P : PetichPayload> petich(
    type: String,
    declare: PetichDefinitionBuilder<P>.() -> Unit,
): PetichDefinition<P> {
    require(type.isNotBlank()) { "a definition was declared with a blank type" }
    return PetichDefinitionBuilder<P>(type).apply(declare).build()
}

/**
 * What this member recorded, if it is of the type asked for.
 *
 * ```kotlin
 * override suspend fun compensate(ctx: PetichStepContext, payload: OrderPayload) {
 *     val done = ctx.recorded<Reservation>() ?: return   // the step never ran
 *     stock.release(done.id)
 * }
 * ```
 *
 * A safe cast rather than an unchecked one: a record read back from storage was written by an
 * earlier version of the step, and a type that has since changed should read as absent rather than
 * bring the rollback down.
 */
public inline fun <reified T : PetichStepRecord> PetichMemberContext.recorded(): T? = recordedValue() as? T
