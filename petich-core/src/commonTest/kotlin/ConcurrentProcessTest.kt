package io.github.youndie.petich

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What eight callers arriving for one saga at once are told, and how much work gets done.
 *
 * WHAT THIS ADDS TO WHAT IS ALREADY THERE, because the difference is easy to lose.
 * `EngineConfigTest > two passes over one petich never run at the same time` already contends the
 * engine's lock on `Dispatchers.Default` and asserts that no two passes OVERLAP — the mechanism. It
 * does so against a stateless store, so it cannot see what happens to the callers that lost: this
 * one uses a store with the optimistic predicate a real one has and asserts the CONSEQUENCE — the
 * saga's step runs once and every caller is handed the same finished result, rather than seven of
 * them getting a version conflict or a second execution.
 *
 * WHY THE ASSERTION IS A COUNT AND NOT A DURATION. A count is the same number on a fast machine and
 * a loaded one; a timing assertion on a shared runner measures the runner.
 */
class ConcurrentProcessTest {
    private data class Payload(
        val data: String,
    ) : PetichPayload()

    /** Counts how many times the saga's one step actually ran, and holds the window open. */
    private class CountingStep : PetichStep<Payload> {
        private val counter = Mutex()
        var executions: Int = 0
            private set

        override suspend fun execute(
            ctx: PetichStepContext,
            payload: Payload,
        ) {
            counter.withLock { executions++ }
            // Outside the counter's lock on purpose: this is the window in which a second caller
            // that was not stopped by the engine would get in. Without it the race is theoretically
            // there and practically never observed.
            delay(20)
        }

        override suspend fun compensate(
            ctx: PetichStepContext,
            payload: Payload,
        ) {}
    }

    /**
     * A store with the optimistic predicate a real one has, and a mutex of its own.
     *
     * The mutex is what keeps this test about the ENGINE: an in-memory map written from several
     * threads would race on its own, and the failure would be the fixture's rather than petich's.
     */
    private class LockingStore : PetichRepository {
        private val guard = Mutex()
        private val rows = mutableMapOf<String, Petich>()

        override suspend fun findById(id: String): Petich? = guard.withLock { rows[id] }

        override suspend fun saveOrGet(petich: Petich): Petich = guard.withLock { rows.getOrPut(petich.id) { petich } }

        override suspend fun update(petich: Petich): Boolean =
            guard.withLock {
                val stored = rows[petich.id] ?: return@withLock false
                if (stored.version != petich.version - 1) return@withLock false
                rows[petich.id] = petich
                true
            }
    }

    @Test
    fun `eight callers arriving for one saga at once run its step once`() =
        runBlocking {
            val step = CountingStep()
            val engine =
                PetichEngine(
                    repository = LockingStore(),
                    definitions = listOf(petichDefinition<Payload>("concurrency") { step("count", step) }),
                )
            val petich =
                Petich(
                    id = "contended",
                    type = "concurrency",
                    status = PetichStatus.PROCESSING,
                    payload = Payload("x"),
                )

            val results = mutableListOf<PetichResult>()
            val collected = Mutex()

            // Dispatchers.Default, not the test dispatcher: several threads on both targets is the
            // whole point. Under a single-threaded dispatcher this test passes against an engine
            // with no lock at all.
            withContext(Dispatchers.Default) {
                coroutineScope {
                    repeat(8) {
                        launch {
                            val result = engine.process(petich)
                            collected.withLock { results += result }
                        }
                    }
                }
            }

            assertEquals(
                1,
                step.executions,
                "the saga's step ran ${step.executions} times for eight callers: " +
                    "the per-saga lock let a second caller in while the first was still working",
            )
            assertEquals(
                8,
                results.count { it is PetichResult.Success },
                "the callers that lost the race were told: ${results.map { it::class.simpleName }}; " +
                    "each of them should be replayed the finished saga, not handed a conflict",
            )
        }
}
