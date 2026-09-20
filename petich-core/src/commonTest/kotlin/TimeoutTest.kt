package io.github.youndie.petich

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class TimeoutTest {
    data class TestPayload(
        val data: String,
    ) : PetichPayload()

    class SlowCheck : PetichCheck<TestPayload> {
        override suspend fun check(
            ctx: PetichCheckContext,
            payload: TestPayload,
        ) {
            delay(2000) // Longer than ENRICHMENT timeout (1000ms)
        }
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
            val repo = MockRepository()
            val engine =
                PetichEngine(
                    repository = repo,
                    definitions = listOf(petich<TestPayload>("type") { enrich("slow", SlowCheck()) }),
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
            assertTrue(result is PetichResult.SystemFailure, "Result should be SystemFailure due to timeout")
            assertTrue(repo.petich?.status == PetichStatus.FAILED, "Petich status should be FAILED")
        }
}
