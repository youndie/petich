package ru.workinprogress.petich

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class CompensationFailureTest {
    data class TestPayload(
        val data: String,
    ) : PetichPayload()

    class FailingCompensateInterceptor(
        override val phase: PetichPhase,
    ) : PetichInterceptor<TestPayload> {
        override fun supports(payload: PetichPayload) = true

        override suspend fun intercept(
            petich: Petich,
            payload: TestPayload,
        ): InterceptorResult = InterceptorResult.Proceed()

        override suspend fun compensate(
            petich: Petich,
            payload: TestPayload,
        ): Unit = throw RuntimeException("Compensation failed")
    }

    class FailingInterceptor(
        override val phase: PetichPhase,
    ) : PetichInterceptor<TestPayload> {
        override fun supports(payload: PetichPayload) = true

        override suspend fun intercept(
            petich: Petich,
            payload: TestPayload,
        ): InterceptorResult = throw RuntimeException("Normal failure")

        override suspend fun compensate(
            petich: Petich,
            payload: TestPayload,
        ) {
        }
    }

    class CapturingCompensationFailureHandler : CompensationFailureHandler {
        var handled = false

        override suspend fun handle(
            e: Exception,
            petich: Petich,
            interceptor: PetichInterceptor<*>,
        ) {
            handled = true
        }
    }

    class MockRepository : PetichRepository {
        var petich: Petich? = null

        override suspend fun findById(id: String): Petich? = petich?.takeIf { it.id == id }

        override suspend fun saveOrGet(petich: Petich): Petich {
            this.petich = petich
            return petich
        }

        override suspend fun update(petich: Petich): Boolean {
            this.petich = petich
            return true
        }
    }

    @Test
    fun testCompensationFailureIsHandled() =
        runBlocking {
            val handler = CapturingCompensationFailureHandler()
            val interceptor1 = FailingCompensateInterceptor(PetichPhase.EXECUTION)
            val interceptor2 = FailingInterceptor(PetichPhase.EXECUTION)

            val engine =
                PetichEngine(
                    listOf(interceptor1, interceptor2),
                    MockRepository(),
                    handler,
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

            try {
                engine.process(petich)
            } catch (e: Exception) {
                // expected
            }

            assertTrue(handler.handled, "Handler should have been called on compensation failure")
        }
}
