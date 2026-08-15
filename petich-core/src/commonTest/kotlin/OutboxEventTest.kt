package ru.workinprogress.petich

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
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
                    ): InterceptorResult = InterceptorResult.Proceed(outboxEvents = listOf(FakeOutboxEvent(id = "evt-2")))

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
}
