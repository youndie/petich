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
}
