package io.github.youndie.petich

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B-49: a counter is not a trace anybody can query.
 *
 * B-41 stopped an announcement's exception from rolling the saga back. What it left was
 * `onAnnouncementFailed` and nothing else — and if the exception lands **before** `ctx.emit`, there
 * is no event, the saga completes, its state is correct, and the consumer at the other end never
 * learns. A test where the member throws AFTER emitting proves nothing here, because the event it
 * already asked for is committed either way (B-41); only the before case separates the two.
 */
class FailedAnnouncementLeavesTheDatabaseTest {
    private data class OrderPayload(
        val sku: String,
    ) : PetichPayload()

    private class Reserve : PetichStep<OrderPayload> {
        override suspend fun execute(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) = Unit

        override suspend fun compensate(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) = Unit
    }

    /** Throws before it can emit, which is the case the counter cannot describe. */
    private class DiesBeforeEmitting : PetichAnnouncement<OrderPayload> {
        override suspend fun announce(
            ctx: PetichAnnouncementContext,
            payload: OrderPayload,
        ): Unit = error("the relay refused the connection")
    }

    private class Announces : PetichAnnouncement<OrderPayload> {
        override suspend fun announce(
            ctx: PetichAnnouncementContext,
            payload: OrderPayload,
        ) {
            ctx.emit(event("order-completed"))
        }
    }

    /** The application's word for it, which is the only shape petich does not invent. */
    private class SaysSo : AnnouncementFailureHandler {
        override suspend fun failed(
            petich: Petich,
            stepKey: String,
            reason: String,
        ): List<OutboxEvent> = listOf(event("unannounced:${petich.id}:$stepKey:$reason"))
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

    /** Cannot store an outbox at all — the wiring `requireOutbox` exists to refuse. */
    private class PlainRepository : PetichRepository {
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
        var dropped: Int = 0
        val failures: MutableList<String> = mutableListOf()

        override fun onDroppedEvents(
            type: String,
            count: Int,
        ) {
            dropped += count
        }

        override fun onAnnouncementFailed(
            type: String,
            key: String,
            reason: String,
        ) {
            failures.add(key)
        }
    }

    @Test
    fun `an announcement that dies before emitting still leaves a row in the outbox`() =
        runBlocking {
            val repository = RowRepository()
            val result = engine(repository, DiesBeforeEmitting(), SaysSo()).process(order("saga-1"))

            assertTrue(result is PetichResult.Success, "the saga still completes: $result")
            assertEquals(
                listOf("unannounced:saga-1:notify:the relay refused the connection"),
                repository.outbox,
            )
        }

    @Test
    fun `it rides with the announcement's own commit rather than a write of its own`() =
        runBlocking {
            val repository = RowRepository()
            val before = repository.row?.version ?: 0
            engine(repository, DiesBeforeEmitting(), SaysSo()).process(order("saga-2"))

            // One member that proceeds is one write. The failure event is committed by that write,
            // which is what makes it free — and what makes "in the same transaction as the state
            // change" true rather than a wish.
            assertEquals(1, repository.outbox.size)
            assertTrue(checkNotNull(repository.row).version > before)
        }

    @Test
    fun `a member that emitted and then died keeps both`() =
        runBlocking {
            val repository = RowRepository()
            engine(repository, EmitsThenDies(), SaysSo()).process(order("saga-3"))

            // What it asked for before it threw is kept (B-41), and the failure is appended after,
            // because the order in the outbox is the order things happened.
            assertEquals(
                listOf("order-completed", "unannounced:saga-3:notify:the relay refused the connection"),
                repository.outbox,
            )
        }

    @Test
    fun `an announcement that succeeds says nothing extra`() =
        runBlocking {
            val repository = RowRepository()
            engine(repository, Announces(), SaysSo()).process(order("saga-4"))

            assertEquals(listOf("order-completed"), repository.outbox)
        }

    @Test
    fun `without a handler nothing is emitted and the counter is the only trace`() =
        runBlocking {
            val repository = RowRepository()
            val metrics = CountingMetrics()
            engine(repository, DiesBeforeEmitting(), NoOpAnnouncementFailureHandler(), metrics)
                .process(order("saga-5"))

            assertEquals(emptyList(), repository.outbox)
            assertEquals(listOf("notify"), metrics.failures)
        }

    @Test
    fun `a repository that cannot store events drops it and counts it like any other`() =
        runBlocking {
            val metrics = CountingMetrics()
            engine(PlainRepository(), DiesBeforeEmitting(), SaysSo(), metrics).process(order("saga-6"))

            // No new rule for this event: it is dropped and counted exactly as any other would be,
            // and `PetichEngineConfig.requireOutbox` is what refuses this wiring at construction.
            assertEquals(1, metrics.dropped)
        }

    private class EmitsThenDies : PetichAnnouncement<OrderPayload> {
        override suspend fun announce(
            ctx: PetichAnnouncementContext,
            payload: OrderPayload,
        ) {
            ctx.emit(event("order-completed"))
            error("the relay refused the connection")
        }
    }

    private fun engine(
        repository: PetichRepository,
        announcement: PetichAnnouncement<OrderPayload>,
        handler: AnnouncementFailureHandler,
        metrics: PetichEngineMetrics = PetichEngineMetrics.NoOp,
    ) = PetichEngine(
        repository = repository,
        metrics = metrics,
        definitions =
            listOf(
                petichDefinition<OrderPayload>("order") {
                    step("reserve", Reserve())
                    announce("notify", announcement)
                },
            ),
        announcementFailureHandler = handler,
    )

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
