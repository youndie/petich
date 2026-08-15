package ru.workinprogress.petich

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// A controllable clock instead of real time: a deadline test that sleeps out the deadline is a
// test that either takes a minute or lies.
private class TtlTestClock(
    var nowMs: Long = 1_700_000_000_000,
) : PetichClock {
    override fun nowEpochMs(): Long = nowMs

    fun advance(ms: Long) {
        nowMs += ms
    }
}

private class TtlOtpResume(
    val code: String,
) : ResumePayload()

private data class TtlPayload(
    val data: String = "x",
) : PetichPayload()

// Suspends on the first pass and proceeds on resume — the same behaviour as a real
// confirmation interceptor.
private class TtlSuspendingInterceptor(
    override val phase: PetichPhase = PetichPhase.AUTHORIZATION,
    private val ttl: kotlin.time.Duration? = null,
    private val resuspendForever: Boolean = false,
) : PetichInterceptor<TtlPayload> {
    var compensated = 0

    override fun supports(payload: PetichPayload) = true

    override suspend fun intercept(
        petich: Petich,
        payload: TtlPayload,
    ): InterceptorResult =
        when {
            resuspendForever -> InterceptorResult.Resuspend("CONFIRM", ttl = ttl)
            petich.resumePayload != null -> InterceptorResult.Proceed()
            else -> InterceptorResult.Suspend("CONFIRM", ttl = ttl)
        }

    override suspend fun compensate(
        petich: Petich,
        payload: TtlPayload,
    ) {
        compensated++
    }
}

private class TtlRepository : ExpiringPetichRepository {
    val stored = mutableMapOf<String, Petich>()

    override suspend fun findById(id: String): Petich? = stored[id]

    override suspend fun saveOrGet(petich: Petich): Petich = stored.getOrPut(petich.id) { petich }

    override suspend fun update(petich: Petich): Boolean {
        stored[petich.id] = petich
        return true
    }

    override suspend fun findExpired(
        nowEpochMs: Long,
        limit: Int,
    ): List<Petich> =
        stored.values
            .filter { it.status == PetichStatus.PENDING_SIGNATURE }
            .filter { (it.suspendedUntilEpochMs ?: Long.MAX_VALUE) <= nowEpochMs }
            .take(limit)
}

private fun petich(id: String = "p-1") =
    Petich(id = id, type = "test", status = PetichStatus.DRAFT, payload = TtlPayload())

private fun engineWith(
    repository: PetichRepository,
    interceptor: PetichInterceptor<*>,
    clock: PetichClock,
    defaultTtl: kotlin.time.Duration? = null,
) = PetichEngine(
    interceptors = listOf(interceptor),
    repository = repository,
    config = PetichEngineConfig(defaultSuspendTtl = defaultTtl),
    clock = clock,
)

class SuspendDeadlineTest {
    @Test
    fun `suspending stamps a deadline from the configured default ttl`() =
        runBlocking {
            val clock = TtlTestClock()
            val repository = TtlRepository()
            engineWith(repository, TtlSuspendingInterceptor(), clock, defaultTtl = 5.minutes).process(petich())

            val stored = repository.stored.getValue("p-1")
            assertEquals(PetichStatus.PENDING_SIGNATURE, stored.status)
            assertEquals(clock.nowMs + 5.minutes.inWholeMilliseconds, stored.suspendedUntilEpochMs)
        }

    // A step's own deadline outranks the blanket one: typing a one-time code and approving a
    // long-running request live on different time scales.
    @Test
    fun `the interceptor's own ttl wins over the engine default`() =
        runBlocking {
            val clock = TtlTestClock()
            val repository = TtlRepository()
            engineWith(repository, TtlSuspendingInterceptor(ttl = 30.seconds), clock, defaultTtl = 5.minutes)
                .process(petich())

            assertEquals(
                clock.nowMs + 30.seconds.inWholeMilliseconds,
                repository.stored.getValue("p-1").suspendedUntilEpochMs,
            )
        }

    // The default value changes no existing behaviour — the same convention as every other field
    // of PetichEngineConfig.
    @Test
    fun `with no ttl configured a suspended petich gets no deadline at all`() =
        runBlocking {
            val repository = TtlRepository()
            engineWith(repository, TtlSuspendingInterceptor(), TtlTestClock()).process(petich())

            assertEquals(PetichStatus.PENDING_SIGNATURE, repository.stored.getValue("p-1").status)
            assertNull(repository.stored.getValue("p-1").suspendedUntilEpochMs)
        }

