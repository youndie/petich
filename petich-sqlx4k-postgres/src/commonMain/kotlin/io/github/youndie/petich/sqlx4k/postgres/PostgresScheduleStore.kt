package io.github.youndie.petich.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLongOrNull
import io.github.youndie.petich.scheduler.Recurrence
import io.github.youndie.petich.scheduler.ScheduleRepository
import io.github.youndie.petich.scheduler.ScheduledJob

/**
 * The schedule on Postgres: what is due, whose it is, and what the worker wrote back after a run.
 */
public class PostgresScheduleStore(
    private val db: Driver,
    private val table: String = DEFAULT_SCHEDULE_TABLE,
) : ScheduleRepository {
    init {
        requireIdentifier(table)
    }

    /**
     * Insert or move the run state — one statement, because the worker's save is not a read.
     *
     * `ON CONFLICT (id) DO UPDATE` changes what a run changes: the next and last run, whether the
     * job is still active, the failure counter, the payload and the recurrence. The owner and the
     * type are left alone, which is what the Exposed store does as well, and what the corpus
     * deliberately has no rule about — the worker never saves back a job whose owner changed.
     */
    override suspend fun save(job: ScheduledJob): ScheduledJob {
        val statement =
            sql(
                "INSERT INTO $table (id, owner_id, type, payload, recurrence, next_run_at, " +
                    "last_run_at, active, consecutive_failures) " +
                    "VALUES (:id, :ownerId, :type, :payload, :recurrence, :nextRunAt, :lastRunAt, " +
                    ":active, :failures) " +
                    "ON CONFLICT (id) DO UPDATE SET " +
                    "payload = EXCLUDED.payload, recurrence = EXCLUDED.recurrence, " +
                    "next_run_at = EXCLUDED.next_run_at, last_run_at = EXCLUDED.last_run_at, " +
                    "active = EXCLUDED.active, consecutive_failures = EXCLUDED.consecutive_failures",
            ).bind("id", job.id)
                .bind("ownerId", job.ownerId)
                .bind("type", job.type)
                .bind("payload", job.payload)
                .bind("recurrence", job.recurrence.name)
                .bind("nextRunAt", job.nextRunAtEpochMs)
                .bind("lastRunAt", job.lastRunAtEpochMs)
                .bind("active", job.active)
                .bind("failures", job.consecutiveFailures)
        db.update(statement)
        return job
    }

    override suspend fun findById(id: String): ScheduledJob? {
        val query = sql("SELECT $COLUMNS FROM $table WHERE id = :id").bind("id", id)
        return db.rows(query).firstOrNull()?.toDomain()
    }

    override suspend fun findDue(
        nowEpochMs: Long,
        limit: Int,
    ): List<ScheduledJob> {
        // Both conditions in SQL: a disabled job is never due however long it has waited, and the
        // point of the query is not to load the whole schedule to find the few that are.
        val query =
            sql("SELECT $COLUMNS FROM $table WHERE active = TRUE AND next_run_at <= :now LIMIT :limit")
                .bind("now", nowEpochMs)
                .bind("limit", limit)
        return db.rows(query).map { it.toDomain() }
    }

    override suspend fun findByOwner(ownerId: String): List<ScheduledJob> {
        val query =
            sql("SELECT $COLUMNS FROM $table WHERE owner_id = :ownerId ORDER BY next_run_at")
                .bind("ownerId", ownerId)
        return db.rows(query).map { it.toDomain() }
    }

    private fun ResultSet.Row.toDomain(): ScheduledJob =
        ScheduledJob(
            id = get("id").asString(),
            ownerId = get("owner_id").asString(),
            type = get("type").asString(),
            payload = get("payload").asString(),
            recurrence = Recurrence.valueOf(get("recurrence").asString()),
            nextRunAtEpochMs = get("next_run_at").asLong(),
            lastRunAtEpochMs = get("last_run_at").asLongOrNull(),
            active = get("active").asBoolean(),
            consecutiveFailures = get("consecutive_failures").asInt(),
        )

    private companion object {
        const val COLUMNS =
            "id, owner_id, type, payload, recurrence, next_run_at, last_run_at, active, consecutive_failures"
    }
}
