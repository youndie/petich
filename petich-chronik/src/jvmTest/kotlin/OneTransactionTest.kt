package ru.workinprogress.petich.chronik

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import ru.workinprogress.chronik.EpochSeconds
import ru.workinprogress.chronik.TimerState
import ru.workinprogress.chronik.postgres.ExposedTimerStore
import ru.workinprogress.chronik.postgres.TimersTable
import ru.workinprogress.chronik.postgres.asTimerTransaction
import ru.workinprogress.petich.EnrichedPayload
import ru.workinprogress.petich.InterceptorResult
import ru.workinprogress.petich.OutboxAwarePetichRepository
import ru.workinprogress.petich.OutboxEvent
import ru.workinprogress.petich.Petich
import ru.workinprogress.petich.PetichEngine
import ru.workinprogress.petich.PetichEngineConfig
import ru.workinprogress.petich.PetichInterceptor
import ru.workinprogress.petich.PetichPayload
import ru.workinprogress.petich.PetichPhase
import ru.workinprogress.petich.PetichRepository
import ru.workinprogress.petich.PetichStatus
import ru.workinprogress.petich.SimpleEnrichedPayload
import ru.workinprogress.petich.postgres.ExposedPetichRepository
import ru.workinprogress.petich.postgres.OutboxEventsTable
import ru.workinprogress.petich.postgres.PetichTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The claim the whole change exists for: a saga's state change and its timer become visible
 * TOGETHER, or neither does.
 *
 * Against a real Postgres, because nothing smaller can answer it. A fake repository commits when
 * the test tells it to, which is precisely the thing being asked about; H2 would not settle whether
 * two nested Exposed transactions are one transaction or two, which is the mechanism this leans on.
 */
class OneTransactionTest {
    private companion object {
        val container: PostgreSQLContainer =
            PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine")).apply {
                withDatabaseName("petich")
                withUsername("petich")
                withPassword("petich")
                start()
            }

