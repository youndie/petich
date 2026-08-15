package ru.workinprogress.petich.postgres

import org.jetbrains.exposed.v1.core.Table

// Schedule storage lives here, next to the petich, outbox and idempotency tables, on the same
// principle: :petich-scheduler knows nothing about a database, and the bridge between it and SQL
// belongs in this module.
class ScheduledJobsTable : Table("scheduled_jobs") {
    val id = varchar("id", 64)
    val ownerId = varchar("owner_id", 64)
    val type = varchar("type", 64)

    // Already-serialised JSON — the scheduler has no idea what is inside (see ScheduledJob.payload).
    val payload = text("payload")

    val recurrence = varchar("recurrence", 16)

    // The due-jobs query filters on this column, so a real database wants an index on
    // (active, next_run_at).
    val nextRunAt = long("next_run_at")
    val lastRunAt = long("last_run_at").nullable()
    val active = bool("active").default(true)
    val consecutiveFailures = integer("consecutive_failures").default(0)

    override val primaryKey = PrimaryKey(id)
}
