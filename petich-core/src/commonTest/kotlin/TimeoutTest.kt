package ru.workinprogress.petich

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class TimeoutTest {
    data class TestPayload(
        val data: String,
    ) : PetichPayload()

    class SlowInterceptor : PetichInterceptor<TestPayload> {
        override val phase: PetichPhase = PetichPhase.ENRICHMENT

        override fun supports(payload: PetichPayload) = true

        override suspend fun intercept(
            petich: Petich,
            payload: TestPayload,
        ): InterceptorResult {
            delay(2000) // Longer than ENRICHMENT timeout (1000ms)
            return InterceptorResult.Proceed()
        }

        override suspend fun compensate(
            petich: Petich,
            payload: TestPayload,
        ) {}
    }

    class MockRepository : PetichRepository {
        var petich: Petich? = null

        override suspend fun findById(id: String): Petich? = petich?.takeIf { it.id == id }

        override suspend fun saveOrGet(petich: Petich): Petich = petich

        override suspend fun update(petich: Petich): Boolean {
            this.petich = petich
            return true
        }
    }

    @Test
    fun testPhaseTimeoutSetsFailedStatus() =
        runBlocking {
            val interceptor = SlowInterceptor()
            val repo = MockRepository()
            val engine = PetichEngine(listOf(interceptor), repo)

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
            assertTrue(result is PetichResult.SystemFailure, "Result should be SystemFailure due to timeout")
            assertTrue(repo.petich?.status == PetichStatus.FAILED, "Petich status should be FAILED")
        }
}
