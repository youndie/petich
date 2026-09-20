package io.github.youndie.petich

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B-20: a refusal undoes what ran, and is still a refusal.
 *
 * `Reject` wrote REJECTED and stopped. That is right for a validation that refuses before anything
 * has happened and is silent theft after a member has touched the outside world — and the member
 * cannot tell the two apart, because whether an EARLIER one had an effect is knowledge about
 * somebody else's members. Every refusal in this suite happened to sit before EXECUTION, so the
 * suite was green and said nothing about it.
 */
class RejectRollsBackTest {
    data class OrderPayload(
        val sku: String,
    ) : PetichPayload()

    class Step(
        private val name: String,
        private val log: MutableList<String>,
        private val rejectWith: String? = null,
    ) : PetichStep<OrderPayload> {
        override suspend fun execute(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            log.add("do:$name")
            rejectWith?.let { ctx.reject(it) }
        }

        override suspend fun compensate(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            log.add("undo:$name")
        }
    }

    class RowRepository : PetichRepository {
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

    private fun row(id: String) =
        Petich(
            id = id,
            type = "order",
            status = PetichStatus.PROCESSING,
            payload = OrderPayload("sku-1"),
        )

    @Test
    fun `a refusal after a step has acted undoes it and is still a refusal`() =
        runBlocking {
            val log = mutableListOf<String>()
            val repository = RowRepository()
            val engine =
                PetichEngine(
                    repository = repository,
                    definitions =
                        listOf(
                            // Two members of one phase, and their order is these two lines rather
                            // than priority 10 against priority 5.
                            petich<OrderPayload>("order") {
                                step("reserve", Step("reserve", log))
                                step("limits", Step("limits", log, rejectWith = "over the limit"))
                            },
                        ),
                )

            val result = engine.process(row("p-rejected"))

            assertTrue(result is PetichResult.Error, "a refusal is not a fault: $result")
            assertEquals("over the limit", result.reason, "the reason is the step's, not the engine's")
            assertEquals(
                listOf("do:reserve", "do:limits", "undo:reserve"),
                log,
                "the reservation has to come back; the step that declined to act has nothing to undo",
            )
            assertEquals(
                PetichStatus.REJECTED,
                repository.row?.status,
                "a business refusal must not read as a server fault on a replay",
            )
        }

    @Test
    fun `a refusal before anything has run undoes nothing`() =
        runBlocking {
            val log = mutableListOf<String>()
            val repository = RowRepository()
            val engine =
                PetichEngine(
                    repository = repository,
                    definitions =
                        listOf(
                            // A STEP rather than a check, because this refusal comes from a member
                            // that COULD have acted — which is what the case is about. Since B-39
                            // that means EXECUTION: the phases before it take checks, which have no
                            // undo to leave unused.
                            petich<OrderPayload>("order") {
                                step("validate", Step("validate", log, rejectWith = "malformed"))
                                step("reserve", Step("reserve", log))
                            },
                        ),
                )

            val result = engine.process(row("p-early"))

            assertTrue(result is PetichResult.Error, "expected a refusal: $result")
            assertEquals(
                listOf("do:validate"),
                log,
                "nothing ran, so nothing is undone - and the step after the refusal never starts",
            )
            assertEquals(PetichStatus.REJECTED, repository.row?.status)
        }

    @Test
    fun `a refused saga replays as refused`() =
        runBlocking {
            val log = mutableListOf<String>()
            val repository = RowRepository()
            val engine =
                PetichEngine(
                    repository = repository,
                    definitions =
                        listOf(
                            // Two members of one phase, and their order is these two lines rather
                            // than priority 10 against priority 5.
                            petich<OrderPayload>("order") {
                                step("reserve", Step("reserve", log))
                                step("limits", Step("limits", log, rejectWith = "over the limit"))
                            },
                        ),
                )

            engine.process(row("p-replay"))
            val before = log.toList()
            val replay = engine.process(repository.row!!)

            assertTrue(replay is PetichResult.Error, "expected an error: $replay")
            assertTrue(
                replay.reason.contains("rejected"),
                "a repeat under the same id has to say it was refused: ${replay.reason}",
            )
            assertEquals(before, log, "and must not undo anything a second time")
        }
}
