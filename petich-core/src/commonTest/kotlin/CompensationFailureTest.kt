package io.github.youndie.petich

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class CompensationFailureTest {
    data class TestPayload(
        val data: String,
    ) : PetichPayload()

    class FailsToCompensate : PetichStep<TestPayload> {
        override suspend fun execute(
            ctx: PetichStepContext,
            payload: TestPayload,
        ) = Unit

        override suspend fun compensate(
            ctx: PetichStepContext,
            payload: TestPayload,
        ): Unit = throw RuntimeException("Compensation failed")
    }

    class FailsOutright : PetichStep<TestPayload> {
        override suspend fun execute(
            ctx: PetichStepContext,
            payload: TestPayload,
        ) = throw RuntimeException("Normal failure")

        override suspend fun compensate(
            ctx: PetichStepContext,
            payload: TestPayload,
        ) {
        }
    }

    class CapturingCompensationFailureHandler : CompensationFailureHandler {
        var handled = false

        override suspend fun handle(
            e: Exception,
            petich: Petich,
            stepKey: String,
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
            val engine =
                PetichEngine(
                    repository = MockRepository(),
                    compensationFailureHandler = handler,
                    definitions =
                        listOf(
                            petich<TestPayload>("type") {
                                step("acts", FailsToCompensate())
                                step("throws", FailsOutright())
                            },
                        ),
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
            assertTrue(handler.handled, "Handler should have been called on compensation failure")
        }
}
