package io.github.youndie.petich.postgres

import io.github.youndie.petich.EnrichedPayload
import io.github.youndie.petich.PetichPayload
import io.github.youndie.petich.PetichRepository
import io.github.youndie.petich.PetichStepRecord
import io.github.youndie.petich.SimpleEnrichedPayload
import io.github.youndie.petich.conformance.ConformancePayload
import io.github.youndie.petich.conformance.ConformanceRecord
import io.github.youndie.petich.conformance.Finding
import io.github.youndie.petich.conformance.MovableClock
import io.github.youndie.petich.conformance.PetichStoreConformance
import io.github.youndie.petich.conformance.PetichStoreSubject
import io.github.youndie.petich.outbox.OutboxRecord
import io.github.youndie.petich.sqlx4k.postgres.petichPostgresSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Exposed store, run against a database built by the **native** store's DDL.
 *
 * WHAT THIS IS FOR. `petichPostgresSchema()`'s own documentation promises that "a service moving
 * from the JVM to Kotlin/Native — or running both while it moves — points the two stores at one
 * database, and a saga written by either is read by the other". Nothing ran that promise. It was
 * checked by reading two texts and seeing the same column names, and the sentence that says so is
 * careful to say *names*: "a schema that differed by a column name would make that migration a data
 * migration".
 *
 * The names do match. The **types** do not. `PetichTable` declares the JSON-shaped columns with
 * Exposed's `json()`, which Postgres creates as `json`; the native schema spells every one of them
 * `TEXT`. Neither `tools/schema-notes-audit.py` nor Exposed's own migration statements can see it —
 * the audit reads only the column *name* out of `PetichTable`, because Exposed spells types in
 * Kotlin, and Exposed's `statementsRequiredForDatabaseMigration` compares defaults and not types.
 * konekt found the default and never mentioned the type (B-34).
 *
 * So this runs the whole corpus, not a read-back of one row: the promise is about every rule the
 * store is supposed to satisfy, and the mismatch is in the columns every rule touches.
 */
class NativeSchemaCompatibilityTest {
    private companion object {
        val container: PostgreSQLContainer =
            PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine")).apply {
                withDatabaseName("petich")
                withUsername("petich")
                withPassword("petich")
                start()
            }

        // A DATABASE OF ITS OWN, not a schema and not the one ConformanceTest uses. The Exposed
        // table objects name their tables unqualified, so anything sharing a search path with the
        // generator's tables would silently test the generator's tables instead — which is the one
        // result this test must not be able to produce.
        val db: Database =
            run {
                // PLAIN JDBC AND autoCommit, not Exposed's `transaction`: Postgres refuses
                // CREATE DATABASE inside a transaction block, and Exposed opens one for every
                // statement it runs.
                DriverManager
                    .getConnection(container.jdbcUrl, container.username, container.password)
                    .use { connection ->
                        connection.autoCommit = true
                        connection.createStatement().use { it.execute("CREATE DATABASE petich_native") }
                    }

                Database.connect(
                    url = container.jdbcUrl.replace("/petich", "/petich_native"),
                    driver = "org.postgresql.Driver",
                    user = container.username,
                    password = container.password,
                )
            }
    }

    private val json =
        Json {
            ignoreUnknownKeys = true
            serializersModule =
                SerializersModule {
                    polymorphic(PetichPayload::class) { subclass(ConformancePayload::class) }
                    polymorphic(EnrichedPayload::class) { subclass(SimpleEnrichedPayload::class) }
                    polymorphic(PetichStepRecord::class) { subclass(ConformanceRecord::class) }
                }
        }

    private val petichTable = PetichTable(json)
    private val outboxTable = OutboxEventsTable()
    private val movableClock = MovableClock()

    private val store = ExposedPetichRepository(db, petichTable, outboxTable, movableClock)

    init {
        // THE NATIVE STORE'S OWN STATEMENTS, called rather than copied. A copy of this DDL in the
        // test would pass forever after the native schema changed, which is the failure this test
        // exists to make impossible.
        transaction(db) {
            petichPostgresSchema().forEach { exec(it) }
        }
    }

    /**
     * The types Postgres actually created, read from the catalogue.
     *
     * This is the finding stated as an assertion rather than as prose: if the two stores are ever
     * reconciled, this test fails and says which column moved. It asserts what IS, and the item
     * that changes it is the one that updates this list.
     */
    @Test
    fun `the native schema spells the JSON columns as text`() {
        val types =
            transaction(db) {
                exec(
                    "SELECT column_name, data_type FROM information_schema.columns " +
                        "WHERE table_name = 'petiches' AND column_name IN " +
                        "('payload', 'enriched_payload', 'step_records') ORDER BY column_name",
                ) { rs ->
                    buildMap {
                        while (rs.next()) put(rs.getString(1), rs.getString(2))
                    }
                }
            }

        assertEquals(
            mapOf("enriched_payload" to "text", "payload" to "text", "step_records" to "text"),
            types,
            "the native schema's spelling of the columns PetichTable declares with json()",
        )
    }

    /**
     * And the answer to the question B-34 was filed on: does it matter?
     *
     * A green run here does not make the divergence harmless — it makes it survivable for the rules
     * the corpus states. A red one names the rule it breaks.
     */
    @Test
    fun `the Exposed store satisfies every rule of the corpus on the native schema`() =
        runBlocking {
            val findings = PetichStoreConformance().run(NativeSchemaSubject())
            assertTrue(
                findings.isEmpty(),
                "the Exposed store against the native store's DDL: " +
                    findings.joinToString("\n") { "  ${it.rule}: ${it.detail}" },
            )
        }

    private inner class NativeSchemaSubject(
        override val repository: PetichRepository = store,
    ) : PetichStoreSubject {
        override val clock = movableClock

        override suspend fun reset() {
            transaction(db) {
                petichTable.deleteAll()
                outboxTable.deleteAll()
            }
        }

        override suspend fun outboxRows(): List<OutboxRecord> =
            transaction(db) {
                outboxTable.selectAll().map {
                    OutboxRecord(
                        id = it[outboxTable.id],
                        type = it[outboxTable.type],
                        payload = it[outboxTable.payload],
                        retryCount = it[outboxTable.retryCount],
                    )
                }
            }
    }
}
