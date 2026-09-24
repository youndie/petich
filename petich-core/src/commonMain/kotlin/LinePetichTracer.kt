package io.github.youndie.petich

/**
 * A [PetichTracer] that writes one line per event to whatever [write] is — an application's logger,
 * standard output, a test's list (B-60).
 *
 * It exists so that a trace is read before anything heavier is built on it: the research behind
 * the tracer (`docs/research/research-petich-tracer.md`, kill criterion 2) asks whether two weeks of
 * reading these lines answers anything a counting test double had not. A sink per consumer would be
 * a format per consumer; this is one, and it lives here so the next consumer does not write a third.
 *
 * **The line is stable and greppable**, `key=value` after a fixed prefix, one saga per line:
 *
 *     petich.trace ts=1727200000000 replica=api-1 saga=order-1 type=order event=MemberEntered phase=EXECUTION index=0 key=hold
 *
 * `ts` and `replica` come from here, not from the event — the engine has neither to give, and this
 * instance is called at the moment of the event in one process (see [PetichTracer]). A value with a
 * space or a quote in it — only a reason can have one — is quoted.
 *
 * [write] is called synchronously, so it inherits the tracer's contract: return at once. A logger
 * that buffers is fine; one that blocks on a disk makes every saga wait for it.
 */
public class LinePetichTracer(
    private val replica: String,
    private val clock: PetichClock,
    private val write: (String) -> Unit,
) : PetichTracer {
    override fun onEvent(event: PetichTraceEvent) {
        write(line(event))
    }

    /** The line [onEvent] writes, without writing it. */
    public fun line(event: PetichTraceEvent): String {
        val fields =
            listOf(
                "ts" to clock.nowEpochMs().toString(),
                "replica" to replica,
                "saga" to event.sagaId,
                "type" to event.type,
                "event" to (event::class.simpleName ?: "unknown"),
            ) + details(event)
        return "petich.trace " + fields.joinToString(" ") { (name, value) -> "$name=${quoted(value)}" }
    }

    private fun details(event: PetichTraceEvent): List<Pair<String, String>> =
        when (event) {
            is PetichTraceEvent.PassStarted -> {
                listOf(
                    "attempt" to "${event.attempt}",
                    "status" to "${event.status}",
                    "phase" to "${event.phase}",
                    "index" to "${event.index}",
                )
            }

            is PetichTraceEvent.PassRetried -> {
                listOf("attempt" to "${event.attempt}")
            }

            is PetichTraceEvent.MemberEntered -> {
                member(event.phase, event.index, event.key)
            }

            is PetichTraceEvent.MemberProceeded -> {
                member(event.phase, event.index, event.key)
            }

            is PetichTraceEvent.MemberRejected -> {
                member(event.phase, event.index, event.key) + ("reason" to event.reason)
            }

            is PetichTraceEvent.MemberFailed -> {
                member(event.phase, event.index, event.key) +
                    listOf("thrown" to "${event.thrown}", "reason" to event.reason)
            }

            is PetichTraceEvent.MemberTimedOut -> {
                member(event.phase, event.index, event.key) + ("reason" to event.reason)
            }

            is PetichTraceEvent.MemberSuspended -> {
                member(event.phase, event.index, event.key) + ("action" to event.requiredAction)
            }

            is PetichTraceEvent.MemberResuspended -> {
                member(event.phase, event.index, event.key) + ("action" to event.requiredAction)
            }

            is PetichTraceEvent.AnnouncementFailed -> {
                listOf("key" to event.key, "reason" to event.reason)
            }

            is PetichTraceEvent.ClaimWon -> {
                listOf("queue" to "${event.queue}")
            }

            is PetichTraceEvent.ClaimLost -> {
                listOf("queue" to "${event.queue}")
            }

            is PetichTraceEvent.RollbackStarted -> {
                listOf(
                    "phase" to "${event.phase}",
                    "from" to "${event.fromIndex}",
                    "towards" to "${event.towards}",
                    "reason" to event.reason,
                )
            }

            is PetichTraceEvent.StepUndone -> {
                member(event.phase, event.index, event.key)
            }

            is PetichTraceEvent.RollbackGaveUp -> {
                listOf(
                    "key" to (event.key ?: "-"),
                    "attempt" to "${event.attempt}",
                    "exhausted" to "${event.exhausted}",
                )
            }

            is PetichTraceEvent.ChainRefused -> {
                listOf("phase" to "${event.phase}", "index" to "${event.index}")
            }

            is PetichTraceEvent.Finished -> {
                listOf("status" to "${event.status}")
            }
        }

    private fun member(
        phase: PetichPhase,
        index: Int,
        key: String,
    ): List<Pair<String, String>> = listOf("phase" to "$phase", "index" to "$index", "key" to key)

    private fun quoted(value: String): String =
        if (value.isNotEmpty() && value.none { it == ' ' || it == '"' || it == '=' || it.isWhitespace() }) {
            value
        } else {
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
        }
}
