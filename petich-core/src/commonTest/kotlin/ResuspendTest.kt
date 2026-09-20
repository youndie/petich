package io.github.youndie.petich

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class ResuspendTest {
    data class TestPayload(
        val data: String,
    ) : PetichPayload()

    class ResuspendInterceptor(
        val id: String,
    ) : PetichStep<TestPayload> {
        var callCount = 0

        override suspend fun execute(
            ctx: PetichStepContext,
            payload: TestPayload,
        ) {
            callCount++
            return ctx.resuspendFor("RESUSPEND_ACTION")
        }

        override suspend fun compensate(
            ctx: PetichStepContext,
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
            val interceptor = ResuspendInterceptor("1")

            val repo = MockRepository()
            val engine =
                PetichEngine(
                    repository = repo,
                    definitions = listOf(petichDefinition<TestPayload>("type") { step("re-ask", interceptor) }),
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
