package io.github.youndie.petich.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.youndie.petich.outbox.OutboxRecord
import io.github.youndie.petich.outbox.OutboxRepository

/**
 * The relay's side of the outbox table [PostgresPetichStore] writes into.
 *
 * Rows are never created here: an event exists because a saga's state change created it, in that
 * state change's own transaction. This class only moves rows between the three statuses a relay
 * knows — pending, delivered, dead-lettered — and counts attempts.
 */
public class PostgresOutboxStore(
    private val db: Driver,
    private val table: String = DEFAULT_OUTBOX_TABLE,
) : OutboxRepository {
    init {
        requireIdentifier(table)
    }

    override suspend fun fetchPending(limit: Int): List<OutboxRecord> {
        // ORDER BY created_at is fairness rather than a promise: delivery order is not part of the
        // contract (the corpus deliberately has no rule about it), and the stamp is written by
        // whichever instance produced the event.
        val query =
            sql(
                "SELECT id, type, payload, retry_count FROM $table " +
                    "WHERE status = 'PENDING' ORDER BY created_at LIMIT :limit",
            ).bind("limit", limit)
        return db.rows(query).map { row ->
            OutboxRecord(
                id = row.get("id").asString(),
                type = row.get("type").asString(),
                payload = row.get("payload").asString(),
                retryCount = row.get("retry_count").asInt(),
            )
        }
    }

    override suspend fun markDelivered(id: String) {
        val statement = sql("UPDATE $table SET status = 'DELIVERED' WHERE id = :id").bind("id", id)
        db.update(statement)
    }

    /**
     * The attempt is counted **in SQL**, and that is a real difference from the Exposed store.
     *
     * `petich-postgres` reads `retry_count`, adds one and writes it back, so two relays that fail to
     * deliver the same event both write the same number and one attempt disappears. `+ 1` in the
     * statement cannot lose one. The corpus cannot see the difference — it runs one caller at a time
     * — which is exactly why it is written here rather than left for someone to notice under load.
     */
    override suspend fun markFailed(id: String) {
        val statement =
            sql("UPDATE $table SET retry_count = retry_count + 1 WHERE id = :id").bind("id", id)
        db.update(statement)
    }

    override suspend fun markDeadLettered(id: String) {
        val statement =
            sql("UPDATE $table SET status = 'DEAD_LETTERED' WHERE id = :id").bind("id", id)
        db.update(statement)
    }
}
