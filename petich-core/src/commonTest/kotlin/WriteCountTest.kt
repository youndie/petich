package io.github.youndie.petich

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * B-23: the number the README sells the engine on, counted.
 *
 * "About 17 database writes, 11 of them into the saga table" was taken once, by hand, through
 * `pg_stat_user_tables`, for a scenario named in half a sentence. Nothing reproduced it, and four
 * items have since changed what the engine writes — attempts on a failed rollback, a stamp on every
 * row, a fingerprint on every write — without changing how many, which is a fact nobody could check.
 *
 * **Counted here rather than read from the statistics collector**, which is asynchronous and
 * cumulative: a test reading it is a test that fails on a busy machine. What the README is really
 * claiming is the number of statements the engine issues, and a repository that counts its own calls
 * observes exactly that, deterministically and with no database at all.
 */
class WriteCountTest {
    data class OrderPayload(
        val sku: String,
    ) : PetichPayload()

    /**
     * POST_PROCESSING's member, and it still costs a write: an announcement proceeds like anything
     * else, so the position it advances to and the event it asked for are committed together.
     */
    class Announces(
        private val name: String,
        private val events: Int = 0,
    ) : PetichAnnouncement<OrderPayload> {
        override suspend fun announce(
            ctx: PetichAnnouncementContext,
            payload: OrderPayload,
        ) {
            repeat(events) { n ->
                ctx.emit(
                    object : OutboxEvent {
                        override val id = "${ctx.petich.id}:$name:$n"
                        override val type = "test.event"
                        override val payload = "{}"
                    },
                )
            }
        }
    }

    class Step(
        private val name: String,
        private val suspendHere: Boolean = false,
        private val events: Int = 0,
    ) : PetichStep<OrderPayload> {
        override suspend fun execute(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            if (suspendHere) return ctx.suspendFor("CONFIRM")
            (0 until events).forEach { ctx.emit(event("${ctx.petich.id}:$name:$it")) }
        }

        override suspend fun compensate(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) = Unit

        private fun event(id: String) =
            object : OutboxEvent {
                override val id = id
                override val type = "test.event"
                override val payload = "{}"
            }
    }

    /** Counts what the engine asks of storage, separating the row from the events beside it. */
    class CountingRepository : OutboxAwarePetichRepository {
        var row: Petich? = null
        var inserts: Int = 0
        var updates: Int = 0
        var events: Int = 0

        val sagaTableWrites: Int get() = inserts + updates

        override suspend fun findById(id: String): Petich? = row?.takeIf { it.id == id }

        override suspend fun saveOrGet(petich: Petich): Petich {
            val existing = row
            if (existing != null && existing.id == petich.id) return existing
            row = petich
            inserts++
            return petich
        }

        override suspend fun update(
            petich: Petich,
            outboxEvents: List<OutboxEvent>,
        ): Boolean {
            val existing = row ?: return false
            if (petich.version != existing.version + 1) return false
            row = petich
            updates++
            events += outboxEvents.size
            return true
        }
    }

    private fun row(id: String) =
        Petich(
            id = id,
            type = "order",
            status = PetichStatus.PROCESSING,
            payload = OrderPayload("sku-1"),
        )

    /**
     * The same six members, one per phase but for EXECUTION, which carries two.
     *
     * All six are steps, so all six are in EXECUTION: what this test counts is WRITES per member,
     * and a check costs the same write a step does. Keeping them steps keeps the count at six with
     * no member losing its undo — and since B-39 a member that can act has one phase to be in.
     */
    private fun sixSteps(suspendAt: String? = null) =
        petichDefinition<OrderPayload>("order") {
            step("enrich", Step("enrich"))
            step("validate", Step("validate"))
            step("authorise", Step("authorise", suspendHere = suspendAt == "authorise"))
            step("reserve", Step("reserve", events = 1))
            step("charge", Step("charge", events = 1))
            announce("notify", Announces("notify", events = 1))
        }

    @Test
    fun `a six-step saga that runs straight through costs one write per step plus two`() =
        runBlocking {
            val repository = CountingRepository()
            val result =
                PetichEngine(
                    repository = repository,
                    definitions = listOf(sixSteps()),
                ).process(row("p-straight"))

            assertEquals(PetichResult.Success::class, result::class, "expected a completed saga: $result")
            assertEquals(1, repository.inserts, "the saga is inserted once")
            assertEquals(7, repository.updates, "one per step, plus the write that completes it")
            assertEquals(8, repository.sagaTableWrites, "1 INSERT + 6 steps + 1 final")
            assertEquals(3, repository.events, "the events the steps handed over, committed with those writes")
        }

    /**
     * The surprise this test was written to find, and did: **a suspension adds no write to the saga
     * table, it moves one.** The step that suspends writes PENDING_SIGNATURE instead of the Proceed
     * it would have written, and it is deliberately not re-executed on resume — so the same six
     * steps cost the same eight writes whether the saga waits for a human in the middle or not.
     *
     * What a suspension does cost is a `saveOrGet` at the head of the resuming pass. It changes no
     * tuple here, which is why this counter does not see it; in the sqlx4k store it is an
     * `INSERT … ON CONFLICT DO NOTHING`, so a consumer counting STATEMENTS rather than tuple changes
     * will see one more than this test does. That difference is the reason the README now says which
     * of the two it means.
     */
    @Test
    fun `a suspension moves a write rather than adding one`() =
        runBlocking {
            val repository = CountingRepository()
            val engine = PetichEngine(repository = repository, definitions = listOf(sixSteps(suspendAt = "authorise")))

            val suspended = engine.process(row("p-suspended"))
            assertEquals(
                PetichResult.ActionRequired::class,
                suspended::class,
                "expected a suspension: $suspended",
            )
            val untilHere = repository.sagaTableWrites

            val resumed = engine.process(repository.row!!)
            assertEquals(PetichResult.Success::class, resumed::class, "expected a completed saga: $resumed")

            assertEquals(4, untilHere, "1 INSERT + 2 steps + the write that records the wait")
            assertEquals(
                8,
                repository.sagaTableWrites,
                "the same as the straight-through saga: the suspending step is not re-executed, so " +
                    "its PENDING_SIGNATURE write stands in for the Proceed it never made",
            )
            assertEquals(
                3,
                repository.events,
                "a suspension costs writes, not events: the steps hand over the same three",
            )
        }
}
