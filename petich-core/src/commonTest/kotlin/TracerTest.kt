package io.github.youndie.petich

import io.github.youndie.petich.PetichTraceEvent.AnnouncementFailed
import io.github.youndie.petich.PetichTraceEvent.ChainRefused
import io.github.youndie.petich.PetichTraceEvent.ClaimLost
import io.github.youndie.petich.PetichTraceEvent.ClaimWon
import io.github.youndie.petich.PetichTraceEvent.Finished
import io.github.youndie.petich.PetichTraceEvent.MemberEntered
import io.github.youndie.petich.PetichTraceEvent.MemberFailed
import io.github.youndie.petich.PetichTraceEvent.MemberProceeded
import io.github.youndie.petich.PetichTraceEvent.MemberRejected
import io.github.youndie.petich.PetichTraceEvent.MemberResuspended
import io.github.youndie.petich.PetichTraceEvent.MemberSuspended
import io.github.youndie.petich.PetichTraceEvent.MemberTimedOut
import io.github.youndie.petich.PetichTraceEvent.PassRetried
import io.github.youndie.petich.PetichTraceEvent.PassStarted
import io.github.youndie.petich.PetichTraceEvent.RollbackGaveUp
import io.github.youndie.petich.PetichTraceEvent.RollbackStarted
import io.github.youndie.petich.PetichTraceEvent.StepUndone
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * B-58: what one saga did, read from outside.
 *
 * Three kinds of test. **The fixture pairs** of `research-petich-tracer.md` §1.4 — two runs a trace
 * must tell apart, both reproducible today — are the acceptance: a tracer that renders both halves
 * of a pair the same way has failed, whatever it renders. **The enumeration** keeps every variant of
 * the event type emitted by something, so one added and never sent fails here. **The contract** —
 * a tracer that throws or blocks — says what an application's tracer can and cannot do to a saga.
 *
 * Every trace is read as a list of short lines ([line]), because the claim being tested is "a person
 * can read this", and a person reads the line, not the data class.
 */
class TracerTest {
    private data class Order(
        val id: String,
    ) : PetichPayload()

    private class Recorder : PetichTracer {
        val events: MutableList<PetichTraceEvent> = mutableListOf()

        override fun onEvent(event: PetichTraceEvent) {
            events.add(event)
        }

        fun lines(): List<String> = events.map(::line)

        fun dump(): String = lines().joinToString("\n")
    }

    private class Does(
        private val log: MutableList<String> = mutableListOf(),
        private val undo: () -> Unit = {},
        private val act: (PetichStepContext) -> Unit = {},
    ) : PetichStep<Order> {
        override suspend fun execute(
            ctx: PetichStepContext,
            payload: Order,
        ) {
            log.add("do")
            act(ctx)
        }

        override suspend fun compensate(
            ctx: PetichStepContext,
            payload: Order,
        ) {
            undo()
            log.add("undo")
        }
    }

    private class Hangs : PetichStep<Order> {
        override suspend fun execute(
            ctx: PetichStepContext,
            payload: Order,
        ) = delay(10_000)

        override suspend fun compensate(
            ctx: PetichStepContext,
            payload: Order,
        ) = Unit
    }

    private class Announces(
        private val body: suspend () -> Unit,
    ) : PetichAnnouncement<Order> {
        override suspend fun announce(
            ctx: PetichAnnouncementContext,
            payload: Order,
        ) = body()
    }

    private class Killed : Error("the process went away")

    private class HandlerFailures : PetichEngineMetrics {
        val callbacks: MutableList<String> = mutableListOf()

        override fun onHandlerFailed(
            type: String,
            callback: String,
            reason: String,
        ) {
            callbacks.add(callback)
        }
    }

    /** Optimistic versions, whole objects; [refuseUpdates] is a process dying before each commit. */
    private class Rows : ExpiringPetichRepository {
        val rows: MutableMap<String, Petich> = mutableMapOf()
        var refuseUpdates: Boolean = false
        var now: Long = 0
        var writes: Int = 0
        private val stamps = mutableMapOf<String, Long>()

        fun seed(petich: Petich) {
            rows[petich.id] = petich
            stamps[petich.id] = now
        }

        override suspend fun findById(id: String): Petich? = rows[id]

        override suspend fun saveOrGet(petich: Petich): Petich =
            rows.getOrPut(petich.id) {
                writes++
                petich.also { stamps[it.id] = now }
            }

