package io.github.youndie.petich

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Waiting for another answer at the same member, rather than for the one that moves past it.
 *
 * Settled by shashki's order saga (B-37): it offers a ride to the nearest driver and waits; a
 * decline releases that driver, offers the ride to the next, and waits again — and the next answer
 * has to land in the same member, because the member IS the cascade. `suspendFor` stores the
 * position one past the member and could not express it.
 */
class ReaskAtTheSameMemberTest {
    @Serializable
    private data class OrderPayload(
        val sku: String,
    ) : PetichPayload()

    private class Cascade(
        private val log: MutableList<String>,
        private val answers: MutableList<String>,
    ) : PetichStep<OrderPayload> {
        override suspend fun execute(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            val answer = answers.removeFirstOrNull()
            log.add("ask:${answer ?: "first"}")
            if (answers.isNotEmpty()) return ctx.resuspendFor("ANSWER", 5.minutes)
            if (answer == null) return ctx.suspendFor("ANSWER", 5.minutes)
        }

        override suspend fun compensate(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            log.add("undo:${ctx.stepKey}")
        }
    }

    private class WaitsOnce(
        private val log: MutableList<String>,
    ) : PetichStep<OrderPayload> {
        private var asked = false

        override suspend fun execute(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            log.add(if (asked) "ask:again" else "ask:first")
            if (!asked) {
                asked = true
                return ctx.suspendFor("ANSWER", 5.minutes)
            }
        }

        override suspend fun compensate(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) = Unit
    }

    private class After(
        private val log: MutableList<String>,
    ) : PetichStep<OrderPayload> {
        override suspend fun execute(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            log.add("after")
        }

        override suspend fun compensate(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) = Unit
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

    @Test
    fun `a member that re-asks keeps the next answer for itself`() =
        runBlocking {
            val log = mutableListOf<String>()
            val repository = RowRepository()
            val engine =
                PetichEngine(
                    repository = repository,
                    clock = PetichClock { 1_000L },
                    definitions =
                        listOf(
                            petich<OrderPayload>("order") {
                                step("offer", Cascade(log, mutableListOf("declined", "accepted")))
                                step("assign", After(log))
                            },
                        ),
                )

            val first = engine.process(row("p-1"))
            assertTrue(first is PetichResult.ActionRequired, "the first offer waits: $first")

            engine.process(repository.row!!)

            assertEquals(
                listOf("ask:declined", "ask:accepted", "after"),
                log,
                "the second answer landed in the member that asked, and only then did the saga move on",
            )
        }

    /**
     * The control, and without it the case above proves nothing: `suspendFor` stores the position
     * one PAST the member, so the same shape run through it does not re-enter and the second answer
     * belongs to whatever comes next. That is the behaviour money depends on — a hold taken once —
     * and it is why these are two verbs rather than a flag.
     */
    @Test
    fun `a member that suspends does not get the next answer`() =
        runBlocking {
            val log = mutableListOf<String>()
            val repository = RowRepository()
            val engine =
                PetichEngine(
                    repository = repository,
                    clock = PetichClock { 1_000L },
                    definitions =
                        listOf(
                            petich<OrderPayload>("order") {
                                step("offer", WaitsOnce(log))
                                step("assign", After(log))
                            },
                        ),
                )

            val first = engine.process(row("p-2"))
            assertTrue(first is PetichResult.ActionRequired, "it waits: $first")

            engine.process(repository.row!!)

            assertEquals(
                listOf("ask:first", "after"),
                log,
                "the member that suspended is not re-entered; the resume runs what comes after it",
            )
        }

    private fun row(id: String) =
        Petich(
            id = id,
            type = "order",
            status = PetichStatus.PROCESSING,
            payload = OrderPayload("sku-1"),
        )
}
