package io.github.youndie.petich

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * B-28: the engine runs a definition, and the outcomes a member records mean what B-20 decided.
 *
 * `ctx.reject` and `ctx.fail` both roll back what ran — that is B-20 — and differ in the name the
 * saga ends under. A check has nothing to undo and cannot sit after a step, so a refusal from one is
 * always a refusal before anything happened.
 */
class DefinitionEngineTest {
    data class OrderPayload(
        val sku: String,
    ) : PetichPayload()

    private class Log {
        val entries: MutableList<String> = mutableListOf()
    }

    private class Decides(
        private val name: String,
        private val log: Log,
        private val refuse: String? = null,
    ) : PetichCheck<OrderPayload> {
        override suspend fun check(
            ctx: PetichCheckContext,
            payload: OrderPayload,
        ) {
            log.entries.add("check:$name")
            refuse?.let { return ctx.reject(it) }
        }
    }

    /** POST_PROCESSING's member type: it says what happened and cannot take it back. */
    private class Announces(
        private val name: String,
        private val log: Log,
        private val onRun: (PetichAnnouncementContext) -> Unit = {},
    ) : PetichAnnouncement<OrderPayload> {
        override suspend fun announce(
            ctx: PetichAnnouncementContext,
            payload: OrderPayload,
        ) {
            log.entries.add("do:$name")
            onRun(ctx)
        }
    }

    private class Acts(
        private val name: String,
        private val log: Log,
        private val onRun: (PetichStepContext) -> Unit = {},
    ) : PetichStep<OrderPayload> {
        override suspend fun execute(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            log.entries.add("do:$name")
            onRun(ctx)
        }

        override suspend fun compensate(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            log.entries.add("undo:$name")
        }
    }

    /** Outbox-aware, because two of the cases below are about what rides with the write. */
    private class RowRepository : OutboxAwarePetichRepository {
        var row: Petich? = null
        val outbox: MutableList<String> = mutableListOf()

        override suspend fun findById(id: String): Petich? = row?.takeIf { it.id == id }

        override suspend fun saveOrGet(petich: Petich): Petich {
            val existing = row
            if (existing != null && existing.id == petich.id) return existing
            row = petich
            return petich
        }

        override suspend fun update(
            petich: Petich,
            outboxEvents: List<OutboxEvent>,
        ): Boolean {
            val existing = row ?: return false
            if (petich.version != existing.version + 1) return false
            row = petich
            outboxEvents.forEach { outbox.add(it.id) }
            return true
        }
    }

    private fun event(id: String) =
        object : OutboxEvent {
            override val id = id
            override val type = "test.event"
            override val payload = "{}"
        }

    private fun row(id: String) =
        Petich(
            id = id,
            type = "order",
            status = PetichStatus.PROCESSING,
            payload = OrderPayload("sku-1"),
        )

    private fun engineFor(
        definition: PetichDefinition<OrderPayload>,
        repository: PetichRepository,
    ) = PetichEngine(
        repository = repository,
        clock = PetichClock { 1_000L },
        definitions = listOf(definition),
    )

    @Test
    fun `a definition runs its members in the order it declares them`() =
        runBlocking {
            val log = Log()
            val repository = RowRepository()
            val definition =
                petichDefinition<OrderPayload>("order") {
                    validate("limits", Decides("limits", log))
                    step("reserve", Acts("reserve", log))
                    step("charge", Acts("charge", log))
                }

            val result = engineFor(definition, repository).process(row("p-happy"))

            assertTrue(result is PetichResult.Success, "expected a completed saga: $result")
            assertEquals(listOf("check:limits", "do:reserve", "do:charge"), log.entries)
            assertEquals(PetichStatus.COMPLETED, repository.row?.status)
        }

