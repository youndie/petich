package ru.workinprogress.petich

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class PetichTest {
    // Mock Payload
    data class TestPayload(
        val data: String,
    ) : PetichPayload()

    // Mock Interceptor
    class TestInterceptor(
        override val phase: PetichPhase,
        val id: String,
        val shouldFail: Boolean = false,
        var compensated: Boolean = false,
    ) : PetichInterceptor<TestPayload> {
        override fun supports(payload: PetichPayload) = true

        override suspend fun intercept(
            petich: Petich,
            payload: TestPayload,
        ): InterceptorResult {
            if (shouldFail) throw RuntimeException("Fail")
            return InterceptorResult.Proceed()
        }

        override suspend fun compensate(
            petich: Petich,
            payload: TestPayload,
        ) {
            compensated = true
        }
    }

    // Mock Repository
    class MockRepository : PetichRepository {
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
            if (current != null && current.version != petich.version - 1) {
                return false
            }
            this.petich = petich
            return true
        }
    }

    @Test
    fun testCompensationOnFailure() =
        runBlocking {
            val interceptor1 = TestInterceptor(PetichPhase.EXECUTION, "1")
            val interceptor2 = TestInterceptor(PetichPhase.EXECUTION, "2", shouldFail = true)

            val engine =
                PetichEngine(
                    listOf(interceptor1, interceptor2),
                    MockRepository(),
                )

            val payload = TestPayload("test")
            val petich =
                Petich(
                    id = "1",
                    type = "type",
                    currentPhase = PetichPhase.EXECUTION,
                    status = PetichStatus.PROCESSING,
                    payload = payload,
                )

            // NO try/catch AROUND THIS, and no assertFailsWith either. The empty catch that used
            // to be here passed whether or not anything was thrown, and replacing it with
            // assertFailsWith made both tests fail: the engine does not throw here at all, it
            // reports the outcome in its result. The catch was decoration, and it hid that.
            val result = engine.process(petich)

            assertTrue(result !is PetichResult.Success, "the saga was not supposed to succeed: $result")
            assertTrue(interceptor1.compensated, "Interceptor 1 should have been compensated")
        }

    @Test
    fun testEnrichedPayloadUpdates() =
        runBlocking {
            class EnrichedInterceptor : PetichInterceptor<TestPayload> {
                override val phase: PetichPhase = PetichPhase.ENRICHMENT

                override fun supports(payload: PetichPayload) = true

                override suspend fun intercept(
                    petich: Petich,
                    payload: TestPayload,
                ): InterceptorResult = InterceptorResult.Proceed(SimpleEnrichedPayload(mapOf("key" to "value")))

                override suspend fun compensate(
                    petich: Petich,
                    payload: TestPayload,
                ) {
                }
            }

            val engine =
                PetichEngine(
                    listOf(EnrichedInterceptor()),
                    MockRepository(),
                )

            val payload = TestPayload("test")
            val petich =
                Petich(
                    id = "1",
                    type = "type",
                    currentPhase = PetichPhase.ENRICHMENT,
                    status = PetichStatus.PROCESSING,
                    payload = payload,
                )

            val result = engine.process(petich)
            assertTrue(result is PetichResult.Success)
            val successResult = result
            val enriched = successResult.petich.enrichedPayload as SimpleEnrichedPayload
            assertTrue(enriched.data["key"] == "value")
        }

    @Test
    fun testEnrichedPayloadMerge() =
        runBlocking {
            class MergeInterceptor1 : PetichInterceptor<TestPayload> {
                override val phase: PetichPhase = PetichPhase.ENRICHMENT

                override fun supports(payload: PetichPayload) = true

                override suspend fun intercept(
                    petich: Petich,
                    payload: TestPayload,
                ): InterceptorResult = InterceptorResult.Proceed(SimpleEnrichedPayload(mapOf("key1" to "val1")))

                override suspend fun compensate(
                    petich: Petich,
                    payload: TestPayload,
                ) {
                }
            }

            class MergeInterceptor2 : PetichInterceptor<TestPayload> {
                override val phase: PetichPhase = PetichPhase.ENRICHMENT
                override val priority: Int = -1 // Run after 1

                override fun supports(payload: PetichPayload) = true

                override suspend fun intercept(
                    petich: Petich,
                    payload: TestPayload,
                ): InterceptorResult = InterceptorResult.Proceed(SimpleEnrichedPayload(mapOf("key2" to "val2")))

                override suspend fun compensate(
                    petich: Petich,
                    payload: TestPayload,
                ) {
                }
            }

            val engine =
                PetichEngine(
                    listOf(MergeInterceptor1(), MergeInterceptor2()),
                    MockRepository(),
                )

            val payload = TestPayload("test")
            val petich =
                Petich(
                    id = "1",
                    type = "type",
                    currentPhase = PetichPhase.ENRICHMENT,
                    status = PetichStatus.PROCESSING,
                    payload = payload,
                )

            val result = engine.process(petich)
            assertTrue(result is PetichResult.Success)
            val successResult = result
            val enriched = successResult.petich.enrichedPayload as SimpleEnrichedPayload
            assertTrue(enriched.data["key1"] == "val1")
            assertTrue(enriched.data["key2"] == "val2")
        }
}
