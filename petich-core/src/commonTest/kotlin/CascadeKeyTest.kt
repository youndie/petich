package io.github.youndie.petich

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B-47: the key is issued per member and a cascade acts many times at one position.
 *
 * `resuspendFor` exists because a member can be the cascade (B-37). Handed the plain key on every
 * attempt, its second call arrives under the first call's name — and the far side this test models
 * is the ordinary one: it **deduplicates**, so the second ask is answered with the first ask's
 * result and nothing anywhere says the refusal happened.
 *
 * The two members below differ in one argument, and that is the point: the defect is left standing
 * as a live control rather than described.
 */
class CascadeKeyTest {
    private data class RidePayload(
        val rideId: String,
    ) : PetichPayload()

    /** A far side that answers a repeated key with the first answer, which is what such a key means. */
    private class OfferBoard {
        val offered: MutableList<String> = mutableListOf()
        private val byKey: MutableMap<String, String> = mutableMapOf()

        /** Returns the driver the offer actually went to — the first one, if the key is a repeat. */
        fun offer(
            key: String,
            driverId: String,
        ): String =
            byKey.getOrPut(key) {
                offered.add(driverId)
                driverId
            }

        fun withdraw(key: String) {
            byKey.remove(key)?.let { offered.remove(it) }
        }

        fun outstanding(): List<String> = offered.toList()
    }

    private fun saga(id: String) =
        Petich(
            id = id,
            type = "ride",
            status = PetichStatus.PROCESSING,
            payload = RidePayload(id),
        )

    @Test
    fun `a cascade on the plain key offers the second ride to the first driver`() {
        val board = OfferBoard()
        val probe = PetichMemberProbe(saga("ride-1"), stepKey = "offer")

        // The whole cascade as a member would write it, following the rule without its second half.
        listOf("driver-1", "driver-2", "driver-3").forEach { driver ->
            board.offer(probe.idempotencyKey, driver)
        }

        // THE DEFECT, kept live. Two candidates were asked and neither was reached; the board still
        // shows the first, and every call returned a plausible answer.
        assertEquals(listOf("driver-1"), board.outstanding())
    }

    @Test
    fun `a cascade on a discriminated key reaches every candidate`() {
        val board = OfferBoard()
        val probe = PetichMemberProbe(saga("ride-1"), stepKey = "offer")

        listOf("driver-1", "driver-2", "driver-3").forEach { driver ->
            board.offer(probe.idempotencyKey(driver), driver)
        }

        assertEquals(listOf("driver-1", "driver-2", "driver-3"), board.outstanding())
    }

    @Test
    fun `a rollback that withdraws only the last sub-key leaves the rest outstanding`() {
        val board = OfferBoard()
        val probe = PetichMemberProbe(saga("ride-1"), stepKey = "offer")
        val asked = listOf("driver-1", "driver-2", "driver-3")
        asked.forEach { board.offer(probe.idempotencyKey(it), it) }

        // The other half of the rule, and the mistake it names: cancelling by the discriminator the
        // member happens to be holding when it fails.
        board.withdraw(probe.idempotencyKey(asked.last()))

        assertEquals(listOf("driver-1", "driver-2"), board.outstanding())
    }

    @Test
    fun `a rollback that withdraws every sub-key leaves nothing`() {
        val board = OfferBoard()
        val probe = PetichMemberProbe(saga("ride-1"), stepKey = "offer")
        val asked = listOf("driver-1", "driver-2", "driver-3")
        asked.forEach { board.offer(probe.idempotencyKey(it), it) }

        asked.forEach { board.withdraw(probe.idempotencyKey(it)) }

        assertEquals(emptyList(), board.outstanding())
    }

    @Test
    fun `a sub-key is stable across passes and distinct per discriminator`() {
        val forward = PetichMemberProbe(saga("ride-1"), stepKey = "offer")
        val rollback =
            PetichMemberProbe(
                saga("ride-1").copy(status = PetichStatus.COMPENSATING, version = 7),
                stepKey = "offer",
            )

        assertEquals(forward.idempotencyKey("driver-2"), rollback.idempotencyKey("driver-2"))
        assertEquals("ride-1:offer:driver-2", forward.idempotencyKey("driver-2"))
        assertTrue(forward.idempotencyKey("driver-2") != forward.idempotencyKey("driver-3"))
        // And a sub-key is not the plain key, so a member cannot cancel a cascade by asking for the
        // undiscriminated one.
        assertTrue(forward.idempotencyKey("driver-2") != forward.idempotencyKey)
    }
}
