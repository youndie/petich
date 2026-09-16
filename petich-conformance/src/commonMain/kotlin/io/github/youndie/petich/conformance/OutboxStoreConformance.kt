package io.github.youndie.petich.conformance

import io.github.youndie.petich.outbox.OutboxRepository

/**
 * The corpus needs rows to exist before it can ask what happens to them, and `OutboxRepository` has
 * no way to write one: events are written by the petich store, in the same transaction as the state
 * change. So the subject supplies that half.
 */
public interface OutboxStoreSubject : ConformanceSubject {
    public val repository: OutboxRepository

    /** Put a pending row into the store, as an applied petich update would have. */
    public suspend fun givenPending(
        id: String,
        type: String = "conformance.event",
        payload: String = """{"marker":"x"}""",
    )
}

/**
 * What `OutboxRepository` promises: which rows a relay is handed, and what the three marks do.
 *
 * NOT A RULE HERE: the order of `fetchPending`. The one implementation orders by a creation stamp
 * written from each replica's own clock, so skew reorders it and nothing promises otherwise
 * (youndie/petich#20). A corpus that demanded an order would be inventing a guarantee the engine
 * does not make — and the second store would then be "fixed" to satisfy it.
 */
public class OutboxStoreConformance {
    public val cases: List<Case<OutboxStoreSubject>> = corpus()

    public suspend fun run(subject: OutboxStoreSubject): List<Finding> = runCorpus(cases, subject)

    private fun case(
        rule: String,
        check: suspend (OutboxStoreSubject) -> String?,
    ) = Case(rule, check)

    private fun corpus(): List<Case<OutboxStoreSubject>> =
        listOf(
            case("a pending row is handed to the relay with its payload intact") { subject ->
                subject.givenPending(id = "evt", type = "order.placed", payload = """{"n":1}""")
                val pending = subject.repository.fetchPending()
                val row = pending.singleOrNull()
                expect(
                    row?.id == "evt" && row.type == "order.placed" &&
                        row.payload == """{"n":1}""" && row.retryCount == 0,
                ) { "fetchPending returned $pending" }
            },
            case("a delivered row is never handed out again") { subject ->
                subject.givenPending("evt")
                subject.repository.markDelivered("evt")
                val pending = subject.repository.fetchPending()
                expect(pending.isEmpty()) { "fetchPending still returns ${pending.map { it.id }} after markDelivered" }
            },
            case("a failed row counts the attempt and stays pending") { subject ->
                subject.givenPending("evt")
                subject.repository.markFailed("evt")
                val pending = subject.repository.fetchPending()
                val row = pending.singleOrNull()
                expect(row?.id == "evt" && row.retryCount == 1) {
                    "after markFailed the relay sees $pending"
                }
            },
            case("attempts accumulate rather than reset") { subject ->
                subject.givenPending("evt")
                subject.repository.markFailed("evt")
                subject.repository.markFailed("evt")
                subject.repository.markFailed("evt")
                val row = subject.repository.fetchPending().singleOrNull()
                expect(row?.retryCount == 3) { "three failures left retryCount = ${row?.retryCount}" }
            },
            case("a dead-lettered row leaves the queue for good") { subject ->
                subject.givenPending("evt")
                subject.repository.markDeadLettered("evt")
                val pending = subject.repository.fetchPending()
                expect(pending.isEmpty()) {
                    "fetchPending still returns ${pending.map { it.id }} after markDeadLettered"
                }
            },
            case("fetchPending returns no more than the limit asked for") { subject ->
                repeat(5) { subject.givenPending("evt-$it") }
                val pending = subject.repository.fetchPending(limit = 2)
                expect(pending.size == 2) { "fetchPending(limit = 2) returned ${pending.size} rows" }
            },
            case("marking a row that does not exist changes nothing and does not throw") { subject ->
                subject.givenPending("evt")
                subject.repository.markDelivered("absent")
                subject.repository.markFailed("absent")
                subject.repository.markDeadLettered("absent")
                val pending = subject.repository.fetchPending()
                expect(pending.singleOrNull()?.id == "evt" && pending.single().retryCount == 0) {
                    "marks against an absent id left $pending"
                }
            },
        )
}
