package io.github.youndie.petich

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Engine configurability, plus the properties that must not be lost to a refactor: mutual
// exclusion per petich, and a legible diagnostic for an incorrect supports().
class EngineConfigTest {
    data class TestPayload(
        val data: String = "x",
    ) : PetichPayload()

    data class OtherPayload(
        val data: String = "y",
    ) : PetichPayload()

    // Stores nothing: saveOrGet returns whatever it was given. Needed where every call must run
    // the scenario afresh rather than hit a terminal status left by the previous one.
    class StatelessRepository : PetichRepository {
        override suspend fun findById(id: String): Petich? = null

        override suspend fun saveOrGet(petich: Petich): Petich = petich

        override suspend fun update(petich: Petich): Boolean = true
    }

    class RecordingRepository : PetichRepository {
        val stored = mutableMapOf<String, Petich>()

        override suspend fun findById(id: String): Petich? = stored[id]

        override suspend fun saveOrGet(petich: Petich): Petich = stored.getOrPut(petich.id) { petich }

        override suspend fun update(petich: Petich): Boolean {
            stored[petich.id] = petich
            return true
        }
    }

    private fun row(id: String = "1") =
        Petich(
            id = id,
            type = "test",
            currentPhase = PetichPhase.ENRICHMENT,
            status = PetichStatus.PROCESSING,
            payload = TestPayload(),
        )

    // ---- configurability -----------------------------------------------------------------------

    @Test
    fun `a phase timeout comes from the config rather than from the default table`() =
        runBlocking {
            // A CHECK, because that is all it ever was: it delays and returns. ENRICHMENT takes
            // checks only, which is the model saying that a member with nothing to undo is what
            // belongs there.
            val slow =
                object : PetichCheck<TestPayload> {
                    override suspend fun check(
                        ctx: PetichCheckContext,
                        payload: TestPayload,
                    ) {
                        // Longer than the shortened timeout but three times shorter than the
                        // default of 1000 ms: had the config been ignored, the member would have
                        // finished in time.
                        delay(300)
                    }
                }

            val repository = RecordingRepository()
            val engine =
                PetichEngine(
                    repository = repository,
                    config = PetichEngineConfig(phaseTimeoutsMs = mapOf(PetichPhase.ENRICHMENT to 50)),
                    definitions = listOf(petichDefinition<TestPayload>("test") { enrich("slow", slow) }),
                )

            val result = engine.process(row("short-timeout"))

            assertTrue(result is PetichResult.SystemFailure, "the shortened timeout did not fire: $result")
        }

    @Test
    fun `the compensation timeout is configured separately from the forward pass`() =
        runBlocking {
            var compensationInterrupted = false

            val hanging =
                object : PetichStep<TestPayload> {
                    override suspend fun execute(
                        ctx: PetichStepContext,
                        payload: TestPayload,
                    ) = Unit

                    override suspend fun compensate(
                        ctx: PetichStepContext,
                        payload: TestPayload,
                    ) {
                        try {
                            delay(5000)
                        } finally {
                            compensationInterrupted = true
                        }
                    }
                }

            val failing =
                object : PetichStep<TestPayload> {
                    override suspend fun execute(
                        ctx: PetichStepContext,
                        payload: TestPayload,
                    ) = ctx.fail("rollback")

                    override suspend fun compensate(
                        ctx: PetichStepContext,
                        payload: TestPayload,
                    ) = Unit
                }

            var handledFailure: Exception? = null
            val handler =
                object : CompensationFailureHandler {
                    override suspend fun handle(
                        e: Exception,
                        petich: Petich,
                        stepKey: String,
                    ) {
                        handledFailure = e
                    }
                }

            val engine =
                PetichEngine(
                    repository = RecordingRepository(),
                    compensationFailureHandler = handler,
                    // EXECUTION, and the phase is the model rather than convenience: a compensation
                    // timeout needs a member that HAS a compensation, and every phase before this
                    // one takes checks, which have none (B-39). What is under test — a hung rollback
                    // is interrupted and reaches the failure handler — is untouched by which phase
                    // it happens in.
                    config = PetichEngineConfig(compensationTimeoutsMs = mapOf(PetichPhase.EXECUTION to 100)),
                    definitions =
                        listOf(
                            petichDefinition<TestPayload>("test") {
                                step("hangs", hanging)
                                step("fails", failing)
                            },
                        ),
                )

            engine.process(row("compensation-timeout"))

            assertTrue(compensationInterrupted, "a hung compensation was not interrupted")
            // An interrupted rollback is NOT a successful rollback: it must reach the
            // compensation failure handler, or the system will conclude everything was undone
            // when it was not.
            assertTrue(handledFailure != null, "the compensation failure never reached CompensationFailureHandler")
        }

