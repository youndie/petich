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

/** What every member may do, whichever kind it is. */
public interface PetichMemberContext {
    /** The saga as it stands, for a member that needs its id or its enriched payload. */
    public val petich: Petich

    /** Merge into the payload the saga carries forward. */
    public fun enrich(payload: EnrichedPayload)

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
     * Refuse the saga on business grounds. Whatever ran is rolled back, and the saga ends
     * `REJECTED` rather than `FAILED`.
     *
     * **Rolling back and naming the outcome are separate questions** (B-20). A client repeating a
     * request that was refused has to be told it was refused; that it also got its money back is not
     * the same fact.
     */
    public fun reject(reason: String)
}

/** What a check may do: decide, wait, or refuse. It cannot fail the saga, having nothing to undo. */
public interface PetichCheckContext : PetichMemberContext

/** What a step may do, and the one thing a check may not: report a fault. */
public interface PetichStepContext : PetichMemberContext {
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
     * `AUTHORIZATION` takes **either kind**, and that is a decision rather than an oversight (D3).
     * konekt's authorisation holds money and then waits for a confirmation, as one step, on a
     * recorded decision of its own; a verb that implied the type would force it to be cut in two to
     * satisfy the model.
     */
    public fun authorize(
        key: String,
        check: PetichCheck<P>,
    ): Unit = add(key, PetichPhase.AUTHORIZATION, check = check)

    public fun authorize(
        key: String,
        step: PetichStep<P>,
    ): Unit = add(key, PetichPhase.AUTHORIZATION, step = step)

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
