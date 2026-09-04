package ru.workinprogress.petich.chronik

import kotlinx.coroutines.test.runTest
import ru.workinprogress.chronik.EpochSeconds
import ru.workinprogress.chronik.FiredTimer
import ru.workinprogress.petich.InterceptorResult
import ru.workinprogress.petich.Petich
import ru.workinprogress.petich.PetichEngine
import ru.workinprogress.petich.PetichInterceptor
import ru.workinprogress.petich.PetichPayload
import ru.workinprogress.petich.PetichPhase
import ru.workinprogress.petich.PetichRepository
import ru.workinprogress.petich.PetichStatus
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

    /**
     * The step that waits. Higher priority, so it runs first.
     *
     * It suspends unconditionally: a `Suspend` moves the saga on to the NEXT step when it is
     * resumed (the engine stores `index + 1`), so this one is entered exactly once and never sees
     * the resume at all. Getting that wrong is the first mistake anybody writing an `awaitUntil`
     * will make, which is why the two roles are separate classes here rather than one.
     */
    private class WaitForTheDeadline : PetichInterceptor<Payload> {
        override val phase = PetichPhase.EXECUTION
        override val priority = 10

        override fun supports(payload: PetichPayload) = payload is Payload

        override suspend fun intercept(
            petich: Petich,
            payload: Payload,
        ): InterceptorResult = InterceptorResult.Suspend(requiredAction = "AWAIT_DEADLINE")

        override suspend fun compensate(
            petich: Petich,
            payload: Payload,
        ) = Unit
    }

    /** The step the saga continues into once something wakes it. */
    private class ObserveHowItWoke(
        val resumed: MutableList<String>,
    ) : PetichInterceptor<Payload> {
        override val phase = PetichPhase.EXECUTION
        override val priority = 0

        override fun supports(payload: PetichPayload) = payload is Payload

        override suspend fun intercept(
            petich: Petich,
            payload: Payload,
        ): InterceptorResult {
            // Which somebody came back is in the payload's type: a timer here, a person in an
            // application that also resumes by hand. "The client confirmed" and "nobody came and
            // the deadline passed" lead to opposite branches.
            val resume = petich.resumePayload
            resumed += if (resume is TimerFired) "${petich.id}:late=${resume.lateness}" else "${petich.id}:by-hand"
            return InterceptorResult.Proceed()
        }

        override suspend fun compensate(
            petich: Petich,
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
            val engine = PetichEngine(listOf(WaitForTheDeadline(), ObserveHowItWoke(resumed)), repository)

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

            SagaTimerSink(repository, engineFor = { engine }).deliver(fired("timer-1", "s1"))

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
            val engine = PetichEngine(listOf(WaitForTheDeadline(), ObserveHowItWoke(mutableListOf())), repository)
            val missing = mutableListOf<Pair<String, String>>()

            val sink =
                SagaTimerSink(
                    repository,
                    engineFor = { engine },
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

            val unowned = mutableListOf<String>()
            val sink = SagaTimerSink(repository, engineFor = { null }, onUnowned = { unowned += it.id })

            sink.deliver(fired("timer-1", "s1"))

            // Silence here would mean somebody added a saga type and forgot to register it, and
            // those sagas pile up suspended with nothing saying so.
            assertEquals(listOf("s1"), unowned)
        }

    @Test
    fun `an engine that fails to resume lets the delivery fail, so chronik retries it`() =
        runTest {
            val repository = InMemoryRepository()
            val exploding =
                object : PetichRepository by repository {
                    override suspend fun findById(id: String) = error("the saga store is down")
                }
            val engine = PetichEngine(listOf(WaitForTheDeadline(), ObserveHowItWoke(mutableListOf())), repository)

            val sink = SagaTimerSink(exploding, engineFor = { engine })

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
