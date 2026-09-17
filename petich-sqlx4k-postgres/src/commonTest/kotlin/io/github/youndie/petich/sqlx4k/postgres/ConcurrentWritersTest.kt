package io.github.youndie.petich.sqlx4k.postgres

import io.github.youndie.petich.EnrichedPayload
import io.github.youndie.petich.Petich
import io.github.youndie.petich.PetichClock
import io.github.youndie.petich.PetichPayload
import io.github.youndie.petich.PetichStatus
import io.github.youndie.petich.SimpleEnrichedPayload
import io.github.youndie.petich.conformance.ConformancePayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the corpus structurally cannot ask: two callers competing for one row.
 *
 * `petich-conformance` runs one caller at a time, so a store that satisfies every rule in it can
 * still let two workers both believe they own a saga — the defect chronik's corpus could not see in
 * its own second store, named in B-07 as this item's debt rather than left to be discovered.
 *
 * Both cases here run on `Dispatchers.Default`, which is several threads on both targets, and both
 * assert a **count**: the same number on a fast machine and a loaded one, where a duration would be
 * measuring the runner.
 */
class ConcurrentWritersTest {
    private val db = PostgresHarness.open()
    private val tables = PostgresHarness.tables()

    private val json =
        Json {
            serializersModule =
                SerializersModule {
                    polymorphic(PetichPayload::class) { subclass(ConformancePayload::class) }
                    polymorphic(EnrichedPayload::class) { subclass(SimpleEnrichedPayload::class) }
                }
        }

    private val store =
        PostgresPetichStore(db, json, PetichClock { 0L }, tables.petiches, tables.outbox)

    private suspend fun prepare() {
        db.createSchema(tables)
        db.truncate(tables)
    }

    private fun petich(
        id: String,
        marker: String,
        version: Long = 0L,
    ) = Petich(
        id = id,
        type = "concurrency",
        status = PetichStatus.PROCESSING,
        payload = ConformancePayload(marker),
        version = version,
    )

    @Test
    fun `four writers racing to advance one saga leave exactly one winner`() =
        runBlocking {
            prepare()
            store.saveOrGet(petich("contended", "initial"))

            val applied =
                withContext(Dispatchers.Default) {
                    coroutineScope {
                        (1..4)
                            .map { writer ->
                                async { store.update(petich("contended", "writer-$writer", version = 1L)) }
                            }.awaitAll()
                    }
                }

            assertEquals(
                1,
                applied.count { it },
                "$applied — the version predicate is what makes this one, and a store without it " +
                    "would let every writer through and lose three updates silently",
            )
            assertEquals(1L, store.findById("contended")?.version)
        }

    @Test
    fun `four callers saving one new saga at once store it once and all see the same row`() =
        runBlocking {
            prepare()

            val returned =
                withContext(Dispatchers.Default) {
                    coroutineScope {
                        (1..4)
                            .map { caller ->
                                async { store.saveOrGet(petich("fresh", "caller-$caller")) }
                            }.awaitAll()
                    }
                }

            assertEquals(
                1,
                returned.map { (it.payload as ConformancePayload).marker }.distinct().size,
                "the four callers were handed different rows: " +
                    "${returned.map { (it.payload as ConformancePayload).marker }}",
            )
            val stored = db.rows(sql("SELECT id FROM ${tables.petiches}")).size
            assertEquals(1, stored, "the table holds $stored rows for one id")
        }
}
