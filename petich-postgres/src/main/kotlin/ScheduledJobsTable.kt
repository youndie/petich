package io.github.youndie.petich.postgres

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table

// Schedule storage lives here, next to the petich, outbox and idempotency tables, on the same
// principle: :petich-scheduler knows nothing about a database, and the bridge between it and SQL
// belongs in this module.
public class ScheduledJobsTable : Table("scheduled_jobs") {
    public val id: Column<String> = varchar("id", 64)
    public val ownerId: Column<String> = varchar("owner_id", 64)
    public val type: Column<String> = varchar("type", 64)

    // Already-serialised JSON — the scheduler has no idea what is inside (see ScheduledJob.payload).
    public val payload: Column<String> = text("payload")

    public val recurrence: Column<String> = varchar("recurrence", 16)

    // The due-jobs query filters on this column, so a real database wants an index on
    // (active, next_run_at) — declared below rather than described here.
    public val nextRunAt: Column<Long> = long("next_run_at")
    public val lastRunAt: Column<Long?> = long("last_run_at").nullable()
    public val active: Column<Boolean> = bool("active").default(true)
    public val consecutiveFailures: Column<Int> = integer("consecutive_failures").default(0)

    override val primaryKey: PrimaryKey = PrimaryKey(id)

    // See PetichTable: a described-but-undeclared index is one the migration generator proposes
    // dropping. This is the scheduler's polling table, read on every tick.
    init {
        index("idx_scheduled_jobs_active_next_run_at", false, active, nextRunAt)
    }
}
