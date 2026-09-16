package io.github.youndie.petich.conformance

import kotlin.coroutines.cancellation.CancellationException

/**
 * A rule a storage implementation has to satisfy, as a case that can be run against it.
 *
 * CASES ARE NAMED BY THE RULE, not numbered. Whoever reads a failure needs to know what was
 * expected; "case 7" sends them looking for the case to find out.
 */
public class Case<in S : ConformanceSubject> internal constructor(
    public val rule: String,
    internal val check: suspend (S) -> String?,
)

/** A rule the implementation did not satisfy, and what was seen instead. */
public data class Finding(
    val rule: String,
    val detail: String,
)

/**
 * What every corpus needs of the thing it is run against: a way back to a known-empty state.
 *
 * `reset()` runs before each case rather than once per corpus. A case that leaves a row behind
 * would otherwise decide the next case's answer, and the corpus would report a defect belonging to
 * the case before it.
 */
public interface ConformanceSubject {
    public suspend fun reset()
}

/**
 * Run a corpus. An empty list means the implementation satisfies every rule in it.
 *
 * FINDINGS ARE COLLECTED, NOT THROWN. A run reaches the end and shows everything, because a new
 * store otherwise gets fixed one finding per run — and the second finding is often the one that
 * explains the first.
 *
 * A case that throws is a finding too, and the message says so: an implementation that fails with
 * an exception has not passed, and swallowing that would be the corpus lying on its behalf.
 * Cancellation is not a finding and is rethrown — a run that was never allowed to finish must not
 * be reported as a conformance failure.
 */
internal suspend fun <S : ConformanceSubject> runCorpus(
    cases: List<Case<S>>,
    subject: S,
): List<Finding> {
    val findings = mutableListOf<Finding>()
    for (case in cases) {
        subject.reset()
        val detail =
            try {
                case.check(subject)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                "the implementation threw: ${e::class.simpleName}: ${e.message}"
            }
        if (detail != null) findings += Finding(case.rule, detail)
    }
    return findings
}

/** Reads as the assertions do: `null` when the expectation holds, the message when it does not. */
internal fun expect(
    condition: Boolean,
    detail: () -> String,
): String? = if (condition) null else detail()
