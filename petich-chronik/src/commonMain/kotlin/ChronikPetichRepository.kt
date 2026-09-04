package ru.workinprogress.petich.chronik

import ru.workinprogress.chronik.Timer
import ru.workinprogress.chronik.TimerTransaction
import ru.workinprogress.chronik.TransactionalTimerStore
import ru.workinprogress.petich.OutboxAwarePetichRepository
import ru.workinprogress.petich.OutboxEvent
import ru.workinprogress.petich.Petich
import ru.workinprogress.petich.PetichRepository
import ru.workinprogress.petich.PetichSideEffect
import ru.workinprogress.petich.SideEffectAwarePetichRepository

/**
 * A saga repository that also writes chronik timers — in the caller's transaction, alongside the
 * saga's own row.
 *
 * A DECORATOR RATHER THAN A FORK. Both halves belong to somebody: the saga's storage to the engine,
 * the timer's to chronik. What this class owns is the one thing neither can know on its own — that
 * these two writes are one write.
 *
 * [inTransaction] is the join. It is supplied rather than assumed because chronik never opens a
 * transaction and this class must not either: whoever wires it up knows how their storage begins
 * one, and hands over a way to run both writes inside it.
 */
public class ChronikPetichRepository(
    private val delegate: OutboxAwarePetichRepository,
    private val timers: TransactionalTimerStore,
    /**
     * Run [body] inside one transaction, handing it the handle chronik writes through.
     *
     * The delegate's own write happens inside [body] too, so an implementation must make its
     * transaction the one the delegate joins.
     *
     * FOR EXPOSED, THAT MEANS `suspendTransaction` ON BOTH SIDES, and the trap is worth stating
     * because it costs nothing to fall into: the blocking `transaction {}` keeps its transaction in
     * a thread local, the suspending one in the coroutine context, and they do not see each other.
     * A `suspendTransaction` nested inside a blocking `transaction` finds nothing to join, opens
     * its own and COMMITS IT — so the timer survives a rollback of the state change it belonged to,
     * which is the exact failure this class exists to prevent, arrived at through the wiring rather
     * than the design. Observed, not reasoned about: the test in this module failed that way first.
     */
    private val inTransaction: suspend (suspend (TimerTransaction) -> Boolean) -> Boolean,
) : SideEffectAwarePetichRepository,
    PetichRepository by delegate {
    override suspend fun update(
        petich: Petich,
        outboxEvents: List<OutboxEvent>,
        sideEffects: List<PetichSideEffect>,
    ): Boolean =
        inTransaction { tx ->
            // The state first: if it does not go through — a version conflict, most often — the
            // timers must not either, and nothing else in this block runs.
            if (!delegate.update(petich, outboxEvents)) {
                return@inTransaction false
            }

            for (effect in sideEffects) {
                when (effect) {
                    is ScheduleTimer -> {
                        timers.insert(
                            tx,
                            Timer(id = effect.id, dueAt = effect.at, payload = effect.payload),
                        )
                    }

                    is CancelTimer -> {
                        timers.cancel(tx, effect.id)
                    }

                    // Somebody else's side effect, in a repository that does not know it. Ignoring
                    // it silently would be the drop the engine's counter exists to make visible —
                    // except here it would be invisible even to that, because this repository DID
                    // claim it could store side effects.
                    else -> {
                        throw IllegalArgumentException(
                            "ChronikPetichRepository was handed ${effect::class.simpleName}, which it " +
                                "cannot store. Wrapping several side-effect writers is not supported: " +
                                "chain them, or the one that does not recognise a value will drop it.",
                        )
                    }
                }
            }
            true
        }
}
