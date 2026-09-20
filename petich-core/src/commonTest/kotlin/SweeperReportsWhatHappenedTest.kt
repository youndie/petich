package io.github.youndie.petich

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * B-55: three things the sweeper said that were not what happened.
 *
 * All three are about the worker's own reporting rather than about any saga. They share a shape:
 * from outside, a sweeper doing nothing and a sweeper failing look identical, so everything it says
 * about itself is load-bearing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SweeperReportsWhatHappenedTest {
    private data class OrderPayload(
        val sku: String,
    ) : PetichPayload()

    private class Step(
        private val log: MutableList<String>,
    ) : PetichStep<OrderPayload> {
        override suspend fun execute(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            log.add("do:${ctx.petich.id}")
        }

        override suspend fun compensate(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) = Unit
    }

    /** Stamps every write from the clock it is given, exactly as the two SQL stores do. */
    private class Rows(
        private val clock: PetichClock,
    ) : ExpiringPetichRepository {
        private val rows = mutableMapOf<String, Petich>()
        private val stamps = mutableMapOf<String, Long>()

        /** The failure the item's second half is about: one queue's index is unavailable. */
        var expiredQueryFails: Boolean = false

        /** A query that never answers, so the worker can be cancelled while it is inside one. */
        var expiredQueryHangs: Boolean = false

        /** Counted rather than logged: it is the witness that the worker is still going round. */
        var stuckQueries: Int = 0

        fun seed(
            petich: Petich,
            stampedAt: Long,
        ) {
            rows[petich.id] = petich
            stamps[petich.id] = stampedAt
        }

        fun row(id: String): Petich? = rows[id]

        override suspend fun findById(id: String): Petich? = rows[id]

        override suspend fun saveOrGet(petich: Petich): Petich {
            val existing = rows[petich.id]
            if (existing != null) return existing
            rows[petich.id] = petich
            stamps[petich.id] = clock.nowEpochMs()
            return petich
        }

        override suspend fun update(petich: Petich): Boolean {
            val existing = rows[petich.id] ?: return false
            if (petich.version != existing.version + 1) return false
            rows[petich.id] = petich
            stamps[petich.id] = clock.nowEpochMs()
            return true
        }

        override suspend fun findExpired(
            nowEpochMs: Long,
            limit: Int,
        ): List<Petich> {
            if (expiredQueryFails) throw IllegalStateException("the expiry index is being rebuilt")
            if (expiredQueryHangs) awaitCancellation()
            return emptyList()
        }

        override suspend fun findStuck(
            status: PetichStatus,
            notTouchedSinceEpochMs: Long,
            limit: Int,
        ): List<Petich> {
            stuckQueries++
            return rows.values
                .filter { it.status == status }
                .filter { (stamps[it.id] ?: 0L) < notTouchedSinceEpochMs }
                .take(limit)
        }
    }

    private fun stranded(
        id: String,
        fingerprint: String? = null,
    ) = Petich(
        id = id,
        type = "order",
        currentPhase = PetichPhase.EXECUTION,
        status = PetichStatus.PROCESSING,
        payload = OrderPayload("sku-1"),
        chainFingerprint = fingerprint,
    )

    private fun sweeperOver(
        repository: Rows,
        clock: PetichClock,
        log: MutableList<String>,
        revived: MutableList<String>,
        failures: MutableList<Pair<String, String>>,
        onFailureThrows: Boolean = false,
    ): SuspendedPetichSweeper {
        val engine =
            PetichEngine(
                repository = repository,
                clock = clock,
                definitions = listOf(petichDefinition<OrderPayload>("order") { step("act", Step(log)) }),
            )
        return SuspendedPetichSweeper(
            repository = repository,
            engine = engine,
            clock = clock,
            pollInterval = 30.seconds,
            stuckAfter = 5.minutes,
            onRevived = { revived.add(it) },
            onWorkerFailure = { stage, cause ->
                failures.add(stage to (cause.message ?: ""))
                if (onFailureThrows) throw IllegalStateException("the log is down too")
            },
        )
    }

    @Test
    fun `a saga the engine refuses is reported and not counted as a rescue`() =
        runBlocking {
            val now = 10.minutes.inWholeMilliseconds
            val clock = PetichClock { now }
            val repository = Rows(clock)
            val log = mutableListOf<String>()
            val revived = mutableListOf<String>()
            val failures = mutableListOf<Pair<String, String>>()

            // The fingerprint of a chain this build does not have: the deploy that wrote it is gone.
            repository.seed(stranded("p-refused", fingerprint = "from-another-deploy"), stampedAt = 0L)

            val swept = sweeperOver(repository, clock, log, revived, failures).sweepStuck()

            // THE ACCEPTANCE. This saga comes back on every single pass — nothing is written for it,
            // deliberately — so counting it was not one wrong number but one per poll, for ever.
            assertEquals(0, swept)
            assertTrue(revived.isEmpty(), "a refusal was counted as a revival: $revived")
            assertEquals(listOf("stuck:p-refused"), failures.map { it.first })
            assertTrue(
                failures.single().second.contains("the interceptor chain changed"),
                "the reason was not carried: ${failures.single().second}",
            )
            assertTrue(log.isEmpty(), "nothing should have run: $log")
            // Not even claimed: the claim is a write, and writing would restamp the row.
            assertEquals(0L, repository.row("p-refused")?.version)
        }

    @Test
    fun `a failing expiry queue does not stop the stranded one`() =
        runTest {
            val now = 10.minutes.inWholeMilliseconds
            val clock = PetichClock { now }
            val repository = Rows(clock)
            val log = mutableListOf<String>()
            val revived = mutableListOf<String>()
            val failures = mutableListOf<Pair<String, String>>()

            repository.expiredQueryFails = true
            repository.seed(stranded("p-stranded"), stampedAt = 0L)

            val job = sweeperOver(repository, clock, log, revived, failures).start(this)
            runCurrent()
            job.cancel()

            // THE ACCEPTANCE. The two queues read different tables and answer different questions;
            // one `try` around both meant an unavailable expiry index stopped the stranded queue as
            // well, for as long as it lasted.
            assertEquals(listOf("do:p-stranded"), log, "the stranded queue was not swept")
            assertEquals(listOf("p-stranded"), revived)
            // And the failure names the queue rather than the pass.
            assertEquals(listOf("sweep"), failures.map { it.first })
        }

    @Test
    fun `a reporter that throws does not end the worker`() =
        runTest {
            val now = 10.minutes.inWholeMilliseconds
            val clock = PetichClock { now }
            val repository = Rows(clock)
            val failures = mutableListOf<Pair<String, String>>()

            repository.expiredQueryFails = true

            val job =
                sweeperOver(
                    repository,
                    clock,
                    mutableListOf(),
                    mutableListOf(),
                    failures,
                    onFailureThrows = true,
                ).start(this)
            runCurrent()
            val afterFirstPass = repository.stuckQueries
            advanceTimeBy(31.seconds)
            runCurrent()
            val afterSecondPass = repository.stuckQueries
            val alive = job.isActive
            job.cancel()

            // THE ACCEPTANCE. The reporter is called from the `catch` that keeps the worker going,
            // so a throw from it used to leave the `while` loop — the sweeper stopped for the life
            // of the process, and the only thing that would have said so is the reporter itself.
            assertTrue(alive, "the worker was ended by its own reporter")
            // Two queries per pass: one for PROCESSING and one for COMPENSATING.
            assertEquals(2, afterFirstPass)
            assertEquals(4, afterSecondPass, "the second pass never happened")
            assertEquals(listOf("sweep", "sweep"), failures.map { it.first })
        }

    @Test
    fun `a worker cancelled inside a query stops rather than reporting it`() =
        runTest {
            // The one throw that must get past both guards: it is the caller going away, not a
            // queue failing. Cancelled INSIDE a query on purpose — cancelled while waiting out the
            // poll interval never reaches either `catch`, so a test that did that would pass
            // whatever the catches said.
            val now = 10.minutes.inWholeMilliseconds
            val clock = PetichClock { now }
            val repository = Rows(clock)
            val failures = mutableListOf<Pair<String, String>>()

            repository.expiredQueryHangs = true

            val job =
                sweeperOver(repository, clock, mutableListOf(), mutableListOf(), failures).start(this)
            runCurrent()
            assertTrue(job.isActive, "the worker did not reach the query")
            job.cancel(CancellationException("shutting down"))
            runCurrent()

            assertTrue(job.isCancelled)
            assertTrue(failures.isEmpty(), "cancellation was reported as a worker failure: $failures")
        }
}
