package ru.workinprogress.petich.postgres

import org.jetbrains.exposed.v1.core.Table

class IdempotencyKeysTable : Table("idempotency_keys") {
    val key = varchar("key", 255)
    val requestFingerprint = varchar("request_fingerprint", 64)
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(key)
}
