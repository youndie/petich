package io.github.youndie.petich

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/**
 * B-66: the window B-65 left. A resume whose FIRST member died before its commit wrote nothing, so the
 * row was exactly what the suspension wrote — `PENDING_SIGNATURE`, the deadline, and a rollback start
 * covering the member that parked and everything before it. The expiry then undid all of that and not
 * the member that died in the resume, whose outcome is the unknown one B-18 rolls back.
 *
 * Reproduced as written, then closed by a resume that writes its start. After that write the question
 * the item asked — "does the expiry undo it?" — no longer arises: the row is `PROCESSING`, the expiry
 * does not touch it, and the stuck queue carries the saga on, which honours the client's confirmation
 * rather than discarding it.
 */
class ExpiryAfterADeadResumeTest {
    private data class OrderPayload(
        val sku: String,
    ) : PetichPayload()

    private class Killed : Error("the process went away")

    private class Step(
        private val name: String,
        private val log: MutableList<String>,
        private val dies: () -> Boolean = { false },
        private val throws: () -> Boolean = { false },
        private val parks: Boolean = false,
    ) : PetichStep<OrderPayload> {
        override suspend fun execute(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            log.add("do:$name")
            if (dies()) throw Killed()
            if (throws()) error("the capture call timed out after it landed")
            if (parks) ctx.suspendFor("CONFIRM", 5.minutes)
        }

        override suspend fun compensate(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            log.add("undo:$name")
        }
    }

    private class Rows : PetichRepository {
        var row: Petich? = null

        override suspend fun findById(id: String): Petich? = row?.takeIf { it.id == id }

        override suspend fun saveOrGet(petich: Petich): Petich = row ?: petich.also { row = it }

        override suspend fun update(petich: Petich): Boolean {
            val existing = row ?: return false
            if (petich.version != existing.version + 1) return false
            row = petich
            return true
        }
    }

    @Test
    fun `a resume that dies in its first member is carried on rather than expired without it`() =
        runBlocking {
            val rows = Rows()
            val log = mutableListOf<String>()
            var now = 0L
            var kill = false
            val engine =
                PetichEngine(
                    repository = rows,
                    clock = PetichClock { now },
                    definitions =
                        listOf(
                            petichDefinition<OrderPayload>("order") {
                                step("hold", Step("hold", log, parks = true))
                                step("capture", Step("capture", log, dies = { kill }))
                                step("ship", Step("ship", log))
                            },
                        ),
                )

            engine.process(
                Petich(id = "order-1", type = "order", status = PetichStatus.DRAFT, payload = OrderPayload("sku")),
            )
            assertEquals(PetichStatus.PENDING_SIGNATURE, rows.row?.status)

            kill = true
            try {
                engine.process(checkNotNull(rows.row).copy(resumePayload = Confirmed))
                throw AssertionError("the resume was supposed to die")
            } catch (expected: Killed) {
                kill = false
            }

            // THE START WAS WRITTEN before `capture` ran: the row is no longer waiting for anyone.
            // Before B-66 it read PENDING_SIGNATURE with its deadline, and the expiry below rolled
            // back `hold` and left `capture` — the leak this item reproduced first.
            val dead = checkNotNull(rows.row)
            assertEquals(PetichStatus.PROCESSING, dead.status, "$dead")
            assertEquals(null, dead.suspendedUntilEpochMs, "$dead")

            now = 10.minutes.inWholeMilliseconds
            assertEquals(ExpireResult.NotSuspended(PetichStatus.PROCESSING), engine.expireSuspended("order-1"))

            // What the stuck queue does with it once claimed (StrandedMidPassTest covers the claim).
            engine.process(dead)

            assertEquals(PetichStatus.COMPLETED, rows.row?.status)
            assertEquals(listOf("do:hold", "do:capture", "do:capture", "do:ship"), log)
        }

    /**
     * The same stale start, reached without a crash: the first member after a resume THROWS. B-18
     * says a member that threw is part of its own rollback, and on this pass the parking's start
     * overrode that — the row still carried where a rollback of the PARKED member would begin.
     */
    @Test
    fun `a member that throws first after a resume is part of its own rollback`() =
        runBlocking {
            val rows = Rows()
            val log = mutableListOf<String>()
            var fail = false
            val engine =
                PetichEngine(
                    repository = rows,
                    clock = PetichClock { 0 },
                    definitions =
                        listOf(
                            petichDefinition<OrderPayload>("order") {
                                step("hold", Step("hold", log, parks = true))
                                step("capture", Step("capture", log, throws = { fail }))
                            },
                        ),
                )

            engine.process(
                Petich(id = "order-1", type = "order", status = PetichStatus.DRAFT, payload = OrderPayload("sku")),
            )
            fail = true
            engine.process(checkNotNull(rows.row).copy(resumePayload = Confirmed))

            assertEquals(PetichStatus.FAILED, rows.row?.status)
            assertEquals(listOf("do:hold", "do:capture", "undo:capture", "undo:hold"), log)
        }
}

private object Confirmed : ResumePayload()
