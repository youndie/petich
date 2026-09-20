package io.github.youndie.petich

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * B-26: the saga's own row is the arbiter, and the loser skips.
 *
 * Two replicas sweeping one table is an ordinary deployment. The per-saga mutex inside the engine is
 * per PROCESS — it keeps two coroutines apart and says nothing about the instance next door — so the
 * optimistic lock on the row is the only thing both of them can see. What these cases hold is not
 * that a claim exists but that **losing it stops the loser before any effect**: a sweeper that
 * retried a lost claim would be two rollbacks of one saga, which is what konekt's B-64 was.
 */
class SweepClaimTest {
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
            if (suspendHere) ctx.suspendFor("CONFIRM", ttl = 5.minutes)
        }

        override suspend fun compensate(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            log.add("undo:$name")
        }
    }

    /**
     * A row with an optimistic lock and the store's own stamp, plus one switch: the next write is
     * refused. That switch is how a lost race is reproduced deterministically — the alternative is
     * two threads and a hope, which is a flaky test pretending to be a concurrency one.
     */
    class RacingRepository(
        private val clock: PetichClock,
    ) : ExpiringPetichRepository {
        private val rows = mutableMapOf<String, Petich>()
        private val stamps = mutableMapOf<String, Long>()
        var refuseNextUpdate: Boolean = false

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
            if (refuseNextUpdate) {
                refuseNextUpdate = false
                return false
            }
            val existing = rows[petich.id] ?: return false
            if (petich.version != existing.version + 1) return false
            rows[petich.id] = petich
            stamps[petich.id] = clock.nowEpochMs()
            return true
        }

        override suspend fun findExpired(
            nowEpochMs: Long,
            limit: Int,
        ): List<Petich> =
            rows.values
                .filter { it.status == PetichStatus.PENDING_SIGNATURE }
                .filter { (it.suspendedUntilEpochMs ?: Long.MAX_VALUE) <= nowEpochMs }
                .take(limit)

        override suspend fun findStuck(
            status: PetichStatus,
            notTouchedSinceEpochMs: Long,
            limit: Int,
        ): List<Petich> =
            rows.values
                .filter { it.status == status }
                .filter { (stamps[it.id] ?: 0L) < notTouchedSinceEpochMs }
                .take(limit)
    }

    private fun row(
        id: String,
        status: PetichStatus = PetichStatus.PROCESSING,
    ) = Petich(
        id = id,
        type = "order",
        currentPhase = PetichPhase.EXECUTION,
        status = status,
        payload = OrderPayload("sku-1"),
    )

    // ---- the expiry queue: the claim IS the status transition ---------------------------------

    @Test
    fun `a sweeper that loses the claim on an expired saga compensates nothing`() =
        runBlocking {
            var reading = 10.minutes.inWholeMilliseconds
            val clock = PetichClock { reading }
            val repository = RacingRepository(clock)
            val log = mutableListOf<String>()
            val engine =
                PetichEngine(
                    repository = repository,
                    clock = clock,
                    definitions =
                        listOf(
                            petich<OrderPayload>("order") {
                                step("reserve", Step("reserve", log))
                                step("confirm", Step("confirm", log, suspendHere = true))
                            },
                        ),
                )

            engine.process(row("p-lost"))
            reading += 6.minutes.inWholeMilliseconds
            val ranBefore = log.toList()

            // The other replica got the transition; this one's write is refused.
            repository.refuseNextUpdate = true
            val outcome = engine.expireSuspended("p-lost")

            assertTrue(outcome is ExpireResult.Contended, "expected the claim to be lost: $outcome")
            assertEquals(
                ranBefore,
                log,
                "a sweeper that lost the claim must not compensate a single step",
            )
            assertEquals(
                PetichStatus.PENDING_SIGNATURE,
                repository.row("p-lost")?.status,
                "and must not have moved the row it failed to claim",
            )
        }

    @Test
    fun `a sweeper that wins the claim rolls the saga back once`() =
        runBlocking {
            val now = 10.minutes.inWholeMilliseconds
            var reading = now
            val clock = PetichClock { reading }
            val repository = RacingRepository(clock)
            val log = mutableListOf<String>()
            val engine =
                PetichEngine(
                    repository = repository,
                    clock = clock,
                    definitions =
                        listOf(
                            petich<OrderPayload>("order") {
                                step("reserve", Step("reserve", log))
                                step("confirm", Step("confirm", log, suspendHere = true))
                            },
                        ),
                )

            engine.process(row("p-won"))
            reading += 6.minutes.inWholeMilliseconds

            val outcome = engine.expireSuspended("p-won")

            assertTrue(outcome is ExpireResult.Expired, "expected an expiry: $outcome")
            assertEquals(
                listOf("do:reserve", "do:confirm", "undo:confirm", "undo:reserve"),
                log,
                "the rollback runs exactly once, from the step that suspended",
            )
            assertEquals(PetichStatus.FAILED, repository.row("p-won")?.status)
        }

    // ---- the stuck queue: the claim moves the row out of its own predicate ---------------------

    private fun sweeperOver(
        repository: RacingRepository,
        engine: PetichEngine,
        clock: PetichClock,
        contended: MutableList<String>,
        revived: MutableList<String>,
    ) = SuspendedPetichSweeper(
        repository = repository,
        engine = engine,
        clock = clock,
        stuckAfter = 5.minutes,
        onRevived = { revived.add(it) },
        onContended = { contended.add(it) },
    )

    @Test
    fun `a sweeper that loses the claim on a stranded saga runs no step`() =
        runBlocking {
            val now = 10.minutes.inWholeMilliseconds
            val clock = PetichClock { now }
            val repository = RacingRepository(clock)
            val log = mutableListOf<String>()
            val contended = mutableListOf<String>()
            val revived = mutableListOf<String>()
            val engine =
                PetichEngine(
                    repository = repository,
                    clock = clock,
                    definitions = listOf(petich<OrderPayload>("order") { step("reserve", Step("reserve", log)) }),
                )

            repository.seed(row("p-taken"), stampedAt = now - 9.minutes.inWholeMilliseconds)
            repository.refuseNextUpdate = true

            val swept = sweeperOver(repository, engine, clock, contended, revived).sweepStuck()

            assertEquals(0, swept)
            assertEquals(listOf("p-taken"), contended, "the loser has to be countable, not silent")
            assertTrue(revived.isEmpty(), "and must not report work it did not do")
            assertTrue(log.isEmpty(), "nothing may run before the claim is won: $log")
        }

    @Test
    fun `winning the claim takes the saga out of the query that found it`() =
        runBlocking {
            val now = 10.minutes.inWholeMilliseconds
            val clock = PetichClock { now }
            val repository = RacingRepository(clock)
            val log = mutableListOf<String>()
            val contended = mutableListOf<String>()
            val revived = mutableListOf<String>()

            // A step that suspends, so the saga stays in flight after the sweep and remains a
            // candidate for the query — the claim is what has to remove it, not the outcome.
            val engine =
                PetichEngine(
                    repository = repository,
                    clock = clock,
                    definitions =
                        listOf(
                            petich<OrderPayload>("order") {
                                step("confirm", Step("confirm", log, suspendHere = true))
                            },
                        ),
                )
            repository.seed(row("p-claimed"), stampedAt = now - 9.minutes.inWholeMilliseconds)

            val sweeper = sweeperOver(repository, engine, clock, contended, revived)
            assertEquals(1, sweeper.sweepStuck(), "the first pass takes it")
            assertEquals(listOf("p-claimed"), revived)

            val again = sweeper.sweepStuck()

            assertEquals(0, again, "the claim re-stamped the row, so the query no longer matches it")
            assertTrue(contended.isEmpty(), "and nothing had to lose a race to get that answer")
        }
}
