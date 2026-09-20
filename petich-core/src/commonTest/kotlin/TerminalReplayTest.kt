package io.github.youndie.petich

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the engine answers when a finished petich is replayed under the same id.
 *
 * A terminal status answers the question "did the operation go through", and that answer must not
 * depend on which request number this is. Before these tests it did: a saga rolled back through
 * Compensate returned Error, while its replay returned SystemFailure — a 500 on the route. A load
 * harness caught it first.
 */
class TerminalReplayTest {
    private data class ReplayPayload(
        val data: String,
    ) : PetichPayload()

    private class ReplayInterceptor(
        private val result: (PetichStepContext) -> Unit,
    ) : PetichStep<ReplayPayload> {
        var compensations = 0
            private set

        override suspend fun execute(
            ctx: PetichStepContext,
            payload: ReplayPayload,
        ) = result(ctx)

        override suspend fun compensate(
            ctx: PetichStepContext,
            payload: ReplayPayload,
        ) {
            compensations++
        }
    }

    private class ReplayRepository : PetichRepository {
        var petich: Petich? = null

        override suspend fun findById(id: String): Petich? = petich?.takeIf { it.id == id }

        override suspend fun saveOrGet(petich: Petich): Petich = this.petich ?: petich.also { this.petich = it }

        override suspend fun update(petich: Petich): Boolean {
            val current = this.petich
            if (current != null && current.version != petich.version - 1) return false
            this.petich = petich
            return true
        }
    }

    private fun row(id: String) =
        Petich(
            id = id,
            type = "replay",
            status = PetichStatus.PROCESSING,
            payload = ReplayPayload("x"),
        )

    @Test
    fun `a rolled back petich answers a repeat the same way it answered the pass that rolled it back`() =
        runBlocking {
            val work = ReplayInterceptor { }
            val fault = ReplayInterceptor { it.fail("downstream refused after the work was done") }
            val repository = ReplayRepository()
            val engine =
                PetichEngine(
                    repository = repository,
                    definitions =
                        listOf(
                            petichDefinition<ReplayPayload>("replay") {
                                step("work", work)
                                step("fault", fault)
                            },
                        ),
                )

            val first = engine.process(row("rolled-back"))
            assertTrue(
                first is PetichResult.Error,
                "a rolled-back saga is a business outcome; expected Error, got $first",
            )
            assertEquals(PetichStatus.FAILED, repository.petich?.status)
            assertEquals(1, work.compensations)

            // Exactly what a client does when repeating a request with the same idempotency key.
            val repeat = engine.process(row("rolled-back"))
            assertTrue(
                repeat is PetichResult.Error,
                "replaying a finished petich is not a server fault; expected Error, got $repeat",
            )
            // And the replay does no work: the compensation already happened, and there is
            // nothing to call a second time.
            assertEquals(1, work.compensations, "a replay must not compensate a second time")
        }

    @Test
    fun `a completed petich still replays its success and a rejected one still replays its rejection`() =
        runBlocking {
            val okRepository = ReplayRepository()
            val okEngine =
                PetichEngine(
                    repository = okRepository,
                    definitions =
                        listOf(
                            petichDefinition<ReplayPayload>("replay") { step("work", ReplayInterceptor { }) },
                        ),
                )
            okEngine.process(row("done"))
            assertTrue(okEngine.process(row("done")) is PetichResult.Success)

            val rejectedRepository = ReplayRepository()
            val rejectedEngine =
                PetichEngine(
                    repository = rejectedRepository,
                    definitions =
                        listOf(
                            petichDefinition<ReplayPayload>("replay") {
                                step("refuses", ReplayInterceptor { it.reject("no") })
                            },
                        ),
                )
            rejectedEngine.process(row("rejected"))
            assertTrue(rejectedEngine.process(row("rejected")) is PetichResult.Error)
        }
}
