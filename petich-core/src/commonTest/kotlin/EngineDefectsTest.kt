package io.github.youndie.petich

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Tests that pin down specific engine defects. Written BEFORE the fixes and failing against the
// code as it stood — otherwise there is no way to claim a defect exists rather than merely seems to.
class EngineDefectsTest {
    data class TestPayload(
        val data: String = "x",
    ) : PetichPayload()

    data class OtherPayload(
        val data: String = "y",
    ) : PetichPayload()

    // An ordinary repository with optimistic locking: an update is accepted only when the version
    // is exactly one greater than the stored one.
    open class MockRepository : PetichRepository {
        val stored = mutableMapOf<String, Petich>()
        val updateAttempts = mutableListOf<Petich>()

        override suspend fun findById(id: String): Petich? = stored[id]

        override suspend fun saveOrGet(petich: Petich): Petich = stored.getOrPut(petich.id) { petich }

        override suspend fun update(petich: Petich): Boolean {
            updateAttempts += petich
            stored[petich.id] = petich
            return true
        }
    }

    private fun row(id: String) =
        Petich(
            id = id,
            type = "test",
            currentPhase = PetichPhase.ENRICHMENT,
            status = PetichStatus.PROCESSING,
            payload = TestPayload(),
        )

    private fun proceedingInterceptor(phase: PetichPhase = PetichPhase.ENRICHMENT) =
        object : PetichStep<TestPayload> {
            override suspend fun execute(
                ctx: PetichStepContext,
                payload: TestPayload,
            ) = Unit

            override suspend fun compensate(
                ctx: PetichStepContext,
                payload: TestPayload,
            ) = Unit
        }

    // ---- 1. Leaking locks ----------------------------------------------------------------------

    @Test
    fun `the engine does not accumulate mutexes of processed petiches`() =
        runBlocking {
            val engine =
                PetichEngine(
                    repository = MockRepository(),
                    definitions = listOf(petich<TestPayload>("test") { step("proceeds", proceedingInterceptor()) }),
                )

            repeat(200) { index -> engine.process(row("petich-$index")) }

            // A mutex is needed only while processing. Left in the map forever, the map grows
            // linearly with the number of PROCESSED petiches — an OOM over a long enough run.
            assertEquals(0, engine.activeLockCount, "locks of finished petiches are not released")
        }

    // ---- 2. Compensation without a timeout -----------------------------------------------------

    // runTest rather than runBlocking: what is checked is the ratio of two TIME quantities — how
    // long the rollback hangs and where the engine must cut it off. A real clock adds nothing to
    // the meaning here but makes the check depend on machine load: on a busy host the caller
    // returns after the deadline not because the timeout failed but because the coroutine did not
    // get the CPU. That reproduced under `./gradlew build --rerun-tasks`, with the whole repository
    // compiling alongside the tests. In virtual time both the hang and the timeout are delays on
    // one scheduler, so the measurement is exact and blind to load.
    //
    // Virtual time is right here precisely because the whole processing runs in the caller's
    // coroutine — the engine does not switch dispatchers, it only uses withContext(NonCancellable).
    // Where REAL concurrent delivery between coroutines is under test, virtual time breaks the test
    // instead.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a hung compensation does not hold the caller past the phase timeout`() =
        runTest {
            val slowCompensation =
                object : PetichStep<TestPayload> {
                    override suspend fun execute(
                        ctx: PetichStepContext,
                        payload: TestPayload,
                    ) = Unit

                    override suspend fun compensate(
                        ctx: PetichStepContext,
                        payload: TestPayload,
                    ) {
                        // Simulates a hung network call inside the rollback.
                        delay(HUNG_COMPENSATION_MS)
                    }
                }

            val compensating =
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

            val engine =
                PetichEngine(
                    repository = MockRepository(),
                    definitions =
                        listOf(
                            petich<TestPayload>("test") {
                                authorize("slow-undo", slowCompensation)
                                step("fails", compensating)
                            },
                        ),
                )

            val startedAt = testScheduler.currentTime
            engine.process(row("hung-compensation"))
            val elapsed = testScheduler.currentTime - startedAt

            // The forward call is wrapped in withTimeout; the rollback was not. The caller must get
            // control back rather than wait on a hung rollback indefinitely. The bound is exactly
            // the timeout of the phase the compensation hangs in — compensation takes the same one
            // by default, see PetichEngineConfig.compensationTimeoutMs — and not half the hang
            // duration: in virtual time no slack for the scheduler is needed.
            //
            // AUTHORIZATION rather than ENRICHMENT, because that is where the hanging member now
            // sits: a compensation needs a member that HAS one, and ENRICHMENT takes checks.
            assertTrue(
                elapsed <= PetichPhase.AUTHORIZATION.timeoutMs,
                "compensation is not bounded by a timeout: the caller waited ${elapsed}ms " +
                    "against a phase timeout of ${PetichPhase.AUTHORIZATION.timeoutMs}ms",
            )
        }

