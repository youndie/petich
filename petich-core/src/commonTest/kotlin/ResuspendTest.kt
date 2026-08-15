package ru.workinprogress.petich

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class ResuspendTest {
    data class TestPayload(
        val data: String,
    ) : PetichPayload()

    class ResuspendInterceptor(
        override val phase: PetichPhase,
        val id: String,
    ) : PetichInterceptor<TestPayload> {
        var callCount = 0

        override fun supports(payload: PetichPayload) = true

        override suspend fun intercept(
            petich: Petich,
            payload: TestPayload,
        ): InterceptorResult {
            callCount++
            return InterceptorResult.Resuspend("RESUSPEND_ACTION")
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
    fun testResuspendInterceptorIsReExecutedOnResume() =
        runBlocking {
            val interceptor = ResuspendInterceptor(PetichPhase.EXECUTION, "1")

            val repo = MockRepository()
            val engine =
                PetichEngine(
                    listOf(interceptor),
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

            // 1. First run: Resuspend
            engine.process(petich)

            assertEquals(1, interceptor.callCount, "Interceptor should be called once on first run")

            // 2. Second run: Resume
            engine.process(repo.petich!!)

            assertEquals(2, interceptor.callCount, "Interceptor SHOULD be re-executed on resume because of Resuspend")
        }
}
