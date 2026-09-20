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
        private val name: String,
        private val log: MutableList<String>,
        private val suspendHere: Boolean = false,
    ) : PetichStep<OrderPayload> {
        override suspend fun execute(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            log.add("do:$name")
            if (suspendHere) ctx.suspendFor("CONFIRM")
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
            currentPhase = PetichPhase.EXECUTION,
            status = PetichStatus.PROCESSING,
            payload = OrderPayload("sku-1"),
        )

    /**
     * An engine over a chain named by its member keys, in order.
     *
     * The order used to be `priority = 20, 10, 5, 1` across four classes; it is the order of these
     * arguments now, which is the same fact with nowhere left to disagree with itself.
     */
    private fun engine(
        repository: PetichRepository,
        log: MutableList<String>,
        vararg keys: String,
        suspendAt: String? = null,
    ) = PetichEngine(
        repository = repository,
        definitions =
            listOf(
                petichDefinition<OrderPayload>("order") {
                    keys.forEach { key -> step(key, Step(key, log, suspendHere = key == suspendAt)) }
                },
            ),
    )

    /** Runs a saga up to its suspension and returns the row as storage holds it. */
    private suspend fun suspendedSaga(
        repository: RowRepository,
        log: MutableList<String>,
    ): Petich {
        val first =
            engine(repository, log, "reserve", "confirm", "ship", suspendAt = "confirm")
        val result = first.process(row("p-1"))
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
                engine(repository, log, "audit", "reserve", "confirm", "ship", suspendAt = "confirm")

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
                engine(repository, log, "reserve", "confirm", "ship", "notify", suspendAt = "confirm")

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
                engine(repository, log, "audit", "reserve", "confirm", "ship", suspendAt = "confirm")
                    .process(repository.row!!)

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

    // TWO CASES REMOVED HERE WITH THE CONCEPT THEY TESTED (B-33). One asserted that two steps of
    // equal priority ran in `stepKey` order rather than in the order a container handed them over;
    // the other asserted that `requireDistinctPriorities` named the pair that collided. A member has
    // no priority now — the order is the order of the declaration — so neither has a subject, and
    // the property the first one protected is asserted directly by `DefinitionEngineTest`'s "a
    // definition runs its members in the order it declares them". The config flag went with them:
    // its only implementation was in the chain arm this item deleted, and a flag that reads as
    // protection and does nothing is worse than no flag.

    @Test
    fun `the resolved chain can be printed for review`() =
        runBlocking {
            val log = mutableListOf<String>()
            val dump =
                engine(RowRepository(), log, "reserve", "confirm").describeChain(OrderPayload("sku-1"), "order")

            assertTrue(
                dump.contains("EXECUTION: reserve -> confirm"),
                "the dump has to show the order a person would otherwise reconstruct: $dump",
            )
            assertTrue(dump.contains("ENRICHMENT: -"), "and the phases nothing applies to: $dump")
        }
}