        override suspend fun update(petich: Petich): Boolean {
            if (refuseUpdates) return false
            val existing = rows[petich.id] ?: return false
            if (petich.version != existing.version + 1) return false
            rows[petich.id] = petich
            stamps[petich.id] = now
            writes++
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
        tracer: PetichTracer,
        metrics: PetichEngineMetrics = PetichEngineMetrics.NoOp,
        timeoutMs: Long? = null,
        body: PetichDefinitionBuilder<Order>.() -> Unit,
    ) = PetichEngine(
        repository = rows,
        clock = PetichClock { rows.now },
        metrics = metrics,
        config =
            timeoutMs?.let { ms -> PetichEngineConfig(phaseTimeoutsMs = PetichPhase.entries.associateWith { ms }) }
                ?: PetichEngineConfig(),
        definitions = listOf(petichDefinition("order", body)),
        tracer = tracer,
    )

    /**
     * The one failure a scenario is built to end in — a pass that cannot commit, a process killed
     * mid-member. Anything else, cancellation included, is not caught.
     */
    private inline fun <reified E : Throwable> expecting(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            if (e !is E) throw e
            return
        }
        throw AssertionError("the scenario was supposed to end in ${E::class.simpleName}")
    }

    private fun order(
        id: String = "o-1",
        status: PetichStatus = PetichStatus.DRAFT,
    ) = Petich(id = id, type = "order", status = status, payload = Order(id))

    // ---------------------------------------------------------------------------------------------
    // The fixture pairs (research §1.4)
    // ---------------------------------------------------------------------------------------------

    /**
     * (a) THE SIX SENDS — and the half of the pair the draft had wrong.
     *
     * The draft paired an unnamed announcement against a named one. To the engine the two are the
     * same run: both are entered on every pass, and the difference — whether the far side drops the
     * second send — happens where no engine event can see it. So the pair the trace CAN tell apart is
     * a pass that committed first time against one whose commits were refused, and what it answers is
     * the fixture's question: how many times the member ran, and why.
     */
    @Test
    fun `pair a - the trace says how many times the receipt ran and what made it run again`() =
        runBlocking {
            fun receiptEngine(
                rows: Rows,
                tracer: Recorder,
            ) = engine(rows, tracer) { announce("receipt", Announces { }) }

            val once = Recorder()
            receiptEngine(Rows(), once).process(order())

            val again = Recorder()
            val dying = Rows().apply { refuseUpdates = true }
            expecting<OptimisticLockException> { receiptEngine(dying, again).process(order()) }
            dying.refuseUpdates = false
            receiptEngine(dying, again).process(checkNotNull(dying.rows["o-1"]))

            assertEquals(1, once.events.count { it is MemberEntered && it.key == "receipt" }, once.dump())
            val entries = again.events.count { it is MemberEntered && it.key == "receipt" }
            val retries = again.events.count { it is PassRetried }
            assertTrue(entries > 1, again.dump())
            // WHY, and not only how many: every entry but the last is followed by a pass that lost
            // its commit. That sentence is the whole of B-50's finding, readable without the code.
            assertEquals(entries - 1, retries, again.dump())
            assertTrue(again.events.last() is Finished, again.dump())
        }

    /**
     * (b) THE HUNG ANNOUNCEMENT. One that outran the deadline B-52 gave it, against one whose body's
     * OWN `withTimeout` fired. Both end `COMPLETED` with nothing undone, and the trace names whose
     * deadline it was.
     *
     * The second half used to be rolled back — the route B-52 did not close — and this test said so
     * until B-59 closed it. The rollback it showed is now reproduced by reverting B-59, which is how
     * that item was checked.
     */
    @Test
    fun `pair b - the trace says whose deadline an announcement outran`() =
        runBlocking {
            val hung = Recorder()
            engine(Rows(), hung, timeoutMs = 30) {
                step("reserve", Does())
                announce("notify", Announces { delay(10_000) })
            }.process(order())

            val escaped = Recorder()
            engine(Rows(), escaped, timeoutMs = 10_000) {
                step("reserve", Does())
                announce("notify", Announces { withTimeout(10) { delay(10_000) } })
            }.process(order())

            assertTrue(hung.events.any { it is AnnouncementFailed && it.reason.contains("timed out") }, hung.dump())
            assertTrue(hung.events.none { it is RollbackStarted }, hung.dump())
            assertEquals(Finished("o-1", "order", PetichStatus.COMPLETED), hung.events.last(), hung.dump())

            val own = escaped.events.filterIsInstance<AnnouncementFailed>().single()
            assertTrue(
                !own.reason.startsWith("timed out after"),
                "the body's deadline, not the engine's: ${escaped.dump()}",
            )
            assertTrue(escaped.events.none { it is RollbackStarted }, escaped.dump())
            assertEquals(Finished("o-1", "order", PetichStatus.COMPLETED), escaped.events.last(), escaped.dump())
        }