    @Test
    fun `a check that refuses ends the saga rejected with nothing to undo`() =
        runBlocking {
            val log = Log()
            val repository = RowRepository()
            val definition =
                petichDefinition<OrderPayload>("order") {
                    validate("limits", Decides("limits", log, refuse = "over the limit"))
                    step("reserve", Acts("reserve", log))
                }

            val result = engineFor(definition, repository).process(row("p-refused"))

            assertTrue(result is PetichResult.Error, "a refusal is not a fault: $result")
            assertEquals("over the limit", result.reason)
            assertEquals(listOf("check:limits"), log.entries, "nothing ran, so nothing is undone")
            assertEquals(PetichStatus.REJECTED, repository.row?.status)
        }

    @Test
    fun `a step that refuses undoes what ran and is still a refusal`() =
        runBlocking {
            val log = Log()
            val repository = RowRepository()
            val definition =
                petichDefinition<OrderPayload>("order") {
                    step("reserve", Acts("reserve", log))
                    step("charge", Acts("charge", log) { ctx -> ctx.reject("card declined") })
                }

            val result = engineFor(definition, repository).process(row("p-step-refused"))

            assertTrue(result is PetichResult.Error, "expected a refusal: $result")
            assertEquals(
                listOf("do:reserve", "do:charge", "undo:reserve"),
                log.entries,
                "the reservation comes back; the member that declined has nothing of its own to undo",
            )
            assertEquals(
                PetichStatus.REJECTED,
                repository.row?.status,
                "rolling back and naming the outcome are separate questions",
            )
        }

    @Test
    fun `a step that reports a fault undoes what ran and ends failed`() =
        runBlocking {
            val log = Log()
            val repository = RowRepository()
            val definition =
                petichDefinition<OrderPayload>("order") {
                    step("reserve", Acts("reserve", log))
                    step("charge", Acts("charge", log) { ctx -> ctx.fail("the provider is down") })
                }

            engineFor(definition, repository).process(row("p-fault"))

            assertEquals(listOf("do:reserve", "do:charge", "undo:reserve"), log.entries)
            assertEquals(PetichStatus.FAILED, repository.row?.status, "a fault is not a refusal")
        }

    /** konekt's shape: a member acts, and then waits for a human, as one member. */
    @Test
    fun `a member may act and then suspend`() =
        runBlocking {
            val log = Log()
            val repository = RowRepository()
            val definition =
                petichDefinition<OrderPayload>("order") {
                    step(
                        "hold-funds",
                        Acts("hold-funds", log) { ctx -> ctx.suspendFor("CONFIRM", 5.minutes) },
                    )
                    step("charge", Acts("charge", log))
                }
            val engine = engineFor(definition, repository)

            val waiting = engine.process(row("p-wizard"))
            assertTrue(waiting is PetichResult.ActionRequired, "expected a suspension: $waiting")
            assertEquals("CONFIRM", waiting.actionType)
            assertEquals(listOf("do:hold-funds"), log.entries, "the money is held once, before the wait")
            assertEquals(PetichStatus.PENDING_SIGNATURE, repository.row?.status)

            val finished = engine.process(repository.row!!)
            assertTrue(finished is PetichResult.Success, "expected the saga to finish: $finished")
            assertEquals(
                listOf("do:hold-funds", "do:charge"),
                log.entries,
                "the member that suspended is not re-entered on resume",
            )
        }

    /** B-18 through the new vocabulary: the member whose outcome was never learned is undone too. */
    @Test
    fun `a member that throws is undone along with the ones before it`() =
        runBlocking {
            val log = Log()
            val repository = RowRepository()
            val definition =
                petichDefinition<OrderPayload>("order") {
                    step("reserve", Acts("reserve", log))
                    step("charge", Acts("charge", log) { error("the answer was lost") })
                }

            val result = engineFor(definition, repository).process(row("p-threw"))

            assertTrue(result is PetichResult.SystemFailure, "expected a system failure: $result")
            assertEquals(
                listOf("do:reserve", "do:charge", "undo:charge", "undo:reserve"),
                log.entries,
                "the member that threw is undone first - the engine never learned whether it acted",
            )
        }

