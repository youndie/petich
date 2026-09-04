package ru.workinprogress.petich

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Work an interceptor asks to have committed with its state change.
 *
 * The three outcomes that matter are: it reaches a repository that can take it, it is COUNTED when
 * it reaches one that cannot, and the mistake can be refused at wiring time instead. The middle one
 * is the reason the other two exist — a dropped side effect leaves the saga correct, the write
 * successful and every natural assertion passing, so a counter is the only trace there is.
 */
class SideEffectTest {
    private data class Payload(
        val what: String = "x",
    ) : PetichPayload()

    private data class WriteThis(
        val id: String,
    ) : PetichSideEffect

    private open class PlainRepository : PetichRepository {
        val saved = mutableMapOf<String, Petich>()

        override suspend fun findById(id: String) = saved[id]

        override suspend fun saveOrGet(petich: Petich) = saved.getOrPut(petich.id) { petich }

        override suspend fun update(petich: Petich): Boolean {
            saved[petich.id] = petich
            return true
        }
    }

    private class RecordingRepository :
        PlainRepository(),
        SideEffectAwarePetichRepository {
        val committed = mutableListOf<Pair<String, List<PetichSideEffect>>>()

        override suspend fun update(
            petich: Petich,
            outboxEvents: List<OutboxEvent>,
            sideEffects: List<PetichSideEffect>,
        ): Boolean {
            // Both in one call on purpose: the guarantee is one transaction, and a repository asked
            // twice would open two.
            committed += petich.id to sideEffects
            return update(petich)
        }
    }

    private class Suspending(
        val effects: List<PetichSideEffect>,
    ) : PetichInterceptor<Payload> {
        override val phase = PetichPhase.EXECUTION

        override fun supports(payload: PetichPayload) = payload is Payload

        override suspend fun intercept(
            petich: Petich,
            payload: Payload,
        ) = InterceptorResult.Suspend(requiredAction = "WAIT", sideEffects = effects)

        override suspend fun compensate(
            petich: Petich,
            payload: Payload,
        ) = Unit
    }

    private fun saga(id: String = "s1") =
        Petich(
            id = id,
            type = "t",
            currentPhase = PetichPhase.EXECUTION,
            status = PetichStatus.PROCESSING,
            payload = Payload(),
        )

    /**
     * The case the whole thing exists for: a step that SUSPENDS could hand the engine nothing at
     * all before this, not even an outbox event.
     */
    @Test
    fun `a suspending step gets its side effects committed with its state`() =
        runTest {
            val repository = RecordingRepository()
            val engine = PetichEngine(listOf(Suspending(listOf(WriteThis("timer-1")))), repository)

            engine.process(saga())

            assertEquals(listOf("s1"), repository.committed.map { it.first })
            assertEquals(listOf<PetichSideEffect>(WriteThis("timer-1")), repository.committed.single().second)
            assertEquals(PetichStatus.PENDING_SIGNATURE, repository.saved["s1"]?.status)
        }

    @Test
    fun `a repository that cannot store them drops them and says so`() =
        runTest {
            val dropped = mutableListOf<Pair<String, Int>>()
            val metrics =
                object : PetichEngineMetrics {
                    override fun onDroppedSideEffects(
                        petichType: String,
                        count: Int,
                    ) {
                        dropped += petichType to count
                    }
                }
            val repository = PlainRepository()
            val engine =
                PetichEngine(
                    listOf(Suspending(listOf(WriteThis("a"), WriteThis("b")))),
                    repository,
                    metrics = metrics,
                )

            engine.process(saga())

            // The saga is correct and the write succeeded. Without this counter nothing anywhere
            // would differ from a run in which the side effects were stored.
            assertEquals(PetichStatus.PENDING_SIGNATURE, repository.saved["s1"]?.status)
            assertEquals(listOf("t" to 2), dropped)
        }

    @Test
    fun `no side effects means no counter and no complaint`() =
        runTest {
            var dropped = 0
            val metrics =
                object : PetichEngineMetrics {
                    override fun onDroppedSideEffects(
                        petichType: String,
                        count: Int,
                    ) {
                        dropped++
                    }
                }
            val engine = PetichEngine(listOf(Suspending(emptyList())), PlainRepository(), metrics = metrics)

            engine.process(saga())

            // Nothing happening is not a loss, and the two are deliberately different events.
            assertEquals(0, dropped)
        }

    @Test
    fun `requireSideEffects refuses the wiring rather than the first saga`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                PetichEngine(
                    listOf(Suspending(emptyList())),
                    PlainRepository(),
                    config = PetichEngineConfig(requireSideEffects = true),
                )
            }

        assertTrue("PlainRepository" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `requireSideEffects is satisfied by a repository that can store them`() {
        PetichEngine(
            listOf(Suspending(emptyList())),
            RecordingRepository(),
            config = PetichEngineConfig(requireSideEffects = true),
        )
    }
}