    /**
     * (c) THE LOST `REJECTED`. An interrupted refusal resumed from a row that remembers what it was
     * rolling back towards, against the same row written before that column existed. The trace names
     * the target the rollback carried and the status the saga ended in, so the fallback is visible as
     * the fallback.
     */
    @Test
    fun `pair c - the trace says what the rollback was for and what the saga ended as`() =
        runBlocking {
            fun interrupted(towards: PetichStatus?) =
                order(status = PetichStatus.COMPENSATING).copy(
                    currentPhase = PetichPhase.EXECUTION,
                    currentInterceptorIndex = 1,
                    compensatingFromIndex = 1,
                    compensatingTowards = towards,
                )

            suspend fun resume(towards: PetichStatus?): Recorder {
                val recorder = Recorder()
                val rows = Rows().apply { seed(interrupted(towards)) }
                engine(rows, recorder) { step("hold", Does()) }.process(checkNotNull(rows.rows["o-1"]))
                return recorder
            }

            val remembered = resume(PetichStatus.REJECTED)
            val legacy = resume(null)

            assertTrue(
                remembered.events.any { it is RollbackStarted && it.towards == PetichStatus.REJECTED },
                remembered.dump(),
            )
            assertEquals(Finished("o-1", "order", PetichStatus.REJECTED), remembered.events.last())
            assertTrue(legacy.events.any { it is RollbackStarted && it.towards == PetichStatus.FAILED }, legacy.dump())
            assertEquals(Finished("o-1", "order", PetichStatus.FAILED), legacy.events.last())
        }

    /**
     * H1: best-effort is enough, because the pass that died is followed by the event that says so.
     * The first pass stops mid-member with nothing after it; the sweeper's claim opens the next.
     *
     * Seeded `PROCESSING`, as `petich-ktor`'s create route writes it. The engine itself never writes
     * that status, which is B-65 and not this test's claim.
     */
    @Test
    fun `H1 - a pass that died reads as entered then nothing then claimed and carried on`() =
        runBlocking {
            val recorder = Recorder()
            val rows = Rows()
            var kill = true
            val saga =
                engine(rows, recorder) {
                    step(
                        "hold",
                        Does(act = {
                            if (kill) {
                                kill = false
                                throw Killed()
                            }
                        }),
                    )
                }
            expecting<Killed> { saga.process(order(status = PetichStatus.PROCESSING)) }
            rows.now = 10.minutes.inWholeMilliseconds
            SuspendedPetichSweeper(rows, saga, PetichClock { rows.now }, stuckAfter = 1.minutes).sweepStuck()

            assertEquals(
                listOf(
                    "o-1 pass 1 from PROCESSING ENRICHMENT#0",
                    "o-1 enter EXECUTION#0 hold",
                    "o-1 claimed STUCK",
                    "o-1 pass 1 from PROCESSING ENRICHMENT#0",
                    "o-1 enter EXECUTION#0 hold",
                    "o-1 proceed EXECUTION#0 hold",
                    "o-1 finished COMPLETED",
                ),
                recorder.lines(),
            )
        }

    // ---------------------------------------------------------------------------------------------
    // The enumeration
    // ---------------------------------------------------------------------------------------------

