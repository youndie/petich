package ru.workinprogress.petich

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
        override val phase: PetichPhase,
        override val priority: Int,
        private val result: () -> InterceptorResult,
    ) : PetichInterceptor<ReplayPayload> {
        var compensations = 0
            private set

        override fun supports(payload: PetichPayload) = payload is ReplayPayload

        override suspend fun intercept(
            petich: Petich,
            payload: ReplayPayload,
        ): InterceptorResult = result()

        override suspend fun compensate(
            petich: Petich,
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

    private fun petich(id: String) =
        Petich(
            id = id,
            type = "replay",
            status = PetichStatus.PROCESSING,
            payload = ReplayPayload("x"),
        )

    @Test
    fun `a rolled back petich answers a repeat the same way it answered the pass that rolled it back`() =
        runBlocking {
            val work = ReplayInterceptor(PetichPhase.EXECUTION, priority = 10) { InterceptorResult.Proceed() }
            val fault =
                ReplayInterceptor(PetichPhase.EXECUTION, priority = 5) {
                    InterceptorResult.Compensate("downstream refused after the work was done")
                }
            val repository = ReplayRepository()
            val engine = PetichEngine(listOf(work, fault), repository)

            val first = engine.process(petich("rolled-back"))
            assertTrue(
                first is PetichResult.Error,
                "a rolled-back saga is a business outcome; expected Error, got $first",
            )
            assertEquals(PetichStatus.FAILED, repository.petich?.status)
            assertEquals(1, work.compensations)

            // Exactly what a client does when repeating a request with the same idempotency key.
            val repeat = engine.process(petich("rolled-back"))
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
                    listOf(ReplayInterceptor(PetichPhase.EXECUTION, priority = 10) { InterceptorResult.Proceed() }),
                    okRepository,
                )
            okEngine.process(petich("done"))
            assertTrue(okEngine.process(petich("done")) is PetichResult.Success)

            val rejectedRepository = ReplayRepository()
            val rejectedEngine =
                PetichEngine(
                    listOf(
                        ReplayInterceptor(PetichPhase.VALIDATION, priority = 10) {
                            InterceptorResult.Reject("no")
                        },
                    ),
                    rejectedRepository,
                )
            rejectedEngine.process(petich("rejected"))
            assertTrue(rejectedEngine.process(petich("rejected")) is PetichResult.Error)
        }
}
