package io.github.youndie.petich.postgres

import io.github.youndie.petich.EnrichedPayload
import io.github.youndie.petich.ExpiringPetichRepository
import io.github.youndie.petich.OutboxAwarePetichRepository
import io.github.youndie.petich.OutboxEvent
import io.github.youndie.petich.Petich
import io.github.youndie.petich.PetichClock
import io.github.youndie.petich.PetichPayload
import io.github.youndie.petich.PetichRepository
import io.github.youndie.petich.PetichStatus
import io.github.youndie.petich.SimpleEnrichedPayload
import io.github.youndie.petich.conformance.ConformancePayload
import io.github.youndie.petich.conformance.Finding
import io.github.youndie.petich.conformance.IdempotencyStoreConformance
import io.github.youndie.petich.conformance.IdempotencyStoreSubject
import io.github.youndie.petich.conformance.MovableClock
import io.github.youndie.petich.conformance.OutboxStoreConformance
import io.github.youndie.petich.conformance.OutboxStoreSubject
import io.github.youndie.petich.conformance.PetichStoreConformance
import io.github.youndie.petich.conformance.PetichStoreSubject
import io.github.youndie.petich.conformance.ScheduleStoreConformance
import io.github.youndie.petich.conformance.ScheduleStoreSubject
import io.github.youndie.petich.idempotency.IdempotencyRecord
import io.github.youndie.petich.idempotency.IdempotencyRepository
import io.github.youndie.petich.outbox.OutboxRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The four corpora of :petich-conformance, run against the Exposed store on a real Postgres.
 *
 * WHY A REAL DATABASE. Half the rules are about when a write becomes visible and what a refused
 * update leaves behind. An in-memory fake decides both for itself, which is the question rather
 * than the answer; and the version predicate, the unique constraint behind `tryClaim` and the
 * filters behind `findExpired`/`findDue` are SQL, so they only exist where SQL does.
 *
 * WHY THE MUTATIONS AT THE BOTTOM MATTER MORE THAN THE GREEN RUN. Everything here passed the first
 * time, and a corpus that has never reported anything is indistinguishable from one that cannot.
 * The three tests after the green ones break the store in a specific way each and assert that the
 * corpus names the broken rule.
 */
class ConformanceTest {
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

    // The corpus writes a ConformancePayload, and polymorphic serialisation is registration on
    // every platform — see the KDoc on ConformancePayload. Unregistered, every case would fail with
    // a serialisation error rather than with a rule.
    private val json =
        Json {
            ignoreUnknownKeys = true
            serializersModule =
                SerializersModule {
                    polymorphic(PetichPayload::class) { subclass(ConformancePayload::class) }
                    polymorphic(EnrichedPayload::class) { subclass(SimpleEnrichedPayload::class) }
                }
        }

    private val petichTable = PetichTable(json)
    private val outboxTable = OutboxEventsTable()
    private val idempotencyTable = IdempotencyKeysTable()
    private val scheduleTable = ScheduledJobsTable()

    // Movable, because one rule of the corpus is about what an UPDATE stamps and cannot be asked
    // while time stands still.
    private val movableClock = MovableClock()

    private val store = ExposedPetichRepository(db, petichTable, outboxTable, movableClock)

    init {
        transaction(db) {
            SchemaUtils.create(petichTable, outboxTable, idempotencyTable, scheduleTable)
        }
    }

    /**
     * The one statement a schema generator cannot produce, run against a real database.
     *
     * Without this the tuning is a string in a KDoc: a function nobody calls always works, and a
     * fill factor that was never applied looks exactly like one that was. The read-back is from
     * `pg_class`, which is where Postgres says what it actually did rather than what it was asked.
     */
    @Test
    fun `the tuning statement applies and Postgres reports it`() {
        transaction(db) {
            petichTable.tuningStatements().forEach { exec(it) }
        }

        val options =
            transaction(db) {
                exec("SELECT reloptions FROM pg_class WHERE relname = '${petichTable.tableName}'") { rs ->
                    if (rs.next()) rs.getString(1) else null
                }
            }

        assertTrue(
            options?.contains("fillfactor=80") == true,
            "pg_class reports reloptions = $options for ${petichTable.tableName}",
        )
    }

    private fun truncate() {
        transaction(db) {
            petichTable.deleteAll()
            outboxTable.deleteAll()
            idempotencyTable.deleteAll()
            scheduleTable.deleteAll()
        }
    }

