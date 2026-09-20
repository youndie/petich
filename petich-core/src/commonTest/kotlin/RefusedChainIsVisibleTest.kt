package io.github.youndie.petich

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * B-44: a saga refused for a changed chain is left exactly as it was, so nothing about the row says
 * it is stuck — and until this counter existed, nothing anywhere did.
 *
 * Refusing is right and is B-21's whole point. What it costs is that the saga keeps whatever it held
 * for as long as the deploy stands, while its row still reads what a healthy saga reads. This is the
 * second condition in the engine with no automatic way out; the other one, an exhausted rollback, at
 * least has a status of its own.
 */
class RefusedChainIsVisibleTest {
    private data class OrderPayload(
        val sku: String,
    ) : PetichPayload()

    private class Step(
        private val name: String,
        private val suspendHere: Boolean = false,
    ) : PetichStep<OrderPayload> {
        override suspend fun execute(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            if (suspendHere) ctx.suspendFor("CONFIRM")
        }

        override suspend fun compensate(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) = Unit
    }

    private class RowRepository : PetichRepository {
        var row: Petich? = null

        override suspend fun findById(id: String): Petich? = row?.takeIf { it.id == id }

        override suspend fun saveOrGet(petich: Petich): Petich {
            val existing = row
            if (existing != null && existing.id == petich.id) return existing
            row = petich
            return petich
        }

        override suspend fun update(petich: Petich): Boolean {
            val existing = row ?: return false
            if (petich.version != existing.version + 1) return false
            row = petich
            return true
        }
    }

    private class CountingMetrics : PetichEngineMetrics {
        val refusals: MutableList<String> = mutableListOf()

        override fun onChainRefused(
            type: String,
            phase: PetichPhase,
        ) {
            refusals.add("$type/$phase")
        }
    }

    @Test
    fun `a saga refused for a changed chain is counted and its row is not touched`() =
        runBlocking {
            val repository = RowRepository()
            val metrics = CountingMetrics()
            suspendedSaga(repository)
            val asStored = checkNotNull(repository.row)

            afterDeploy(repository, metrics).process(asStored)

            assertEquals(listOf("order/EXECUTION"), metrics.refusals)
            // The whole reason the counter is needed: from the row, this saga is indistinguishable
            // from one that is simply waiting. Nothing may be written here — see the case below.
            assertEquals(asStored, repository.row)
        }

    @Test
    fun `it is counted again on every pass while the condition holds`() =
        runBlocking {
            val repository = RowRepository()
            val metrics = CountingMetrics()
            suspendedSaga(repository)
            val engine = afterDeploy(repository, metrics)

            engine.process(checkNotNull(repository.row))
            engine.process(checkNotNull(repository.row))
            engine.process(checkNotNull(repository.row))

            // NOT an oversight, and the reason it is a test rather than a comment. The mismatch is
            // not an event that happened once: it holds while the two versions disagree, and a
            // signal that went quiet after the first sweep would read as "resolved" to anybody
            // watching. The rate is the measurement — non-zero means sagas are refused right now.
            assertEquals(3, metrics.refusals.size)
        }

    @Test
    fun `rolling the deploy back lets the saga finish because nothing was written`() =
        runBlocking {
            val repository = RowRepository()
            val metrics = CountingMetrics()
            suspendedSaga(repository)
            afterDeploy(repository, metrics).process(checkNotNull(repository.row))

            // The remedy from the runbook, as a test: the old chain comes back and the saga carries
            // on. A terminal status would have been unremovable by then — this is the case that
            // refuses one.
            val rolledBack = engine(repository, metrics, "reserve", "confirm", "ship")
            val result = rolledBack.process(checkNotNull(repository.row).copy(resumePayload = null))

            assertTrue(result is PetichResult.Success, "the saga should have finished: $result")
            assertEquals(PetichStatus.COMPLETED, repository.row?.status)
        }

    @Test
    fun `an ordinary release appends a member and is not counted`() =
        runBlocking {
            val repository = RowRepository()
            val metrics = CountingMetrics()
            suspendedSaga(repository)

            val appended = engine(repository, metrics, "reserve", "confirm", "ship", "notify")
            appended.process(checkNotNull(repository.row))

            assertEquals(emptyList(), metrics.refusals, "a normal deploy must not raise this")
        }