    /**
     * Found by migrating the first real consumer (B-32): a definition member could not announce
     * anything. konekt's top-up saga ends with a step whose whole job is to emit an event in the
     * same write as the state change, and the model had no way to say it.
     */
    @Test
    fun `a member announces in the write that records what it did`() =
        runBlocking {
            val log = Log()
            val repository = RowRepository()
            val definition =
                petichDefinition<OrderPayload>("order") {
                    step("charge", Acts("charge", log))
                    announce("notify", Announces("notify", log) { ctx -> ctx.emit(event("order-completed")) })
                }

            val result = engineFor(definition, repository).process(row("p-announced"))

            assertTrue(result is PetichResult.Success, "expected a completed saga: $result")
            assertEquals(listOf("order-completed"), repository.outbox, "the event rides with the write")
        }

    /**
     * And from a compensation, which is what `compensateWithEvents` was for: a rollback that has to
     * be announced is announced by the member that did the undoing, in the write that records it.
     */
    @Test
    fun `a compensation announces what it undid`() =
        runBlocking {
            val log = Log()
            val repository = RowRepository()
            val undoing =
                object : PetichStep<OrderPayload> {
                    override suspend fun execute(
                        ctx: PetichStepContext,
                        payload: OrderPayload,
                    ) {
                        log.entries.add("do:reserve")
                    }

                    override suspend fun compensate(
                        ctx: PetichStepContext,
                        payload: OrderPayload,
                    ) {
                        log.entries.add("undo:reserve")
                        ctx.emit(event("reservation-released"))
                    }
                }
            val definition =
                petichDefinition<OrderPayload>("order") {
                    step("reserve", undoing)
                    step("charge", Acts("charge", log) { ctx -> ctx.fail("the provider is down") })
                }

            engineFor(definition, repository).process(row("p-announced-undo"))

            assertTrue(
                repository.outbox.contains("reservation-released"),
                "the rollback said so, in its own write: ${repository.outbox}",
            )
        }

    /**
     * The sharpest edge the migration of a consumer found: a saga whose type matches no definition
     * used to run zero members and report Success. Four of konekt's tests then failed on a balance
     * that had not moved, and none of them on the cause — because the cause reported success.
     */
    @Test
    fun `a saga that matches no member is refused rather than completed`() =
        runBlocking {
            val log = Log()
            val repository = RowRepository()
            val definition = petichDefinition<OrderPayload>("order") { step("charge", Acts("charge", log)) }
            val engine =
                PetichEngine(
                    repository = repository,
                    clock = PetichClock { 1_000L },
                    definitions = listOf(definition),
                )

            val result = engine.process(row("p-wrong-type").copy(type = "ordr"))

            assertTrue(result is PetichResult.SystemFailure, "expected a refusal, not a success: $result")
            assertTrue(
                result.details.contains("`ordr`") && result.details.contains("order"),
                "the refusal has to name the type it got and the ones it knows: ${result.details}",
            )
            assertTrue(log.entries.isEmpty(), "and nothing ran: ${log.entries}")
        }

    /**
     * The question an application used to answer with a mapping of its own (B-31).
     *
     * `SuspendedPetichSweeper` and `SagaTimerSink` took `engineFor: (Petich) -> PetichEngine?`
     * because several engines shared one saga store. The definition is the value that says what an
     * `order` saga is, so the engine answers it — and there is no mapping left for anyone to leave
     * an entry out of.
     */
    @Test
    fun `an engine owns the types its definitions name and no others`() {
        val log = Log()
        val engine =
            engineFor(
                petichDefinition<OrderPayload>("order") { step("reserve", Acts("reserve", log)) },
                RowRepository(),
            )

        assertTrue(engine.owns(row("p-1")), "the type it was given a definition for")
        assertFalse(
            engine.owns(row("p-2").copy(type = "settlement")),
            "and not a type it has never heard of",
        )
    }
}