    private fun outboxRowsNow(): List<OutboxRecord> =
        transaction(db) {
            outboxTable
                .selectAll()
                .map {
                    OutboxRecord(
                        id = it[outboxTable.id],
                        type = it[outboxTable.type],
                        payload = it[outboxTable.payload],
                        retryCount = it[outboxTable.retryCount],
                    )
                }
        }

    private inner class ExposedPetichSubject(
        override val repository: PetichRepository = store,
    ) : PetichStoreSubject {
        override val clock = movableClock

        override suspend fun reset() = truncate()

        // Read from the table rather than through fetchPending: two rules are about rows that must
        // NOT be there, and an outbox whose fetch is broken also returns nothing.
        override suspend fun outboxRows(): List<OutboxRecord> = outboxRowsNow()
    }

    private inner class ExposedOutboxSubject : OutboxStoreSubject {
        override val repository = ExposedOutboxRepository(db, outboxTable)

        override suspend fun reset() = truncate()

        override suspend fun givenPending(
            id: String,
            type: String,
            payload: String,
        ) {
            transaction(db) {
                outboxTable.insert {
                    it[this.id] = id
                    it[this.type] = type
                    it[this.payload] = payload
                    it[createdAt] = 0L
                }
            }
        }
    }

    private inner class ExposedIdempotencySubject(
        override val repository: IdempotencyRepository = ExposedIdempotencyRepository(db, idempotencyTable),
    ) : IdempotencyStoreSubject {
        override suspend fun reset() = truncate()
    }

    private inner class ExposedScheduleSubject : ScheduleStoreSubject {
        override val repository = ExposedScheduleRepository(db, scheduleTable)

        override suspend fun reset() = truncate()
    }

    @Test
    fun `the Exposed petich store satisfies every rule of the corpus`() =
        runBlocking {
            assertNoFindings(PetichStoreConformance().run(ExposedPetichSubject()))
        }

    @Test
    fun `the Exposed outbox store satisfies every rule of the corpus`() =
        runBlocking {
            assertNoFindings(OutboxStoreConformance().run(ExposedOutboxSubject()))
        }

    @Test
    fun `the Exposed idempotency store satisfies every rule of the corpus`() =
        runBlocking {
            assertNoFindings(IdempotencyStoreConformance().run(ExposedIdempotencySubject()))
        }

    @Test
    fun `the Exposed schedule store satisfies every rule of the corpus`() =
        runBlocking {
            assertNoFindings(ScheduleStoreConformance().run(ExposedScheduleSubject()))
        }

    // --- The controls. A corpus is only worth its green runs if a broken store turns it red. ---

    /** Last writer wins: the stored version is read and the write is rewritten to match it. */
    private inner class VersionBlindStore :
        OutboxAwarePetichRepository,
        ExpiringPetichRepository {
        override suspend fun findById(id: String): Petich? = store.findById(id)

        override suspend fun saveOrGet(petich: Petich): Petich = store.saveOrGet(petich)

        override suspend fun findExpired(
            nowEpochMs: Long,
            limit: Int,
        ): List<Petich> = store.findExpired(nowEpochMs, limit)

        override suspend fun findStuck(
            status: PetichStatus,
            notTouchedSinceEpochMs: Long,
            limit: Int,
        ): List<Petich> = store.findStuck(status, notTouchedSinceEpochMs, limit)

        override suspend fun update(
            petich: Petich,
            outboxEvents: List<OutboxEvent>,
        ): Boolean {
            val stored = findById(petich.id) ?: return false
            return store.update(petich.copy(version = stored.version + 1), outboxEvents)
        }
    }

    /** The state change commits and the intent to notify is dropped — silently, as it would be. */
    private inner class OutboxBlindStore :
        OutboxAwarePetichRepository,
        ExpiringPetichRepository {
        override suspend fun findById(id: String): Petich? = store.findById(id)

        override suspend fun saveOrGet(petich: Petich): Petich = store.saveOrGet(petich)

        override suspend fun findExpired(
            nowEpochMs: Long,
            limit: Int,
        ): List<Petich> = store.findExpired(nowEpochMs, limit)

        override suspend fun findStuck(
            status: PetichStatus,
            notTouchedSinceEpochMs: Long,
            limit: Int,
        ): List<Petich> = store.findStuck(status, notTouchedSinceEpochMs, limit)

        override suspend fun update(
            petich: Petich,
            outboxEvents: List<OutboxEvent>,
        ): Boolean = store.update(petich, emptyList())
    }

