package io.github.youndie.petich.postgres

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table

public class IdempotencyKeysTable : Table("idempotency_keys") {
    public val key: Column<String> = varchar("key", 255)
    public val requestFingerprint: Column<String> = varchar("request_fingerprint", 64)
    public val createdAt: Column<Long> = long("created_at")

    override val primaryKey: PrimaryKey = PrimaryKey(key)
}
