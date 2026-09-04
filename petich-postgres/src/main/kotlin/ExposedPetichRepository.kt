package ru.workinprogress.petich.postgres

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import ru.workinprogress.petich.ExpiringPetichRepository
import ru.workinprogress.petich.OutboxAwarePetichRepository
import ru.workinprogress.petich.OutboxEvent
import ru.workinprogress.petich.Petich
import ru.workinprogress.petich.PetichStatus

class ExposedPetichRepository(
    private val db: Database,
    private val table: PetichTable,
    private val outboxTable: OutboxEventsTable,
) : OutboxAwarePetichRepository,
    ExpiringPetichRepository {
    // Dispatchers.IO is load-bearing, not cosmetic. Without it the transaction runs on whatever
    // dispatcher called it — for routes, that means directly on the Ktor engine threads. JDBC is
    // blocking, and an engine thread stuck in it cannot accept connections, so under load this
    // produced ConnectTimeout on the clients rather than merely slow responses.
    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { suspendTransaction(db = db) { block() } }

    override suspend fun findById(id: String): Petich? =
        dbQuery {
            table
                .selectAll()
                .where { table.id eq id }
                .singleOrNull()
                ?.toDomain()
        }

    override suspend fun saveOrGet(petich: Petich): Petich =
        dbQuery {
            val existing =
                table
                    .selectAll()
                    .where { table.id eq petich.id }
                    .singleOrNull()

            if (existing != null) return@dbQuery existing.toDomain()

            table.insert {
                it[id] = petich.id
                it[type] = petich.type
                it[currentPhase] = petich.currentPhase
                it[currentInterceptorIndex] = petich.currentInterceptorIndex
                it[status] = petich.status
                it[payload] = petich.payload
                it[enrichedPayload] = petich.enrichedPayload
                it[version] = petich.version
                it[suspendedUntil] = petich.suspendedUntilEpochMs
            }
            petich
        }

    // Updating the petich and inserting the outbox events happen in ONE SQL transaction
    // (dbQuery = suspendTransaction): if the update fails — on a version conflict, say — the
    // events are not written either. That is what makes a dual write between the business
    // mutation and the intent to notify structurally impossible, rather than merely unlikely
    // (see OutboxAwarePetichRepository in :petich-core).
    override suspend fun update(
        petich: Petich,
        outboxEvents: List<OutboxEvent>,
    ): Boolean =
        dbQuery {
            val updatedRows =
                table.update({
                    (table.id eq petich.id) and (table.version eq petich.version - 1)
                }) {
                    it[currentPhase] = petich.currentPhase
                    it[currentInterceptorIndex] = petich.currentInterceptorIndex
                    it[status] = petich.status
                    it[payload] = petich.payload
                    it[enrichedPayload] = petich.enrichedPayload
                    it[version] = petich.version
                    it[suspendedUntil] = petich.suspendedUntilEpochMs
                }

            if (updatedRows > 0 && outboxEvents.isNotEmpty()) {
                // Suppressed rather than fixed, and the reason is that the fix is not local.
                //
                // The rule is right: this stamp is read from THIS instance's clock, and
                // ExposedOutboxRepository.fetchPending orders by it. Several replicas write these,
                // so their skew reorders the queue — two events written in causal order by
                // different instances can be handed to the relay in the other one. Delivery order
                // is not promised, so nothing is incorrect; what is affected is fairness, and it
                // degrades silently with the size of the skew.
                //
                // The fix is a server-side default on the column, which changes the DDL — and this
                // library deliberately ships none, so the change lands in every consumer's
                // migrations. That is a decision with a release behind it, not a line in a build
                // bump. Tracked in youndie/petich#20.
                @Suppress(
                    "ktlint:kapkan:wall-clock",
                    "Replica skew reorders the outbox queue; the fix is a column default, see #20",
                )
                val now = System.currentTimeMillis()
                outboxTable.batchInsert(outboxEvents) { event ->
                    this[outboxTable.id] = event.id
                    this[outboxTable.type] = event.type
                    this[outboxTable.payload] = event.payload
                    this[outboxTable.createdAt] = now
                }
            }

            updatedRows > 0
        }

    private fun ResultRow.toDomain(): Petich =
        Petich(
            id = this[table.id],
            type = this[table.type],
            currentPhase = this[table.currentPhase],
            currentInterceptorIndex = this[table.currentInterceptorIndex],
            status = this[table.status],
            payload = this[table.payload],
            enrichedPayload = this[table.enrichedPayload],
            version = this[table.version],
            suspendedUntilEpochMs = this[table.suspendedUntil],
        )

    // Filtering in SQL rather than in memory: the whole point of this query is to avoid loading
    // every suspended petich just to find the handful that have expired.
    override suspend fun findExpired(
        nowEpochMs: Long,
        limit: Int,
    ): List<Petich> =
        dbQuery {
            table
                .selectAll()
                .where {
                    (table.status eq PetichStatus.PENDING_SIGNATURE) and
                        table.suspendedUntil.isNotNull() and
                        (table.suspendedUntil lessEq nowEpochMs)
                }.limit(limit)
                .map { it.toDomain() }
        }
}
