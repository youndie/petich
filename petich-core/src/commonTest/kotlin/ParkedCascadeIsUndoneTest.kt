package io.github.youndie.petich

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * B-53: what `resuspendFor` writes down, and the two things it used to leave out.
 *
 * The rollback's starting point was **derived** from `currentInterceptorIndex`, on the assumption
 * that the number means "one past the member to undo". That is true of a row `suspendFor` wrote and
 * false of one `resuspendFor` wrote, where it is the member itself — so a cascade waiting for a
 * driver's answer was left out of its own rollback and never withdrew the offers it had made.
 *
 * And `resuspendFor` did not write the phase, so a re-asking member that is the first of its phase
 * left the row pointing at the PREVIOUS phase with `index = 0`, and every resume re-ran all of it.
 */
class ParkedCascadeIsUndoneTest {
    private data class OrderPayload(
        val rideId: String,
    ) : PetichPayload()

    /** Offers to one candidate after another, at its own position — the shape B-37 made a verb for. */
    private class Cascade(
        private val log: MutableList<String>,
    ) : PetichStep<OrderPayload> {
        override suspend fun execute(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            log.add("offer")
            ctx.resuspendFor("DRIVER_ANSWER", ttl = 5.minutes)
        }

        override suspend fun compensate(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            log.add("withdraw")
        }
    }

    private class Decides(
        private val log: MutableList<String>,
    ) : PetichCheck<OrderPayload> {
        override suspend fun check(
            ctx: PetichCheckContext,
            payload: OrderPayload,
        ) {
            log.add("check")
        }
    }

    private class Holds(
        private val log: MutableList<String>,
    ) : PetichStep<OrderPayload> {
        override suspend fun execute(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            log.add("hold")
        }

        override suspend fun compensate(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            log.add("release")
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
    fun `a cascade whose deadline passes withdraws what it offered`() =
        runBlocking {
            val log = mutableListOf<String>()
            val repository = RowRepository()
            var now = 1_000_000L
            val engine =
                PetichEngine(
                    repository = repository,
                    clock = PetichClock { now },
                    definitions =
                        listOf(
                            petichDefinition<OrderPayload>("order") {
                                step("hold", Holds(log))
                                step("offer", Cascade(log))
                            },
                        ),
                )

            val parked = engine.process(order())
            assertTrue(parked is PetichResult.ActionRequired, "$parked")
            assertEquals(listOf("hold", "offer"), log)

            now += 6.minutes.inWholeMilliseconds
            engine.expireSuspended(checkNotNull(repository.row).id)

            // THE ACCEPTANCE. `withdraw` is the cascade undoing its own offers, which is what
            // CascadeKeyTest says such a member owes — and what this path never asked it for.
            assertEquals(listOf("hold", "offer", "withdraw", "release"), log)
        }

    @Test
    fun `a check before a re-asking member runs once across a resume`() =
        runBlocking {
            val log = mutableListOf<String>()
            val repository = RowRepository()
            val engine =
                PetichEngine(
                    repository = repository,
                    definitions =
                        listOf(
                            petichDefinition<OrderPayload>("order") {
                                validate("limits", Decides(log))
                                step("offer", Cascade(log))
                            },
                        ),
                )

            engine.process(order())
            engine.process(checkNotNull(repository.row).copy(resumePayload = null))

            // The re-asking member is the first of EXECUTION, so before B-53 the row said VALIDATION
            // with index 0 and the resume re-ran the check. For a check that asks for a one-time
            // code, that is a second code sent to a person.
            assertEquals(1, log.count { it == "check" }, "the check ran more than once: $log")
        }

    private fun order() =
        Petich(
            id = "ride-1",
            type = "order",
            status = PetichStatus.DRAFT,
            payload = OrderPayload("ride-1"),
        )
}
