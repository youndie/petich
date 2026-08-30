package ru.workinprogress.petich

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

    private fun petich(id: String = "1") =
        Petich(
            id = id,
            type = "test",
            currentPhase = PetichPhase.ENRICHMENT,
            status = PetichStatus.PROCESSING,
            payload = TestPayload(),
        )

    // ---- configurability -----------------------------------------------------------------------

    @Test
    fun `a phase timeout comes from the config, not from the default table`() =
        runBlocking {
            val slow =
                object : PetichInterceptor<TestPayload> {
                    override val phase = PetichPhase.ENRICHMENT

                    override fun supports(payload: PetichPayload) = payload is TestPayload

                    override suspend fun intercept(
                        petich: Petich,
                        payload: TestPayload,
                    ): InterceptorResult {
                        // Longer than the shortened timeout but three times shorter than the
                        // default of 1000 ms: had the config been ignored, the interceptor would
                        // have finished in time.
                        delay(300)
                        return InterceptorResult.Proceed()
                    }

                    override suspend fun compensate(
                        petich: Petich,
                        payload: TestPayload,
                    ) = Unit
                }

            val repository = RecordingRepository()
            val engine =
                PetichEngine(
                    listOf(slow),
                    repository,
                    config = PetichEngineConfig(phaseTimeoutsMs = mapOf(PetichPhase.ENRICHMENT to 50)),
                )

            val result = engine.process(petich("short-timeout"))

            assertTrue(result is PetichResult.SystemFailure, "the shortened timeout did not fire: $result")
        }

    @Test
    fun `the compensation timeout is configured separately from the forward pass`() =
        runBlocking {
            var compensationInterrupted = false

            val hanging =
                object : PetichInterceptor<TestPayload> {
                    override val phase = PetichPhase.ENRICHMENT

                    override fun supports(payload: PetichPayload) = payload is TestPayload

                    override suspend fun intercept(
                        petich: Petich,
                        payload: TestPayload,
                    ) = InterceptorResult.Proceed()

                    override suspend fun compensate(
                        petich: Petich,
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
                object : PetichInterceptor<TestPayload> {
                    override val phase = PetichPhase.VALIDATION

                    override fun supports(payload: PetichPayload) = payload is TestPayload

                    override suspend fun intercept(
                        petich: Petich,
                        payload: TestPayload,
                    ) = InterceptorResult.Compensate("rollback")

                    override suspend fun compensate(
                        petich: Petich,
                        payload: TestPayload,
                    ) = Unit
                }

            var handledFailure: Exception? = null
            val handler =
                object : CompensationFailureHandler {
                    override suspend fun handle(
                        e: Exception,
                        petich: Petich,
                        interceptor: PetichInterceptor<*>,
                    ) {
                        handledFailure = e
                    }
                }

            val engine =
                PetichEngine(
                    listOf(hanging, failing),
                    RecordingRepository(),
                    handler,
                    PetichEngineConfig(compensationTimeoutsMs = mapOf(PetichPhase.ENRICHMENT to 100)),
                )

            engine.process(petich("compensation-timeout"))

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
                object : PetichInterceptor<TestPayload> {
                    override val phase = PetichPhase.ENRICHMENT

                    override fun supports(payload: PetichPayload) = payload is TestPayload

                    override suspend fun intercept(
                        petich: Petich,
                        payload: TestPayload,
                    ): InterceptorResult {
                        active++
                        if (active > maxActive) maxActive = active
                        delay(50)
                        active--
                        return InterceptorResult.Proceed()
                    }

                    override suspend fun compensate(
                        petich: Petich,
                        payload: TestPayload,
                    ) = Unit
                }

            val engine = PetichEngine(listOf(overlapping), StatelessRepository())

            // Releasing the lock by reference count is the very change that could break mutual
            // exclusion: drop the mutex while a second call is waiting on it and two passes enter
            // one petich at once.
            withContext(Dispatchers.Default) {
                repeat(4) { launch { engine.process(petich("same-id")) } }
            }

            assertEquals(1, maxActive, "one petich was processed concurrently")
            assertEquals(0, engine.activeLockCount, "the lock was not released after completion")
        }

    // ---- diagnostics ---------------------------------------------------------------------------

    @Test
    fun `an incorrect supports yields a message naming the interceptor`() =
        runBlocking {
            val lying =
                object : PetichInterceptor<OtherPayload> {
                    override val phase = PetichPhase.ENRICHMENT

                    override fun supports(payload: PetichPayload) = true

                    override suspend fun intercept(
                        petich: Petich,
                        payload: OtherPayload,
                    ) = InterceptorResult.Proceed()

                    override suspend fun compensate(
                        petich: Petich,
                        payload: OtherPayload,
                    ) = Unit
                }

            val engine = PetichEngine(listOf(lying), RecordingRepository())
            val result = engine.process(petich("lying"))

            assertTrue(result is PetichResult.SystemFailure, "expected SystemFailure, got $result")
            // The log used to carry an anonymous ClassCastException, from which there was no way
            // to tell which interceptor had lied.
            val details = result.details
            assertTrue(details.contains("supports()"), "the diagnostic does not mention supports(): $details")
            assertTrue(
                details.contains("TestPayload"),
                "the diagnostic does not mention the payload received: $details",
            )
        }
}
