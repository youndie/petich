package io.github.youndie.petich

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * B-21: a saga's position is an index into a list assembled at runtime, and the row stores nothing
 * else. A deploy that adds, removes or re-prioritises a step in the same or an earlier phase
 * silently re-points every suspended saga at a DIFFERENT step, and the rollback with it.
 */
class ChainFingerprintTest {
    data class OrderPayload(
        val sku: String,
    ) : PetichPayload()

    class Step(
        override val stepKey: String,
        private val log: MutableList<String>,
        override val priority: Int,
        private val suspendHere: Boolean = false,
    ) : PetichInterceptor<OrderPayload> {
        override val phase = PetichPhase.EXECUTION

        override fun supports(payload: PetichPayload) = payload is OrderPayload

        override suspend fun intercept(
            petich: Petich,
            payload: OrderPayload,
        ): InterceptorResult {
            log.add("do:$stepKey")
            return if (suspendHere) {
                InterceptorResult.Suspend(requiredAction = "CONFIRM")
            } else {
                InterceptorResult.Proceed()
            }
        }

        override suspend fun compensate(
            petich: Petich,
            payload: OrderPayload,
        ) {
            log.add("undo:$stepKey")
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

    private fun petich(id: String) =
        Petich(
            id = id,
            type = "order",
            currentPhase = PetichPhase.EXECUTION,
            status = PetichStatus.PROCESSING,
            payload = OrderPayload("sku-1"),
        )

    private fun engine(
        repository: PetichRepository,
        vararg steps: PetichInterceptor<*>,
    ) = PetichEngine(steps.toList(), repository)

    /** Runs a saga up to its suspension and returns the row as storage holds it. */
    private suspend fun suspendedSaga(
        repository: RowRepository,
        log: MutableList<String>,
    ): Petich {
        val first =
            engine(
                repository,
                Step("reserve", log, priority = 10),
                Step("confirm", log, priority = 5, suspendHere = true),
                Step("ship", log, priority = 1),
            )
        val result = first.process(petich("p-1"))
        assertTrue(result is PetichResult.ActionRequired, "expected a suspension: $result")
        return repository.row!!
    }

    @Test
    fun `a step inserted before the saga's position stops it instead of moving it`() =
        runBlocking {
            val repository = RowRepository()
            val log = mutableListOf<String>()
            suspendedSaga(repository, log)
            val ranBefore = log.toList()

            // The deploy: a step that runs FIRST. The stored index now points one step to the left
            // of what it meant, and nothing in the row says so.
            val afterDeploy =
                engine(
                    repository,
                    Step("audit", log, priority = 20),
                    Step("reserve", log, priority = 10),
                    Step("confirm", log, priority = 5, suspendHere = true),
                    Step("ship", log, priority = 1),
                )

            val result = afterDeploy.process(repository.row!!)

            assertTrue(result is PetichResult.SystemFailure, "expected a refusal: $result")
            assertTrue(
                result.details.contains("chain changed"),
                "the refusal has to say what is wrong: ${result.details}",
            )
            assertEquals(ranBefore, log, "and nothing may run while the position is ambiguous")
        }

    @Test
    fun `a step appended after the saga's position does not stop it`() =
        runBlocking {
            val repository = RowRepository()
            val log = mutableListOf<String>()
            suspendedSaga(repository, log)

            // The ordinary release. A fingerprint over the WHOLE chain would refuse every saga in
            // flight here, and a guard that fires on a normal deploy is switched off in a week.
            val afterDeploy =
                engine(
                    repository,
                    Step("reserve", log, priority = 10),
                    Step("confirm", log, priority = 5, suspendHere = true),
                    Step("ship", log, priority = 1),
                    Step("notify", log, priority = 0),
                )

            val result = afterDeploy.process(repository.row!!)

            assertTrue(result is PetichResult.Success, "the saga should have carried on: $result")
            assertTrue(log.contains("do:notify"), "including through the newly added step: $log")
        }

    @Test
    fun `a saga written before fingerprints existed is never refused`() =
        runBlocking {
            val repository = RowRepository()
            val log = mutableListOf<String>()
            val stored = suspendedSaga(repository, log)

            // What an upgrade finds in the table: a row with no fingerprint, and a chain that has
            // also changed. It must still run, or the release stops every saga in flight.
            repository.row = stored.copy(chainFingerprint = null)

            val result =
                engine(
                    repository,
                    Step("audit", log, priority = 20),
                    Step("reserve", log, priority = 10),
                    Step("confirm", log, priority = 5, suspendHere = true),
                    Step("ship", log, priority = 1),
                ).process(repository.row!!)

            // NOT refused — and what it does instead is the old behaviour in full view: the stored
            // index now points at `confirm` rather than `ship`, so the saga asks for a confirmation
            // it already had. That is the price of a nullable column, and it is the right price:
            // refusing here would stop every saga in flight at the upgrade that introduces the
            // guard, which is the one release where nothing is yet protected by it.
            assertTrue(
                result !is PetichResult.SystemFailure,
                "a null fingerprint carries no opinion and must not refuse: $result",
            )
            assertTrue(result is PetichResult.ActionRequired, "expected the old behaviour: $result")
            assertEquals(
                "do:confirm",
                log.last(),
                "the old behaviour is landing on the wrong step in silence, which is what the guard is for",
            )
        }

    @Test
    fun `steps of equal priority run in a stated order and not in registration order`() =
        runBlocking {
            val forwards = mutableListOf<String>()
            val backwards = mutableListOf<String>()

            engine(
                RowRepository(),
                Step("bravo", forwards, priority = 0),
                Step("alpha", forwards, priority = 0),
            ).process(petich("p-forwards"))

            engine(
                RowRepository(),
                Step("alpha", backwards, priority = 0),
                Step("bravo", backwards, priority = 0),
            ).process(petich("p-backwards"))

            assertEquals(listOf("do:alpha", "do:bravo"), forwards)
            assertEquals(
                forwards,
                backwards,
                "the order of two steps must not depend on the order a container handed them over",
            )
        }

    @Test
    fun `requireDistinctPriorities names the steps that collide`() =
        runBlocking {
            val log = mutableListOf<String>()
            val engine =
                PetichEngine(
                    listOf(Step("alpha", log, priority = 0), Step("bravo", log, priority = 0)),
                    RowRepository(),
                    config = PetichEngineConfig(requireDistinctPriorities = true),
                )

            val failure = assertFailsWith<IllegalArgumentException> { engine.describeChain(OrderPayload("sku-1")) }

            assertTrue(
                failure.message?.contains("alpha") == true && failure.message?.contains("bravo") == true,
                "the refusal has to name both: ${failure.message}",
            )
        }

    @Test
    fun `the resolved chain can be printed for review`() =
        runBlocking {
            val log = mutableListOf<String>()
            val dump =
                engine(
                    RowRepository(),
                    Step("confirm", log, priority = 5),
                    Step("reserve", log, priority = 10),
                ).describeChain(OrderPayload("sku-1"))

            assertTrue(
                dump.contains("EXECUTION: reserve(10) -> confirm(5)"),
                "the dump has to show the order a person would otherwise reconstruct: $dump",
            )
            assertTrue(dump.contains("ENRICHMENT: -"), "and the phases nothing applies to: $dump")
        }
}
