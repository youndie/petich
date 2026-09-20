package io.github.youndie.petich

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * B-19, the half its own order puts first: a rollback that cannot finish now counts its attempts
 * and, at the bound, stops being something anyone waits for.
 *
 * Before this the engine wrote nothing at all when a compensation threw. The saga stayed
 * COMPENSATING with no record that a rollback had even been tried, and on the default handler in
 * silence — which is how the only state with no automatic way out was also the least visible one.
 */
class CompensationGivesUpTest {
    data class OrderPayload(
        val sku: String,
    ) : PetichPayload()

    class Log {
        val entries: MutableList<String> = mutableListOf()
    }

    class Step(
        private val name: String,
        private val log: Log,
        private val throwOnIntercept: Boolean = false,
        private val throwOnCompensate: Boolean = false,
    ) : PetichStep<OrderPayload> {
        override suspend fun execute(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            log.entries.add("do:$name")
            if (throwOnIntercept) throw RuntimeException("the answer was lost")
            return
        }

        override suspend fun compensate(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            log.entries.add("undo:$name")
            if (throwOnCompensate) throw RuntimeException("the far side refuses")
        }
    }

    /** A row with an optimistic lock, and an outbox the test can look into. */
    class RowRepository : OutboxAwarePetichRepository {
        var row: Petich? = null
        val outbox: MutableList<String> = mutableListOf()

        override suspend fun findById(id: String): Petich? = row?.takeIf { it.id == id }

        override suspend fun saveOrGet(petich: Petich): Petich {
            val existing = row
            if (existing != null && existing.id == petich.id) return existing
            row = petich
            return petich
        }

        override suspend fun update(
            petich: Petich,
            outboxEvents: List<OutboxEvent>,
        ): Boolean {
            val existing = row ?: return false
            if (petich.version != existing.version + 1) return false
            row = petich
            outboxEvents.forEach { outbox.add(it.id) }
            return true
        }
    }

    class CountingHandler : CompensationFailureHandler {
        var handled: Int = 0
        var exhaustedAt: Int? = null

        override suspend fun handle(
            e: Exception,
            petich: Petich,
            stepKey: String,
        ) {
            handled++
        }

        override suspend fun exhausted(
            petich: Petich,
            stepKey: String,
            attempts: Int,
        ): List<OutboxEvent> {
            exhaustedAt = attempts
            return listOf(
                object : OutboxEvent {
                    override val id = "gave-up:${petich.id}"
                    override val type = "test.compensation_failed"
                    override val payload = """{"attempts":$attempts}"""
                },
            )
        }
    }

    class CapturingMetrics : PetichEngineMetrics {
        val failures: MutableList<Pair<Int, Boolean>> = mutableListOf()

        override fun onCompensationFailure(
            type: String,
            attempt: Int,
            exhausted: Boolean,
        ) {
            failures.add(attempt to exhausted)
        }
    }

    private fun row(id: String) =
        Petich(
            id = id,
            type = "order",
            currentPhase = PetichPhase.EXECUTION,
            status = PetichStatus.PROCESSING,
            payload = OrderPayload("sku-1"),
        )

    private fun engine(
        log: Log,
        repository: RowRepository,
        handler: CompensationFailureHandler,
        metrics: PetichEngineMetrics = PetichEngineMetrics.NoOp,
        maxAttempts: Int = 2,
    ) = PetichEngine(
        repository = repository,
        compensationFailureHandler = handler,
        config = PetichEngineConfig(maxCompensationAttempts = maxAttempts),
        metrics = metrics,
        definitions =
            listOf(
                petich<OrderPayload>("order") {
                    step("reserve", Step("reserve", log))
                    step("charge", Step("charge", log, throwOnIntercept = true, throwOnCompensate = true))
                },
            ),
    )

    @Test
    fun `a rollback that keeps failing counts its attempts and gives up at the bound`() =
        runBlocking {
            val log = Log()
            val repository = RowRepository()
            val handler = CountingHandler()
            val metrics = CapturingMetrics()
            val engine = engine(log, repository, handler, metrics)

            engine.process(row("p-gives-up"))

            assertEquals(
                PetichStatus.COMPENSATING,
                repository.row?.status,
                "one failed rollback is not the end of the road",
            )
            assertEquals(1, repository.row?.compensationAttempts, "the first attempt has to be recorded")
            assertTrue(repository.outbox.isEmpty(), "nothing final has happened yet")

            engine.process(repository.row!!)

            assertEquals(
                PetichStatus.COMPENSATION_FAILED,
                repository.row?.status,
                "at the bound the saga stops being something anyone waits for",
            )
            assertEquals(2, repository.row?.compensationAttempts)
            assertEquals(2, handler.handled, "the handler sees every failure, not only the last")
            assertEquals(2, handler.exhaustedAt, "and is told which attempt gave up")
            assertEquals(
                listOf("gave-up:p-gives-up"),
                repository.outbox,
                "the announcement is committed with the status, and only once",
            )
            assertEquals(
                listOf(1 to false, 2 to true),
                metrics.failures,
                "both attempts are countable, and only one of them is final",
            )
        }

    @Test
    fun `a saga that gave up is terminal and is not rolled back again`() =
        runBlocking {
            val log = Log()
            val repository = RowRepository()
            val engine = engine(log, repository, CountingHandler())

            engine.process(row("p-terminal"))
            engine.process(repository.row!!)
            assertEquals(PetichStatus.COMPENSATION_FAILED, repository.row?.status)

            val undoneBefore = log.entries.count { it == "undo:charge" }
            val replay = engine.process(repository.row!!)

            assertTrue(replay is PetichResult.Error, "a terminal saga reports, it does not work: $replay")
            assertTrue(
                replay.reason.contains("in part"),
                "a half-undone saga must not read as an ordinary failure: ${replay.reason}",
            )
            assertEquals(
                undoneBefore,
                log.entries.count { it == "undo:charge" },
                "nothing is compensated a third time",
            )
        }

    @Test
    fun `requireCompensationHandler refuses the handler that goes nowhere`() =
        runBlocking {
            val failure =
                assertFailsWith<IllegalArgumentException> {
                    PetichEngine(
                        repository = RowRepository(),
                        compensationFailureHandler = NoOpCompensationFailureHandler(),
                        config = PetichEngineConfig(requireCompensationHandler = true),
                    )
                }

            assertTrue(
                failure.message?.contains("no-op handler") == true,
                "the refusal has to name what is wrong: ${failure.message}",
            )

            // And it is off by default, so nothing existing has to configure its own silence.
            PetichEngine(repository = RowRepository(), compensationFailureHandler = NoOpCompensationFailureHandler())
            Unit
        }
}
