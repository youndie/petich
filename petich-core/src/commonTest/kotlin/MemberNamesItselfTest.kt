package io.github.youndie.petich

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A member's own address, which it lost when `phase` and `priority` moved into the definition.
 *
 * Found by migrating shashki (B-36): it names a tracing span after the step's phase and asserts the
 * string in a test, because an earlier version shipped the unexpanded template to a collector. An
 * interceptor knew its own phase; a step does not, and the key was private to the engine — which had
 * been constructing the context with it all along.
 */
class MemberNamesItselfTest {
    @Serializable
    private data class OrderPayload(
        val sku: String,
    ) : PetichPayload()

    private class NamesItself(
        private val log: MutableList<String>,
        private val failAfter: Boolean = false,
    ) : PetichStep<OrderPayload> {
        override suspend fun execute(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            log.add("do:${ctx.stepKey}")
            if (failAfter) ctx.fail("so that the rollback runs")
        }

        override suspend fun compensate(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            log.add("undo:${ctx.stepKey}")
        }
    }

    private class ChecksItself(
        private val log: MutableList<String>,
    ) : PetichCheck<OrderPayload> {
        override suspend fun check(
            ctx: PetichCheckContext,
            payload: OrderPayload,
        ) {
            log.add("check:${ctx.stepKey}")
        }
    }

    private class RowRepository : OutboxAwarePetichRepository {
        var row: Petich? = null

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
            return true
        }
    }

    private fun row(id: String) =
        Petich(
            id = id,
            type = "order",
            status = PetichStatus.PROCESSING,
            payload = OrderPayload("sku-1"),
        )

    private fun engineFor(definition: PetichDefinition<OrderPayload>) =
        PetichEngine(
            repository = RowRepository(),
            clock = PetichClock { 1_000L },
            definitions = listOf(definition),
        )

    @Test
    fun `every kind of member reads the key its definition declares`() =
        runBlocking {
            val log = mutableListOf<String>()

            engineFor(
                petichDefinition<OrderPayload>("order") {
                    validate("in-service-area", ChecksItself(log))
                    step("reserve-stock", NamesItself(log))
                },
            ).process(row("p-1"))

            assertEquals(listOf("check:in-service-area", "do:reserve-stock"), log)
        }

    /**
     * The property that makes it usable for naming: the same value going either way. A span named
     * from a compensation has to name the member being undone rather than wherever the rollback has
     * reached — which is what `petich.currentPhase` would have given.
     */
    @Test
    fun `a compensation reads the same key its forward pass did`() =
        runBlocking {
            val log = mutableListOf<String>()

            engineFor(
                petichDefinition<OrderPayload>("order") {
                    step("reserve-stock", NamesItself(log))
                    step("charge-card", NamesItself(log, failAfter = true))
                },
            ).process(row("p-2"))

            assertEquals(
                listOf("do:reserve-stock", "do:charge-card", "undo:reserve-stock"),
                log,
                "the undo names the member it is undoing",
            )
        }
}
