package io.github.youndie.petich

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * What each outcome carries of what a member asked to have committed — one case per outcome.
 *
 * WHY THIS FILE EXISTS. `RecordingContext.outcome()` folded events and effects into `Proceed` and,
 * on `Suspend`, folded the effects and dropped the events. On `Reject` and `Compensate` it dropped
 * both. The whole suite was green while three outcomes out of four lost announcements, which is the
 * definition of a suite that is not a guard — so the rule is stated here, per outcome, and each case
 * fails when the folding stops carrying what that outcome is supposed to carry (B-35).
 *
 * The old model could not express any of this: `InterceptorResult.Suspend` had no field for an
 * event, so nobody wrote one. The definition model accepted `ctx.emit` and threw the result away,
 * which is worse than a missing capability and is the reason this is a defect rather than a feature.
 */
class AnnouncementPerOutcomeTest {
    @Serializable
    private data class OrderPayload(
        val sku: String,
    ) : PetichPayload()

    private class Recorder : PetichEngineMetrics {
        val discarded: MutableList<String> = mutableListOf()

        override fun onAnnouncementDiscarded(
            type: String,
            stepKey: String,
            count: Int,
        ) {
            discarded.add("$type/$stepKey=$count")
        }
    }

    /** Outbox- and side-effect-aware, because every case here is about what rides with a write. */
    private class RowRepository :
        OutboxAwarePetichRepository,
        SideEffectAwarePetichRepository {
        var row: Petich? = null
        val outbox: MutableList<String> = mutableListOf()
        val effects: MutableList<String> = mutableListOf()

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
        ): Boolean = update(petich, outboxEvents, emptyList())

        override suspend fun update(
            petich: Petich,
            outboxEvents: List<OutboxEvent>,
            sideEffects: List<PetichSideEffect>,
        ): Boolean {
            val existing = row ?: return false
            if (petich.version != existing.version + 1) return false
            row = petich
            outboxEvents.forEach { outbox.add(it.id) }
            sideEffects.forEach { effects.add(it.toString()) }
            return true
        }
    }

    private class Announces(
        private val name: String,
        private val then: (PetichStepContext) -> Unit = {},
    ) : PetichStep<OrderPayload> {
        override suspend fun execute(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            ctx.emit(event("$name-happened"))
            then(ctx)
        }

        override suspend fun compensate(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) = Unit
    }

    private fun engineFor(
        definition: PetichDefinition<OrderPayload>,
        repository: PetichRepository,
        metrics: PetichEngineMetrics = PetichEngineMetrics.NoOp,
    ) = PetichEngine(
        repository = repository,
        clock = PetichClock { 1_000L },
        metrics = metrics,
        definitions = listOf(definition),
    )

    private fun row(id: String) =
        Petich(
            id = id,
            type = "order",
            status = PetichStatus.PROCESSING,
            payload = OrderPayload("sku-1"),
        )

    @Test
    fun `a member that proceeds has its announcement committed`() =
        runBlocking {
            val repository = RowRepository()
            val definition = petichDefinition<OrderPayload>("order") { step("ship", Announces("ship")) }

            engineFor(definition, repository).process(row("p-proceed"))

            assertEquals(listOf("ship-happened"), repository.outbox, "a Proceed carries what it was handed")
        }

    /**
     * The case the item was filed on. A suspension is a committed write — the row goes to
     * PENDING_SIGNATURE — so an announcement has something to ride on, and konekt's authorisation is
     * exactly the member that acts and then waits.
     */
    @Test
    fun `a member that announces and then suspends has its announcement committed`() =
        runBlocking {
            val repository = RowRepository()
            val definition =
                petichDefinition<OrderPayload>("order") {
                    step("hold", Announces("hold") { it.suspendFor("CONFIRM", 5.minutes) })
                }

            val result = engineFor(definition, repository).process(row("p-suspend"))

            assertTrue(result is PetichResult.ActionRequired, "the saga was supposed to wait: $result")
            assertEquals(
                listOf("hold-happened"),
                repository.outbox,
                "the announcement rides with the write that suspends, as the side effects already did",
            )
        }

    @Test
    fun `a member that announces and then refuses loses it and the loss is reported`() =
        runBlocking {
            val repository = RowRepository()
            val metrics = Recorder()
            val definition =
                petichDefinition<OrderPayload>("order") {
                    step("ship", Announces("ship") { it.reject("out of stock") })
                }

            engineFor(definition, repository, metrics).process(row("p-reject"))

            assertEquals(
                emptyList(),
                repository.outbox,
                "a refusal begins a rollback, and petich does not announce work it is undoing",
            )
            assertEquals(
                listOf("order/ship=1"),
                metrics.discarded,
                "and it says so: a silent drop is what this item exists to remove",
            )
        }

    @Test
    fun `a member that announces and then faults loses it and the loss is reported`() =
        runBlocking {
            val repository = RowRepository()
            val metrics = Recorder()
            val definition =
                petichDefinition<OrderPayload>("order") {
                    step("ship", Announces("ship") { it.fail("the courier is down") })
                }

            engineFor(definition, repository, metrics).process(row("p-fail"))

            assertEquals(emptyList(), repository.outbox, "same rule, the other terminal name")
            assertEquals(listOf("order/ship=1"), metrics.discarded, "and the same counter names the member")
        }

    private companion object {
        fun event(id: String) =
            object : OutboxEvent {
                override val id = id
                override val type = "test.event"
                override val payload = "{}"
            }
    }
}
