package io.github.youndie.petich

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * B-48: a far side that deduplicates but cannot be addressed by the caller's name.
 *
 * B-43's rule ends "cancel whatever is under this key", which needs the far side to accept that name
 * as an address. The common shape does not: the key is a token with a lifetime, a replay inside the
 * window returns the original answer, and cancellation is by the id the far side generated. The
 * gateway below is exactly that, and deliberately has no `releaseByKey` to reach for.
 *
 * What works there is to **replay and cancel by the id the replay hands back**, and the point of the
 * cases is that the member cannot tell which one it is in — which is the whole reason the first call
 * was ambiguous.
 */
class ReplayThenCancelTest {
    private data class OrderPayload(
        val amount: Long,
    ) : PetichPayload()

    private class Hold(
        val id: String,
    )

    /** Deduplicates for as long as it remembers a key; cancels by generated id and nothing else. */
    private class Gateway {
        private val byKey: MutableMap<String, Hold> = mutableMapOf()
        val live: MutableSet<String> = mutableSetOf()
        var calls: Int = 0
            private set
        private var next = 1

        fun hold(key: String): Hold {
            calls++
            return byKey.getOrPut(key) {
                Hold("hold-${next++}").also { live.add(it.id) }
            }
        }

        fun release(id: String) {
            live.remove(id)
        }

        /** The retention window closing on a key the caller may still replay. */
        fun forget(key: String) {
            byKey.remove(key)
        }
    }

    /** Replays its own request and cancels by the id that comes back — B-48's second form. */
    private class HoldFunds(
        private val gateway: Gateway,
        private val loseTheAnswer: Boolean,
        private val landsAtAll: Boolean = true,
    ) : PetichStep<OrderPayload> {
        override suspend fun execute(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            if (landsAtAll) gateway.hold(ctx.idempotencyKey)
            if (loseTheAnswer) error("the gateway never answered")
        }

        override suspend fun compensate(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            gateway.release(gateway.hold(ctx.idempotencyKey).id)
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
    fun `the money comes back when the first call landed and its answer was lost`() =
        runBlocking {
            val gateway = Gateway()
            run(gateway, HoldFunds(gateway, loseTheAnswer = true))

            assertEquals(emptySet(), gateway.live, "a hold survived the rollback")
            // Two calls, ONE hold: the replay was answered by the first call rather than taking a
            // second, which is the property the whole form rests on.
            assertEquals(2, gateway.calls)
        }

    @Test
    fun `the money comes back when the first call never landed at all`() =
        runBlocking {
            val gateway = Gateway()
            run(gateway, HoldFunds(gateway, loseTheAnswer = true, landsAtAll = false))

            // Here the replay CREATES the hold and the release removes it. Net zero, and the member
            // wrote the same two lines — which is the point, because it cannot tell the cases apart.
            assertEquals(emptySet(), gateway.live)
            assertEquals(1, gateway.calls)
        }

    @Test
    fun `a key the far side has forgotten turns the replay into a second effect`() =
        runBlocking {
            val gateway = Gateway()
            val saga = order("saga-expired")
            val member = HoldFunds(gateway, loseTheAnswer = false)
            val probe = PetichMemberProbe(saga, stepKey = "hold-funds")

            member.execute(probe, OrderPayload(1_000))
            // THE RETENTION WINDOW CLOSING, which is what the inequality in the README is for. The
            // rollback arrives after the far side stopped recognising the key.
            gateway.forget(probe.idempotencyKey)
            member.compensate(probe, OrderPayload(1_000))

            // The replay took a SECOND hold and released that one; the first is still standing. This
            // is the failure the arithmetic prevents, kept as a live control rather than a warning.
            assertEquals(setOf("hold-1"), gateway.live)
            assertEquals(2, gateway.calls)
        }

    private suspend fun run(
        gateway: Gateway,
        member: PetichStep<OrderPayload>,
    ): PetichResult {
        val engine =
            PetichEngine(
                repository = RowRepository(),
                definitions =
                    listOf(petichDefinition<OrderPayload>("order") { step("hold-funds", member) }),
            )
        return engine.process(order("saga-1"))
    }

    private fun order(id: String) =
        Petich(
            id = id,
            type = "order",
            status = PetichStatus.DRAFT,
            payload = OrderPayload(1_000),
        )
}
