package io.github.youndie.petich.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.youndie.petich.EnrichedPayload
import io.github.youndie.petich.PetichClock
import io.github.youndie.petich.PetichPayload
import io.github.youndie.petich.SimpleEnrichedPayload
import io.github.youndie.petich.conformance.ConformancePayload
import io.github.youndie.petich.conformance.Finding
import io.github.youndie.petich.conformance.IdempotencyStoreConformance
import io.github.youndie.petich.conformance.IdempotencyStoreSubject
import io.github.youndie.petich.conformance.OutboxStoreConformance
import io.github.youndie.petich.conformance.OutboxStoreSubject
import io.github.youndie.petich.conformance.PetichStoreConformance
import io.github.youndie.petich.conformance.PetichStoreSubject
import io.github.youndie.petich.conformance.ScheduleStoreConformance
import io.github.youndie.petich.conformance.ScheduleStoreSubject
import io.github.youndie.petich.outbox.OutboxRecord
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The four corpora of `petich-conformance`, run against this store on a real Postgres — on both
 * targets, which is the point of the module.
 *
 * The corpus was written while there was one implementation, deliberately
 * ([B-07](../../../../../../../../docs/backlog/B-07-storage-conformance-corpus.md)), so what it
 * reports here is a statement about petich's contract rather than about the intersection of two
 * stores. What it cannot see — two callers competing for one row — is in `ConcurrentWritersTest`
 * next to it, and that gap was named in B-07 rather than discovered.
 */
class PostgresConformanceTest {
    private val db = PostgresHarness.open()
    private val tables = PostgresHarness.tables()

    // The corpus writes a ConformancePayload, and polymorphic serialisation is registration on
    // every platform. Unregistered, every case fails with a serialisation error rather than a rule.
    private val json =
        Json {
            ignoreUnknownKeys = true
            serializersModule =
                SerializersModule {
                    polymorphic(PetichPayload::class) { subclass(ConformancePayload::class) }
                    polymorphic(EnrichedPayload::class) { subclass(SimpleEnrichedPayload::class) }
                }
        }

    private val clock = PetichClock { 1_000L }

    private val store =
        PostgresPetichStore(db, json, clock, table = tables.petiches, outboxTable = tables.outbox)

    private suspend fun prepare() {
        db.createSchema(tables)
        db.truncate(tables)
    }

    private inner class PetichSubject : PetichStoreSubject {
        override val repository = store

        override suspend fun reset() = db.truncate(tables)

        // Read from the table rather than through fetchPending: two rules are about rows that must
        // NOT be there, and an outbox whose fetch is broken also returns nothing.
        override suspend fun outboxRows(): List<OutboxRecord> =
            db
                .rows(sql("SELECT id, type, payload, retry_count FROM ${tables.outbox}"))
                .map {
                    OutboxRecord(
                        id = it.get("id").asString(),
                        type = it.get("type").asString(),
                        payload = it.get("payload").asString(),
                        retryCount = it.get("retry_count").asInt(),
                    )
                }
    }

    private inner class OutboxSubject : OutboxStoreSubject {
        override val repository = PostgresOutboxStore(db, tables.outbox)

        override suspend fun reset() = db.truncate(tables)

        override suspend fun givenPending(
            id: String,
            type: String,
            payload: String,
        ) {
            db.update(
                sql(
                    "INSERT INTO ${tables.outbox} (id, type, payload, status, retry_count, created_at) " +
                        "VALUES (:id, :type, :payload, 'PENDING', 0, 0)",
                ).bind("id", id).bind("type", type).bind("payload", payload),
            )
        }
    }

    private inner class IdempotencySubject : IdempotencyStoreSubject {
        override val repository = PostgresIdempotencyStore(db, clock, tables.idempotency)

        override suspend fun reset() = db.truncate(tables)
    }

    private inner class ScheduleSubject : ScheduleStoreSubject {
        override val repository = PostgresScheduleStore(db, tables.schedule)

        override suspend fun reset() = db.truncate(tables)
    }

    @Test
    fun `the Postgres saga store satisfies every rule of the corpus`() =
        runBlocking {
            prepare()
            assertNoFindings(PetichStoreConformance().run(PetichSubject()))
        }

    @Test
    fun `the Postgres outbox store satisfies every rule of the corpus`() =
        runBlocking {
            prepare()
            assertNoFindings(OutboxStoreConformance().run(OutboxSubject()))
        }

    @Test
    fun `the Postgres idempotency store satisfies every rule of the corpus`() =
        runBlocking {
            prepare()
            assertNoFindings(IdempotencyStoreConformance().run(IdempotencySubject()))
        }

    @Test
    fun `the Postgres schedule store satisfies every rule of the corpus`() =
        runBlocking {
            prepare()
            assertNoFindings(ScheduleStoreConformance().run(ScheduleSubject()))
        }

    private fun assertNoFindings(findings: List<Finding>) =
        assertTrue(
            findings.isEmpty(),
            "the corpus reported:\n" + findings.joinToString("\n") { "  ${it.rule}: ${it.detail}" },
        )
}
