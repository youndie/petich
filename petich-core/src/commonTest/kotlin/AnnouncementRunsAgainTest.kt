package io.github.youndie.petich

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B-50: an announcement is a member like any other, and the position advances AFTER its body returns.
 *
 * A member with no `compensate` reads as a member that runs once. It is not one: a process that dies
 * inside `announce` leaves the row pointing at it, and `SuspendedPetichSweeper` re-drives sagas left
 * in `PROCESSING`. An announcement that only calls `ctx.emit` is saved by the outbox key; one that
 * sends a mail is not, and shashki has exactly that.
 *
 * Written against a member that does I/O, because one that only emits cannot show this.
 */
class AnnouncementRunsAgainTest {
    private data class OrderPayload(
        val rideId: String,
    ) : PetichPayload()

    /** A relay that will happily send the same receipt twice unless told the two are one. */
    private class Mailer {
        val sent: MutableList<String> = mutableListOf()
        private val seen: MutableSet<String> = mutableSetOf()

        fun send(
            key: String?,
            to: String,
        ) {
            if (key != null && !seen.add(key)) return
            sent.add(to)
        }
    }

    private class SendReceipt(
        private val mailer: Mailer,
        private val named: Boolean,
    ) : PetichAnnouncement<OrderPayload> {
        override suspend fun announce(
            ctx: PetichAnnouncementContext,
            payload: OrderPayload,
        ) {
            mailer.send(if (named) ctx.idempotencyKey else null, "rider@example.test")
        }
    }

    /**
     * Refuses the write that would commit the announcement's progress, which is what a process dying
     * between the body and the commit looks like from storage: the effect happened and the row did
     * not move.
     */
    private class DyingRepository : PetichRepository {
        var row: Petich? = null
        var refuseUpdates: Boolean = true

        override suspend fun findById(id: String): Petich? = row?.takeIf { it.id == id }

        override suspend fun saveOrGet(petich: Petich): Petich {
            val existing = row
            if (existing != null && existing.id == petich.id) return existing
            row = petich
            return petich
        }

        override suspend fun update(petich: Petich): Boolean {
            if (refuseUpdates) return false
            val existing = row ?: return false
            if (petich.version != existing.version + 1) return false
            row = petich
            return true
        }
    }

    @Test
    fun `an unnamed announcement sends the receipt twice when the first pass never committed`() =
        runBlocking {
            val mailer = Mailer()
            val repository = DyingRepository()
            val saga = order()

            diedBeforeCommitting(engine(repository, mailer, named = false), saga)
            repository.refuseUpdates = false
            engine(repository, mailer, named = false).process(checkNotNull(repository.row))

            // THE DEFECT, kept live — and larger than the item described it. The crash window is
            // not the only way in: `processWithRetry` re-runs the whole pass on an optimistic-lock
            // conflict, `maxProcessAttempts` times, and the announcement is re-run with it. Six
            // receipts here: five attempts plus the pass that finally committed.
            //
            // Asserted as "more than one" rather than as six, because six is a property of the
            // default `maxProcessAttempts` and the claim is not about that number.
            assertTrue(mailer.sent.size > 1, "one receipt would mean the re-run was prevented")
        }

    @Test
    fun `a named announcement sends it once`() =
        runBlocking {
            val mailer = Mailer()
            val repository = DyingRepository()
            val saga = order()

            diedBeforeCommitting(engine(repository, mailer, named = true), saga)
            repository.refuseUpdates = false
            val result = engine(repository, mailer, named = true).process(checkNotNull(repository.row))

            assertEquals(1, mailer.sent.size)
            assertEquals(true, result is PetichResult.Success, "$result")
        }

    /**
     * The pass that never lands: every write is refused, so the engine exhausts `maxProcessAttempts`
     * and gives up. Named rather than swallowed, because the exception is the point — it is what a
     * process dying between a member's body and its commit leaves behind.
     */
    private suspend fun diedBeforeCommitting(
        engine: PetichEngine,
        saga: Petich,
    ) {
        try {
            engine.process(saga)
            throw IllegalStateException("the pass was supposed to fail to commit")
        } catch (expected: OptimisticLockException) {
            check(expected.message != null)
        }
    }

    private fun engine(
        repository: PetichRepository,
        mailer: Mailer,
        named: Boolean,
    ) = PetichEngine(
        repository = repository,
        definitions =
            listOf(
                petichDefinition<OrderPayload>("order") {
                    announce("receipt", SendReceipt(mailer, named))
                },
            ),
    )

    private fun order() =
        Petich(
            id = "ride-1",
            type = "order",
            status = PetichStatus.DRAFT,
            payload = OrderPayload("ride-1"),
        )
}