    // Otherwise the sweeper would pick up, on a stale deadline, a petich that has already moved on.
    @Test
    fun `the deadline is cleared once the petich proceeds`() =
        runBlocking {
            val clock = TtlTestClock()
            val repository = TtlRepository()
            val engine = engineWith(repository, TtlSuspendingInterceptor(), clock, defaultTtl = 5.minutes)
            engine.process(petich())
            assertNotNull(repository.stored.getValue("p-1").suspendedUntilEpochMs)

            engine.process(repository.stored.getValue("p-1").copy(resumePayload = TtlOtpResume("0000")))

            assertNull(repository.stored.getValue("p-1").suspendedUntilEpochMs)
        }

    // Every new round of waiting gets a fresh deadline instead of living out the previous one.
    @Test
    fun `a resuspend restarts the deadline`() =
        runBlocking {
            val clock = TtlTestClock()
            val repository = TtlRepository()
            val engine =
                engineWith(repository, TtlSuspendingInterceptor(resuspendForever = true), clock, defaultTtl = 5.minutes)
            engine.process(petich())
            val first = repository.stored.getValue("p-1").suspendedUntilEpochMs

            clock.advance(60_000)
            engine.process(repository.stored.getValue("p-1").copy(resumePayload = TtlOtpResume("bad")))

            val second = repository.stored.getValue("p-1").suspendedUntilEpochMs
            assertEquals((first ?: 0) + 60_000, second)
        }
}

class ExpireSuspendedTest {
    @Test
    fun `an expired petich is compensated and ends terminally`() =
        runBlocking {
            val clock = TtlTestClock()
            val repository = TtlRepository()
            val interceptor = TtlSuspendingInterceptor()
            val engine = engineWith(repository, interceptor, clock, defaultTtl = 5.minutes)
            engine.process(petich())

            clock.advance(5.minutes.inWholeMilliseconds + 1)
            val result = engine.expireSuspended("p-1")

            assertIs<ExpireResult.Expired>(result)
            assertEquals(PetichStatus.FAILED, repository.stored.getValue("p-1").status)
        }

    // Until the deadline passes, hands off: otherwise the sweeper would roll back live scenarios.
    @Test
    fun `a petich whose deadline has not passed is left alone`() =
        runBlocking {
            val clock = TtlTestClock()
            val repository = TtlRepository()
            val engine = engineWith(repository, TtlSuspendingInterceptor(), clock, defaultTtl = 5.minutes)
            engine.process(petich())

            clock.advance(1.minutes.inWholeMilliseconds)

            assertEquals(ExpireResult.NotExpiredYet, engine.expireSuspended("p-1"))
            assertEquals(PetichStatus.PENDING_SIGNATURE, repository.stored.getValue("p-1").status)
        }

    // The race "the client answered at the exact moment of expiry": the sweeper arrives second and
    // must see that the petich is no longer waiting. Checked inside the engine's lock, not against
    // the query results.
    @Test
    fun `a petich that resumed just before the sweep is not rolled back`() =
        runBlocking {
            val clock = TtlTestClock()
            val repository = TtlRepository()
            val interceptor = TtlSuspendingInterceptor()
            val engine = engineWith(repository, interceptor, clock, defaultTtl = 5.minutes)
            engine.process(petich())
            clock.advance(5.minutes.inWholeMilliseconds + 1)

            // The client made it: the petich moved on and completed.
            engine.process(repository.stored.getValue("p-1").copy(resumePayload = TtlOtpResume("0000")))
            val statusAfterResume = repository.stored.getValue("p-1").status

            val result = engine.expireSuspended("p-1")

            assertIs<ExpireResult.NotSuspended>(result)
            assertEquals(statusAfterResume, repository.stored.getValue("p-1").status)
            assertEquals(0, interceptor.compensated)
        }

    @Test
    fun `a petich with no deadline is never expired`() =
        runBlocking {
            val clock = TtlTestClock()
            val repository = TtlRepository()
            engineWith(repository, TtlSuspendingInterceptor(), clock).process(petich())

            clock.advance(365L * 24 * 60 * 60 * 1000)

            assertEquals(ExpireResult.NotExpiredYet, engine(repository, clock).expireSuspended("p-1"))
            assertEquals(PetichStatus.PENDING_SIGNATURE, repository.stored.getValue("p-1").status)
        }

    @Test
    fun `an unknown petich is reported as not found`() =
        runBlocking {
            val engine = engine(TtlRepository(), TtlTestClock())

            assertEquals(ExpireResult.NotFound, engine.expireSuspended("nope"))
        }

