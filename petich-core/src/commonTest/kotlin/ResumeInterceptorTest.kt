package ru.workinprogress.petich

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class ResumeInterceptorTest {
    data class TestPayload(
        val data: String,
    ) : PetichPayload()

    class CountingInterceptor(
        override val phase: PetichPhase,
        val id: String,
        val shouldSuspend: Boolean = false,
    ) : PetichInterceptor<TestPayload> {
        var callCount = 0

        override fun supports(payload: PetichPayload) = true

        override suspend fun intercept(
            petich: Petich,
            payload: TestPayload,
        ): InterceptorResult {
            callCount++
            if (shouldSuspend) return InterceptorResult.Suspend("SUSPEND_ACTION")
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

        override suspend fun saveOrGet(petich: Petich): Petich {
            if (this.petich == null) {
                this.petich = petich
                return petich
            }
            return this.petich!!
        }

        override suspend fun update(petich: Petich): Boolean {
            this.petich = petich
            return true
        }
    }

    @Test
    fun testNoDoubleExecutionOnResume() =
        runBlocking {
            val interceptor1 = CountingInterceptor(PetichPhase.EXECUTION, "1")
            val interceptor2 = CountingInterceptor(PetichPhase.EXECUTION, "2", shouldSuspend = true)

            val repo = MockRepository()
            val engine =
                PetichEngine(
                    listOf(interceptor1, interceptor2),
                    repo,
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

            // 1. First run: Suspend
            engine.process(petich)

            assertEquals(1, interceptor1.callCount, "Interceptor 1 should be called once")
            assertEquals(1, interceptor2.callCount, "Interceptor 2 should be called once")

            // 2. Second run: Resume
            engine.process(repo.petich!!)

            assertEquals(1, interceptor1.callCount, "Interceptor 1 should NOT be re-executed")
            assertEquals(1, interceptor2.callCount, "Interceptor 2 should NOT be re-executed")
        }
}
