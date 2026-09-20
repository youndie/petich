package io.github.youndie.petich

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B-41: by the time a member announces, the work is done — so nothing it does can undo it.
 *
 * The type carries most of this and a test cannot assert it: [PetichAnnouncementContext] has no
 * `fail`, no `reject`, no `suspendFor`, and [PetichAnnouncement] has no `compensate`, so those
 * mistakes are compile errors and a compile error is not a test case. What a test CAN hold is the
 * half the type cannot reach — **a member that throws** — and that half is the one that used to make
 * the rest untrue: withholding `fail` stops a member from deciding to end the saga and does nothing
 * about one that dies, and a death meant exactly the same rollback.
 */
class AnnouncementCannotFailTest {
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
            log.add("reserve")
        }

        override suspend fun compensate(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            log.add("release")
        }
    }

    /** Announces, then dies — the shape of a notifier whose far side is down. */
    private class AnnouncesThenDies(
        private val emitFirst: Boolean,
    ) : PetichAnnouncement<OrderPayload> {
        override suspend fun announce(
            ctx: PetichAnnouncementContext,
            payload: OrderPayload,
        ) {
            if (emitFirst) ctx.emit(event("order-completed"))
            error("the relay refused the connection")
        }
    }

    private class Announces : PetichAnnouncement<OrderPayload> {
        override suspend fun announce(
            ctx: PetichAnnouncementContext,
            payload: OrderPayload,
        ) {
            ctx.emit(event("order-completed"))
        }
    }

    private class RowRepository : OutboxAwarePetichRepository {
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

    private class CountingMetrics : PetichEngineMetrics {
        val failures: MutableList<String> = mutableListOf()
        var compensations: Int = 0

        override fun onAnnouncementFailed(
            type: String,
            key: String,
            reason: String,
        ) {
            failures.add("$type/$key/$reason")
        }

        override fun onCompensation(
            type: String,
            reason: String,
        ) {
            compensations++
        }
    }

    @Test
    fun `an announcement that throws does not roll the saga back`() =
        runBlocking {
            val log = mutableListOf<String>()
            val repository = RowRepository()
            val metrics = CountingMetrics()
            val engine =
                PetichEngine(
                    repository = repository,
                    metrics = metrics,
                    definitions =
                        listOf(
                            petich<OrderPayload>("order") {
                                step("reserve", Reserve(log))
                                announce("notify", AnnouncesThenDies(emitFirst = false))
                            },
                        ),
                )

            val result = engine.process(order("saga-1"))

            assertTrue(result is PetichResult.Success, "a notification that did not go is not a failed saga: $result")
            assertEquals(listOf("reserve"), log, "nothing was released")
            assertEquals(0, metrics.compensations, "and no rollback was started")
        }

    @Test
    fun `the failure is counted with the member that had it and the reason it gave`() =
        runBlocking {
            val metrics = CountingMetrics()
            val engine =
                PetichEngine(
                    repository = RowRepository(),
                    metrics = metrics,
                    definitions =
                        listOf(
                            petich<OrderPayload>("order") {
                                step("reserve", Reserve(mutableListOf()))
                                announce("notify", AnnouncesThenDies(emitFirst = false))
                            },
                        ),
                )

            engine.process(order("saga-2"))

            // Not silent, because "the saga is fine" is exactly the reading that would otherwise
            // make a broken notification path invisible: it is not in the failure rate either.
            assertEquals(
                listOf("order/notify/the relay refused the connection"),
                metrics.failures,
            )
        }

    @Test
    fun `what an announcement asked for before it threw is still committed`() =
        runBlocking {
            val repository = RowRepository()
            val engine =
                PetichEngine(
                    repository = repository,
                    definitions =
                        listOf(
                            petich<OrderPayload>("order") {
                                step("reserve", Reserve(mutableListOf()))
                                announce("notify", AnnouncesThenDies(emitFirst = true))
                            },
                        ),
                )

            engine.process(order("saga-3"))

            // A REFUSAL carries nothing (B-35) because it begins a rollback and petich will not
            // announce work it is undoing. There is no rollback here, so there is nothing to protect
            // by dropping the event — and dropping it would lose the one write the outbox exists to
            // make certain.
            assertEquals(listOf("order-completed"), repository.outbox)
        }

    @Test
    fun `an announcement that returns normally is the same saga as before`() =
        runBlocking {
            val repository = RowRepository()
            val metrics = CountingMetrics()
            val engine =
                PetichEngine(
                    repository = repository,
                    metrics = metrics,
                    definitions =
                        listOf(
                            petich<OrderPayload>("order") {
                                step("reserve", Reserve(mutableListOf()))
                                announce("notify", Announces())
                            },
                        ),
                )

            val result = engine.process(order("saga-4"))

            assertTrue(result is PetichResult.Success)
            assertEquals(listOf("order-completed"), repository.outbox)
            assertEquals(emptyList(), metrics.failures, "a member that did not throw is not counted")
        }

    @Test
    fun `a chain dump names an announcement as one`() {
        val dump =
            petich<OrderPayload>("order") {
                step("reserve", Reserve(mutableListOf()))
                announce("notify", Announces())
            }.describeChain()

        assertTrue(dump.contains("notify (announcement)"), "a reader's only view of a definition: $dump")
        assertTrue(dump.contains("EXECUTION: reserve"), "and a step is still unadorned: $dump")
    }

    private fun order(id: String) =
        Petich(
            id = id,
            type = "order",
            status = PetichStatus.DRAFT,
            payload = OrderPayload("sku-1"),
        )

    private companion object {
        fun event(eventId: String) =
            object : OutboxEvent {
                override val id = eventId
                override val type = "test.event"
                override val payload = "{}"
            }
    }
}
