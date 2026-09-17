package io.github.youndie.petich.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.youndie.petich.PetichClock
import io.github.youndie.petich.idempotency.IdempotencyRecord
import io.github.youndie.petich.idempotency.IdempotencyRepository

/**
 * "This key has already been used, and with this request" — on Postgres.
 *
 * **The claim is one statement, and it has to be.** `INSERT … ON CONFLICT DO NOTHING` lets the
 * database answer "somebody else has it" with a row count of zero; a read followed by an insert
 * gives two callers arriving with one new key the same answer — that it is free — and both proceed.
 * The corpus has a case with eight concurrent callers for exactly this, and it is the case a
 * read-then-write implementation fails.
 */
public class PostgresIdempotencyStore(
    private val db: Driver,
    private val clock: PetichClock,
    private val table: String = DEFAULT_IDEMPOTENCY_TABLE,
) : IdempotencyRepository {
    init {
        requireIdentifier(table)
    }

    override suspend fun tryClaim(
        key: String,
        requestFingerprint: String,
    ): Boolean {
        val claim =
            sql(
                "INSERT INTO $table (key, request_fingerprint, created_at) " +
                    "VALUES (:key, :fingerprint, :createdAt) ON CONFLICT (key) DO NOTHING",
            ).bind("key", key)
                .bind("fingerprint", requestFingerprint)
                // Written, never compared and never ordered on: only key sweeping reads it. The
                // clock is a parameter anyway, because this module has no platform to ask.
                .bind("createdAt", clock.nowEpochMs())
        return db.update(claim) > 0
    }

    override suspend fun find(key: String): IdempotencyRecord? {
        val query =
            sql("SELECT key, request_fingerprint FROM $table WHERE key = :key")
                .bind("key", key)
        val row = db.rows(query).firstOrNull() ?: return null
        return IdempotencyRecord(
            key = row.get("key").asString(),
            requestFingerprint = row.get("request_fingerprint").asString(),
        )
    }
}
