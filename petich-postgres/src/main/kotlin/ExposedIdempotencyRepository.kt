import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import ru.workinprogress.petich.postgres.IdempotencyKeysTable
import ru.workinprogress.petich.idempotency.IdempotencyRecord
import ru.workinprogress.petich.idempotency.IdempotencyRepository

class ExposedIdempotencyRepository(
    private val db: Database,
    private val table: IdempotencyKeysTable,
) : IdempotencyRepository {
    // Dispatchers.IO is load-bearing, not cosmetic. Without it the transaction runs on whatever
    // dispatcher called it — for routes, that means directly on the Ktor engine threads. JDBC is
    // blocking, and an engine thread stuck in it cannot accept connections, so under load this
    // produced ConnectTimeout on the clients rather than merely slow responses.
    private suspend fun <T> dbQuery(block: suspend () -> T): T = withContext(Dispatchers.IO) { suspendTransaction(db = db) { block() } }

    // key is the PRIMARY KEY (see IdempotencyKeysTable), so inserting an existing key throws
    // ExposedSQLException on the uniqueness violation instead of quietly overwriting the row.
    // We catch exactly that and read it as "the key is already claimed by someone else": it is
    // the only constraint on this table, so an insert failure here can mean nothing else.
    override suspend fun tryClaim(
        key: String,
        requestFingerprint: String,
    ): Boolean =
        dbQuery {
            try {
                table.insert {
                    it[this.key] = key
                    it[this.requestFingerprint] = requestFingerprint
                    it[createdAt] = System.currentTimeMillis()
                }
                true
            } catch (_: ExposedSQLException) {
                false
            }
        }

    override suspend fun find(key: String): IdempotencyRecord? =
        dbQuery {
            table
                .selectAll()
                .where { table.key eq key }
                .singleOrNull()
                ?.let { IdempotencyRecord(key = it[table.key], requestFingerprint = it[table.requestFingerprint]) }
        }
}
