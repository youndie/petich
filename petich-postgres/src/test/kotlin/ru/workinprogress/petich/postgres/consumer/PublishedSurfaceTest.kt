// Deliberately NOT in ru.workinprogress.petich.postgres. This package stands in for a consumer's
// own code, and it is the only position from which the two defects below are visible at all: from
// inside the module's package both look fine, which is why the build stayed green through them.
package ru.workinprogress.petich.postgres.consumer

import kotlinx.serialization.json.Json
import ru.workinprogress.petich.postgres.ExposedIdempotencyRepository
import ru.workinprogress.petich.postgres.ExposedOutboxRepository
import ru.workinprogress.petich.postgres.ExposedPetichRepository
import ru.workinprogress.petich.postgres.ExposedScheduleRepository
import ru.workinprogress.petich.postgres.IdempotencyKeysTable
import ru.workinprogress.petich.postgres.OutboxEventsTable
import ru.workinprogress.petich.postgres.PetichTable
import ru.workinprogress.petich.postgres.ScheduledJobsTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PublishedSurfaceTest {
    // The imports above ARE this test. A class in the default package has no qualified name to
    // write, so naming these four from any package at all is what fails to compile when they slip
    // back out of one — which is how they shipped: no test stood outside the module's own package.
    @Test
    fun `the four repositories can be named from another package`() {
        assertEquals(
            listOf(
                "ru.workinprogress.petich.postgres.ExposedIdempotencyRepository",
                "ru.workinprogress.petich.postgres.ExposedOutboxRepository",
                "ru.workinprogress.petich.postgres.ExposedPetichRepository",
                "ru.workinprogress.petich.postgres.ExposedScheduleRepository",
            ),
            listOf(
                ExposedIdempotencyRepository::class,
                ExposedOutboxRepository::class,
                ExposedPetichRepository::class,
                ExposedScheduleRepository::class,
            ).map { it.qualifiedName },
        )
    }

    // Table.indices is what Exposed's migration tooling reads: MigrationUtils compares it against
    // the live database and proposes DROP INDEX for anything the database has and the Table does
    // not. Asserting on it is asserting on the input to that comparison.
    //
    // The names are asserted, not just the presence: they are the part a consumer's DDL has to
    // agree with, and two consumers naming one index differently is the situation this closes.
    @Test
    fun `the polled tables declare the indexes their queries need, by name`() {
        val declared =
            mapOf(
                "petiches" to PetichTable(Json).indices,
                "scheduled_jobs" to ScheduledJobsTable().indices,
                "outbox_events" to OutboxEventsTable().indices,
            )

        assertEquals(
            mapOf(
                "petiches" to listOf("idx_petiches_status_suspended_until"),
                "scheduled_jobs" to listOf("idx_scheduled_jobs_active_next_run_at"),
                "outbox_events" to listOf("idx_outbox_events_status_created_at"),
            ),
            declared.mapValues { (_, indices) -> indices.map { it.indexName } },
        )
    }

    // The columns, not only the name: an index named right over the wrong columns satisfies the
    // assertion above and helps no query.
    @Test
    fun `each index covers the columns its query filters on`() {
        assertEquals(
            listOf("status", "suspended_until"),
            PetichTable(Json).indices.single().columns.map { it.name },
        )
        assertEquals(
            listOf("active", "next_run_at"),
            ScheduledJobsTable().indices.single().columns.map { it.name },
        )
        assertEquals(
            listOf("status", "created_at"),
            OutboxEventsTable().indices.single().columns.map { it.name },
        )
    }

    // IdempotencyKeysTable is the control: it is polled by nothing and should carry no index, so a
    // change that sprayed indexes over every table would fail here rather than pass three tests.
    @Test
    fun `a table nothing polls declares no index`() {
        assertTrue(IdempotencyKeysTable().indices.isEmpty(), IdempotencyKeysTable().indices.toString())
    }
}
