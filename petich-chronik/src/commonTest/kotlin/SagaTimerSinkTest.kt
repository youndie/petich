package io.github.youndie.petich.chronik

import io.github.youndie.chronik.EpochSeconds
import io.github.youndie.chronik.FiredTimer
import io.github.youndie.petich.Petich
import io.github.youndie.petich.PetichEngine
import io.github.youndie.petich.PetichPayload
import io.github.youndie.petich.PetichPhase
import io.github.youndie.petich.PetichRepository
import io.github.youndie.petich.PetichStatus
import io.github.youndie.petich.PetichStep
import io.github.youndie.petich.PetichStepContext
import io.github.youndie.petich.petich
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The claim: a fired timer resumes a saga that was waiting, through the engine's ordinary path.
 *
 * Checked by looking at the SAGA, not at the sink. A sink that swallowed everything and returned
 * quietly would satisfy any assertion about the sink itself.
 */
class SagaTimerSinkTest {
    private class InMemoryRepository : PetichRepository {
        val saved = mutableMapOf<String, Petich>()

        override suspend fun findById(id: String): Petich? = saved[id]

        override suspend fun saveOrGet(petich: Petich): Petich = saved.getOrPut(petich.id) { petich }

        override suspend fun update(petich: Petich): Boolean {
            saved[petich.id] = petich
            return true
        }
    }

    private data class Payload(
        val what: String = "waiting",
    ) : PetichPayload()

    /** The saga both halves belong to: wait, then observe how the waiting ended. */
    private fun waitThenObserve(observer: ObserveHowItWoke) =
        petich<Payload>("t") {
            step("await", WaitForTheDeadline())
            step("observe", observer)
        }

    /**
     * The step that waits. Declared first, so it runs first — it used to be a higher priority.
     *
     * It suspends unconditionally: `suspendFor` moves the saga on to the NEXT member when it is
     * resumed (the engine stores `index + 1`), so this one is entered exactly once and never sees
     * the resume at all. Getting that wrong is the first mistake anybody writing an `awaitUntil`
     * will make, which is why the two roles are separate classes here rather than one —
     * `resuspendFor` is the verb for the other shape.
     */
    private class WaitForTheDeadline : PetichStep<Payload> {
        override suspend fun execute(
            ctx: PetichStepContext,
            payload: Payload,
        ) = ctx.suspendFor("AWAIT_DEADLINE")

        override suspend fun compensate(
            ctx: PetichStepContext,
            payload: Payload,
        ) = Unit
    }

    /** The step the saga continues into once something wakes it. */
    private class ObserveHowItWoke(
        val resumed: MutableList<String>,
    ) : PetichStep<Payload> {
        override suspend fun execute(
            ctx: PetichStepContext,
            payload: Payload,
        ) {
            // Which somebody came back is in the payload's type: a timer here, a person in an
            // application that also resumes by hand. "The client confirmed" and "nobody came and
            // the deadline passed" lead to opposite branches.
            val resume = ctx.petich.resumePayload
            val id = ctx.petich.id
            resumed += if (resume is TimerFired) "$id:late=${resume.lateness}" else "$id:by-hand"
        }

        override suspend fun compensate(
            ctx: PetichStepContext,
            payload: Payload,
        ) = Unit
    }

    private class ResumeAnything : PetichStep<Payload> {
        override suspend fun execute(
            ctx: PetichStepContext,
            payload: Payload,
        ) = Unit

        override suspend fun compensate(
            ctx: PetichStepContext,
            payload: Payload,
        ) = Unit
    }

    private fun fired(
        timerId: String,
        sagaId: String,
    ) = FiredTimer(
        id = timerId,
        payload = sagaId,
        dueAt = EpochSeconds(10),
        firedAt = EpochSeconds(12),
        lateness = 2,
    )

