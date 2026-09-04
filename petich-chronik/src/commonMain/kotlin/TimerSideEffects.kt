package ru.workinprogress.petich.chronik

import ru.workinprogress.chronik.EpochSeconds
import ru.workinprogress.petich.PetichSideEffect

/**
 * "Schedule this timer, in the transaction that writes my state change."
 *
 * Returned from a saga step's [ru.workinprogress.petich.InterceptorResult], carried by the engine
 * as an opaque value, and written by a repository that understands it. The step itself never calls
 * chronik — a step that did would be opening a second transaction, and if the process died between
 * the two the state would be committed with no timer behind it: the saga correct, the write
 * successful, and only the thing nobody is waiting for right now never happening.
 */
public data class ScheduleTimer(
    val id: String,
    val at: EpochSeconds,
    /**
     * What the timer carries. For a saga waiting on a deadline this is the saga's id and nothing
     * else — see [SagaTimerSink], which reads it back.
     */
    val payload: String,
) : PetichSideEffect

/**
 * "Cancel this timer, in the transaction that writes my state change."
 *
 * Compensation for [ScheduleTimer], and it belongs in the same place for the same reason: a
 * rollback that committed while the timer survived would leave a saga that has been undone and a
 * wake-up still coming for it.
 */
public data class CancelTimer(
    val id: String,
) : PetichSideEffect
