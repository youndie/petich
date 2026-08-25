package ru.workinprogress.petich.postgres

import org.jetbrains.exposed.v1.core.Table

class OutboxEventsTable : Table("outbox_events") {
    val id = varchar("id", 255)
    val type = varchar("type", 100)
    val payload = text("payload")
    val status = varchar("status", 20).default("PENDING")
    val retryCount = integer("retry_count").default(0)
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)

    // Never carried the comment the other two did, and has the same shape: fetchPending filters on
    // status and orders by created_at, which a relay does on every tick against a table that grows
    // with every event ever emitted. Declared here so the schema says so rather than a reader
    // having to infer it from the query.
    init {
        index("idx_outbox_events_status_created_at", false, status, createdAt)
    }
}
