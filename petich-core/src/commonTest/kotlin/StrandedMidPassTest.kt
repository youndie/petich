package io.github.youndie.petich

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/**
 * B-65: the stuck queue asks for `PROCESSING`, and until this nothing in the engine wrote it.
 *
 * Every test of that queue seeded the row `PROCESSING` by hand, as `petich-ktor`'s create route
 * does. Both consumers create their sagas `DRAFT`, and a saga that has been resumed moves forward as
 * `PENDING_SIGNATURE` with its deadline cleared — so a process dying mid-pass left a row that matched
 * neither `findStuck` (wrong status) nor `findExpired` (no deadline), holding whatever its members
 * had already done, for ever.
 *
 * These start from what an application actually writes and let the engine write the rest.
 */
class StrandedMidPassTest {
    private data class OrderPayload(
        val sku: String,
    ) : PetichPayload()

    private class Killed : Error("the process went away")

    private class Step(
        private val name: String,
        private val log: MutableList<String>,
        private val dies: () -> Boolean = { false },
        private val parks: Boolean = false,
    ) : PetichStep<OrderPayload> {
        override suspend fun execute(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            log.add("do:$name")
            if (dies()) throw Killed()
            if (parks) ctx.suspendFor("CONFIRM")
        }

        override suspend fun compensate(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            log.add("undo:$name")
        }
    }

    /** Stamps every write, as both SQL stores do, and answers both of the sweeper's queries. */
    private class Rows : ExpiringPetichRepository {
        val rows: MutableMap<String, Petich> = mutableMapOf()
        var now: Long = 0
        private val stamps = mutableMapOf<String, Long>()

        override suspend fun findById(id: String): Petich? = rows[id]

        override suspend fun saveOrGet(petich: Petich): Petich =
            rows.getOrPut(petich.id) {
                stamps[petich.id] = now
                petich
            }

        override suspend fun update(petich: Petich): Boolean {
            val existing = rows[petich.id] ?: return false
            if (petich.version != existing.version + 1) return false
            rows[petich.id] = petich
            stamps[petich.id] = now
            return true
        }

        override suspend fun findExpired(
            nowEpochMs: Long,
            limit: Int,
        ): List<Petich> =
            rows.values.filter {
                it.status == PetichStatus.PENDING_SIGNATURE &&
                    (it.suspendedUntilEpochMs ?: Long.MAX_VALUE) <= nowEpochMs
            }

        override suspend fun findStuck(
            status: PetichStatus,
            notTouchedSinceEpochMs: Long,
            limit: Int,
        ): List<Petich> = rows.values.filter { it.status == status && (stamps[it.id] ?: 0) < notTouchedSinceEpochMs }
    }

    private fun engine(
        rows: Rows,
        body: PetichDefinitionBuilder<OrderPayload>.() -> Unit,
    ) = PetichEngine(
        repository = rows,
        clock = PetichClock { rows.now },
        definitions = listOf(petichDefinition("order", body)),
    )

    private suspend fun killed(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Killed) {
            return
        }
        throw AssertionError("the pass was supposed to die")
    }

    private suspend fun sweepLater(
        rows: Rows,
        engine: PetichEngine,
    ): Int {
        rows.now += 10.minutes.inWholeMilliseconds
        val sweeper = SuspendedPetichSweeper(rows, engine, PetichClock { rows.now }, stuckAfter = 1.minutes)
        return sweeper.sweep() + sweeper.sweepStuck()
    }

    private fun order() =
        Petich(id = "order-1", type = "order", status = PetichStatus.DRAFT, payload = OrderPayload("sku"))

    @Test
    fun `a saga created DRAFT that dies after its first step is carried on by the sweeper`() =
        runBlocking {
            val rows = Rows()
            val log = mutableListOf<String>()
            var kill = true
            val saga =
                engine(rows) {
                    step("reserve", Step("reserve", log))
                    step("charge", Step("charge", log, dies = { kill.also { kill = false } }))
                }

            killed { saga.process(order()) }
            assertEquals(1, sweepLater(rows, saga), "picked up by the sweeper: ${rows.rows["order-1"]}")

            assertEquals(PetichStatus.COMPLETED, rows.rows["order-1"]?.status)
            assertEquals(listOf("do:reserve", "do:charge", "do:charge"), log)
        }

    @Test
    fun `a saga created DRAFT that dies inside its first step is carried on by the sweeper`() =
        runBlocking {
            val rows = Rows()
            val log = mutableListOf<String>()
            var kill = true
            val saga = engine(rows) { step("reserve", Step("reserve", log, dies = { kill.also { kill = false } })) }

            killed { saga.process(order()) }
            assertEquals(1, sweepLater(rows, saga), "picked up by the sweeper: ${rows.rows["order-1"]}")

            assertEquals(PetichStatus.COMPLETED, rows.rows["order-1"]?.status)
        }

    @Test
    fun `a resumed saga that dies after moving on is carried on by the sweeper`() =
        runBlocking {
            val rows = Rows()
            val log = mutableListOf<String>()
            var kill = false
            val saga =
                engine(rows) {
                    step("hold", Step("hold", log, parks = true))
                    step("capture", Step("capture", log))
                    step("ship", Step("ship", log, dies = { kill.also { kill = false } }))
                }

            saga.process(order())
            assertEquals(PetichStatus.PENDING_SIGNATURE, rows.rows["order-1"]?.status)
            kill = true
            killed { saga.process(checkNotNull(rows.rows["order-1"]).copy(resumePayload = EmptyResume)) }
            assertEquals(1, sweepLater(rows, saga), "picked up by the sweeper: ${rows.rows["order-1"]}")

            assertEquals(PetichStatus.COMPLETED, rows.rows["order-1"]?.status)
            assertEquals(listOf("do:hold", "do:capture", "do:ship", "do:ship"), log)
        }
}

private object EmptyResume : ResumePayload()