    /**
     * Every variant is sent by something. [kind] is an exhaustive `when`, so a variant added to the
     * type and not to it does not compile; one added to both and sent by nothing fails the assertion.
     */
    @Test
    fun `every kind of event is sent by at least one scenario`() =
        runBlocking {
            val recorder = Recorder()
            val rows = Rows()
            var n = 0

            suspend fun saga(
                status: PetichStatus = PetichStatus.DRAFT,
                timeoutMs: Long? = null,
                body: PetichDefinitionBuilder<Order>.() -> Unit,
            ): PetichEngine {
                val e = engine(rows, recorder, timeoutMs = timeoutMs, body = body)
                e.process(order("s-${++n}", status))
                return e
            }

            saga { step("a", Does()) }
            saga { step("a", Does(act = { it.reject("no") })) }
            saga { step("a", Does(act = { it.fail("broke") })) }
            saga { step("a", Does(act = { error("boom") })) }
            saga(timeoutMs = 20) { step("a", Hangs()) }
            saga { step("a", Does(act = { it.resuspendFor("AGAIN") })) }
            saga { announce("a", Announces { error("mail is down") }) }
            saga {
                step("a", Does(undo = { error("warehouse is down") }))
                step("b", Does(act = { it.fail("broke") }))
            }
            val parked = saga { step("a", Does(act = { it.suspendFor("CONFIRM", 1.minutes) })) }
            rows.now = 5.minutes.inWholeMilliseconds
            parked.expireSuspended("s-$n")

            rows.seed(
                order(
                    "refused",
                ).copy(currentPhase = PetichPhase.EXECUTION, currentInterceptorIndex = 1, chainFingerprint = "0"),
            )
            engine(rows, recorder) { step("a", Does()) }.process(checkNotNull(rows.rows["refused"]))

            rows.refuseUpdates = true
            expecting<OptimisticLockException> {
                engine(
                    rows,
                    recorder,
                ) { step("a", Does()) }.process(order("conflict"))
            }
            rows.refuseUpdates = false

            rows.seed(order("stuck", PetichStatus.PROCESSING))
            rows.now += 10.minutes.inWholeMilliseconds
            val stuckEngine = engine(rows, recorder) { step("a", Does()) }
            SuspendedPetichSweeper(rows, stuckEngine, PetichClock { rows.now }, stuckAfter = 1.minutes).sweepStuck()
            rows.seed(order("contended", PetichStatus.PROCESSING))
            rows.now += 10.minutes.inWholeMilliseconds
            rows.refuseUpdates = true
            SuspendedPetichSweeper(rows, stuckEngine, PetichClock { rows.now }, stuckAfter = 1.minutes).sweepStuck()
            rows.refuseUpdates = false

            val seen = recorder.events.map(::kind).toSet()
            assertEquals(ALL_KINDS, seen, "never sent: ${ALL_KINDS - seen}")
        }

