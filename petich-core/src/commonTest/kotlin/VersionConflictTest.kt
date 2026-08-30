package ru.workinprogress.petich

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class VersionConflictTest {
    data class TestPayload(
        val data: String,
    ) : PetichPayload()

    class ProceedInterceptor : PetichInterceptor<TestPayload> {
        override val phase: PetichPhase = PetichPhase.EXECUTION

        override fun supports(payload: PetichPayload) = true

        override suspend fun intercept(
            petich: Petich,
            payload: TestPayload,
        ): InterceptorResult = InterceptorResult.Proceed()

        override suspend fun compensate(
            petich: Petich,
            payload: TestPayload,
        ) {}
    }

    class VersionConflictRepository : PetichRepository {
        override suspend fun findById(id: String): Petich? = null

        override suspend fun saveOrGet(petich: Petich): Petich = petich

        override suspend fun update(petich: Petich): Boolean {
            // Simulate version conflict for FAILED status update
            if (petich.status == PetichStatus.FAILED) {
                return false
            }
            return true
        }
    }

    @Test
    fun testVersionConflictRetrySucceeds() =
        runBlocking {
            val interceptor = ProceedInterceptor()

            // Repository fails on first attempt, succeeds on second
            class RetryRepository(
                var initialPetich: Petich,
            ) : PetichRepository {
                var attempt = 0
                var savedPetich: Petich? = initialPetich

                override suspend fun findById(id: String): Petich? = savedPetich?.takeIf { it.id == id }

                override suspend fun saveOrGet(petich: Petich): Petich = savedPetich ?: petich

                override suspend fun update(petich: Petich): Boolean {
                    if (attempt == 0) {
                        attempt++
                        return false // Conflict
                    }
                    savedPetich = petich
                    return true // Success
                }
            }

            val payload = TestPayload("test")
            val petichObj =
                Petich(
                    id = "1",
                    type = "type",
                    currentPhase = PetichPhase.EXECUTION,
                    status = PetichStatus.PROCESSING,
                    payload = payload,
                )

            val repo = RetryRepository(petichObj)
            val engine =
                PetichEngine(
                    listOf(interceptor),
                    repo,
                )

            val result = engine.process(petichObj)

            println("[DEBUG_LOG] Result: $result")
            // Re-fetch from repository to check if it was updated
            val saved = repo.savedPetich
            println(
                "[DEBUG_LOG] Final petich status: ${saved?.status}, version: ${saved?.version}, attempts: ${repo.attempt}",
            )

            assertTrue(result is PetichResult.Success, "Should succeed on second attempt, result: $result")
        }

    @Test
    fun testCompensationVersionConflictCausesInconsistentRetry() =
        runBlocking {
            class CompensateInterceptor : PetichInterceptor<TestPayload> {
                override val phase: PetichPhase = PetichPhase.EXECUTION

                override fun supports(payload: PetichPayload) = true

                override suspend fun intercept(
                    petich: Petich,
                    payload: TestPayload,
                ): InterceptorResult = InterceptorResult.Compensate("Fail")

                override suspend fun compensate(
                    petich: Petich,
                    payload: TestPayload,
                ) {
                }
            }

            var callCount = 0

            class ConflictRepository : PetichRepository {
                var petich: Petich? = null
                var attempt = 0

                override suspend fun findById(id: String): Petich? = petich?.takeIf { it.id == id }

                override suspend fun saveOrGet(petich: Petich): Petich {
                    if (this.petich == null) this.petich = petich
                    return this.petich!!
                }

                override suspend fun update(petich: Petich): Boolean {
                    if (petich.status == PetichStatus.FAILED && attempt == 0) {
                        attempt++
                        return false // Conflict
                    }
                    this.petich = petich
                    return true
                }
            }

            val repo = ConflictRepository()
            val interceptor = CompensateInterceptor()
            val engine = PetichEngine(listOf(interceptor), repo)

            val payload = TestPayload("test")
            val petichObj =
                Petich(
                    id = "1",
                    type = "type",
                    currentPhase = PetichPhase.EXECUTION,
                    status = PetichStatus.PROCESSING,
                    payload = payload,
                )

            // Should not throw OptimisticLockException up to process() because of the retry mechanism
            // But if the fix is not implemented, the status in repo will not be FAILED,
            // and the next retry in `process` will re-run the interceptor.

            engine.process(petichObj)

            // Check status in repository
            val saved = repo.petich
            assertTrue(saved?.status == PetichStatus.FAILED, "Status should be FAILED, but was ${saved?.status}")
        }
}
