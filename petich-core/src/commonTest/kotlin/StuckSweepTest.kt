package io.github.youndie.petich

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * B-19, second half: somebody finally calls [PetichEngine.process] for a saga whose process died.
 *
 * The engine has always resumed an interrupted pass and an interrupted rollback correctly — that is
 * what the 17 writes per saga are for. Until this, nothing in the library ever asked it to.
 */
class StuckSweepTest {
    data class OrderPayload(
        val sku: String,
    ) : PetichPayload()

    /** A member that exists only so a definition is not empty; it is never reached. */
    class Inert : PetichStep<OrderPayload> {
        override suspend fun execute(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) = Unit

        override suspend fun compensate(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) = Unit
    }

    class Step(
        private val log: MutableList<String>,
    ) : PetichInterceptor<OrderPayload> {
        override val phase = PetichPhase.EXECUTION

        override fun supports(payload: PetichPayload) = payload is OrderPayload

        override suspend fun intercept(
            petich: Petich,
            payload: OrderPayload,
        ): InterceptorResult {
            log.add("do:${petich.id}")
            return InterceptorResult.Proceed()
        }

        override suspend fun compensate(
            petich: Petich,
            payload: OrderPayload,
        ) {
            log.add("undo:${petich.id}")
        }
    }

    /** Stamps every write from the clock it is given, exactly as the two SQL stores do. */
    class StampingRepository(
        private val clock: PetichClock,
    ) : ExpiringPetichRepository {
        private val rows = mutableMapOf<String, Petich>()
        private val stamps = mutableMapOf<String, Long>()

        fun seed(
            petich: Petich,
            stampedAt: Long,
        ) {
            rows[petich.id] = petich
            stamps[petich.id] = stampedAt
        }

        fun status(id: String): PetichStatus? = rows[id]?.status

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
        ): List<Petich> = emptyList()

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

    private fun stranded(id: String) =
        Petich(
            id = id,
            type = "order",
            currentPhase = PetichPhase.EXECUTION,
            status = PetichStatus.PROCESSING,
            payload = OrderPayload("sku-1"),
        )

    private fun sweeperOver(
        repository: StampingRepository,
        log: MutableList<String>,
        clock: PetichClock,
        stuckAfter: kotlin.time.Duration?,
        revived: MutableList<String>,
    ): SuspendedPetichSweeper {
        val engine = PetichEngine(listOf(Step(log)), repository, clock = clock)
        return SuspendedPetichSweeper(
            repository = repository,
            engine = engine,
            clock = clock,
            stuckAfter = stuckAfter,
            onRevived = { revived.add(it) },
        )
    }

    @Test
    fun `a saga abandoned mid-pass is carried to the end`() =
        runBlocking {
            val now = 10.minutes.inWholeMilliseconds
            val clock = PetichClock { now }
            val repository = StampingRepository(clock)
            val log = mutableListOf<String>()
            val revived = mutableListOf<String>()

            // Written nine minutes ago by a process that is not coming back.
            repository.seed(stranded("p-stranded"), stampedAt = now - 9.minutes.inWholeMilliseconds)

            val swept = sweeperOver(repository, log, clock, stuckAfter = 5.minutes, revived = revived).sweepStuck()

            assertEquals(1, swept)
            assertEquals(listOf("p-stranded"), revived)
            assertEquals(listOf("do:p-stranded"), log, "the pass was continued, not restarted from nothing")
            assertEquals(PetichStatus.COMPLETED, repository.status("p-stranded"))
        }

    @Test
    fun `a saga written more recently than the threshold is left alone`() =
        runBlocking {
            val now = 10.minutes.inWholeMilliseconds
            val clock = PetichClock { now }
            val repository = StampingRepository(clock)
            val log = mutableListOf<String>()
            val revived = mutableListOf<String>()

            // One minute old: this is what a live instance working slowly looks like from outside,
            // and there is no lease that could tell the difference.
            repository.seed(stranded("p-busy"), stampedAt = now - 1.minutes.inWholeMilliseconds)

            val swept = sweeperOver(repository, log, clock, stuckAfter = 5.minutes, revived = revived).sweepStuck()

            assertEquals(0, swept)
            assertTrue(log.isEmpty(), "nothing should have been re-driven: $log")
            assertEquals(PetichStatus.PROCESSING, repository.status("p-busy"))
        }

    @Test
    fun `the stuck half stays off until a threshold is chosen`() =
        runBlocking {
            val now = 10.minutes.inWholeMilliseconds
            val clock = PetichClock { now }
            val repository = StampingRepository(clock)
            val log = mutableListOf<String>()
            val revived = mutableListOf<String>()

            repository.seed(stranded("p-old"), stampedAt = 0L)

            val swept = sweeperOver(repository, log, clock, stuckAfter = null, revived = revived).sweepStuck()

            assertEquals(0, swept, "no threshold means the application has not asked for this")
            assertTrue(log.isEmpty(), "and nothing is re-driven behind its back: $log")
        }

    @Test
    fun `a saga whose type has no engine is reported and not touched`() =
        runBlocking {
            val now = 10.minutes.inWholeMilliseconds
            val clock = PetichClock { now }
            val repository = StampingRepository(clock)
            val unowned = mutableListOf<String>()
            repository.seed(stranded("p-orphan"), stampedAt = 0L)

            val swept =
                SuspendedPetichSweeper(
                    repository = repository,
                    // AN ENGINE THAT KNOWS A DIFFERENT TYPE. There is no mapping to leave an
                    // entry out of any more, so a saga is unowned exactly when its type has no
                    // definition here (B-31); the saga below is an `order`.
                    engine =
                        PetichEngine(
                            repository = repository,
                            clock = clock,
                            definitions = listOf(petich<OrderPayload>("something-else") { step("x", Inert()) }),
                        ),
                    clock = clock,
                    stuckAfter = 5.minutes,
                    onUnknownType = { unowned.add(it.id) },
                ).sweepStuck()

            assertEquals(0, swept)
            assertEquals(listOf("p-orphan"), unowned, "an unregistered type must not be swept at random")
            assertEquals(PetichStatus.PROCESSING, repository.status("p-orphan"))
        }
}
