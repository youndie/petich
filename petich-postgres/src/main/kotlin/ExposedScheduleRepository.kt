package ru.workinprogress.petich.postgres

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import ru.workinprogress.petich.scheduler.Recurrence
import ru.workinprogress.petich.scheduler.ScheduleRepository
import ru.workinprogress.petich.scheduler.ScheduledJob

// Same shape as ExposedPetichRepository and ExposedOutboxRepository: Database and table through
// the constructor, every method inside suspendTransaction. Default package, like its neighbours
// in this module.
class ExposedScheduleRepository(
    private val db: Database,
    private val table: ScheduledJobsTable,
) : ScheduleRepository {
    // Dispatchers.IO is load-bearing, not cosmetic. Without it the transaction runs on whatever
    // dispatcher called it — for routes, that means directly on the Ktor engine threads. JDBC is
    // blocking, and an engine thread stuck in it cannot accept connections, so under load this
    // produced ConnectTimeout on the clients rather than merely slow responses.
    private suspend fun <T> dbQuery(block: suspend () -> T): T = withContext(Dispatchers.IO) { suspendTransaction(db = db) { block() } }

    override suspend fun save(job: ScheduledJob): ScheduledJob =
        dbQuery {
            val exists = table.selectAll().where { table.id eq job.id }.empty().not()
            if (exists) {
                table.update({ table.id eq job.id }) {
                    it[nextRunAt] = job.nextRunAtEpochMs
                    it[lastRunAt] = job.lastRunAtEpochMs
                    it[active] = job.active
                    it[consecutiveFailures] = job.consecutiveFailures
                    it[payload] = job.payload
                    it[recurrence] = job.recurrence.name
                }
            } else {
                table.insert {
                    it[id] = job.id
                    it[ownerId] = job.ownerId
                    it[type] = job.type
                    it[payload] = job.payload
                    it[recurrence] = job.recurrence.name
                    it[nextRunAt] = job.nextRunAtEpochMs
                    it[lastRunAt] = job.lastRunAtEpochMs
                    it[active] = job.active
                    it[consecutiveFailures] = job.consecutiveFailures
                }
            }
            job
        }

    override suspend fun findById(id: String): ScheduledJob? =
        dbQuery {
            table.selectAll().where { table.id eq id }.singleOrNull()?.toDomain()
        }

    // Filtering in SQL: the point of this query is to avoid loading the entire schedule just to
    // find the few jobs that are due.
    override suspend fun findDue(
        nowEpochMs: Long,
        limit: Int,
    ): List<ScheduledJob> =
        dbQuery {
            table
                .selectAll()
                .where { (table.active eq true) and (table.nextRunAt lessEq nowEpochMs) }
                .limit(limit)
                .map { it.toDomain() }
        }

    override suspend fun findByOwner(ownerId: String): List<ScheduledJob> =
        dbQuery {
            table
                .selectAll()
                .where { table.ownerId eq ownerId }
                .sortedBy { it[table.nextRunAt] }
                .map { it.toDomain() }
        }

    private fun ResultRow.toDomain() =
        ScheduledJob(
            id = this[table.id],
            ownerId = this[table.ownerId],
            type = this[table.type],
            payload = this[table.payload],
            recurrence = Recurrence.valueOf(this[table.recurrence]),
            nextRunAtEpochMs = this[table.nextRunAt],
            lastRunAtEpochMs = this[table.lastRunAt],
            active = this[table.active],
            consecutiveFailures = this[table.consecutiveFailures],
        )
}