    @Test
    fun `an expiry over a changed chain is refused and rolls nothing back`() =
        runBlocking {
            // THE THIRD PATH, and the one that had no test at all (B-56). `process` is covered
            // above; `sweepStuck` is covered in SweeperReportsWhatHappenedTest; the expiry queue
            // reaches `chainMismatch` through `expireSuspended` and nothing asserted on it — on the
            // path whose own comment says the refusal matters MOST, because an expiry rolls back
            // without anyone watching and a rollback over a changed chain compensates steps that
            // never ran.
            var now = 0L
            val clock = PetichClock { now }
            val repository = RowRepository()
            val metrics = CountingMetrics()

            val parked =
                timed(
                    repository,
                    PetichEngineMetrics.NoOp,
                    clock,
                    "reserve",
                    "confirm",
                    "ship",
                ).process(order())
            check(parked is PetichResult.ActionRequired) { "expected a suspension: $parked" }
            val asStored = checkNotNull(repository.row)

            // The deadline passes, and the deploy that moves the saga's index has happened.
            now += 10.minutes.inWholeMilliseconds
            val afterDeploy = timed(repository, metrics, clock, "audit", "reserve", "confirm", "ship")

            val first = afterDeploy.expireSuspended("p-1")

            assertTrue(first is ExpireResult.ChainChanged, "expected a refusal: $first")
            assertEquals(listOf("order/EXECUTION"), metrics.refusals)
            // Untouched, which is what makes the rate below possible and what keeps the deploy the
            // remedy: roll back and this saga expires normally.
            assertEquals(asStored, repository.row)

            // AND THE RATE, which is the sentence D14 and the README make. Nothing was written, so
            // the row still matches the query that found it and the next poll refuses it again. A
            // claim taken before the check would hide it for as long as the deadline is re-read.
            val second = afterDeploy.expireSuspended("p-1")
            assertTrue(second is ExpireResult.ChainChanged, "expected a second refusal: $second")
            assertEquals(2, metrics.refusals.size)
        }

    private fun order() =
        Petich(
            id = "p-1",
            type = "order",
            currentPhase = PetichPhase.EXECUTION,
            status = PetichStatus.PROCESSING,
            payload = OrderPayload("sku-1"),
        )

    /** The same engine with a clock and a deadline, so a suspension can actually expire. */
    private fun timed(
        repository: PetichRepository,
        metrics: PetichEngineMetrics,
        clock: PetichClock,
        vararg keys: String,
    ) = PetichEngine(
        repository = repository,
        config = PetichEngineConfig(defaultSuspendTtl = 5.minutes),
        clock = clock,
        metrics = metrics,
        definitions =
            listOf(
                petichDefinition<OrderPayload>("order") {
                    keys.forEach { key -> step(key, Step(key, suspendHere = key == "confirm")) }
                },
            ),
    )

    private fun engine(
        repository: PetichRepository,
        metrics: PetichEngineMetrics,
        vararg keys: String,
    ) = PetichEngine(
        repository = repository,
        metrics = metrics,
        definitions =
            listOf(
                petichDefinition<OrderPayload>("order") {
                    keys.forEach { key -> step(key, Step(key, suspendHere = key == "confirm")) }
                },
            ),
    )

    /** The deploy that moves the saga's index: a member inserted BEFORE where it stopped. */
    private fun afterDeploy(
        repository: PetichRepository,
        metrics: PetichEngineMetrics,
    ) = engine(repository, metrics, "audit", "reserve", "confirm", "ship")

    private suspend fun suspendedSaga(repository: RowRepository) {
        val result =
            engine(repository, PetichEngineMetrics.NoOp, "reserve", "confirm", "ship")
                .process(
                    Petich(
                        id = "p-1",
                        type = "order",
                        currentPhase = PetichPhase.EXECUTION,
                        status = PetichStatus.PROCESSING,
                        payload = OrderPayload("sku-1"),
                    ),
                )
        check(result is PetichResult.ActionRequired) { "expected a suspension: $result" }
    }
}
