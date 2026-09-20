package io.github.youndie.petich

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B-54: the two things a rollback in progress has to know about itself.
 *
 * **What it will end as.** A saga refused on business grounds rolls back towards `REJECTED`; a saga
 * that faulted rolls back towards `FAILED`. The target was held in a parameter and in nothing else,
 * so the pass that RESUMED an interrupted rollback had no way to learn it and defaulted to `FAILED`.
 * A client repeating a refused request was then told the server had broken. B-20 exists to keep
 * those two answers apart, and this was the one path that collapsed them.
 *
 * **And that it is not the second rollback of the same saga.** A replica paused longer than
 * `stuckAfter` wakes holding a petich it read before the pause, while another replica's sweeper has
 * already finished the rollback and named the outcome. It used to write `COMPENSATING` straight over
 * that terminal row and undo everything again — a second refund, a reservation released that someone
 * else has since taken.
 *
 * **What these two tests do NOT check is that the columns exist.** The repository below keeps the
 * `Petich` object whole, so a field the engine sets is a field it reads back whether or not any store
 * would have kept it. That is precisely how B-53's fix looked green while neither store had the
 * column. The claim about storage is made where it can be made: `PetichStoreConformance`'s "every
 * field lands" and "a rollback that gave up" cases now carry both fields, and every store runs them.
 */
class RollbackKnowsItsEndingTest {
    private data class OrderPayload(
        val marker: String,
    ) : PetichPayload()

    /** Dies mid-rollback: an `Error` is the one throw nothing on the path catches. */
    private class Killed : Error("the process went away")

    private class Holds(
        private val log: MutableList<String>,
        private val onCompensate: () -> Unit = {},
    ) : PetichStep<OrderPayload> {
        override suspend fun execute(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            log.add("hold")
        }

        override suspend fun compensate(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            onCompensate()
            log.add("release")
        }
    }

    /**
     * Refuses from EXECUTION rather than from a check, because the point is a refusal that has
     * something to undo — and phases only ever move forwards, so a check cannot sit after a step.
     */
    private class Refuses(
        private val log: MutableList<String>,
    ) : PetichStep<OrderPayload> {
        override suspend fun execute(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            log.add("refuse")
            ctx.reject("the card is not the passenger's")
        }

        override suspend fun compensate(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            log.add("un-refuse")
        }
    }

    private class RowRepository : PetichRepository {
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

    private class RefusalCounter : PetichEngineMetrics {
        val refused = mutableListOf<PetichStatus>()

        override fun onTerminalWriteRefused(
            type: String,
            attempted: PetichStatus,
        ) {
            refused.add(attempted)
        }
    }

    @Test
    fun `a refusal whose rollback was interrupted is finished as a refusal`() =
        runBlocking {
            val log = mutableListOf<String>()
            val repository = RowRepository()
            // Throws the first time only: the pass that dies is the one that starts the rollback,
            // and the pass that picks it up has to get through.
            var kill = true
            val definitions =
                listOf(
                    petichDefinition<OrderPayload>("order") {
                        step(
                            "hold",
                            Holds(log) {
                                if (kill) {
                                    kill = false
                                    throw Killed()
                                }
                            },
                        )
                        step("charge", Refuses(log))
                    },
                )
            val engine = PetichEngine(repository = repository, definitions = definitions)

            // The rollback starts and the process goes away inside it. Nothing here catches an
            // Error, so what is left behind is exactly what a `kill -9` leaves: the mark, and no
            // outcome.
            var died = false
            try {
                engine.process(order())
            } catch (e: Killed) {
                died = true
            }
            assertTrue(died, "the first pass finished instead of dying: ${repository.row}")
            val interrupted = checkNotNull(repository.row)
            assertEquals(PetichStatus.COMPENSATING, interrupted.status, "$interrupted")
            assertEquals(PetichStatus.REJECTED, interrupted.compensatingTowards, "$interrupted")

            // A second pass over the same row, as the sweeper makes.
            val resumed = PetichEngine(repository = repository, definitions = definitions)
            resumed.process(interrupted)

            // THE ACCEPTANCE. Before this it read FAILED, and a client asking again about a card
            // that was refused was told the server had broken.
            assertEquals(PetichStatus.REJECTED, checkNotNull(repository.row).status, "${repository.row}")
            // "un-refuse" is absent because the refusing member's own compensation does not run
            // (B-35): it declined to act, so it has nothing to undo.
            assertEquals(listOf("hold", "refuse", "release"), log)
        }

    @Test
    fun `a saga another pass has already finished is not rolled back a second time`() =
        runBlocking {
            val log = mutableListOf<String>()
            val repository = RowRepository()
            val metrics = RefusalCounter()
            val engine =
                PetichEngine(
                    repository = repository,
                    metrics = metrics,
                    definitions =
                        listOf(
                            petichDefinition<OrderPayload>("order") {
                                step("hold", Holds(log))
                                step(
                                    "charge",
                                    object : PetichStep<OrderPayload> {
                                        override suspend fun execute(
                                            ctx: PetichStepContext,
                                            payload: OrderPayload,
                                        ) {
                                            // The other replica's sweeper, arriving while this pass
                                            // is still inside a member: it decided this saga was
                                            // stuck, rolled it back and named the outcome.
                                            repository.row =
                                                checkNotNull(repository.row).copy(
                                                    status = PetichStatus.FAILED,
                                                    version = checkNotNull(repository.row).version + 1,
                                                )
                                            throw IllegalStateException("the acquirer timed out")
                                        }

                                        override suspend fun compensate(
                                            ctx: PetichStepContext,
                                            payload: OrderPayload,
                                        ) {
                                            log.add("refund")
                                        }
                                    },
                                )
                            },
                        ),
                )

            engine.process(order())

            val finished = checkNotNull(repository.row)
            // THE ACCEPTANCE. The row the other pass wrote is the row that stands, and the members
            // it had already undone are not undone again.
            assertEquals(PetichStatus.FAILED, finished.status, "$finished")
            assertEquals(listOf("hold"), log, "a terminal saga was rolled back a second time")
            // And the refusal is not silent: this counter is the only outward sign that two passes
            // were working the same saga.
            assertEquals(listOf(PetichStatus.COMPENSATING), metrics.refused)
        }

    private fun order() =
        Petich(
            id = "order-1",
            type = "order",
            status = PetichStatus.DRAFT,
            payload = OrderPayload("order-1"),
        )
}
