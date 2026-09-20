package io.github.youndie.petich.postgres

import io.github.youndie.petich.ExpiringPetichRepository
import io.github.youndie.petich.OutboxAwarePetichRepository
import io.github.youndie.petich.OutboxEvent
import io.github.youndie.petich.Petich
import io.github.youndie.petich.PetichClock
import io.github.youndie.petich.PetichStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

public class ExposedPetichRepository(
    private val db: Database,
    private val table: PetichTable,
    private val outboxTable: OutboxEventsTable,
    // WHERE TIME COMES FROM, as a parameter — the same shape the engine has always had
    // (PetichClock) and the one the sqlx4k store was born with, which has no platform to read.
    //
    // The default keeps every existing consumer compiling and behaving exactly as before. What it
    // buys is the seam: an application running several replicas can hand all of them ONE source of
    // time, which is the cheap half of youndie/petich#20 — the other half, a server-side default on
    // the column, changes DDL this library does not ship.
    private val clock: PetichClock = PetichClock { systemTimeMillis() },
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
                it[compensationAttempts] = petich.compensationAttempts
                it[compensatingFromIndex] = petich.compensatingFromIndex
                it[compensatingTowards] = petich.compensatingTowards?.name
                it[updatedAt] = clock.nowEpochMs()
                it[chainFingerprint] = petich.chainFingerprint
                it[stepRecords] = petich.stepRecords
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
                    // payload is NOT here. It is written once, by the insert above, and the engine
                    // never changes it: a saga does not change what it is. Sending it anyway
                    // rewrote the largest column in the row on all eleven writes a six-step saga
                    // makes — and if it is past the TOAST threshold, that is eleven full rewrites
                    // out of line plus the dead chunks they leave for autovacuum, for a value that
                    // was identical every time.
                    it[enrichedPayload] = petich.enrichedPayload
                    it[version] = petich.version
                    it[suspendedUntil] = petich.suspendedUntilEpochMs
                    it[compensationAttempts] = petich.compensationAttempts
                    it[compensatingFromIndex] = petich.compensatingFromIndex
                    it[compensatingTowards] = petich.compensatingTowards?.name
                    it[updatedAt] = clock.nowEpochMs()
                    it[chainFingerprint] = petich.chainFingerprint
                    it[stepRecords] = petich.stepRecords
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
                //
                // What DID change (B-10): the clock is a constructor parameter, so an application
                // that can give its replicas one source of time no longer has to wait for that
                // release. The default is the old behaviour, unchanged.
                val now = clock.nowEpochMs()
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
            compensationAttempts = this[table.compensationAttempts],
            compensatingFromIndex = this[table.compensatingFromIndex],
            compensatingTowards = this[table.compensatingTowards]?.let(PetichStatus::valueOf),
            chainFingerprint = this[table.chainFingerprint],
            stepRecords = this[table.stepRecords],
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

    // The same shape as findExpired and for the same reason: the sifting belongs in SQL. What is
    // deliberately NOT here is an ORDER BY - the sweeper takes a batch, not the oldest batch, and
    // ordering by a column with no index would sort the whole match on every poll.
    override suspend fun findStuck(
        status: PetichStatus,
        notTouchedSinceEpochMs: Long,
        limit: Int,
    ): List<Petich> =
        dbQuery {
            table
                .selectAll()
                .where {
                    (table.status eq status) and (table.updatedAt less notTouchedSinceEpochMs)
                }.limit(limit)
                .map { it.toDomain() }
        }
}
