package io.github.youndie.petich

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * B-60: the line is a format other repositories grep for, so it is pinned here rather than
 * described. A change to it is a change to every consumer's log queries, and has to show up as a
 * failing test before it shows up as a query that quietly matches nothing.
 */
class LinePetichTracerTest {
    private data class Order(
        val id: String,
    ) : PetichPayload()

    private class Step(
        private val act: (PetichStepContext) -> Unit = {},
    ) : PetichStep<Order> {
        override suspend fun execute(
            ctx: PetichStepContext,
            payload: Order,
        ) = act(ctx)

        override suspend fun compensate(
            ctx: PetichStepContext,
            payload: Order,
        ) = Unit
    }

    private class Rows : PetichRepository {
        var row: Petich? = null

        override suspend fun findById(id: String): Petich? = row

        override suspend fun saveOrGet(petich: Petich): Petich = row ?: petich.also { row = it }

        override suspend fun update(petich: Petich): Boolean {
            val existing = row ?: return false
            if (petich.version != existing.version + 1) return false
            row = petich
            return true
        }
    }

    private val tracer = LinePetichTracer(replica = "api-1", clock = { 1727200000000 }, write = {})

    @Test
    fun `a member event names the saga and the member`() {
        assertEquals(
            "petich.trace ts=1727200000000 replica=api-1 saga=order-1 type=order event=MemberEntered " +
                "phase=EXECUTION index=0 key=hold",
            tracer.line(PetichTraceEvent.MemberEntered("order-1", "order", PetichPhase.EXECUTION, 0, "hold")),
        )
    }

    @Test
    fun `a reason with spaces or quotes is quoted and escaped`() {
        assertEquals(
            "petich.trace ts=1727200000000 replica=api-1 saga=order-1 type=order event=RollbackStarted " +
                "phase=EXECUTION from=1 towards=REJECTED reason=\"the card is \\\"stolen\\\"\"",
            tracer.line(
                PetichTraceEvent.RollbackStarted(
                    "order-1",
                    "order",
                    PetichPhase.EXECUTION,
                    1,
                    PetichStatus.REJECTED,
                    "the card is \"stolen\"",
                ),
            ),
        )
    }

    @Test
    fun `a saga run through the engine writes one line per event in order`() =
        runBlocking {
            val lines = mutableListOf<String>()
            PetichEngine(
                repository = Rows(),
                definitions = listOf(petichDefinition<Order>("order") { step("hold", Step()) }),
                tracer = LinePetichTracer("api-1", { 1 }) { lines.add(it) },
            ).process(Petich(id = "order-1", type = "order", status = PetichStatus.DRAFT, payload = Order("order-1")))

            assertEquals(
                listOf(
                    "petich.trace ts=1 replica=api-1 saga=order-1 type=order event=PassStarted attempt=1 " +
                        "status=PROCESSING phase=ENRICHMENT index=0",
                    "petich.trace ts=1 replica=api-1 saga=order-1 type=order event=MemberEntered " +
                        "phase=EXECUTION index=0 key=hold",
                    "petich.trace ts=1 replica=api-1 saga=order-1 type=order event=MemberProceeded " +
                        "phase=EXECUTION index=0 key=hold",
                    "petich.trace ts=1 replica=api-1 saga=order-1 type=order event=Finished status=COMPLETED",
                ),
                lines,
            )
        }
}
