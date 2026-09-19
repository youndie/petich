package io.github.youndie.petich

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * B-28: the engine runs a definition, and the outcomes a member records mean what B-20 decided.
 *
 * `ctx.reject` and `ctx.fail` both roll back what ran — that is B-20 — and differ in the name the
 * saga ends under. A check has nothing to undo and cannot sit after a step, so a refusal from one is
 * always a refusal before anything happened.
 */
class DefinitionEngineTest {
    data class OrderPayload(
        val sku: String,
    ) : PetichPayload()

    private class Log {
        val entries: MutableList<String> = mutableListOf()
    }

    private class Decides(
        private val name: String,
        private val log: Log,
        private val refuse: String? = null,
    ) : PetichCheck<OrderPayload> {
        override suspend fun check(
            ctx: PetichCheckContext,
            payload: OrderPayload,
        ) {
            log.entries.add("check:$name")
            refuse?.let { return ctx.reject(it) }
        }
    }

    private class Acts(
        private val name: String,
        private val log: Log,
        private val onRun: (PetichStepContext) -> Unit = {},
    ) : PetichStep<OrderPayload> {
        override suspend fun execute(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            log.entries.add("do:$name")
            onRun(ctx)
        }

        override suspend fun compensate(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            log.entries.add("undo:$name")
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

    private fun petich(id: String) =
        Petich(
            id = id,
            type = "order",
            status = PetichStatus.PROCESSING,
            payload = OrderPayload("sku-1"),
        )

    private fun engineFor(
        definition: PetichDefinition<OrderPayload>,
        repository: PetichRepository,
    ) = PetichEngine(
        repository = repository,
        clock = PetichClock { 1_000L },
        definitions = listOf(definition),
    )

    @Test
    fun `a definition runs its members in the order it declares them`() =
        runBlocking {
            val log = Log()
            val repository = RowRepository()
            val definition =
                petich<OrderPayload>("order") {
                    validate("limits", Decides("limits", log))
                    step("reserve", Acts("reserve", log))
                    step("charge", Acts("charge", log))
                }

            val result = engineFor(definition, repository).process(petich("p-happy"))

            assertTrue(result is PetichResult.Success, "expected a completed saga: $result")
            assertEquals(listOf("check:limits", "do:reserve", "do:charge"), log.entries)
            assertEquals(PetichStatus.COMPLETED, repository.row?.status)
        }

    @Test
    fun `a check that refuses ends the saga rejected with nothing to undo`() =
        runBlocking {
            val log = Log()
            val repository = RowRepository()
            val definition =
                petich<OrderPayload>("order") {
                    validate("limits", Decides("limits", log, refuse = "over the limit"))
                    step("reserve", Acts("reserve", log))
                }

            val result = engineFor(definition, repository).process(petich("p-refused"))

            assertTrue(result is PetichResult.Error, "a refusal is not a fault: $result")
            assertEquals("over the limit", result.reason)
            assertEquals(listOf("check:limits"), log.entries, "nothing ran, so nothing is undone")
            assertEquals(PetichStatus.REJECTED, repository.row?.status)
        }

    @Test
    fun `a step that refuses undoes what ran and is still a refusal`() =
        runBlocking {
            val log = Log()
            val repository = RowRepository()
            val definition =
                petich<OrderPayload>("order") {
                    step("reserve", Acts("reserve", log))
                    step("charge", Acts("charge", log) { ctx -> ctx.reject("card declined") })
                }

            val result = engineFor(definition, repository).process(petich("p-step-refused"))

            assertTrue(result is PetichResult.Error, "expected a refusal: $result")
            assertEquals(
                listOf("do:reserve", "do:charge", "undo:reserve"),
                log.entries,
                "the reservation comes back; the member that declined has nothing of its own to undo",
            )
            assertEquals(
                PetichStatus.REJECTED,
                repository.row?.status,
                "rolling back and naming the outcome are separate questions",
            )
        }

    @Test
    fun `a step that reports a fault undoes what ran and ends failed`() =
        runBlocking {
            val log = Log()
            val repository = RowRepository()
            val definition =
                petich<OrderPayload>("order") {
                    step("reserve", Acts("reserve", log))
                    step("charge", Acts("charge", log) { ctx -> ctx.fail("the provider is down") })
                }

            engineFor(definition, repository).process(petich("p-fault"))

            assertEquals(listOf("do:reserve", "do:charge", "undo:reserve"), log.entries)
            assertEquals(PetichStatus.FAILED, repository.row?.status, "a fault is not a refusal")
        }

    /** konekt's shape: a member acts, and then waits for a human, as one member. */
    @Test
    fun `a member may act and then suspend`() =
        runBlocking {
            val log = Log()
            val repository = RowRepository()
            val definition =
                petich<OrderPayload>("order") {
                    authorize(
                        "hold-funds",
                        Acts("hold-funds", log) { ctx -> ctx.suspendFor("CONFIRM", 5.minutes) },
                    )
                    step("charge", Acts("charge", log))
                }
            val engine = engineFor(definition, repository)

            val waiting = engine.process(petich("p-wizard"))
            assertTrue(waiting is PetichResult.ActionRequired, "expected a suspension: $waiting")
            assertEquals("CONFIRM", waiting.actionType)
            assertEquals(listOf("do:hold-funds"), log.entries, "the money is held once, before the wait")
            assertEquals(PetichStatus.PENDING_SIGNATURE, repository.row?.status)

            val finished = engine.process(repository.row!!)
            assertTrue(finished is PetichResult.Success, "expected the saga to finish: $finished")
            assertEquals(
                listOf("do:hold-funds", "do:charge"),
                log.entries,
                "the member that suspended is not re-entered on resume",
            )
        }

    /** B-18 through the new vocabulary: the member whose outcome was never learned is undone too. */
    @Test
    fun `a member that throws is undone along with the ones before it`() =
        runBlocking {
            val log = Log()
            val repository = RowRepository()
            val definition =
                petich<OrderPayload>("order") {
                    step("reserve", Acts("reserve", log))
                    step("charge", Acts("charge", log) { error("the answer was lost") })
                }

            val result = engineFor(definition, repository).process(petich("p-threw"))

            assertTrue(result is PetichResult.SystemFailure, "expected a system failure: $result")
            assertEquals(
                listOf("do:reserve", "do:charge", "undo:charge", "undo:reserve"),
                log.entries,
                "the member that threw is undone first - the engine never learned whether it acted",
            )
        }
}
