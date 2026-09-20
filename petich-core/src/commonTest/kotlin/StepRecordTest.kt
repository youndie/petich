package io.github.youndie.petich

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * B-29: a compensation can tell "the step did not happen" from "the step happened and recorded
 * nothing", and the answer is evidence rather than a guess.
 *
 * The guess is what youndie/shashki#13 is: a charge id kept in the payload every member shares, a
 * compensation that fell back to another settlement's hold when the id was absent, and a refund of
 * a fare because a tip charge timed out.
 */
class StepRecordTest {
    data class OrderPayload(
        val sku: String,
    ) : PetichPayload()

    @Serializable
    @SerialName("reservation")
    data class Reservation(
        val id: String,
    ) : PetichStepRecord()

    private class Log {
        val entries: MutableList<String> = mutableListOf()
    }

    /** Records what it did, and undoes only what it recorded. */
    private class Reserve(
        private val log: Log,
        private val throwInstead: Boolean = false,
        private val throwAfterRecording: Boolean = false,
        private val suspendAfterRecording: Boolean = false,
    ) : PetichStep<OrderPayload> {
        override suspend fun execute(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            if (throwInstead) error("the answer was lost")
            ctx.record(Reservation("res-for-${payload.sku}"))
            log.entries.add("do:reserve")
            // The far side took it and the answer did not come back — the case B-18 exists for, and
            // the one where the record has to survive a member that never returned.
            if (throwAfterRecording) error("the answer was lost after the reservation was made")
            if (suspendAfterRecording) ctx.suspendFor("CONFIRM", 5.minutes)
        }

        override suspend fun compensate(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            val done = ctx.recorded<Reservation>()
            log.entries.add(if (done == null) "undo:reserve:nothing-to-undo" else "undo:reserve:${done.id}")
        }
    }

