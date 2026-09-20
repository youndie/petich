package io.github.youndie.petich

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B-51: a fault outside a member's own call used to write FAILED and undo nothing.
 *
 * The member succeeded — its effect happened — and only the write that records its progress failed.
 * `FAILED` is terminal, and `SuspendedPetichSweeper`'s stranded queue looks at `PROCESSING` and
 * `COMPENSATING`, so the saga was left holding whatever it held with nothing in the system able to
 * find it. The README meanwhile tells a reader that `FAILED` means what ran was undone.
 */
class FailedMeansUndoneTest {
    private data class OrderPayload(
        val sku: String,
    ) : PetichPayload()

    private class Acts(
        private val name: String,
        private val log: MutableList<String>,
    ) : PetichStep<OrderPayload> {
        override suspend fun execute(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            log.add("do:$name")
        }

        override suspend fun compensate(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            log.add("undo:$name")
        }
    }

    /** Throws a plain fault on the Nth write — a transient storage error, not a version conflict. */
    private class FlakyRepository(
        private val failOnWrite: Int,
        private val keepFailing: Boolean = false,
    ) : PetichRepository {
        var row: Petich? = null
        private var writes = 0

        override suspend fun findById(id: String): Petich? = row?.takeIf { it.id == id }

        override suspend fun saveOrGet(petich: Petich): Petich {
            val existing = row
            if (existing != null && existing.id == petich.id) return existing
            row = petich
            return petich
        }

        override suspend fun update(petich: Petich): Boolean {
            writes++
            if (writes == failOnWrite || (keepFailing && writes >= failOnWrite)) {
                throw RuntimeException("the database went away")
            }
            val existing = row ?: return false
            if (petich.version != existing.version + 1) return false
            row = petich
            return true
        }
    }

    @Test
    fun `a write that fails after a step acted rolls that step back`() =
        runBlocking {
            val log = mutableListOf<String>()
            // Write 1 commits `reserve`; write 2 is `charge`'s and throws.
            val repository = FlakyRepository(failOnWrite = 2)
            val result = engine(repository, log).process(order())

            assertTrue(result !is PetichResult.Success, "$result")
            // BOTH are undone: `charge` because its effect landed and only the bookkeeping failed,
            // and `reserve` because the rollback walks back from there.
            assertEquals(listOf("do:reserve", "do:charge", "undo:charge", "undo:reserve"), log)
            assertEquals(PetichStatus.FAILED, repository.row?.status, "and it still ends FAILED")
        }

    @Test
    fun `the saga is left for the sweeper when the rollback cannot be written either`() =
        runBlocking {
            val log = mutableListOf<String>()
            // The store is gone for good: the rollback's own first write cannot land.
            val repository = FlakyRepository(failOnWrite = 2, keepFailing = true)
            val result = engine(repository, log).process(order())

            assertTrue(result is PetichResult.SystemFailure, "$result")
            assertTrue(
                result.details.contains("rollback could not be started"),
                "the caller has to learn that nothing was undone: ${result.details}",
            )
            // NOT written FAILED. PROCESSING is what the stranded queue re-drives; FAILED is the
            // status nothing looks at, and writing it from a process that cannot reach the store is
            // the unrecoverable lie of the two.
            assertTrue(
                repository.row?.status != PetichStatus.FAILED,
                "a terminal status here is the trap this item is about: ${repository.row?.status}",
            )
        }

    @Test
    fun `a saga that has only run checks is still ended without undoing anything`() =
        runBlocking {
            val log = mutableListOf<String>()
            val repository = FlakyRepository(failOnWrite = 1)
            val engine =
                PetichEngine(
                    repository = repository,
                    definitions =
                        listOf(
                            petichDefinition<OrderPayload>("order") {
                                validate("limits", Decides())
                                step("reserve", Acts("reserve", log))
                            },
                        ),
                )

            engine.process(order())

            // The rollback needs no test of whether an effect happened: a check has no compensate,
            // so walking back over one does nothing. That is why there is no predicate in `unwind`.
            assertEquals(emptyList(), log.filter { it.startsWith("undo:") })
        }

    private class Decides : PetichCheck<OrderPayload> {
        override suspend fun check(
            ctx: PetichCheckContext,
            payload: OrderPayload,
        ) = Unit
    }

    private fun engine(
        repository: PetichRepository,
        log: MutableList<String>,
    ) = PetichEngine(
        repository = repository,
        definitions =
            listOf(
                petichDefinition<OrderPayload>("order") {
                    step("reserve", Acts("reserve", log))
                    step("charge", Acts("charge", log))
                },
            ),
    )

    private fun order() =
        Petich(
            id = "saga-1",
            type = "order",
            status = PetichStatus.DRAFT,
            payload = OrderPayload("sku-1"),
        )
}