    // ---- 3. A lost update result on the emergency path ------------------------------------------

    @Test
    fun `the transition to FAILED survives a version conflict`() =
        runBlocking {
            // The repository rejects the first two attempts to write FAILED — exactly what a race
            // for the version looks like — and accepts the third.
            val repository =
                object : MockRepository() {
                    var refusals = 2

                    override suspend fun update(petich: Petich): Boolean {
                        updateAttempts += petich
                        if (petich.status == PetichStatus.FAILED && refusals > 0) {
                            refusals--
                            return false
                        }
                        stored[petich.id] = petich
                        return true
                    }
                }

            // THE PROVOCATION CHANGED AND THE SUBJECT DID NOT (B-33). It used to be a `supports()`
            // that threw — called while selecting a phase's interceptors, outside the inner try
            // around the member, so it fell through to doProcess's general catch. `supports()` is
            // gone with the interceptor model, and what is under test was never it: it is
            // `failTerminally`, which every one of those paths ends in, and whether its write
            // survives losing the version race twice.
            //
            // A saga whose type no member applies to reaches it directly (#78), which is the
            // shortest honest route there is now.
            val engine = PetichEngine(repository = repository, definitions = emptyList())
            val result = engine.process(row("lost-failed"))

            assertTrue(result is PetichResult.SystemFailure, "expected SystemFailure, got $result")
            // The client got a SystemFailure, and in storage the petich must be terminal:
            // otherwise it hangs in an intermediate status forever with nobody to pick it up.
            assertEquals(
                PetichStatus.FAILED,
                repository.stored.getValue("lost-failed").status,
                "the petich stayed in an intermediate status — the update result was lost",
            )
        }

    // ---- 4. The returned petich diverges from the stored one ------------------------------------

    @Test
    fun `a successful result carries the same version that was written to storage`() =
        runBlocking {
            val repository = MockRepository()
            val engine =
                PetichEngine(
                    repository = repository,
                    definitions = listOf(petich<TestPayload>("test") { step("proceeds", proceedingInterceptor()) }),
                )

            val result = engine.process(row("version-echo"))

            assertTrue(result is PetichResult.Success, "expected Success, got $result")
            // The same class of defect already fixed in the Resuspend branch: the caller reads the
            // petich out of the result and sees something other than what the database holds.
            assertEquals(
                repository.stored.getValue("version-echo").version,
                result.petich.version,
                "the version in the result diverged from the stored one",
            )
            assertEquals(
                PetichStatus.COMPLETED,
                result.petich.status,
                "the status in the result diverged from the stored one",
            )
        }

    // ---- 5. What actually happens when a definition is declared for the wrong payload ----------

    @Test
    fun `a definition declared for another payload fails the petich rather than the process`() =
        runBlocking {
            // The successor to "supports() says yes to ANY payload". There is no supports() now;
            // the mistake that remains is a definition registered under a saga type whose rows
            // carry a payload it is not written for, and the cast at the definition's boundary is
            // the one place it can surface.
            val lying =
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

            val repository = MockRepository()
            val engine =
                PetichEngine(
                    repository = repository,
                    definitions = listOf(petich<OtherPayload>("test") { step("mismatched", lying) }),
                )

            val result = engine.process(row("mismatched-payload"))

            // Pinning the ACTUAL behaviour: the engine catches it like any other member failure and
            // fails the petich — the process stays up.
            assertTrue(result is PetichResult.SystemFailure, "expected SystemFailure, got $result")
        }

    private companion object {
        // Comfortably longer than the ENRICHMENT phase timeout (1000 ms), which is the only
        // requirement on this value. It costs no real time: the hang is measured on a virtual clock.
        const val HUNG_COMPENSATION_MS = 3000L
    }
}
