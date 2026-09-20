package io.github.youndie.petich

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B-43: the effect landed, the answer did not, and the rollback still has to undo it.
 *
 * This is the case `compensate()` may be called for a member that did not happen exists FOR, and the
 * guard that suggests itself is blind in it. A step calls the far side, the far side commits, the
 * answer is lost, the timeout fires. The member never reached `ctx.record(...)` — and could not have,
 * because the identifier it would have written comes back in the answer that was lost.
 *
 * A test where the step simply never ran does not exercise any of this: there the record is absent
 * AND the effect is absent, so a rollback that does nothing is right by accident. Here the two come
 * apart, which is the only arrangement that tells the two guards from each other.
 */
class LostAnswerCompensationTest {
    private data class OrderPayload(
        val sku: String,
    ) : PetichPayload()

    /**
     * A far side that commits and then loses the answer.
     *
     * [byKey] is what the caller named the effect before making the call; [byId] is the identifier
     * the far side generated and would have returned. A compensation that has only the record can
     * reach the second and never the first.
     */
    private class Warehouse {
        val byKey: MutableMap<String, String> = mutableMapOf()
        var nextId: Int = 1

        fun reserve(key: String): String {
            val id = "res-${nextId++}"
            byKey[key] = id
            return id
        }

        fun releaseByKey(key: String) {
            byKey.remove(key)
        }

        fun releaseById(id: String) {
            byKey.entries.removeAll { it.value == id }
        }
    }

    private class Reservation(
        val id: String,
    ) : PetichStepRecord()

    /** The member the README used to describe: record what came back, undo what the record says. */
    private class ReserveByRecord(
        private val warehouse: Warehouse,
        private val loseTheAnswer: Boolean,
    ) : PetichStep<OrderPayload> {
        override suspend fun execute(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            val id = warehouse.reserve(ctx.idempotencyKey)
            // THE ANSWER IS LOST HERE. Everything after this line is what the member would have done
            // had it come back, `record` included — which is the whole point: the record is written
            // FROM the answer.
            if (loseTheAnswer) error("the warehouse never answered")
            ctx.record(Reservation(id))
        }

        override suspend fun compensate(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            val done = ctx.recorded<Reservation>() ?: return
            warehouse.releaseById(done.id)
        }
    }

    /** The member the README describes now: name the effect before the call, undo by that name. */
    private class ReserveByKey(
        private val warehouse: Warehouse,
        private val loseTheAnswer: Boolean,
    ) : PetichStep<OrderPayload> {
        override suspend fun execute(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            warehouse.reserve(ctx.idempotencyKey)
            if (loseTheAnswer) error("the warehouse never answered")
        }

        override suspend fun compensate(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            warehouse.releaseByKey(ctx.idempotencyKey)
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
    fun `a member that undoes by its record leaves the effect standing when the answer was lost`() =
        runBlocking {
            val warehouse = Warehouse()
            val result = run(warehouse, ReserveByRecord(warehouse, loseTheAnswer = true))

            assertTrue(result !is PetichResult.Success, "a step that threw must not end as a success: $result")
            // THE POSITIVE CONTROL for the case below, and the defect B-43 names. The reservation is
            // in the warehouse and the rollback walked past it, because the only evidence it looked
            // for is the one the lost answer carried.
            assertEquals(1, warehouse.byKey.size, "the reservation should still be standing here")
        }

    @Test
    fun `a member that undoes by its key releases the effect the lost answer hid`() =
        runBlocking {
            val warehouse = Warehouse()
            run(warehouse, ReserveByKey(warehouse, loseTheAnswer = true))

            assertEquals(emptyMap(), warehouse.byKey, "an effect that landed was not undone")
        }

    @Test
    fun `undoing by key is still a no-op when the call never landed at all`() =
        runBlocking {
            val warehouse = Warehouse()
            val member = ReserveByKey(warehouse, loseTheAnswer = true)
            val probe = PetichMemberProbe(order("saga-never-ran"), stepKey = "reserve")

            member.compensate(probe, OrderPayload("sku-1"))

            // The other half of the same rule: `release` has to tolerate arriving without its
            // `reserve`. Naming the effect does not weaken that — there is simply nothing under the
            // name.
            assertEquals(emptyMap(), warehouse.byKey)
        }

    @Test
    fun `the key is the same string on the forward pass and inside the compensation`() {
        val saga = order("saga-1")
        assertEquals(
            PetichMemberProbe(saga, stepKey = "reserve").idempotencyKey,
            PetichMemberProbe(saga.copy(status = PetichStatus.COMPENSATING), stepKey = "reserve").idempotencyKey,
        )
        assertEquals("saga-1:reserve", PetichMemberProbe(saga, stepKey = "reserve").idempotencyKey)
        // And two members of one saga do not share it, which is what makes "cancel what is under
        // this name" safe to say.
        assertTrue(
            PetichMemberProbe(saga, stepKey = "reserve").idempotencyKey !=
                PetichMemberProbe(saga, stepKey = "charge").idempotencyKey,
        )
    }

    private suspend fun run(
        warehouse: Warehouse,
        member: PetichStep<OrderPayload>,
    ): PetichResult {
        val engine =
            PetichEngine(
                repository = RowRepository(),
                definitions = listOf(petichDefinition<OrderPayload>("order") { step("reserve", member) }),
            )
        return engine.process(order("saga-lost-answer"))
    }

    private fun order(id: String) =
        Petich(
            id = id,
            type = "order",
            status = PetichStatus.DRAFT,
            payload = OrderPayload("sku-1"),
        )
}