    @Test
    fun `a fired timer resumes the saga it names`() =
        runTest {
            val repository = InMemoryRepository()
            val resumed = mutableListOf<String>()
            val engine =
                PetichEngine(
                    repository = repository,
                    definitions = listOf(waitThenObserve(ObserveHowItWoke(resumed))),
                )

            val saga =
                Petich(
                    id = "s1",
                    type = "t",
                    currentPhase = PetichPhase.EXECUTION,
                    status = PetichStatus.PROCESSING,
                    payload = Payload(),
                )
            repository.saveOrGet(saga)
            engine.process(saga)

            assertEquals(
                PetichStatus.PENDING_SIGNATURE,
                repository.saved["s1"]?.status,
                "the saga must be waiting before the timer fires, or this test proves nothing",
            )

            SagaTimerSink(repository, engine).deliver(fired("timer-1", "s1"))

            assertEquals(
                listOf("s1:late=2"),
                resumed,
                "the saga did not continue into the next step, or it could not tell a timer " +
                    "from a person",
            )
            assertTrue(
                repository.saved["s1"]?.status != PetichStatus.PENDING_SIGNATURE,
                "the saga is still waiting after its timer fired",
            )
        }

    @Test
    fun `a timer naming a saga that is gone is reported and not retried`() =
        runTest {
            val repository = InMemoryRepository()
            val engine =
                PetichEngine(
                    repository = repository,
                    definitions = listOf(waitThenObserve(ObserveHowItWoke(mutableListOf()))),
                )
            val missing = mutableListOf<Pair<String, String>>()

            val sink =
                SagaTimerSink(
                    repository,
                    engine = engine,
                    onMissing = { timerId, sagaId -> missing += timerId to sagaId },
                )

            // Returns rather than throwing: chronik treats a throw as a failed delivery and retries
            // it, and no number of retries will bring back a saga that no longer exists — the timer
            // would burn its attempts and dead letter over something no retry can fix.
            sink.deliver(fired("timer-1", "vanished"))

            assertEquals(listOf("timer-1" to "vanished"), missing)
        }

    @Test
    fun `a saga whose type has no engine is reported rather than resumed at random`() =
        runTest {
            val repository = InMemoryRepository()
            val saga =
                Petich(
                    id = "s1",
                    type = "unregistered",
                    currentPhase = PetichPhase.EXECUTION,
                    status = PetichStatus.PENDING_SIGNATURE,
                    payload = Payload(),
                )
            repository.saveOrGet(saga)

            // AN ENGINE THAT KNOWS A DIFFERENT TYPE, which is the only way to be unowned: the
            // application keeps no mapping that could be missing an entry, so a saga is unowned
            // exactly when its type has no definition here (B-31).
            val known =
                PetichEngine(
                    repository = repository,
                    definitions = listOf(petich<Payload>("t") { step("wait", ResumeAnything()) }),
                )

            val unknown = mutableListOf<String>()
            val sink = SagaTimerSink(repository, known, onUnknownType = { unknown += it.id })

            sink.deliver(fired("timer-1", "s1"))

            // Silence here would leave the saga suspended with nothing saying why. Skipped rather
            // than failed: the engine's own answer to an unknown type is to end the saga, which is
            // right when somebody tries to start one and wrong for a row older than its definition.
            assertEquals(listOf("s1"), unknown)
        }

    @Test
    fun `an engine that fails to resume lets the delivery fail so chronik retries it`() =
        runTest {
            val repository = InMemoryRepository()
            val exploding =
                object : PetichRepository by repository {
                    override suspend fun findById(id: String) = error("the saga store is down")
                }
            val engine =
                PetichEngine(
                    repository = repository,
                    definitions = listOf(waitThenObserve(ObserveHowItWoke(mutableListOf()))),
                )

            val sink = SagaTimerSink(exploding, engine)

            var threw = false
            try {
                sink.deliver(fired("timer-1", "s1"))
            } catch (e: IllegalStateException) {
                threw = true
            }

            // Swallowing this would mark the timer delivered and lose the wake-up for good.
            assertTrue(threw, "a storage failure must surface as a failed delivery")
        }
}
