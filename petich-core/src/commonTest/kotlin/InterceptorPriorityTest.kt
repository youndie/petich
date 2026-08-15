package ru.workinprogress.petich

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class InterceptorPriorityTest {
    data class TestPayload(
        val data: String,
    ) : PetichPayload()

    class OrderedInterceptor(
        override val phase: PetichPhase,
        override val priority: Int,
        val id: String,
        val orderList: MutableList<String>,
    ) : PetichInterceptor<TestPayload> {
        override fun supports(payload: PetichPayload) = true

        override suspend fun intercept(
            petich: Petich,
            payload: TestPayload,
        ): InterceptorResult {
            orderList.add(id)
            return InterceptorResult.Proceed()
        }

        override suspend fun compensate(
            petich: Petich,
            payload: TestPayload,
        ) {
        }
    }

    class MockRepository : PetichRepository {
        override suspend fun findById(id: String): Petich? = null

        override suspend fun saveOrGet(petich: Petich): Petich = petich

        override suspend fun update(petich: Petich): Boolean = true
    }

    @Test
    fun testInterceptorPriorityExecutionOrder() =
        runBlocking {
            val orderList = mutableListOf<String>()
            val lowPriority = OrderedInterceptor(PetichPhase.ENRICHMENT, 1, "low", orderList)
            val highPriority = OrderedInterceptor(PetichPhase.ENRICHMENT, 10, "high", orderList)

            val engine = PetichEngine(listOf(lowPriority, highPriority), MockRepository())

            val payload = TestPayload("test")
            val petich =
                Petich(
                    id = "1",
                    type = "type",
                    currentPhase = PetichPhase.ENRICHMENT,
                    status = PetichStatus.PROCESSING,
                    payload = payload,
                )

            engine.process(petich)

            assertEquals(listOf("high", "low"), orderList, "Interceptors should execute by priority descending")
        }
}
