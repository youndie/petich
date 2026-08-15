package ru.workinprogress.petich.outbox

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

// The worker pass is invoked explicitly (tick) and time is advanced with a TestTimeSource: these
// tests do not sleep on the real clock, nor count how many times the worker managed to wake inside
// a window. An earlier version launched start() on Dispatchers.Default, waited delay(100..500) and
// asserted "exactly 3 attempts". On a loaded machine — a full repository build running alongside
// the tests — the worker gets the CPU less often, makes fewer attempts, and the test fails while
// saying nothing about the worker. No coverage is lost by fixing that: backoff and dead lettering
// are decided by timeSource, not by the number of loop iterations, so an explicit pass exercises
// exactly the same logic, only deterministically. Same shape as SchedulerWorkerTest.
class OutboxRelayWorkerTest {
    // Models retryCount realistically, unlike an earlier version that merely accumulated ids in a
    // failed list without touching the object in pending. Without that, testing backoff and dead
    // lettering here would prove nothing: both read event.retryCount straight from fetchPending().
    class FakeRepository : OutboxRepository {
        val pending = mutableMapOf<String, OutboxRecord>()
        val delivered = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val deadLettered = mutableListOf<String>()

        override suspend fun fetchPending(limit: Int): List<OutboxRecord> = pending.values.take(limit)

        override suspend fun markDelivered(id: String) {
            pending.remove(id)
            delivered.add(id)
        }

        override suspend fun markFailed(id: String) {
            failed.add(id)
            pending[id]?.let { pending[id] = it.copy(retryCount = it.retryCount + 1) }
        }

        override suspend fun markDeadLettered(id: String) {
            deadLettered.add(id)
            pending.remove(id)
        }
    }

    private fun repositoryWith(id: String) =
        FakeRepository().apply {
            pending[id] = OutboxRecord(id = id, type = "test", payload = "{}", retryCount = 0)
        }

    @Test
    fun `pending events get delivered and marked`() =
        runTest {
            val repository = repositoryWith("evt-1")
            val publishedIds = mutableListOf<String>()

            OutboxRelayWorker(
                repository,
                OutboxPublisher { publishedIds.add(it.id) },
                timeSource = TestTimeSource(),
            ).tick()

            assertEquals(listOf("evt-1"), publishedIds)
            assertEquals(listOf("evt-1"), repository.delivered)
        }

    @Test
    fun `a publish failure marks the event failed without killing the worker`() =
        runTest {
            val repository = repositoryWith("evt-fail")
            var attempts = 0

            // The pass completes normally instead of rethrowing the publish failure. Inside
            // start(), that is exactly what separates "one event failed" from "the worker died".
            OutboxRelayWorker(
                repository,
                OutboxPublisher {
                    attempts++
                    throw RuntimeException("boom")
                },
                timeSource = TestTimeSource(),
            ).tick()

            assertEquals(1, attempts, "publisher should have been invoked once")
            assertTrue(repository.failed.contains("evt-fail"))
            // The event stays PENDING — not removed from pending — so the next pass picks it up
            // again, confirming that a single failure does not kill the worker's loop.
            assertTrue(repository.pending.containsKey("evt-fail"))
        }

    // Proves the backoff exists at all: after a failure a retry must NOT happen on every
    // subsequent pass, which would be the old "retry on every poll with no pause" behaviour.
    @Test
    fun `after a failure the worker does not retry again before the backoff window elapses`() =
        runTest {
            val repository = repositoryWith("evt-backoff")
            val time = TestTimeSource()
            var attempts = 0

            val worker =
                OutboxRelayWorker(
                    repository,
                    OutboxPublisher {
                        attempts++
                        throw RuntimeException("boom")
                    },
                    baseBackoff = 1.seconds,
                    timeSource = time,
                )

            worker.tick()
            // Any number of passes, but all of them inside the backoff window.
            repeat(5) {
                time += 100.milliseconds
                worker.tick()
            }

            assertEquals(1, attempts, "backoff should have suppressed every retry within the window")
        }

    // Proves the backoff actually EXPIRES and a retry follows it, rather than the worker simply
    // never retrying again — which would be a regression into dead lettering on the first failure,
    // checked separately below.
    @Test
    fun `after the backoff window elapses the worker retries the same event again`() =
        runTest {
            val repository = repositoryWith("evt-retry")
            val time = TestTimeSource()
            var attempts = 0

            val worker =
                OutboxRelayWorker(
                    repository,
                    OutboxPublisher {
                        attempts++
                        throw RuntimeException("boom")
                    },
                    baseBackoff = 30.milliseconds,
                    maxAttempts = 10,
                    timeSource = time,
                )

            worker.tick()
            time += 31.milliseconds
            worker.tick()

            assertEquals(2, attempts, "expected a second attempt once the backoff elapsed")
        }

    // Proves dead lettering: after maxAttempts consecutive failures the event must reach a
    // terminal status and stop coming back from fetchPending. Otherwise a systematic transport
    // failure would burn attempts — and log, and CPU — forever.
    @Test
    fun `an event is dead-lettered after maxAttempts consecutive failures and stops being retried`() =
        runTest {
            val repository = repositoryWith("evt-dead")
            val time = TestTimeSource()
            var attempts = 0

            val worker =
                OutboxRelayWorker(
                    repository,
                    OutboxPublisher {
                        attempts++
                        throw RuntimeException("boom")
                    },
                    baseBackoff = 5.milliseconds,
                    maxBackoff = 20.milliseconds,
                    maxAttempts = 3,
                    timeSource = time,
                )

            // Comfortably more passes than maxAttempts, each beyond any backoff window: had the
            // event not been dead lettered, there would be six attempts here rather than three.
            repeat(6) {
                time += 1.seconds
                worker.tick()
            }

            assertEquals(3, attempts, "should stop attempting exactly at maxAttempts")
            assertEquals(listOf("evt-dead"), repository.deadLettered)
            assertTrue(!repository.pending.containsKey("evt-dead"), "dead-lettered event must no longer be returned by fetchPending")
        }
}
