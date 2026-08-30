package ru.workinprogress.petich

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OutboxEventTest {
    data class TestPayload(
        val data: String,
    ) : PetichPayload()

    data class FakeOutboxEvent(
        override val id: String,
        override val type: String = "test_event",
        override val payload: String = "{}",
    ) : OutboxEvent

    // A plain, non-outbox-aware double — like MockRepository in PetichTest.kt but WITHOUT outbox
    // support, to prove the degradation: events are dropped silently and the petich still goes
    // through.
    class PlainRepository : PetichRepository {
        var petich: Petich? = null

        override suspend fun findById(id: String): Petich? = petich?.takeIf { it.id == id }

        override suspend fun saveOrGet(petich: Petich): Petich {
            if (this.petich == null) {
                this.petich = petich
                return petich
            }
            return this.petich!!
        }

        override suspend fun update(petich: Petich): Boolean {
            val current = this.petich
            if (current != null && current.version != petich.version - 1) return false
            this.petich = petich
            return true
        }
    }

    // An outbox-aware double: records which events arrived on EVERY update(petich, events) call,
    // so the test can check they really reached the "storage" at the moment of persistence.
    class FakeOutboxAwareRepository : OutboxAwarePetichRepository {
        var petich: Petich? = null
        val recordedEvents = mutableListOf<OutboxEvent>()

        override suspend fun findById(id: String): Petich? = petich?.takeIf { it.id == id }

        override suspend fun saveOrGet(petich: Petich): Petich {
            if (this.petich == null) {
                this.petich = petich
                return petich
            }
            return this.petich!!
        }

        override suspend fun update(
            petich: Petich,
            outboxEvents: List<OutboxEvent>,
        ): Boolean {
            val current = this.petich
            if (current != null && current.version != petich.version - 1) return false
            this.petich = petich
            recordedEvents.addAll(outboxEvents)
            return true
        }
    }

    // Records only the drops. The rest of PetichEngineMetrics is inherited as its no-op defaults,
    // so a test that expects no drop is asserting on an empty list rather than on the absence of a
    // call it never wired.
    class RecordingMetrics : PetichEngineMetrics {
        val dropped = mutableListOf<Pair<String, Int>>()

        override fun onDroppedEvents(
            type: String,
            count: Int,
        ) {
            dropped += type to count
        }
    }

    private fun proceedingInterceptor(events: List<OutboxEvent>) =
        object : PetichInterceptor<TestPayload> {
            override val phase = PetichPhase.EXECUTION

            override fun supports(payload: PetichPayload) = true

            override suspend fun intercept(
                petich: Petich,
                payload: TestPayload,
            ): InterceptorResult = InterceptorResult.Proceed(outboxEvents = events)

            override suspend fun compensate(
                petich: Petich,
                payload: TestPayload,
            ) {
            }
        }

    private fun testPetich() =
        Petich(
            id = "1",
            type = "type",
            currentPhase = PetichPhase.EXECUTION,
            status = PetichStatus.PROCESSING,
            payload = TestPayload("test"),
        )

    @Test
    fun `outbox events attached to Proceed reach an OutboxAwarePetichRepository`() =
        runBlocking {
            val event = FakeOutboxEvent(id = "evt-1")
            val interceptor =
                object : PetichInterceptor<TestPayload> {
                    override val phase = PetichPhase.EXECUTION

                    override fun supports(payload: PetichPayload) = true

                    override suspend fun intercept(
                        petich: Petich,
                        payload: TestPayload,
                    ): InterceptorResult = InterceptorResult.Proceed(outboxEvents = listOf(event))

                    override suspend fun compensate(
                        petich: Petich,
                        payload: TestPayload,
                    ) {
                    }
                }

            val repository = FakeOutboxAwareRepository()
            val engine = PetichEngine(listOf(interceptor), repository)

            val result = engine.process(testPetich())

            assertTrue(result is PetichResult.Success)
            assertEquals(listOf<OutboxEvent>(event), repository.recordedEvents)
        }

    @Test
    fun `outbox events attached to Proceed are silently dropped against a plain PetichRepository`() =
        runBlocking {
            val interceptor =
                object : PetichInterceptor<TestPayload> {
                    override val phase = PetichPhase.EXECUTION

                    override fun supports(payload: PetichPayload) = true

                    override suspend fun intercept(
                        petich: Petich,
                        payload: TestPayload,
                    ): InterceptorResult =
                        InterceptorResult.Proceed(outboxEvents = listOf(FakeOutboxEvent(id = "evt-2")))

                    override suspend fun compensate(
                        petich: Petich,
                        payload: TestPayload,
                    ) {
                    }
                }

            val engine = PetichEngine(listOf(interceptor), PlainRepository())

            // Must not fail: the repository has no outbox support, and the petich still completes.
            val result = engine.process(testPetich())

            assertTrue(result is PetichResult.Success)
        }

    @Test
    fun `a compensating interceptor's events reach the outbox-aware repository during rollback`() =
        runBlocking {
            val compensationEvent = FakeOutboxEvent(id = "evt-compensate", type = "transfer_reversed")

            val succeeding =
                object : PetichInterceptor<TestPayload> {
                    override val phase = PetichPhase.EXECUTION
                    override val priority = 10

                    override fun supports(payload: PetichPayload) = true

                    override suspend fun intercept(
                        petich: Petich,
                        payload: TestPayload,
                    ): InterceptorResult = InterceptorResult.Proceed()

                    override suspend fun compensate(
                        petich: Petich,
                        payload: TestPayload,
                    ) {
                    }

                    override suspend fun compensateWithEvents(
                        petich: Petich,
                        payload: TestPayload,
                    ): List<OutboxEvent> {
                        compensate(petich, payload)
                        return listOf(compensationEvent)
                    }
                }

            val failing =
                object : PetichInterceptor<TestPayload> {
                    override val phase = PetichPhase.EXECUTION
                    override val priority = 5

                    override fun supports(payload: PetichPayload) = true

                    override suspend fun intercept(
                        petich: Petich,
                        payload: TestPayload,
                    ): InterceptorResult = InterceptorResult.Compensate("forced rollback")

                    override suspend fun compensate(
                        petich: Petich,
                        payload: TestPayload,
                    ) {
                    }
                }

            val repository = FakeOutboxAwareRepository()
            val engine = PetichEngine(listOf(succeeding, failing), repository)

            val result = engine.process(testPetich())

            assertTrue(result is PetichResult.Error)
            assertTrue(repository.recordedEvents.contains(compensationEvent))
        }

    // The drop is counted. Everything else about this run is indistinguishable from a healthy one
    // — the saga succeeds and its state is correct — which is the whole reason the counter exists.
    @Test
    fun `dropping events against a plain PetichRepository is counted`() =
        runBlocking {
            val metrics = RecordingMetrics()
            val interceptor =
                proceedingInterceptor(listOf(FakeOutboxEvent(id = "evt-a"), FakeOutboxEvent(id = "evt-b")))

            val engine = PetichEngine(listOf(interceptor), PlainRepository(), metrics = metrics)

            val result = engine.process(testPetich())

            assertTrue(result is PetichResult.Success)
            // The count is how many were lost in that write, not how many writes lost something:
            // a counter that said "1" here would under-report every multi-event saga.
            assertEquals(listOf("type" to 2), metrics.dropped)
        }

    // The control that makes the test above mean anything. updatePetich reaches the plain
    // repository.update along two paths, and only one of them is a loss; a counter placed on the
    // shared condition would fire on every saga that produces no events at all, which is most of
    // them, and the signal would be worthless.
    @Test
    fun `a saga that produces no events counts no drop`() =
        runBlocking {
            val metrics = RecordingMetrics()

            val engine = PetichEngine(listOf(proceedingInterceptor(emptyList())), PlainRepository(), metrics = metrics)

            val result = engine.process(testPetich())

            assertTrue(result is PetichResult.Success)
            assertEquals(emptyList(), metrics.dropped)
        }

    // The other control: events that were actually stored are not a loss.
    @Test
    fun `events reaching an outbox-aware repository count no drop`() =
        runBlocking {
            val metrics = RecordingMetrics()
            val repository = FakeOutboxAwareRepository()

            val engine =
                PetichEngine(
                    listOf(proceedingInterceptor(listOf(FakeOutboxEvent(id = "evt-c")))),
                    repository,
                    metrics = metrics,
                )

            val result = engine.process(testPetich())

            assertTrue(result is PetichResult.Success)
            assertEquals(1, repository.recordedEvents.size)
            assertEquals(emptyList(), metrics.dropped)
        }

    // Compensation events go through the same persistence point, and are dropped the same way. The
    // rollback path is the one where losing the notification hurts most: the forward effect has
    // already been undone, and the event saying so is what the outside world was waiting for.
    @Test
    fun `dropping a compensation's events is counted too`() =
        runBlocking {
            val metrics = RecordingMetrics()

            val compensating =
                object : PetichInterceptor<TestPayload> {
                    override val phase = PetichPhase.EXECUTION
                    override val priority = 10

                    override fun supports(payload: PetichPayload) = true

                    override suspend fun intercept(
                        petich: Petich,
                        payload: TestPayload,
                    ): InterceptorResult = InterceptorResult.Proceed()

                    override suspend fun compensate(
                        petich: Petich,
                        payload: TestPayload,
                    ) {
                    }

                    override suspend fun compensateWithEvents(
                        petich: Petich,
                        payload: TestPayload,
                    ): List<OutboxEvent> {
                        compensate(petich, payload)
                        return listOf(FakeOutboxEvent(id = "evt-rollback", type = "transfer_reversed"))
                    }
                }

            val failing =
                object : PetichInterceptor<TestPayload> {
                    override val phase = PetichPhase.EXECUTION
                    override val priority = 5

                    override fun supports(payload: PetichPayload) = true

                    override suspend fun intercept(
                        petich: Petich,
                        payload: TestPayload,
                    ): InterceptorResult = InterceptorResult.Compensate("forced rollback")

                    override suspend fun compensate(
                        petich: Petich,
                        payload: TestPayload,
                    ) {
                    }
                }

            val engine = PetichEngine(listOf(compensating, failing), PlainRepository(), metrics = metrics)

            val result = engine.process(testPetich())

            assertTrue(result is PetichResult.Error)
            assertEquals(listOf("type" to 1), metrics.dropped)
        }

    @Test
    fun `requireOutbox refuses to construct against a plain PetichRepository`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                PetichEngine(
                    interceptors = emptyList(),
                    repository = PlainRepository(),
                    config = PetichEngineConfig(requireOutbox = true),
                )
            }

        // The message has to name the offending class: the mistake is one repository reaching a
        // place that needed another, and "requireOutbox failed" alone does not say which one came.
        assertTrue(error.message.orEmpty().contains("PlainRepository"), error.message.orEmpty())
    }

    @Test
    fun `requireOutbox constructs against an outbox-aware repository`() {
        PetichEngine(
            interceptors = emptyList(),
            repository = FakeOutboxAwareRepository(),
            config = PetichEngineConfig(requireOutbox = true),
        )
    }

    // The control for the pair above: the default must stay the documented quiet degradation, or
    // this change breaks every application that deliberately runs without an outbox.
    @Test
    fun `a plain PetichRepository still constructs by default`() {
        PetichEngine(interceptors = emptyList(), repository = PlainRepository())
    }
}
