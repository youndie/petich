package io.github.youndie.petich

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

/**
 * The context a consumer gets instead of writing one (B-38).
 *
 * Both consumers had written the same eleven methods by hand, and both broke when the interface
 * grew — each time as a compile error at their next version bump rather than where the method was
 * added. What is shipped is not a second implementation but **the** one: the engine runs every
 * member through this class, so a test asserting against it asserts against what production does.
 *
 * These cases are that claim, stated from the outside: each one calls a member directly and reads
 * back what it asked for.
 */
class MemberProbeTest {
    @Serializable
    private data class OrderPayload(
        val sku: String,
    ) : PetichPayload()

    @Serializable
    @SerialName("held")
    private data class Held(
        val amount: Long,
    ) : PetichStepRecord()

    private val saga =
        Petich(
            id = "p-1",
            type = "order",
            status = PetichStatus.PROCESSING,
            payload = OrderPayload("sku-1"),
        )

    private fun event(id: String) =
        object : OutboxEvent {
            override val id = id
            override val type = "test.event"
            override val payload = "{}"
        }

    @Test
    fun `a member that acts leaves what it asked for readable`() =
        runBlocking {
            val member =
                object : PetichStep<OrderPayload> {
                    override suspend fun execute(
                        ctx: PetichStepContext,
                        payload: OrderPayload,
                    ) {
                        ctx.record(Held(500))
                        ctx.emit(event("held"))
                        ctx.enrich(SimpleEnrichedPayload(mapOf("hold" to "h-1")))
                    }

                    override suspend fun compensate(
                        ctx: PetichStepContext,
                        payload: OrderPayload,
                    ) = Unit
                }

            val probe = PetichMemberProbe(saga, stepKey = "hold")
            member.execute(probe, OrderPayload("sku-1"))

            assertEquals(Held(500), probe.record)
            assertEquals(listOf("held"), probe.events.map { it.id })
            assertEquals("h-1", (probe.enrichment as SimpleEnrichedPayload).data["hold"])
            assertEquals(PetichMemberProbe.Decision.Proceeded, probe.decision)
        }

    /**
     * The case both consumers wrote their double for: a compensation asked whether its own member
     * happened. With nothing recorded it must undo nothing, which is what a rollback of a member
     * that never ran looks like from inside.
     */
    @Test
    fun `a compensation reads the record its own member left and its absence`() =
        runBlocking {
            val undone = mutableListOf<String>()
            val member =
                object : PetichStep<OrderPayload> {
                    override suspend fun execute(
                        ctx: PetichStepContext,
                        payload: OrderPayload,
                    ) = ctx.record(Held(500))

                    override suspend fun compensate(
                        ctx: PetichStepContext,
                        payload: OrderPayload,
                    ) {
                        val held = ctx.recordedValue() as? Held ?: return
                        undone += "release:${held.amount}"
                    }
                }

            member.compensate(PetichMemberProbe(saga, stepKey = "hold"), OrderPayload("sku-1"))
            assertEquals(emptyList(), undone, "nothing was recorded, so nothing is given back")

            val ran = PetichMemberProbe(saga, stepKey = "hold")
            member.execute(ran, OrderPayload("sku-1"))
            member.compensate(ran, OrderPayload("sku-1"))
            assertEquals(listOf("release:500"), undone)
        }

    /**
     * A record the SAGA carries, rather than one written in this call.
     *
     * This is the shape a real rollback has: the member wrote its record on an earlier pass, the row
     * came back from storage, and the undo reads it from there. A double that only remembered what
     * happened in front of it would answer null here and pass a test that production fails.
     */
    @Test
    fun `a record stored on the saga is what a fresh context reads back`() {
        val stored = saga.copy(stepRecords = mapOf("hold" to Held(700)))

        assertEquals(Held(700), PetichMemberProbe(stored, stepKey = "hold").recordedValue())
        assertNull(PetichMemberProbe(stored, stepKey = "charge").recordedValue(), "another member's key")
    }

    @Test
    fun `every decision a member can take is readable as itself`() =
        runBlocking {
            fun probeAfter(act: (PetichStepContext) -> Unit): PetichMemberProbe =
                PetichMemberProbe(saga).also { act(it) }

            assertEquals(
                PetichMemberProbe.Decision.Suspended("CONFIRM", 5.minutes, again = false),
                probeAfter { it.suspendFor("CONFIRM", 5.minutes) }.decision,
            )
            assertEquals(
                PetichMemberProbe.Decision.Suspended("ANSWER", null, again = true),
                probeAfter { it.resuspendFor("ANSWER") }.decision,
                "a re-ask is not a suspension, and the difference is what a hold is taken once by",
            )
            assertEquals(
                PetichMemberProbe.Decision.Refused("out of stock"),
                probeAfter { it.reject("out of stock") }.decision,
            )
            assertEquals(
                PetichMemberProbe.Decision.Failed("the courier is down"),
                probeAfter { it.fail("the courier is down") }.decision,
            )
        }
}
