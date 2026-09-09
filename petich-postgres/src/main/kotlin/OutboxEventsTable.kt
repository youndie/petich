package io.github.youndie.petich.postgres

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table

public class OutboxEventsTable : Table("outbox_events") {
    public val id: Column<String> = varchar("id", 255)
    public val type: Column<String> = varchar("type", 100)
    public val payload: Column<String> = text("payload")
    public val status: Column<String> = varchar("status", 20).default("PENDING")
    public val retryCount: Column<Int> = integer("retry_count").default(0)
    public val createdAt: Column<Long> = long("created_at")

    override val primaryKey: PrimaryKey = PrimaryKey(id)

    // Never carried the comment the other two did, and has the same shape: fetchPending filters on
    // status and orders by created_at, which a relay does on every tick against a table that grows
    // with every event ever emitted. Declared here so the schema says so rather than a reader
    // having to infer it from the query.
    init {
        index("idx_outbox_events_status_created_at", false, status, createdAt)
    }
}