        val db: Database =
            Database.connect(
                url = container.jdbcUrl,
                driver = "org.postgresql.Driver",
                user = container.username,
                password = container.password,
            )
    }

    @Serializable
    private data class Payload(
        val what: String = "waiting",
    ) : PetichPayload()

    // The payload travels as polymorphic JSON, so the subclass has to be registered — the saga
    // table cannot guess it, and the failure comes out at the first write rather than at wiring.
    private val json =
        Json {
            ignoreUnknownKeys = true
            serializersModule =
                SerializersModule {
                    polymorphic(PetichPayload::class) { subclass(Payload::class) }
                    // The engine's own default enriched payload is polymorphic too, and just as
                    // unregistered by default.
                    polymorphic(EnrichedPayload::class) { subclass(SimpleEnrichedPayload::class) }
                }
        }
    private val petichTable = PetichTable(json)
    private val outboxTable = OutboxEventsTable()
    private val timersTable = TimersTable("saga_timers")
    private val timerStore = ExposedTimerStore(db, timersTable)

    private fun freshSchema() {
        transaction(db) {
            SchemaUtils.drop(timersTable, outboxTable, petichTable)
            SchemaUtils.create(petichTable, outboxTable, timersTable)
        }
    }

    /**
     * The join: one Exposed transaction, and the delegate's own `suspendTransaction` nested inside
     * it reuses it rather than opening a second. That reuse is exactly what the test below has to
     * prove, and it is why this wiring is a parameter of the repository rather than an assumption
     * inside it.
     */
    private fun repository(delegate: OutboxAwarePetichRepository) =
        ChronikPetichRepository(delegate, timerStore) { body ->
            suspendTransaction(db = db) { body(asTimerTransaction()) }
        }

    /** A step that waits until an instant, and asks for the timer that will wake it. */
    private class AwaitUntil(
        private val at: EpochSeconds,
    ) : PetichInterceptor<Payload> {
        override val phase = PetichPhase.EXECUTION

        override fun supports(payload: PetichPayload) = payload is Payload

        override suspend fun intercept(
            petich: Petich,
            payload: Payload,
        ): InterceptorResult =
            InterceptorResult.Suspend(
                requiredAction = "AWAIT_DEADLINE",
                sideEffects = listOf(ScheduleTimer("timer-${petich.id}", at, petich.id)),
            )

        override suspend fun compensate(
            petich: Petich,
            payload: Payload,
        ) = Unit
    }

    private fun saga(id: String) =
        Petich(
            id = id,
            type = "t",
            currentPhase = PetichPhase.EXECUTION,
            status = PetichStatus.PROCESSING,
            payload = Payload(),
        )

    @Test
    fun `a suspended saga and its timer are both there`() =
        runTest {
            freshSchema()
            val delegate = ExposedPetichRepository(db, petichTable, outboxTable)
            val engine = PetichEngine(listOf(AwaitUntil(EpochSeconds(1_000))), repository(delegate))

            engine.process(saga("s1"))

            assertEquals(PetichStatus.PENDING_SIGNATURE, delegate.findById("s1")?.status)

            val timer = timerStore.findById("timer-s1")
            assertEquals(EpochSeconds(1_000), timer?.dueAt)
            assertEquals("s1", timer?.payload, "the timer carries the saga it must wake")
            assertEquals(TimerState.PENDING, timer?.state)
        }

    /**
     * A refused state write is not followed by a timer write.
     *
     * NAMED FOR WHAT IT ACTUALLY CHECKS. It first said "takes its timer with it", which claims
     * atomicity — and a control showed it does not: removing the early return in
     * ChronikPetichRepository is what fails it, not any property of the transaction. Ordering and
     * atomicity are different guarantees, and this is the cheaper one. The next test is the other.
     */
    @Test
    fun `a refused state write is not followed by a timer write`() =
        runTest {
            freshSchema()
            val delegate = ExposedPetichRepository(db, petichTable, outboxTable)

            // A repository whose state write always refuses, as a version conflict does. A
            // decorator rather than a subclass: the Exposed one is final, and forking it to test it
            // would test the fork.
            val refusingState =
                object : OutboxAwarePetichRepository, PetichRepository by delegate {
                    override suspend fun update(
                        petich: Petich,
                        outboxEvents: List<OutboxEvent>,
                    ): Boolean = false

                    override suspend fun update(petich: Petich): Boolean = false
                }
            val engine =
                PetichEngine(
                    listOf(AwaitUntil(EpochSeconds(1_000))),
                    repository(refusingState),
                    config = PetichEngineConfig(maxStateUpdateAttempts = 1),
                )

            runCatching { engine.process(saga("s2")) }

            assertNull(
                timerStore.findById("timer-s2"),
                "the timer outlived a state change that never happened",
            )
        }

    @Test
    fun `requireSideEffects accepts this repository and refuses the bare one`() {
        val delegate = ExposedPetichRepository(db, petichTable, outboxTable)

        PetichEngine(
            listOf(AwaitUntil(EpochSeconds(1))),
            repository(delegate),
            config = PetichEngineConfig(requireSideEffects = true),
        )

        val failed =
            runCatching {
                PetichEngine(
                    listOf(AwaitUntil(EpochSeconds(1))),
                    delegate,
                    config = PetichEngineConfig(requireSideEffects = true),
                )
            }.isFailure

        assertEquals(true, failed, "a repository that cannot store side effects must be refused")
    }

    /**
     * ATOMICITY, and it needs the nesting to be real.
     *
     * Both writes go in, and then the caller's own transaction throws. If the delegate's
     * `suspendTransaction` had opened a SECOND transaction rather than joining this one, the saga
     * row would already be committed and would survive — leaving a saga whose timer is gone, or a
     * timer whose saga is gone, depending on which half committed.
     *
     * This is the claim `ChronikPetichRepository` exists to make, and the only one of the two that
     * cannot be had by careful ordering.
     */
    @Test
    fun `an abandoned caller transaction takes both the saga and its timer`() =
        runTest {
            freshSchema()
            val delegate = ExposedPetichRepository(db, petichTable, outboxTable)
            val repository = repository(delegate)

            // Recorded, not asserted, INSIDE the block: the runCatching below swallows the
            // deliberate failure, and it would swallow a failed assertion just as happily — leaving
            // a test that passes because nothing was ever written. Asserted after.
            var timerRowsInside = -1
            var sagaRowsInside = -1

            // The saga has to EXIST before it can be updated: the delegate's write is an
            // `UPDATE ... WHERE id = ? AND version = ?`, and against a missing row it touches
            // nothing, returns false, and the early return means no timer is written either. The
            // first version of this test did exactly that and passed — asserting that a timer which
            // had never been written was absent.
            ExposedPetichRepository(db, petichTable, outboxTable).saveOrGet(saga("s3"))

            // suspendTransaction FOR THE OUTER ONE TOO, and this is the whole mechanism.
            //
            // The blocking `transaction {}` keeps its transaction in a thread local; the suspending
            // one keeps it in the coroutine context. They do not see each other: an inner
            // `suspendTransaction` inside a blocking `transaction` finds nothing to join, opens its
            // own, and COMMITS IT — which is exactly what the first version of this test did, and
            // the timer duly outlived a transaction that was never its own. Mixing the two is the
            // trap anybody wiring this up will fall into.
            runCatching {
                suspendTransaction(db = db) {
                    run {
                        repository.update(
                            // version + 1, which is what the engine writes and what the optimistic
                            // lock in the delegate compares against.
                            saga("s3").copy(version = 1, status = PetichStatus.PENDING_SIGNATURE),
                            emptyList(),
                            listOf(ScheduleTimer("timer-s3", EpochSeconds(1_000), "s3")),
                        )
                    }
                    // BOTH WRITES ARE VISIBLE HERE, INSIDE THE TRANSACTION, and asserting it is
                    // what stops the test below passing vacuously: "gone afterwards" says nothing
                    // if they were never there. Read through this transaction, which is the only
                    // place uncommitted rows exist.
                    timerRowsInside =
                        exec("SELECT count(*) FROM saga_timers WHERE id = 'timer-s3'") { rs ->
                            rs.next()
                            rs.getInt(1)
                        } ?: -1
                    sagaRowsInside =
                        exec("SELECT count(*) FROM petiches WHERE id = 's3'") { rs ->
                            rs.next()
                            rs.getInt(1)
                        } ?: -1

                    // The caller's own step refuses, after both writes have gone in.
                    error("the caller's step refused")
                }
            }

            // Both were there, inside the transaction. Only now does their absence mean anything.
            assertEquals(1, timerRowsInside, "the timer was never written, so its absence proves nothing")
            assertEquals(1, sagaRowsInside, "the saga was never written, so its absence proves nothing")

            assertNull(timerStore.findById("timer-s3"), "the timer outlived the transaction it was written in")

            // The saga ROW was created before the transaction and is meant to survive; what must
            // not survive is the CHANGE the transaction made to it. Asserting its absence was
            // wrong, and the run said so.
            val after = delegate.findById("s3")
            assertEquals(PetichStatus.PROCESSING, after?.status, "the state change outlived its transaction")
            assertEquals(0L, after?.version, "the version moved, so the update was committed after all")
        }
}