    // Compensating an expired petich is an ordinary saga rollback: interceptors that already ran
    // get their compensate call.
    @Test
    fun `expiry rolls back the interceptors that already ran`() =
        runBlocking {
            val clock = TtlTestClock()
            val repository = TtlRepository()
            val executed = RecordingProceed()
            val suspending = TtlSuspendingInterceptor(phase = PetichPhase.AUTHORIZATION)
            val engine =
                PetichEngine(
                    interceptors = listOf(executed, suspending),
                    repository = repository,
                    config = PetichEngineConfig(defaultSuspendTtl = 5.minutes),
                    clock = clock,
                )
            engine.process(petich())
            clock.advance(5.minutes.inWholeMilliseconds + 1)

            engine.expireSuspended("p-1")

            assertEquals(1, executed.compensated, "an executed saga step was not rolled back")
        }

    private fun engine(
        repository: PetichRepository,
        clock: PetichClock,
    ) = engineWith(repository, TtlSuspendingInterceptor(), clock, defaultTtl = 5.minutes)
}

// A separate interceptor that simply proceeds and counts rollbacks, needed to check that expiry
// rolls back the steps ALREADY EXECUTED, not merely the suspended one.
private class RecordingProceed(
    override val phase: PetichPhase = PetichPhase.ENRICHMENT,
) : PetichInterceptor<TtlPayload> {
    var compensated = 0

    override fun supports(payload: PetichPayload) = true

    override suspend fun intercept(
        petich: Petich,
        payload: TtlPayload,
    ): InterceptorResult = InterceptorResult.Proceed()

    override suspend fun compensate(
        petich: Petich,
        payload: TtlPayload,
    ) {
        compensated++
    }
}

class SuspendedPetichSweeperTest {
    @Test
    fun `the sweeper compensates every petich past its deadline`() =
        runBlocking {
            val clock = TtlTestClock()
            val repository = TtlRepository()
            val engine = engineWith(repository, TtlSuspendingInterceptor(), clock, defaultTtl = 5.minutes)
            engine.process(petich("p-1"))
            engine.process(petich("p-2"))
            clock.advance(5.minutes.inWholeMilliseconds + 1)

            val expiredIds = mutableListOf<String>()
            val swept = SuspendedPetichSweeper(repository, { engine }, clock, onExpired = { expiredIds += it }).sweep()

            assertEquals(2, swept)
            assertEquals(setOf("p-1", "p-2"), expiredIds.toSet())
            assertTrue(repository.stored.values.all { it.status == PetichStatus.FAILED })
        }

    @Test
    fun `the sweeper leaves petiches whose deadline has not passed`() =
        runBlocking {
            val clock = TtlTestClock()
            val repository = TtlRepository()
            val engine = engineWith(repository, TtlSuspendingInterceptor(), clock, defaultTtl = 5.minutes)
            engine.process(petich("p-1"))

            assertEquals(0, SuspendedPetichSweeper(repository, { engine }, clock).sweep())
            assertEquals(PetichStatus.PENDING_SIGNATURE, repository.stored.getValue("p-1").status)
        }

    // A petich of someone else's type must not be rolled back by whichever engine comes to hand:
    // it has a different interceptor list, and the wrong compensations would run.
    @Test
    fun `a petich with no owning engine is skipped, not compensated by another`() =
        runBlocking {
            val clock = TtlTestClock()
            val repository = TtlRepository()
            val interceptor = TtlSuspendingInterceptor()
            val engine = engineWith(repository, interceptor, clock, defaultTtl = 5.minutes)
            engine.process(petich("p-1"))
            clock.advance(5.minutes.inWholeMilliseconds + 1)

            val skipped = mutableListOf<String>()
            val swept =
                SuspendedPetichSweeper(
                    repository,
                    engineFor = { null },
                    clock = clock,
                    onUnowned = { skipped += it.id },
                ).sweep()

            assertEquals(0, swept)
            assertEquals(listOf("p-1"), skipped)
            assertEquals(PetichStatus.PENDING_SIGNATURE, repository.stored.getValue("p-1").status)
            assertEquals(0, interceptor.compensated)
        }

    // One petich failing to roll back must not deprive the rest of the batch of their sweep.
    @Test
    fun `one failing petich does not abort the batch`() =
        runBlocking {
            val clock = TtlTestClock()
            val repository = TtlRepository()
            val engine = engineWith(repository, TtlSuspendingInterceptor(), clock, defaultTtl = 5.minutes)
            engine.process(petich("p-1"))
            engine.process(petich("p-2"))
            clock.advance(5.minutes.inWholeMilliseconds + 1)

            // The onExpired handler throws on the first petich; the sweep must still reach the
            // second.
            var seen = 0
            val sweeper =
                SuspendedPetichSweeper(
                    repository,
                    { engine },
                    clock,
                    onExpired = {
                        seen++
                        if (seen == 1) error("notification failed")
                    },
                )

            sweeper.sweep()

            assertEquals(2, seen, "the second petich was not processed after a failure on the first")
            assertTrue(repository.stored.values.all { it.status == PetichStatus.FAILED })
        }
}
