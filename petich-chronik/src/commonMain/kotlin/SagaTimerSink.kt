package ru.workinprogress.petich.chronik

import ru.workinprogress.chronik.FiredTimer
import ru.workinprogress.chronik.TimerSink
import ru.workinprogress.petich.Petich
import ru.workinprogress.petich.PetichEngine
import ru.workinprogress.petich.PetichRepository
import ru.workinprogress.petich.PetichResult
import ru.workinprogress.petich.ResumePayload

/**
 * What the saga is told when a timer, rather than a person, wakes it.
 *
 * A resume payload rather than nothing, because the engine distinguishes a resume from a first
 * entry by exactly this — and because a step that can be woken both ways needs to know which
 * happened. "The client confirmed" and "nobody came and the deadline passed" lead to opposite
 * branches, and a step that cannot tell them apart has to guess.
 *
 * [lateness] travels with it: a step reacting to a deadline sometimes cares how far past it is —
 * a grace period, say — and this is the only place that number is known.
 */
data class TimerFired(
    val timerId: String,
    val lateness: Long,
) : ResumePayload()

/**
 * A fired timer wakes a suspended saga.
 *
 * This is the "later request" the engine already knows how to be resumed by — the mechanism is not
 * changed and not extended. What changes is who makes the request: until now a person had to come
 * back, and now a timer can.
 *
 * WHAT THIS DELIBERATELY DOES NOT DO, and it is the larger half of the original idea: it does not
 * schedule anything. Scheduling a timer inside a saga step so that the timer and the step's state
 * change commit together is not expressible today — `InterceptorResult.Suspend` carries nothing
 * into the write the engine performs afterwards, unlike `Proceed`, which carries `outboxEvents`. A
 * step that scheduled a timer by calling chronik directly would be doing exactly the dual write
 * chronik exists to make impossible, so this module does not offer it rather than offering it
 * broken. See the module's README.
 */
class SagaTimerSink(
    private val repository: PetichRepository,
    /**
     * Which engine owns a given saga.
     *
     * Not one engine for everything: an application usually keeps several, sharing one saga store
     * but each with its own interceptor list. Resuming a saga of one type with another type's
     * engine would run the wrong steps. The same shape the expiry sweeper already uses.
     */
    private val engineFor: (Petich) -> PetichEngine?,
    /** The timer fired and the saga it names is gone, or was never there. */
    private val onMissing: (timerId: String, sagaId: String) -> Unit = { _, _ -> },
    /** The saga's type has no engine registered. Silence here means sagas pile up suspended. */
    private val onUnowned: (Petich) -> Unit = {},
    private val onResumed: (sagaId: String, result: PetichResult) -> Unit = { _, _ -> },
) : TimerSink {
    /**
     * The payload is the saga id and nothing else.
     *
     * chronik keeps the payload opaque, so the convention has to live on this side. Keeping it to
     * a bare id rather than a JSON envelope means there is no schema to version and nothing to
     * parse: whoever schedules the timer writes the saga's id, and the saga carries everything else
     * already.
     */
    override suspend fun deliver(fired: FiredTimer) {
        val sagaId = fired.payload

        val petich = repository.findById(sagaId)
        if (petich == null) {
            onMissing(fired.id, sagaId)
            // Returning rather than throwing: a saga that no longer exists is not a delivery
            // failure, and retrying would burn attempts until the timer dead letters over
            // something no retry can fix.
            return
        }

        val engine = engineFor(petich)
        if (engine == null) {
            onUnowned(petich)
            return
        }

        // Throwing here IS the right behaviour: the engine failing to resume a saga is a delivery
        // that did not happen, and chronik will hold the timer for its backoff and try again.
        //
        // The resume payload is not decoration. The engine tells a resume from a first entry by
        // this field alone (see doProcess, which overlays it onto the stored saga), so a sink that
        // passed the saga through unchanged would re-enter the waiting step and be suspended again
        // — forever, once per timer. Found by the test below, not by reading.
        val resumed = petich.copy(resumePayload = TimerFired(fired.id, fired.lateness))
        onResumed(sagaId, engine.process(resumed))
    }
}