    /** Records nothing, and asks anyway — which is how a member learns it cannot read another's. */
    private class Charge(
        private val log: Log,
        private val fail: Boolean = false,
        private val suspendHere: Boolean = false,
        private val throwInstead: Boolean = false,
    ) : PetichStep<OrderPayload> {
        override suspend fun execute(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            log.entries.add("do:charge")
            if (suspendHere) return ctx.suspendFor("CONFIRM", 5.minutes)
            if (throwInstead) error("the answer was lost")
            if (fail) ctx.fail("the provider is down")
        }

        override suspend fun compensate(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            val borrowed = ctx.recorded<Reservation>()
            log.entries.add("undo:charge:${borrowed?.id ?: "own-record-absent"}")
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

    private fun row(id: String) =
        Petich(id = id, type = "order", status = PetichStatus.PROCESSING, payload = OrderPayload("sku-1"))

    private fun engineOver(
        repository: PetichRepository,
        definition: PetichDefinition<OrderPayload>,
    ) = PetichEngine(
        repository = repository,
        clock = PetichClock { 1_000L },
        definitions = listOf(definition),
    )

    @Test
    fun `a compensation undoes what its own step recorded`() =
        runBlocking {
            val log = Log()
            val repository = RowRepository()
            val definition =
                petich<OrderPayload>("order") {
                    step("reserve", Reserve(log))
                    step("charge", Charge(log, fail = true))
                }

            engineOver(repository, definition).process(row("p-recorded"))

            assertTrue(
                log.entries.contains("undo:reserve:res-for-sku-1"),
                "the compensation read the id its own step wrote: ${log.entries}",
            )
            assertEquals(
                Reservation("res-for-sku-1"),
                repository.row?.stepRecords?.get("reserve"),
                "and it was committed with the position, not kept in memory",
            )
        }

    /**
     * The case the whole item exists for. The step threw before recording anything, so its own
     * compensation — which B-18 now calls — finds nothing and knows it. Under the shared payload
     * this was indistinguishable from a step that ran and had nothing to say.
     */
    @Test
    fun `a step that never got as far as recording has nothing to undo`() =
        runBlocking {
            val log = Log()
            val repository = RowRepository()
            val definition =
                petich<OrderPayload>("order") {
                    step("reserve", Reserve(log, throwInstead = true))
                    step("charge", Charge(log))
                }

            engineOver(repository, definition).process(row("p-threw"))

            assertEquals(
                listOf("undo:reserve:nothing-to-undo"),
                log.entries,
                "the step threw, so its compensation is called and finds no record: ${log.entries}",
            )
            assertTrue(
                repository.row?.stepRecords.isNullOrEmpty(),
                "and nothing was recorded for it: ${repository.row?.stepRecords}",
            )
        }

    /**
     * `charge` THROWS rather than calling `ctx.fail`, and the difference is B-18's: `fail` is a
     * reported outcome, so the member that reports it is not undone, while a throw leaves its
     * outcome unknown and the member IS undone. Only the second reaches `charge`'s own compensation,
     * which is where it can be shown that reserve's record is not visible from there.
     */
    @Test
    fun `a member cannot read a record another member wrote`() =
        runBlocking {
            val log = Log()
            val repository = RowRepository()
            val definition =
                petich<OrderPayload>("order") {
                    step("reserve", Reserve(log))
                    step("charge", Charge(log, throwInstead = true))
                }

            engineOver(repository, definition).process(row("p-scoped"))

            assertTrue(
                log.entries.contains("undo:charge:own-record-absent"),
                "charge asked for a Reservation and got its own absence, not reserve's: ${log.entries}",
            )
        }

    @Test
    fun `a record survives a suspension and the resume that follows`() =
        runBlocking {
            val log = Log()
            val repository = RowRepository()
            val definition =
                petich<OrderPayload>("order") {
                    step("reserve", Reserve(log))
                    step("charge", Charge(log, suspendHere = true))
                }
            val engine = engineOver(repository, definition)

            val waiting = engine.process(row("p-waited"))
            assertTrue(waiting is PetichResult.ActionRequired, "expected a suspension: $waiting")
            assertEquals(
                Reservation("res-for-sku-1"),
                repository.row?.stepRecords?.get("reserve"),
                "the record is in the row the saga waits in",
            )

            val finished = engine.process(repository.row!!)
            assertTrue(finished is PetichResult.Success, "expected the saga to finish: $finished")
            assertEquals(
                Reservation("res-for-sku-1"),
                repository.row?.stepRecords?.get("reserve"),
                "and it is still there when the saga completes",
            )
        }

    /**
     * The case the `finally` in `DefinitionRun` is for, and the one this channel was built around: a
     * member takes the effect, writes down what it did, and then the call it is inside throws. Its
     * own compensation is called (B-18) and must see the record — otherwise it concludes "the step
     * did not happen" about a step that did.
     */
    @Test
    fun `a member that records and then throws is undone with its record in hand`() =
        runBlocking {
            val log = Log()
            val repository = RowRepository()
            val definition =
                petich<OrderPayload>("order") {
                    step("reserve", Reserve(log, throwAfterRecording = true))
                }

            engineOver(repository, definition).process(row("p-threw-after"))

            assertEquals(
                listOf("do:reserve", "undo:reserve:res-for-sku-1"),
                log.entries,
                "the compensation had the id its own step wrote before the throw: ${log.entries}",
            )
        }

    /**
     * And the case mutation found by NOT failing: a member that records and then suspends leaves
     * the record only in memory, so the write that records the wait is the one that has to carry it.
     */
    @Test
    fun `a record made by the member that suspends is committed with the wait`() =
        runBlocking {
            val log = Log()
            val repository = RowRepository()
            val definition =
                petich<OrderPayload>("order") {
                    step("reserve", Reserve(log, suspendAfterRecording = true))
                    step("charge", Charge(log))
                }

            val waiting = engineOver(repository, definition).process(row("p-record-then-wait"))

            assertTrue(waiting is PetichResult.ActionRequired, "expected a suspension: $waiting")
            assertEquals(
                Reservation("res-for-sku-1"),
                repository.row?.stepRecords?.get("reserve"),
                "the record has to be in the row the saga waits in, not only in the pass that made it",
            )
        }
}