    // ---------------------------------------------------------------------------------------------
    // The contract
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a tracer that throws on every event changes nothing about the saga and is counted`() =
        runBlocking {
            val metrics = HandlerFailures()
            val log = mutableListOf<String>()
            val rows = Rows()
            val result =
                engine(rows, PetichTracer { error("the collector is down") }, metrics = metrics) {
                    step("a", Does(log))
                    step("b", Does(log, act = { it.reject("no") }))
                }.process(order())

            assertTrue(result is PetichResult.Error, "$result")
            assertEquals(PetichStatus.REJECTED, rows.rows["o-1"]?.status)
            // `b` refused, so only `a` is undone (B-35).
            assertEquals(listOf("do", "do", "undo"), log)
            assertTrue(
                metrics.callbacks.isNotEmpty() && metrics.callbacks.all { it == "tracer.onEvent" },
                "${metrics.callbacks}",
            )
        }

    /**
     * The contract says return immediately, and this is what breaking it costs: every event is paid
     * for on the saga's own time. Shown rather than hidden — nothing here buffers for the tracer.
     */
    @Test
    fun `a tracer that blocks makes the saga slower by exactly that much`() =
        runBlocking {
            val slow =
                PetichTracer {
                    val until = TimeSource.Monotonic.markNow()
                    while (until.elapsedNow().inWholeMilliseconds < 20) {
                        // spinning: the one thing the contract forbids
                    }
                }
            val mark = TimeSource.Monotonic.markNow()
            engine(Rows(), slow) {
                step("a", Does())
                step("b", Does())
            }.process(order())
            // pass started, 2 × (entered, proceeded), finished: six events.
            assertTrue(mark.elapsedNow().inWholeMilliseconds >= 6 * 20, "${mark.elapsedNow()}")
        }

    @Test
    fun `a reason is cut to the limit and says so`() {
        val long = "x".repeat(TRACE_REASON_LIMIT * 3)
        val cut = long.forTrace()
        assertTrue(cut.length < long.length && cut.endsWith("[truncated]"), cut)
        assertEquals("short", "short".forTrace())
    }

    /** The count `WriteCountTest` pins, asked again with a tracer on: tracing writes nothing. */
    @Test
    fun `the write count is not moved by tracing`() =
        runBlocking {
            suspend fun writes(tracer: PetichTracer): Int {
                val rows = Rows()
                engine(rows, tracer) {
                    step("a", Does())
                    step("b", Does(act = { it.reject("no") }))
                }.process(order())
                return rows.writes
            }
            assertEquals(writes(PetichTracer.NoOp), writes(Recorder()))
        }

    private companion object {
        val ALL_KINDS: Set<String> =
            setOf(
                "PassStarted",
                "PassRetried",
                "MemberEntered",
                "MemberProceeded",
                "MemberRejected",
                "MemberFailed",
                "MemberTimedOut",
                "MemberSuspended",
                "MemberResuspended",
                "AnnouncementFailed",
                "ClaimWon",
                "ClaimLost",
                "RollbackStarted",
                "StepUndone",
                "RollbackGaveUp",
                "ChainRefused",
                "Finished",
            )

        fun kind(event: PetichTraceEvent): String =
            when (event) {
                is PassStarted -> "PassStarted"
                is PassRetried -> "PassRetried"
                is MemberEntered -> "MemberEntered"
                is MemberProceeded -> "MemberProceeded"
                is MemberRejected -> "MemberRejected"
                is MemberFailed -> "MemberFailed"
                is MemberTimedOut -> "MemberTimedOut"
                is MemberSuspended -> "MemberSuspended"
                is MemberResuspended -> "MemberResuspended"
                is AnnouncementFailed -> "AnnouncementFailed"
                is ClaimWon -> "ClaimWon"
                is ClaimLost -> "ClaimLost"
                is RollbackStarted -> "RollbackStarted"
                is StepUndone -> "StepUndone"
                is RollbackGaveUp -> "RollbackGaveUp"
                is ChainRefused -> "ChainRefused"
                is Finished -> "Finished"
            }

        fun line(event: PetichTraceEvent): String {
            val at =
                when (event) {
                    is MemberEntered -> "${event.phase}#${event.index} ${event.key}"
                    is MemberProceeded -> "${event.phase}#${event.index} ${event.key}"
                    is MemberRejected -> "${event.phase}#${event.index} ${event.key}"
                    is MemberFailed -> "${event.phase}#${event.index} ${event.key}"
                    is MemberTimedOut -> "${event.phase}#${event.index} ${event.key}"
                    is MemberSuspended -> "${event.phase}#${event.index} ${event.key}"
                    is MemberResuspended -> "${event.phase}#${event.index} ${event.key}"
                    is StepUndone -> "${event.phase}#${event.index} ${event.key}"
                    else -> ""
                }
            val body =
                when (event) {
                    is PassStarted -> {
                        "pass ${event.attempt} from ${event.status} ${event.phase}#${event.index}"
                    }

                    is PassRetried -> {
                        "retry ${event.attempt}"
                    }

                    is MemberEntered -> {
                        "enter $at"
                    }

                    is MemberProceeded -> {
                        "proceed $at"
                    }

                    is MemberRejected -> {
                        "reject $at: ${event.reason}"
                    }

                    is MemberFailed -> {
                        "fail $at thrown=${event.thrown}: ${event.reason}"
                    }

                    is MemberTimedOut -> {
                        "timeout $at"
                    }

                    is MemberSuspended -> {
                        "suspend $at ${event.requiredAction}"
                    }

                    is MemberResuspended -> {
                        "resuspend $at ${event.requiredAction}"
                    }

                    is AnnouncementFailed -> {
                        "announcement failed ${event.key}: ${event.reason}"
                    }

                    is ClaimWon -> {
                        "claimed ${event.queue}"
                    }

                    is ClaimLost -> {
                        "lost claim ${event.queue}"
                    }

                    is RollbackStarted -> {
                        "rollback ${event.phase}#${event.fromIndex} to ${event.towards}: ${event.reason}"
                    }

                    is StepUndone -> {
                        "undo $at"
                    }

                    is RollbackGaveUp -> {
                        "gave up at ${event.key} attempt ${event.attempt} exhausted=${event.exhausted}"
                    }

                    is ChainRefused -> {
                        "chain refused at ${event.phase}#${event.index}"
                    }

                    is Finished -> {
                        "finished ${event.status}"
                    }
                }
            return "${event.sagaId} $body"
        }
    }
}
