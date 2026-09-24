package io.github.youndie.petich

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B-59: the door B-52 left. B-52 stopped a HUNG announcement from rolling a finished saga back by
 * giving it no outer deadline and a `withTimeoutOrNull` of its own. An announcement whose body has a
 * deadline of ITS OWN — an HTTP client's, say — throws a `TimeoutCancellationException` that is not
 * the engine's; `withTimeoutOrNull` passes a foreign one through, `announce` rethrew every
 * `CancellationException`, and the phase loop's timeout branch rolled the saga back.
 *
 * The line this draws is the caller: a cancellation while the engine's own coroutine is still
 * active came from inside the body and is the body's failure; one while it is not is the process
 * going away, and must still leave.
 */
class AnnouncementOwnTimeoutTest {
    private data class OrderPayload(
        val sku: String,
    ) : PetichPayload()

    private class Reserve(
        private val log: MutableList<String>,
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
        }
    }

    /** A mail client with a 10 ms deadline of its own, talking to a server that does not answer. */
    private class OwnDeadline : PetichAnnouncement<OrderPayload> {
        override suspend fun announce(
            ctx: PetichAnnouncementContext,
            payload: OrderPayload,
        ) {
            withTimeout(10) { delay(10_000) }
        }
    }

    private class Blocks(
        private val entered: CompletableDeferred<Unit>,
    ) : PetichAnnouncement<OrderPayload> {
        override suspend fun announce(
            ctx: PetichAnnouncementContext,
            payload: OrderPayload,
        ) {
            entered.complete(Unit)
            delay(10_000)
        }
    }

    private class Failures : PetichEngineMetrics {
        val reasons: MutableList<String> = mutableListOf()
        var compensations: Int = 0

        override fun onAnnouncementFailed(
            type: String,
            key: String,
            reason: String,
        ) {
            reasons.add(reason)
        }

        override fun onCompensation(
            type: String,
            reason: String,
        ) {
            compensations++
        }
    }

    private class Handler : AnnouncementFailureHandler {
        val asked: MutableList<String> = mutableListOf()

        override suspend fun failed(
            petich: Petich,
            stepKey: String,
            reason: String,
        ): List<OutboxEvent> {
            asked.add(stepKey)
            return emptyList()
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
    fun `an announcement whose own deadline fires does not roll the saga back`() =
        runBlocking {
            val log = mutableListOf<String>()
            val metrics = Failures()
            val handler = Handler()
            val repository = RowRepository()
            val result =
                PetichEngine(
                    repository = repository,
                    metrics = metrics,
                    announcementFailureHandler = handler,
                    definitions =
                        listOf(
                            petichDefinition<OrderPayload>("order") {
                                step("reserve", Reserve(log))
                                announce("notify", OwnDeadline())
                            },
                        ),
                ).process(order())

            assertTrue(result is PetichResult.Success, "a receipt that timed out is not a failed order: $result")
            assertEquals(PetichStatus.COMPLETED, repository.row?.status)
            assertEquals(listOf("do:reserve"), log, "nothing was undone")
            assertEquals(0, metrics.compensations)
            assertEquals(1, metrics.reasons.size, "the failure is still counted: ${metrics.reasons}")
            assertEquals(listOf("notify"), handler.asked, "and still handed to the application")
        }

    @Test
    fun `cancelling the process during an announcement still cancels it`() =
        runBlocking {
            val log = mutableListOf<String>()
            val metrics = Failures()
            val repository = RowRepository()
            val entered = CompletableDeferred<Unit>()
            val engine =
                PetichEngine(
                    repository = repository,
                    metrics = metrics,
                    definitions =
                        listOf(
                            petichDefinition<OrderPayload>("order") {
                                step("reserve", Reserve(log))
                                announce("notify", Blocks(entered))
                            },
                        ),
                )

            val pass = async { engine.process(order()) }
            entered.await()
            pass.cancelAndJoin()

            assertTrue(pass.isCancelled, "the pass should have left as cancelled")
            assertEquals(emptyList(), metrics.reasons, "the caller going away is not an announcement failing")
            assertEquals(0, metrics.compensations)
            assertTrue(repository.row?.status != PetichStatus.COMPLETED, "${repository.row}")
        }

    private fun order() =
        Petich(
            id = "order-1",
            type = "order",
            status = PetichStatus.DRAFT,
            payload = OrderPayload("sku-1"),
        )
}
