package ru.workinprogress.petich.postgres

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import ru.workinprogress.petich.outbox.OutboxRecord
import ru.workinprogress.petich.outbox.OutboxRepository

class ExposedOutboxRepository(
    private val db: Database,
    private val table: OutboxEventsTable,
) : OutboxRepository {
    // Dispatchers.IO is load-bearing, not cosmetic. Without it the transaction runs on whatever
    // dispatcher called it — for routes, that means directly on the Ktor engine threads. JDBC is
    // blocking, and an engine thread stuck in it cannot accept connections, so under load this
    // produced ConnectTimeout on the clients rather than merely slow responses.
    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { suspendTransaction(db = db) { block() } }

    override suspend fun fetchPending(limit: Int): List<OutboxRecord> =
        dbQuery {
            table
                .selectAll()
                .where { table.status eq "PENDING" }
                .orderBy(table.createdAt)
                .limit(limit)
                .map {
                    OutboxRecord(
                        id = it[table.id],
                        type = it[table.type],
                        payload = it[table.payload],
                        retryCount = it[table.retryCount],
                    )
                }
        }

    override suspend fun markDelivered(id: String) {
        dbQuery {
            table.update({ table.id eq id }) {
                it[status] = "DELIVERED"
            }
        }
    }

    override suspend fun markFailed(id: String) {
        dbQuery {
            val currentRetryCount =
                table
                    .selectAll()
                    .where { table.id eq id }
                    .singleOrNull()
                    ?.get(table.retryCount) ?: 0
            table.update({ table.id eq id }) {
                it[retryCount] = currentRetryCount + 1
            }
        }
    }

    override suspend fun markDeadLettered(id: String) {
        dbQuery {
            table.update({ table.id eq id }) {
                it[status] = "DEAD_LETTERED"
            }
        }
    }
}
