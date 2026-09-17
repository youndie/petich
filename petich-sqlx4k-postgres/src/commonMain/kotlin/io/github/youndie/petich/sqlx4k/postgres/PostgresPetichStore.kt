package io.github.youndie.petich.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLongOrNull
import io.github.youndie.petich.EnrichedPayload
import io.github.youndie.petich.ExpiringPetichRepository
import io.github.youndie.petich.OutboxAwarePetichRepository
import io.github.youndie.petich.OutboxEvent
import io.github.youndie.petich.Petich
import io.github.youndie.petich.PetichClock
import io.github.youndie.petich.PetichPayload
import io.github.youndie.petich.PetichPhase
import io.github.youndie.petich.PetichStatus
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json

/**
 * Sagas on Postgres through sqlx4k — the store that runs where the JVM does not.
 *
 * Ships no driver, no pool and no DDL: it takes a [Driver] the application opened and does not know
 * which host it points at, how many connections there are, or how the schema got there
 * ([petichPostgresSchema] states what it needs).
 *
 * **The transactional promise is the reason this class is not four smaller ones.**
 * [update] writes the saga's new state and the events an interceptor asked to emit **in one
 * transaction**: either both are visible or neither is. That is what makes "the work happened and
 * the notification never went out" structurally impossible rather than unlikely, and it is the one
 * thing a store can get wrong while passing every other rule of the corpus.
 *
 * **The clock is a parameter**, as it is for the engine itself. The Exposed store reads
 * `System.currentTimeMillis()` in two places, which is both unportable and — for the outbox stamp —
 * a known defect under replica skew (youndie/petich#20). Here there is nowhere to read it from and
 * no reason to want one.
 */
public class PostgresPetichStore(
    private val db: Driver,
    private val json: Json,
    private val clock: PetichClock,
    private val table: String = DEFAULT_PETICHES_TABLE,
    private val outboxTable: String = DEFAULT_OUTBOX_TABLE,
) : OutboxAwarePetichRepository,
    ExpiringPetichRepository {
    init {
        requireIdentifier(table)
        requireIdentifier(outboxTable)
    }

    override suspend fun findById(id: String): Petich? {
        val query = sql("SELECT $COLUMNS FROM $table WHERE id = :id").bind("id", id)
        return db.rows(query).firstOrNull()?.toDomain()
    }

    /**
     * Insert if it is new, return what is stored either way — in one transaction, because the read
     * and the write are one question.
     *
     * `ON CONFLICT DO NOTHING` rather than a read followed by an insert: two callers arriving with
     * one new saga both see "no such row" in the read-then-write version, and the loser's insert
     * throws instead of being told the row is already there. Which is the same rule the idempotency
     * store lives by, one table over.
     */
    override suspend fun saveOrGet(petich: Petich): Petich =
        db.transaction {
            update(
                sql(
                    "INSERT INTO $table ($COLUMNS) VALUES " +
                        "(:id, :type, :phase, :index, :status, :payload, :enriched, :version, :suspendedUntil) " +
                        "ON CONFLICT (id) DO NOTHING",
                ).bindState(petich)
                    // The type is written once and never updated: a saga does not change what it
                    // is. sqlx4k refuses a parameter the statement does not mention, which is how
                    // the corpus found this the first time the two statements shared one binder.
                    .bind("type", petich.type),
            )
            rows(sql("SELECT $COLUMNS FROM $table WHERE id = :id").bind("id", petich.id))
                .single()
                .toDomain()
        }

    override suspend fun update(
        petich: Petich,
        outboxEvents: List<OutboxEvent>,
    ): Boolean =
        db.transaction {
            val updated =
                update(
                    // The version predicate IS the optimistic lock: the row moves only if it is
                    // still the one this caller read. A store that drops it passes almost every
                    // other rule of the corpus — and quietly loses one of two concurrent writers.
                    sql(
                        "UPDATE $table SET " +
                            "current_phase = :phase, current_interceptor_index = :index, status = :status, " +
                            "payload = :payload, enriched_payload = :enriched, version = :version, " +
                            "suspended_until = :suspendedUntil " +
                            "WHERE id = :id AND version = :expectedVersion",
                    ).bindState(petich)
                        .bind("expectedVersion", petich.version - 1),
                ) > 0

            if (updated && outboxEvents.isNotEmpty()) {
                val now = clock.nowEpochMs()
                for (event in outboxEvents) {
                    update(
                        sql(
                            "INSERT INTO $outboxTable (id, type, payload, status, retry_count, created_at) " +
                                "VALUES (:id, :type, :payload, 'PENDING', 0, :createdAt)",
                        ).bind("id", event.id)
                            .bind("type", event.type)
                            .bind("payload", event.payload)
                            .bind("createdAt", now),
                    )
                }
            }

            updated
        }

    /**
     * Filtering in SQL rather than in memory: the point of this query is to avoid loading every
     * suspended saga to find the handful whose deadline has passed.
     */
    override suspend fun findExpired(
        nowEpochMs: Long,
        limit: Int,
    ): List<Petich> {
        val query =
            sql(
                "SELECT $COLUMNS FROM $table " +
                    "WHERE status = :status AND suspended_until IS NOT NULL AND suspended_until <= :now " +
                    "LIMIT :limit",
            ).bind("status", PetichStatus.PENDING_SIGNATURE.name)
                .bind("now", nowEpochMs)
                .bind("limit", limit)
        return db.rows(query).map { it.toDomain() }
    }

    /** Everything both statements write. The type is not here: only the insert sets it. */
    private fun Statement.bindState(petich: Petich): Statement =
        bind("id", petich.id)
            .bind("phase", petich.currentPhase.name)
            .bind("index", petich.currentInterceptorIndex)
            .bind("status", petich.status.name)
            .bind("payload", json.encodeToString(PAYLOAD, petich.payload))
            .bind("enriched", json.encodeToString(ENRICHED, petich.enrichedPayload))
            .bind("version", petich.version)
            .bind("suspendedUntil", petich.suspendedUntilEpochMs)

    private fun ResultSet.Row.toDomain(): Petich =
        Petich(
            id = get("id").asString(),
            type = get("type").asString(),
            currentPhase = PetichPhase.valueOf(get("current_phase").asString()),
            currentInterceptorIndex = get("current_interceptor_index").asInt(),
            status = PetichStatus.valueOf(get("status").asString()),
            payload = json.decodeFromString(PAYLOAD, get("payload").asString()),
            enrichedPayload = json.decodeFromString(ENRICHED, get("enriched_payload").asString()),
            version = get("version").asLong(),
            suspendedUntilEpochMs = get("suspended_until").asLongOrNull(),
        )

    private companion object {
        /**
         * Named rather than `*`: a row reader that asks for columns by name and a `SELECT *` are a
         * pair that keeps working while the table gains a column and breaks the day it loses one,
         * with an error naming the column rather than the cause.
         */
        const val COLUMNS =
            "id, type, current_phase, current_interceptor_index, status, payload, enriched_payload, " +
                "version, suspended_until"

        /**
         * Polymorphic, because the payload hierarchy is: what is stored carries a discriminator and
         * the `Json` handed in has to have the subclasses registered. Unregistered, every write
         * fails at the first saga rather than at wiring — see the module's document.
         */
        val PAYLOAD = PolymorphicSerializer(PetichPayload::class)
        val ENRICHED = PolymorphicSerializer(EnrichedPayload::class)
    }
}
