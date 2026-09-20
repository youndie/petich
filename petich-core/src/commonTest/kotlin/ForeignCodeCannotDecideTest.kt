package io.github.youndie.petich

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B-52: an application's own code used to decide a saga's fate by throwing.
 *
 * Three doors into one room. An announcement that HUNG was cancelled by the phase loop's
 * `withTimeout`, and a `TimeoutCancellationException` is a `CancellationException` — obliged to pass
 * through everything — so it reached the loop and rolled a finished saga back. B-41 had made the one
 * that THREW harmless and left this one. And every failure handler ran where a throw escaped: the
 * worst was `CompensationFailureHandler.handle`, whose throw skipped the attempt's own bookkeeping,
 * so `maxCompensationAttempts` stopped bounding anything.
 */
class ForeignCodeCannotDecideTest {
    private data class OrderPayload(
        val sku: String,
    ) : PetichPayload()

    private class Reserve(
        private val log: MutableList<String>,
        private val undoThrows: Boolean = false,
    ) : PetichStep<OrderPayload> {
        override suspend fun execute(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            log.add("do:reserve")
        }

        override suspend fun compensate(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            log.add("undo:reserve")
            if (undoThrows) error("the warehouse is down")
        }
    }

    private class Refuses : PetichStep<OrderPayload> {
        override suspend fun execute(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) = ctx.fail("something broke")

        override suspend fun compensate(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) = Unit
    }

    private class Hangs : PetichAnnouncement<OrderPayload> {
        override suspend fun announce(
            ctx: PetichAnnouncementContext,
            payload: OrderPayload,
        ) {
            delay(Long.MAX_VALUE / 2)
        }
    }

    private class ThrowingHandler : AnnouncementFailureHandler {
        var asked: Int = 0

        override suspend fun failed(
            petich: Petich,
            stepKey: String,
            reason: String,
        ): List<OutboxEvent> {
            asked++
            error("the handler is broken too")
        }
    }

    private class ThrowingCompensationHandler : CompensationFailureHandler {
        var asked: Int = 0

        override suspend fun handle(
            e: Exception,
            petich: Petich,
            stepKey: String,
        ) {
            asked++
            error("the reporter is broken")
        }
    }

    private class CountingMetrics : PetichEngineMetrics {
        val announcementFailures: MutableList<String> = mutableListOf()
        val handlerFailures: MutableList<String> = mutableListOf()
        var compensations: Int = 0
        var exhausted: Int = 0

        override fun onAnnouncementFailed(
            type: String,
            key: String,
            reason: String,
        ) {
            announcementFailures.add(reason)
        }

        override fun onHandlerFailed(
            type: String,
            callback: String,
            reason: String,
        ) {
            handlerFailures.add(callback)
        }

        override fun onCompensation(
            type: String,
            reason: String,
        ) {
            compensations++
        }

        override fun onCompensationFailure(
            type: String,
            attempt: Int,
            exhausted: Boolean,
        ) {
            if (exhausted) this.exhausted++
        }
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

    @Test
    fun `an announcement that hangs past its deadline does not roll the saga back`() =
        runBlocking {
            val log = mutableListOf<String>()
            val metrics = CountingMetrics()
            val result =
                PetichEngine(
                    repository = RowRepository(),
                    metrics = metrics,
                    config = PetichEngineConfig(phaseTimeoutsMs = PetichPhase.entries.associateWith { 30L }),
                    definitions =
                        listOf(
                            petichDefinition<OrderPayload>("order") {
                                step("reserve", Reserve(log))
                                announce("notify", Hangs())
                            },
                        ),
                ).process(order())

            assertTrue(result is PetichResult.Success, "a notification that hung is not a failed saga: $result")
            assertEquals(listOf("do:reserve"), log, "nothing was undone")
            assertEquals(0, metrics.compensations)
            // And the failure is still reported, which is only possible because the deadline was
            // caught while the coroutine was alive.
            assertEquals(1, metrics.announcementFailures.size)
            assertTrue(
                metrics.announcementFailures.single().contains("timed out"),
                "the reason should say what happened: ${metrics.announcementFailures}",
            )
        }

    @Test
    fun `an announcement whose handler throws still completes the saga`() =
        runBlocking {
            val metrics = CountingMetrics()
            val handler = ThrowingHandler()
            val result =
                PetichEngine(
                    repository = RowRepository(),
                    metrics = metrics,
                    announcementFailureHandler = handler,
                    definitions =
                        listOf(
                            petichDefinition<OrderPayload>("order") {
                                step("reserve", Reserve(mutableListOf()))
                                announce("notify", Dies())
                            },
                        ),
                ).process(order())

            assertTrue(result is PetichResult.Success, "$result")
            assertEquals(1, handler.asked, "the handler was asked")
            assertEquals(
                listOf("announcementFailureHandler.failed"),
                metrics.handlerFailures,
                "and its own failure was counted rather than acted on",
            )
        }

    @Test
    fun `a reporter that throws every time still lets the bound be reached`() =
        runBlocking {
            val metrics = CountingMetrics()
            val handler = ThrowingCompensationHandler()
            val repository = RowRepository()
            val engine =
                PetichEngine(
                    repository = repository,
                    metrics = metrics,
                    compensationFailureHandler = handler,
                    definitions =
                        listOf(
                            petichDefinition<OrderPayload>("order") {
                                step("reserve", Reserve(mutableListOf(), undoThrows = true))
                                step("break", Refuses())
                            },
                        ),
                ).let { engine ->
                    // Three passes, because the attempt counter is persisted and the bound is across
                    // passes rather than inside one (it is what a sweeper re-drive would do).
                    repeat(3) { engine.process(checkNotNull(repository.row ?: order().also { engine.process(it) })) }
                    engine
                }

            assertTrue(handler.asked > 0, "the reporter was asked")
            assertEquals(
                PetichStatus.COMPENSATION_FAILED,
                repository.row?.status,
                "the bound must still bound: a reporter that throws used to skip the count for ever",
            )
            assertTrue(engine.owns(checkNotNull(repository.row)))
        }

    private class Dies : PetichAnnouncement<OrderPayload> {
        override suspend fun announce(
            ctx: PetichAnnouncementContext,
            payload: OrderPayload,
        ): Unit = error("the relay refused the connection")
    }

    private fun order() =
        Petich(
            id = "saga-1",
            type = "order",
            status = PetichStatus.DRAFT,
            payload = OrderPayload("sku-1"),
        )
}