    @Test
    fun `the config rejects meaningless values`() {
        val invalid =
            listOf<() -> PetichEngineConfig>(
                { PetichEngineConfig(maxProcessAttempts = 0) },
                { PetichEngineConfig(maxStateUpdateAttempts = 0) },
                { PetichEngineConfig(retryJitterMs = -1) },
            )

        invalid.forEach { build ->
            val failed =
                try {
                    build()
                    false
                } catch (e: IllegalArgumentException) {
                    true
                }
            assertTrue(failed, "the config accepted an invalid value")
        }
    }

    // ---- mutual exclusion ----------------------------------------------------------------------

    @Test
    fun `two passes over one petich never run at the same time`() =
        runBlocking {
            var active = 0
            var maxActive = 0

            val overlapping =
                object : PetichStep<TestPayload> {
                    override suspend fun execute(
                        ctx: PetichStepContext,
                        payload: TestPayload,
                    ) {
                        active++
                        if (active > maxActive) maxActive = active
                        delay(50)
                        active--
                        return
                    }

                    override suspend fun compensate(
                        ctx: PetichStepContext,
                        payload: TestPayload,
                    ) = Unit
                }

            val engine =
                PetichEngine(
                    repository = StatelessRepository(),
                    definitions = listOf(petichDefinition<TestPayload>("test") { step("overlaps", overlapping) }),
                )

            // Releasing the lock by reference count is the very change that could break mutual
            // exclusion: drop the mutex while a second call is waiting on it and two passes enter
            // one petich at once.
            withContext(Dispatchers.Default) {
                repeat(4) { launch { engine.process(row("same-id")) } }
            }

            assertEquals(1, maxActive, "one petich was processed concurrently")
            assertEquals(0, engine.activeLockCount, "the lock was not released after completion")
        }

    // ---- diagnostics ---------------------------------------------------------------------------

    /**
     * The successor to "an incorrect `supports` yields a message naming the interceptor".
     *
     * `supports()` is gone, and with it the family of mistakes where one member claimed somebody
     * else's payload. What is left is the single declared cast: a definition written for one
     * payload, registered under a type whose rows carry another. Without a message that is a bare
     * ClassCastException naming two classes and nothing about the saga — which is exactly the
     * diagnostic the old model had and the removal nearly took with it.
     */
    @Test
    fun `a definition handed a payload it is not declared for names the member`() =
        runBlocking {
            val mismatched =
                object : PetichStep<OtherPayload> {
                    override suspend fun execute(
                        ctx: PetichStepContext,
                        payload: OtherPayload,
                    ) = Unit

                    override suspend fun compensate(
                        ctx: PetichStepContext,
                        payload: OtherPayload,
                    ) = Unit
                }

            val engine =
                PetichEngine(
                    repository = RecordingRepository(),
                    definitions = listOf(petichDefinition<OtherPayload>("test") { step("mismatched", mismatched) }),
                )
            val result = engine.process(row("mismatched"))

            assertTrue(result is PetichResult.SystemFailure, "expected SystemFailure, got $result")
            val details = result.details
            assertTrue(details.contains("mismatched"), "the diagnostic does not name the member: $details")
            assertTrue(details.contains("TestPayload"), "nor what actually arrived: $details")
            assertTrue(details.contains("test"), "nor the saga type it was registered under: $details")
        }
}