    /** `tryClaim` as "find, then insert" — the implementation its own KDoc forbids. */
    private inner class ReadThenWriteIdempotency : IdempotencyRepository {
        override suspend fun tryClaim(
            key: String,
            requestFingerprint: String,
        ): Boolean =
            withContext(Dispatchers.IO) {
                val existing = find(key)
                if (existing != null) {
                    false
                } else {
                    transaction(db) {
                        idempotencyTable.insert {
                            it[this.key] = key
                            it[this.requestFingerprint] = requestFingerprint
                            it[createdAt] = 0L
                        }
                    }
                    true
                }
            }

        override suspend fun find(key: String): IdempotencyRecord? =
            ExposedIdempotencyRepository(db, idempotencyTable).find(key)
    }

    @Test
    fun `a store without the version predicate is named by the corpus`() =
        runBlocking {
            val findings = PetichStoreConformance().run(ExposedPetichSubject(VersionBlindStore()))
            // Two rules, and the second one is the more interesting report. Without the version
            // predicate the stale update APPLIES, so the events attached to it are written — the
            // outbox rule is broken by a defect that is not in the outbox at all. A corpus that
            // named only the first would leave a reader looking in the wrong module.
            assertRules(
                findings,
                "an update carrying a stale version is refused and changes nothing",
                "an update that is refused writes no events",
            )
        }

    @Test
    fun `a store that drops the outbox events is named by the corpus`() =
        runBlocking {
            val findings = PetichStoreConformance().run(ExposedPetichSubject(OutboxBlindStore()))
            assertRules(
                findings,
                "outbox events land in the same call that applies the update",
            )
        }

    @Test
    fun `a tryClaim that reads before it writes is named by the corpus`() =
        runBlocking {
            val findings = IdempotencyStoreConformance().run(ExposedIdempotencySubject(ReadThenWriteIdempotency()))
            assertRules(
                findings,
                "two callers racing for one new key produce exactly one winner",
            )
        }

    // --- B-10: where time comes from ------------------------------------------------------------

    /**
     * Both stamps come from the clock the store was handed, not from the platform.
     *
     * The value is deliberately absurd — 4242 is not a plausible epoch millisecond — so that a store
     * which read `System.currentTimeMillis()` instead cannot pass by coincidence: it would write
     * something around 1.7e12.
     */
    @Test
    fun `the outbox stamp comes from the clock the store was given`() =
        runBlocking {
            truncate()
            val fixed = PetichClock { 4_242L }
            val store = ExposedPetichRepository(db, petichTable, outboxTable, fixed)

            store.saveOrGet(petich("stamped"))
            store.update(petich("stamped").copy(version = 1L), listOf(event("evt-stamped")))

            val stamps =
                transaction(db) {
                    outboxTable.selectAll().map { it[outboxTable.createdAt] }
                }
            assertEquals(listOf(4_242L), stamps)
        }

    @Test
    fun `the idempotency stamp comes from the clock the store was given`() =
        runBlocking {
            truncate()
            val fixed = PetichClock { 4_242L }
            val store = ExposedIdempotencyRepository(db, idempotencyTable, fixed)

            store.tryClaim("key", "fingerprint")

            val stamps =
                transaction(db) {
                    idempotencyTable.selectAll().map { it[idempotencyTable.createdAt] }
                }
            assertEquals(listOf(4_242L), stamps)
        }

    private fun petich(id: String) =
        Petich(
            id = id,
            type = "clock",
            status = io.github.youndie.petich.PetichStatus.PROCESSING,
            payload = ConformancePayload(id),
        )

    private fun event(id: String) =
        object : OutboxEvent {
            override val id: String = id
            override val type: String = "clock.event"
            override val payload: String = "{}"
        }

    private fun assertNoFindings(findings: List<Finding>) =
        assertTrue(
            findings.isEmpty(),
            "the corpus reported:\n" + findings.joinToString("\n") { "  ${it.rule}: ${it.detail}" },
        )

    private fun assertRules(
        findings: List<Finding>,
        vararg expected: String,
    ) = assertEquals(
        expected.toList(),
        findings.map { it.rule },
        "the corpus reported:\n" + findings.joinToString("\n") { "  ${it.rule}: ${it.detail}" },
    )
}
