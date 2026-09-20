package io.github.youndie.petich

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * B-18: the step whose outcome the engine never learned is part of the rollback.
 *
 * The interesting assertions here are about a log of names, not about a status. A saga that fails
 * reaches FAILED either way — that is exactly why this defect survived a green suite: every
 * assertion anyone naturally writes about such a run passes while the reservation of the step that
 * failed stays on the far side.
 */
class FailedStepCompensationTest {
    data class OrderPayload(
        val sku: String,
    ) : PetichPayload()

    /** Records what ran and what was undone, in order. */
    class Log {
        val entries: MutableList<String> = mutableListOf()

        fun count(entry: String): Int = entries.count { it == entry }
    }

    open class RecordingInterceptor(
        private val name: String,
        private val log: Log,
        private val onIntercept: suspend (PetichStepContext) -> Unit = { },
        private val onCompensate: suspend (Log) -> Unit = {},
    ) : PetichStep<OrderPayload> {
        override suspend fun execute(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            log.entries.add("do:$name")
            onIntercept(ctx)
        }

        override suspend fun compensate(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            log.entries.add("undo:$name")
            onCompensate(log)
        }
    }

    /**
     * A row with an optimistic lock, which the flat mock repositories elsewhere in this suite do
     * not have — the resume test below turns on losing a write, and a repository that accepts
     * every write cannot lose one.
     */
    class RowRepository : PetichRepository {
        var row: Petich? = null
        var failWrites: Boolean = false

        override suspend fun findById(id: String): Petich? = row?.takeIf { it.id == id }

        override suspend fun saveOrGet(petich: Petich): Petich {
            val existing = row
            if (existing != null && existing.id == petich.id) return existing
            row = petich
            return petich
        }

        override suspend fun update(petich: Petich): Boolean {
            if (failWrites) return false
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

    @Test
    fun `a step that threw after its effect is compensated`() =
        runBlocking {
            val log = Log()
            val engine =
                PetichEngine(
                    repository = RowRepository(),
                    definitions =
                        listOf(
                            petich<OrderPayload>("order") {
                                step("reserve", RecordingInterceptor("reserve", log))
                                step(
                                    "charge",
                                    RecordingInterceptor(
                                        "charge",
                                        log,
                                        onIntercept = { throw RuntimeException("the answer was lost") },
                                    ),
                                )
                            },
                        ),
                )

            val result = engine.process(row("p-threw"))

            assertTrue(result is PetichResult.SystemFailure, "expected a system failure: $result")
            assertEquals(
                listOf("do:reserve", "do:charge", "undo:charge", "undo:reserve"),
                log.entries,
                "the step that threw has to be undone first, then the ones before it",
            )
        }

    @Test
    fun `a step that timed out is compensated`() =
        runBlocking {
            val log = Log()
            val engine =
                PetichEngine(
                    repository = RowRepository(),
                    config = PetichEngineConfig(phaseTimeoutsMs = mapOf(PetichPhase.EXECUTION to 50L)),
                    definitions =
                        listOf(
                            petich<OrderPayload>("order") {
                                step("reserve", RecordingInterceptor("reserve", log))
                                step("charge", RecordingInterceptor("charge", log, onIntercept = { delay(10_000) }))
                            },
                        ),
                )

            val result = engine.process(row("p-timeout"))

            assertTrue(result is PetichResult.SystemFailure, "expected a system failure: $result")
            assertEquals(
                listOf("do:reserve", "do:charge", "undo:charge", "undo:reserve"),
                log.entries,
                "a timeout is the ambiguous failure this exists for - the step is undone",
            )
        }

    /**
     * The negative control for the same flag. An expired suspension is NOT an unknown outcome: the
     * step that suspended reported one and committed, and `currentInterceptorIndex` already points
     * past it. Were the rollback to start one higher here, it would compensate a step nobody
     * entered — so this test fails if the flag is set unconditionally, which is the mistake the
     * implementation is one character away from.
     */
    @Test
    fun `an expired suspension does not compensate a step that never ran`() =
        runBlocking {
            val log = Log()
            var now = 1_000L
            val engine =
                PetichEngine(
                    repository = RowRepository(),
                    clock = PetichClock { now },
                    definitions =
                        listOf(
                            petich<OrderPayload>("order") {
                                step("reserve", RecordingInterceptor("reserve", log))
                                step(
                                    "confirm",
                                    RecordingInterceptor(
                                        "confirm",
                                        log,
                                        onIntercept = { it.suspendFor("CONFIRM", ttl = 5.minutes) },
                                    ),
                                )
                                step("ship", RecordingInterceptor("ship", log))
                            },
                        ),
                )

            val suspended = engine.process(row("p-expired"))
            assertTrue(suspended is PetichResult.ActionRequired, "expected a suspension: $suspended")

            now += 6.minutes.inWholeMilliseconds
            val expired = engine.expireSuspended("p-expired")

            assertTrue(expired is ExpireResult.Expired, "expected an expiry: $expired")
            assertEquals(
                listOf("do:reserve", "do:confirm", "undo:confirm", "undo:reserve"),
                log.entries,
                "ship was never entered and must not be undone",
            )
        }

    /**
     * The rollback commits how far it has got AFTER calling the step, so an interruption in that
     * window resumes on the same step — which is why compensate() is required to be idempotent
     * rather than exactly-once. What must hold is that nothing is SKIPPED: the steps below the
     * interruption are each undone.
     */
    @Test
    fun `an interrupted rollback resumes on the same step and skips nothing below it`() =
        runBlocking {
            val log = Log()
            val repository = RowRepository()
            val engine =
                PetichEngine(
                    repository = repository,
                    config = PetichEngineConfig(maxStateUpdateAttempts = 2),
                    definitions =
                        listOf(
                            petich<OrderPayload>("order") {
                                step("reserve", RecordingInterceptor("reserve", log))
                                step("quota", RecordingInterceptor("quota", log))
                                step(
                                    "charge",
                                    RecordingInterceptor(
                                        "charge",
                                        log,
                                        onIntercept = { throw RuntimeException("the answer was lost") },
                                        // Lose every write from here on, but only on the first pass:
                                        // the interruption lands between this compensation and the
                                        // write that would have recorded it.
                                        onCompensate = { entries ->
                                            if (entries.count("undo:charge") == 1) repository.failWrites = true
                                        },
                                    ),
                                )
                            },
                        ),
                )

            engine.process(row("p-interrupted"))

            assertEquals(
                listOf("do:reserve", "do:quota", "do:charge", "undo:charge"),
                log.entries,
                "the rollback should have stopped where the write was lost",
            )
            assertEquals(
                PetichStatus.COMPENSATING,
                repository.row?.status,
                "a rollback that could not record its progress stays in COMPENSATING",
            )

            repository.failWrites = false
            engine.process(repository.row!!)

            assertEquals(
                listOf(
                    "do:reserve",
                    "do:quota",
                    "do:charge",
                    "undo:charge",
                    "undo:charge",
                    "undo:quota",
                    "undo:reserve",
                ),
                log.entries,
                "the resumed rollback repeats the step it was interrupted on and skips neither below it",
            )
            assertEquals(PetichStatus.FAILED, repository.row?.status, "the rollback finished")
        }
}
