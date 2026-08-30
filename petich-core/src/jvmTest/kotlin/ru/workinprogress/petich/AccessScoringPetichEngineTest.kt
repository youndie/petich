package ru.workinprogress.petich

import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// --- Domain Setup ---

data class AccessScoringPayload(
    val applicantId: String,
    val requestedQuantity: BigDecimal,
    val termMonths: Int,
    val currency: String,
    val monthlyScore: BigDecimal,
    val employmentType: ApplicantCategory,
    val age: Int,
) : PetichPayload()

enum class ApplicantCategory { FULL_TIME, PART_TIME, SELF_EMPLOYED, UNEMPLOYED }

data class AccessScoringEnrichedPayload(
    val accessScore: Int = 0,
    val historyGrade: String? = null,
    val dtiRatio: BigDecimal = BigDecimal.ZERO,
    val interestRate: BigDecimal = BigDecimal.ZERO,
    val monthlyCost: BigDecimal = BigDecimal.ZERO,
    val approvedQuantity: BigDecimal = BigDecimal.ZERO,
    val confirmCodeCode: String? = null,
    val confirmCodeAttempts: Int = 0,
    val reservationId: String? = null,
) : EnrichedPayload() {
    override fun merge(other: EnrichedPayload): EnrichedPayload =
        if (other is AccessScoringEnrichedPayload) {
            copy(
                accessScore = other.accessScore.takeIf { it > 0 } ?: accessScore,
                historyGrade = other.historyGrade ?: historyGrade,
                dtiRatio = other.dtiRatio.takeIf { it > BigDecimal.ZERO } ?: dtiRatio,
                interestRate = other.interestRate.takeIf { it > BigDecimal.ZERO } ?: interestRate,
                monthlyCost = other.monthlyCost.takeIf { it > BigDecimal.ZERO } ?: monthlyCost,
                approvedQuantity = other.approvedQuantity.takeIf { it > BigDecimal.ZERO } ?: approvedQuantity,
                confirmCodeCode = other.confirmCodeCode ?: confirmCodeCode,
                confirmCodeAttempts = other.confirmCodeAttempts.takeIf { it > 0 } ?: confirmCodeAttempts,
                reservationId = other.reservationId ?: reservationId,
            )
        } else {
            this
        }
}

// --- Fakes ---

class FakeReputationService {
    val applicantScores = mutableMapOf<String, Int>()
    val applicantGrades = mutableMapOf<String, String>()

    fun getAccessScore(applicantId: String): Int = applicantScores.getOrDefault(applicantId, 0)

    fun getAccessGrade(applicantId: String): String = applicantGrades.getOrDefault(applicantId, "C")
}

class FakeDenyListService {
    val denyListed = mutableSetOf<String>()

    fun isDenyListed(applicantId: String): Boolean = applicantId in denyListed
}

class FakeGrantService {
    val quotas = mutableMapOf<String, BigDecimal>()
    val reservations = mutableMapOf<String, Pair<String, BigDecimal>>()
    val activations = mutableMapOf<String, BigDecimal>()
    val operationLog = mutableListOf<String>()

    fun reserveQuota(
        applicantId: String,
        quantity: BigDecimal,
        reservationId: String,
    ) {
        quotas[applicantId] = (quotas[applicantId] ?: BigDecimal.ZERO) + quantity
        reservations[reservationId] = applicantId to quantity
        operationLog.add("RESERVE_LIMIT: $applicantId $quantity $reservationId")
    }

    fun cancelReservation(reservationId: String) {
        val reservation = reservations.remove(reservationId) ?: return
        val (applicantId, quantity) = reservation
        quotas[applicantId] = (quotas[applicantId] ?: BigDecimal.ZERO) - quantity
        operationLog.add("CANCEL_LIMIT: $reservationId")
    }

    fun disburse(
        applicantId: String,
        quantity: BigDecimal,
    ) {
        activations[applicantId] = (activations[applicantId] ?: BigDecimal.ZERO) + quantity
        operationLog.add("DISBURSE: $applicantId $quantity")
    }

    fun reverseActivation(
        applicantId: String,
        quantity: BigDecimal,
    ) {
        activations[applicantId] = (activations[applicantId] ?: BigDecimal.ZERO) - quantity
        operationLog.add("REVERSE_DISBURSE: $applicantId $quantity")
    }
}

// --- Interceptors ---

abstract class AccessScoringInterceptor : PetichInterceptor<AccessScoringPayload> {
    override fun supports(payload: PetichPayload) = payload is AccessScoringPayload

    override suspend fun compensate(
        petich: Petich,
        payload: AccessScoringPayload,
    ) {}
}

class HistoryLookupInterceptor(
    private val bureauService: FakeReputationService,
) : AccessScoringInterceptor() {
    override val phase = PetichPhase.ENRICHMENT
    override val priority = 10

    override suspend fun intercept(
        petich: Petich,
        payload: AccessScoringPayload,
    ): InterceptorResult {
        val score = bureauService.getAccessScore(payload.applicantId)
        val grade = bureauService.getAccessGrade(payload.applicantId)
        return InterceptorResult.Proceed(
            AccessScoringEnrichedPayload(accessScore = score, historyGrade = grade),
        )
    }
}

class ScoringCalculationInterceptor : AccessScoringInterceptor() {
    override val phase = PetichPhase.ENRICHMENT
    override val priority = 5

    override suspend fun intercept(
        petich: Petich,
        payload: AccessScoringPayload,
    ): InterceptorResult {
        val enriched = petich.enrichedPayload as AccessScoringEnrichedPayload
        val baseRate =
            when {
                enriched.accessScore >= 750 -> BigDecimal("12.5")
                enriched.accessScore >= 600 -> BigDecimal("18.9")
                enriched.accessScore >= 400 -> BigDecimal("24.5")
                else -> BigDecimal("35.0")
            }
        val employmentMultiplier =
            when (payload.employmentType) {
                ApplicantCategory.FULL_TIME -> BigDecimal("1.0")
                ApplicantCategory.PART_TIME -> BigDecimal("1.15")
                ApplicantCategory.SELF_EMPLOYED -> BigDecimal("1.25")
                ApplicantCategory.UNEMPLOYED -> BigDecimal("2.0")
            }
        val interestRate = baseRate.multiply(employmentMultiplier).setScale(2, RoundingMode.HALF_UP)
        val monthlyRate = interestRate.divide(BigDecimal("1200"), 10, RoundingMode.HALF_UP)
        val onePlusR = BigDecimal.ONE + monthlyRate
        val powN = onePlusR.pow(payload.termMonths)
        val monthlyCost =
            payload.requestedQuantity
                .multiply(monthlyRate)
                .multiply(powN)
                .divide(powN - BigDecimal.ONE, 2, RoundingMode.HALF_UP)

        val dtiRatio = monthlyCost.divide(payload.monthlyScore, 4, RoundingMode.HALF_UP)

        return InterceptorResult.Proceed(
            AccessScoringEnrichedPayload(
                interestRate = interestRate,
                monthlyCost = monthlyCost,
                approvedQuantity = payload.requestedQuantity,
                dtiRatio = dtiRatio,
            ),
        )
    }
}

class EligibilityCheckInterceptor : AccessScoringInterceptor() {
    override val phase = PetichPhase.VALIDATION
    override val priority = 10

    override suspend fun intercept(
        petich: Petich,
        payload: AccessScoringPayload,
    ): InterceptorResult {
        if (payload.age < 21) return InterceptorResult.Reject("Minimum age is 21")
        if (payload.age > 65) return InterceptorResult.Reject("Maximum age is 65")
        return InterceptorResult.Proceed()
    }
}

class DenyListCheckInterceptor(
    private val denyListService: FakeDenyListService,
) : AccessScoringInterceptor() {
    override val phase = PetichPhase.VALIDATION
    override val priority = 5

    override suspend fun intercept(
        petich: Petich,
        payload: AccessScoringPayload,
    ): InterceptorResult {
        if (denyListService.isDenyListed(payload.applicantId)) {
            return InterceptorResult.Compensate("Applicant is denyListed")
        }
        return InterceptorResult.Proceed()
    }
}

class ScoreThresholdInterceptor : AccessScoringInterceptor() {
    override val phase = PetichPhase.VALIDATION
    override val priority = 3

    override suspend fun intercept(
        petich: Petich,
        payload: AccessScoringPayload,
    ): InterceptorResult {
        val enriched = petich.enrichedPayload as AccessScoringEnrichedPayload
        if (enriched.accessScore < 300) return InterceptorResult.Reject("Access score too low")
        if (enriched.dtiRatio > BigDecimal("0.50")) return InterceptorResult.Reject("Debt-to-score ratio too high")
        return InterceptorResult.Proceed()
    }
}

class AccessConfirmCodeInterceptor(
    private val notifierService: FakeNotifierService,
) : AccessScoringInterceptor() {
    override val phase = PetichPhase.AUTHORIZATION
    override val priority = 10

    override suspend fun intercept(
        petich: Petich,
        payload: AccessScoringPayload,
    ): InterceptorResult {
        val code = (100000..999999).random().toString()
        notifierService.send(code)
        return InterceptorResult.Suspend("SMS_CONFIRM_CODE", AccessScoringEnrichedPayload(confirmCodeCode = code))
    }
}

class AccessApprovalInterceptor : AccessScoringInterceptor() {
    override val phase = PetichPhase.AUTHORIZATION
    override val priority = 0

    override suspend fun intercept(
        petich: Petich,
        payload: AccessScoringPayload,
    ): InterceptorResult {
        val enriched = petich.enrichedPayload as AccessScoringEnrichedPayload
        if (enriched.confirmCodeCode == null) return InterceptorResult.Reject("No CONFIRM_CODE issued")
        if (enriched.confirmCodeAttempts >= 3) return InterceptorResult.Reject("Too many CONFIRM_CODE attempts")
        val providedCode = (petich.resumePayload as? ConfirmResumePayload)?.code
        if (providedCode == enriched.confirmCodeCode) return InterceptorResult.Proceed()

        return InterceptorResult.Resuspend(
            "SMS_CONFIRM_CODE",
            AccessScoringEnrichedPayload(
                confirmCodeAttempts =
                    enriched.confirmCodeAttempts + 1,
            ),
        )
    }
}

class QuotaReservationInterceptor(
    private val grantService: FakeGrantService,
) : AccessScoringInterceptor() {
    override val phase = PetichPhase.EXECUTION
    override val priority = 10

    override suspend fun intercept(
        petich: Petich,
        payload: AccessScoringPayload,
    ): InterceptorResult {
        val enriched = petich.enrichedPayload as AccessScoringEnrichedPayload
        val reservationId = UUID.randomUUID().toString()
        grantService.reserveQuota(payload.applicantId, enriched.approvedQuantity, reservationId)
        return InterceptorResult.Proceed(AccessScoringEnrichedPayload(reservationId = reservationId))
    }

    override suspend fun compensate(
        petich: Petich,
        payload: AccessScoringPayload,
    ) {
        val enriched = petich.enrichedPayload as AccessScoringEnrichedPayload
        if (enriched.reservationId != null) {
            grantService.cancelReservation(enriched.reservationId)
        }
    }
}

class ActivationInterceptor(
    private val grantService: FakeGrantService,
    private val shouldFail: Boolean,
) : AccessScoringInterceptor() {
    override val phase = PetichPhase.EXECUTION
    override val priority = 5

    override suspend fun intercept(
        petich: Petich,
        payload: AccessScoringPayload,
    ): InterceptorResult {
        if (shouldFail) throw RuntimeException("Activation service unavailable")
        val enriched = petich.enrichedPayload as AccessScoringEnrichedPayload
        grantService.disburse(payload.applicantId, enriched.approvedQuantity)
        return InterceptorResult.Proceed()
    }

    override suspend fun compensate(
        petich: Petich,
        payload: AccessScoringPayload,
    ) {
        val enriched = petich.enrichedPayload as AccessScoringEnrichedPayload
        grantService.reverseActivation(payload.applicantId, enriched.approvedQuantity)
    }
}

class AccessNotificationInterceptor(
    private val notificationLog: MutableList<String>,
) : AccessScoringInterceptor() {
    override val phase = PetichPhase.POST_PROCESSING
    override val priority = 10

    override suspend fun intercept(
        petich: Petich,
        payload: AccessScoringPayload,
    ): InterceptorResult {
        val enriched = petich.enrichedPayload as AccessScoringEnrichedPayload
        notificationLog.add(
            "APPROVED: ${payload.applicantId} ${enriched.approvedQuantity} at ${enriched.interestRate}%",
        )
        notificationLog.add("MONTHLY_COST: ${payload.applicantId} ${enriched.monthlyCost}")
        return InterceptorResult.Proceed()
    }
}

// --- Tests ---

class AccessScoringPetichEngineTest {
    private fun createEngine(
        bureauService: FakeReputationService,
        denyListService: FakeDenyListService,
        grantService: FakeGrantService,
        notifierService: FakeNotifierService,
        notificationLog: MutableList<String>,
        failActivation: Boolean = false,
    ): Pair<PetichEngine, FakePetichRepository> {
        val repo = FakePetichRepository()
        val interceptors =
            listOf(
                HistoryLookupInterceptor(bureauService),
                ScoringCalculationInterceptor(),
                EligibilityCheckInterceptor(),
                DenyListCheckInterceptor(denyListService),
                ScoreThresholdInterceptor(),
                AccessConfirmCodeInterceptor(notifierService),
                AccessApprovalInterceptor(),
                QuotaReservationInterceptor(grantService),
                ActivationInterceptor(grantService, failActivation),
                AccessNotificationInterceptor(notificationLog),
            )
        return PetichEngine(interceptors, repo) to repo
    }

    private fun defaultPayload(
        applicantId: String = "applicant1",
        quantity: BigDecimal = BigDecimal("500000"),
        termMonths: Int = 24,
        monthlyScore: BigDecimal = BigDecimal("150000"),
        employmentType: ApplicantCategory = ApplicantCategory.FULL_TIME,
        age: Int = 35,
    ) = AccessScoringPayload(applicantId, quantity, termMonths, "RUB", monthlyScore, employmentType, age)

    private fun defaultPetich(
        id: String,
        payload: AccessScoringPayload = defaultPayload(),
    ) = Petich(
        id = id,
        type = "access_scoring",
        status = PetichStatus.PROCESSING,
        payload = payload,
        enrichedPayload = AccessScoringEnrichedPayload(),
    )

    @Test
    fun testHappyPathAccessApproval() =
        runBlocking {
            val bureauService = FakeReputationService()
            bureauService.applicantScores["applicant1"] = 750
            bureauService.applicantGrades["applicant1"] = "A"
            val denyListService = FakeDenyListService()
            val grantService = FakeGrantService()
            val notifierService = FakeNotifierService()
            val notificationLog = mutableListOf<String>()

            val (engine, _) =
                createEngine(
                    bureauService,
                    denyListService,
                    grantService,
                    notifierService,
                    notificationLog,
                )

            val petich = defaultPetich("cs-happy")

            val result1 = engine.process(petich)
            assertTrue(result1 is PetichResult.ActionRequired, "Expected ActionRequired, but got $result1")
            assertEquals("SMS_CONFIRM_CODE", result1.actionType)

            val result2 =
                engine.process(
                    result1.petich.copy(resumePayload = ConfirmResumePayload(notifierService.lastSentCode!!)),
                )
            assertTrue(result2 is PetichResult.Success, "Expected Success, but got $result2")
            assertEquals(PetichStatus.COMPLETED, result2.petich.status)

            val enriched = result2.petich.enrichedPayload as AccessScoringEnrichedPayload
            assertEquals(750, enriched.accessScore)
            assertEquals("A", enriched.historyGrade)
            assertEquals(BigDecimal("12.50"), enriched.interestRate)
            assertTrue(enriched.monthlyCost > BigDecimal.ZERO, "Monthly cost should be calculated")
            assertEquals(BigDecimal("500000"), enriched.approvedQuantity)

            assertEquals(BigDecimal("500000"), grantService.activations["applicant1"])
            assertTrue(grantService.operationLog.any { it.contains("RESERVE_LIMIT") })
            assertTrue(grantService.operationLog.any { it.contains("DISBURSE") })
            assertTrue(notificationLog.any { it.contains("APPROVED: applicant1") })
            assertTrue(notificationLog.any { it.contains("MONTHLY_COST: applicant1") })
        }

    @Test
    fun testDenyListBlockTriggersCompensation() =
        runBlocking {
            val bureauService = FakeReputationService()
            bureauService.applicantScores["applicant1"] = 750
            bureauService.applicantGrades["applicant1"] = "A"
            val denyListService = FakeDenyListService()
            denyListService.denyListed.add("applicant1")
            val grantService = FakeGrantService()
            val notifierService = FakeNotifierService()
            val notificationLog = mutableListOf<String>()

            val (engine, _) =
                createEngine(
                    bureauService,
                    denyListService,
                    grantService,
                    notifierService,
                    notificationLog,
                )

            val petich = defaultPetich("cs-denyList")

            val result = engine.process(petich)
            assertTrue(result is PetichResult.Error, "Expected Error, got $result")
            assertEquals("Applicant is denyListed", result.reason)
        }

    @Test
    fun testActivationFailureTriggersCompensation() =
        runBlocking {
            val bureauService = FakeReputationService()
            bureauService.applicantScores["applicant1"] = 750
            bureauService.applicantGrades["applicant1"] = "A"
            val denyListService = FakeDenyListService()
            val grantService = FakeGrantService()
            val notifierService = FakeNotifierService()
            val notificationLog = mutableListOf<String>()

            val (engine, _) =
                createEngine(
                    bureauService,
                    denyListService,
                    grantService,
                    notifierService,
                    notificationLog,
                    failActivation = true,
                )

            val petich = defaultPetich("cs-disburse-fail")

            val result1 = engine.process(petich)
            assertTrue(result1 is PetichResult.ActionRequired, "Expected ActionRequired, got $result1")

            val result2 =
                engine.process(
                    result1.petich.copy(resumePayload = ConfirmResumePayload(notifierService.lastSentCode!!)),
                )
            assertTrue(result2 is PetichResult.SystemFailure, "Expected SystemFailure, got $result2")

            assertTrue(grantService.reservations.isEmpty(), "Access limit reservation should be cancelled")
            assertTrue(
                grantService.operationLog.any { it.contains("CANCEL_LIMIT") },
                "Log should contain CANCEL_LIMIT: ${grantService.operationLog}",
            )
        }

    @Test
    fun testAccessScoreTooLow() =
        runBlocking {
            val bureauService = FakeReputationService()
            bureauService.applicantScores["applicant1"] = 200
            bureauService.applicantGrades["applicant1"] = "D"
            val denyListService = FakeDenyListService()
            val grantService = FakeGrantService()
            val notifierService = FakeNotifierService()
            val notificationLog = mutableListOf<String>()

            val (engine, _) =
                createEngine(
                    bureauService,
                    denyListService,
                    grantService,
                    notifierService,
                    notificationLog,
                )

            val petich = defaultPetich("cs-low-score")

            val result = engine.process(petich)
            assertTrue(result is PetichResult.Error, "Expected Reject, got $result")
            assertEquals("Access score too low", result.reason)
        }

    @Test
    fun testDebtToScoreRatioTooHigh() =
        runBlocking {
            val bureauService = FakeReputationService()
            bureauService.applicantScores["applicant1"] = 600
            bureauService.applicantGrades["applicant1"] = "B"
            val denyListService = FakeDenyListService()
            val grantService = FakeGrantService()
            val notifierService = FakeNotifierService()
            val notificationLog = mutableListOf<String>()

            val (engine, _) =
                createEngine(
                    bureauService,
                    denyListService,
                    grantService,
                    notifierService,
                    notificationLog,
                )

            val payload =
                defaultPayload(
                    quantity = BigDecimal("2000000"),
                    termMonths = 12,
                    monthlyScore = BigDecimal("80000"),
                )
            val petich = defaultPetich("cs-high-dti", payload)

            val result = engine.process(petich)
            assertTrue(result is PetichResult.Error, "Expected Reject, got $result")
            assertEquals("Debt-to-score ratio too high", result.reason)
        }

    @Test
    fun testAgeTooYoung() =
        runBlocking {
            val bureauService = FakeReputationService()
            bureauService.applicantScores["applicant1"] = 750
            bureauService.applicantGrades["applicant1"] = "A"
            val denyListService = FakeDenyListService()
            val grantService = FakeGrantService()
            val notifierService = FakeNotifierService()
            val notificationLog = mutableListOf<String>()

            val (engine, _) =
                createEngine(
                    bureauService,
                    denyListService,
                    grantService,
                    notifierService,
                    notificationLog,
                )

            val payload = defaultPayload(age = 18)
            val petich = defaultPetich("cs-young", payload)

            val result = engine.process(petich)
            assertTrue(result is PetichResult.Error, "Expected Reject, got $result")
            assertEquals("Minimum age is 21", result.reason)
        }

    @Test
    fun testAgeTooOld() =
        runBlocking {
            val bureauService = FakeReputationService()
            bureauService.applicantScores["applicant1"] = 750
            bureauService.applicantGrades["applicant1"] = "A"
            val denyListService = FakeDenyListService()
            val grantService = FakeGrantService()
            val notifierService = FakeNotifierService()
            val notificationLog = mutableListOf<String>()

            val (engine, _) =
                createEngine(
                    bureauService,
                    denyListService,
                    grantService,
                    notifierService,
                    notificationLog,
                )

            val payload = defaultPayload(age = 70)
            val petich = defaultPetich("cs-old", payload)

            val result = engine.process(petich)
            assertTrue(result is PetichResult.Error, "Expected Reject, got $result")
            assertEquals("Maximum age is 65", result.reason)
        }

    @Test
    fun testWrongConfirmCodeThenCorrectConfirmCode() =
        runBlocking {
            val bureauService = FakeReputationService()
            bureauService.applicantScores["applicant1"] = 750
            bureauService.applicantGrades["applicant1"] = "A"
            val denyListService = FakeDenyListService()
            val grantService = FakeGrantService()
            val notifierService = FakeNotifierService()
            val notificationLog = mutableListOf<String>()

            val (engine, _) =
                createEngine(
                    bureauService,
                    denyListService,
                    grantService,
                    notifierService,
                    notificationLog,
                )

            val petich = defaultPetich("cs-confirmCode-retry")

            val r1 = engine.process(petich)
            assertTrue(r1 is PetichResult.ActionRequired, "Expected Suspend, got $r1")
            assertEquals("SMS_CONFIRM_CODE", r1.actionType)

            val r2 = engine.process(r1.petich.copy(resumePayload = ConfirmResumePayload("wrong_code")))
            assertTrue(r2 is PetichResult.ActionRequired, "Expected Resuspend, got $r2")

            val r3 =
                engine.process(
                    r2.petich.copy(resumePayload = ConfirmResumePayload(notifierService.lastSentCode!!)),
                )
            assertTrue(r3 is PetichResult.Success, "Expected Success, got $r3")
            assertEquals(PetichStatus.COMPLETED, r3.petich.status)
        }

    @Test
    fun testMaxConfirmCodeAttemptsExceeded() =
        runBlocking {
            val bureauService = FakeReputationService()
            bureauService.applicantScores["applicant1"] = 750
            bureauService.applicantGrades["applicant1"] = "A"
            val denyListService = FakeDenyListService()
            val grantService = FakeGrantService()
            val notifierService = FakeNotifierService()
            val notificationLog = mutableListOf<String>()

            val (engine, _) =
                createEngine(
                    bureauService,
                    denyListService,
                    grantService,
                    notifierService,
                    notificationLog,
                )

            val petich = defaultPetich("cs-confirmCode-max")

            val r1 = engine.process(petich)
            assertTrue(r1 is PetichResult.ActionRequired)
            var current = r1.petich

            for (i in 1..3) {
                val r = engine.process(current.copy(resumePayload = ConfirmResumePayload("wrong_code")))
                assertTrue(r is PetichResult.ActionRequired, "Attempt $i: expected Resuspend, got $r")
                current = r.petich
            }

            val rFinal = engine.process(current.copy(resumePayload = ConfirmResumePayload("wrong_code")))
            assertTrue(rFinal is PetichResult.Error, "Expected Reject after max attempts, got $rFinal")
            assertEquals("Too many CONFIRM_CODE attempts", rFinal.reason)
        }

    @Test
    fun testCrossPhaseCompensationRollsBackAllPhases() =
        runBlocking {
            val compensationLog = mutableListOf<String>()

            class TrackedEnrichmentInterceptor : AccessScoringInterceptor() {
                override val phase = PetichPhase.ENRICHMENT
                override val priority = 1

                override suspend fun intercept(
                    petich: Petich,
                    payload: AccessScoringPayload,
                ): InterceptorResult {
                    compensationLog.add("ENRICHMENT_EXECUTED")
                    return InterceptorResult.Proceed()
                }

                override suspend fun compensate(
                    petich: Petich,
                    payload: AccessScoringPayload,
                ) {
                    compensationLog.add("ENRICHMENT_COMPENSATED")
                }
            }

            class TrackedValidationInterceptor : AccessScoringInterceptor() {
                override val phase = PetichPhase.VALIDATION
                override val priority = 10

                override suspend fun intercept(
                    petich: Petich,
                    payload: AccessScoringPayload,
                ): InterceptorResult {
                    compensationLog.add("VALIDATION_EXECUTED")
                    return InterceptorResult.Proceed()
                }

                override suspend fun compensate(
                    petich: Petich,
                    payload: AccessScoringPayload,
                ) {
                    compensationLog.add("VALIDATION_COMPENSATED")
                }
            }

            class FailingExecutionInterceptor : AccessScoringInterceptor() {
                override val phase = PetichPhase.EXECUTION
                override val priority = 10

                override suspend fun intercept(
                    petich: Petich,
                    payload: AccessScoringPayload,
                ): InterceptorResult = throw RuntimeException("Execution failure")
            }

            val bureauService = FakeReputationService()
            bureauService.applicantScores["applicant1"] = 750
            bureauService.applicantGrades["applicant1"] = "A"
            val repo = FakePetichRepository()
            val interceptors =
                listOf(
                    HistoryLookupInterceptor(bureauService),
                    ScoringCalculationInterceptor(),
                    TrackedEnrichmentInterceptor(),
                    TrackedValidationInterceptor(),
                    FailingExecutionInterceptor(),
                )
            val engine = PetichEngine(interceptors, repo)

            val petich = defaultPetich("cs-cross-phase")

            val result = engine.process(petich)
            assertTrue(result is PetichResult.SystemFailure, "Expected SystemFailure, got $result")

            assertTrue(compensationLog.contains("ENRICHMENT_EXECUTED"), "Enrichment should have executed")
            assertTrue(compensationLog.contains("VALIDATION_EXECUTED"), "Validation should have executed")
            assertTrue(
                compensationLog.contains("ENRICHMENT_COMPENSATED"),
                "Cross-phase: ENRICHMENT should be compensated on EXECUTION failure. Log: $compensationLog",
            )
            assertTrue(
                compensationLog.contains("VALIDATION_COMPENSATED"),
                "Cross-phase: VALIDATION should be compensated on EXECUTION failure. Log: $compensationLog",
            )
        }

    @Test
    fun testReprocessCompletedPetichIsIdempotent() =
        runBlocking {
            val bureauService = FakeReputationService()
            bureauService.applicantScores["applicant1"] = 750
            bureauService.applicantGrades["applicant1"] = "A"
            val denyListService = FakeDenyListService()
            val grantService = FakeGrantService()
            val notifierService = FakeNotifierService()
            val notificationLog = mutableListOf<String>()

            val (engine, _) =
                createEngine(
                    bureauService,
                    denyListService,
                    grantService,
                    notifierService,
                    notificationLog,
                )

            val petich = defaultPetich("cs-idempotent")

            val r1 = engine.process(petich)
            val r2 =
                engine.process(
                    (r1 as PetichResult.ActionRequired).petich.copy(
                        resumePayload = ConfirmResumePayload(notifierService.lastSentCode!!),
                    ),
                )
            assertTrue(r2 is PetichResult.Success)

            val disbursedAfterFirst = grantService.activations["applicant1"]

            val r3 = engine.process(r2.petich)
            assertTrue(r3 is PetichResult.Success, "Reprocess should return Success, got $r3")
            assertEquals(
                disbursedAfterFirst,
                grantService.activations["applicant1"],
                "Activation should not change on reprocess",
            )
        }

    @Test
    fun testSelfEmployedHigherInterestRate() =
        runBlocking {
            val bureauService = FakeReputationService()
            bureauService.applicantScores["applicant1"] = 750
            bureauService.applicantGrades["applicant1"] = "A"
            val denyListService = FakeDenyListService()
            val grantService = FakeGrantService()
            val notifierService = FakeNotifierService()
            val notificationLog = mutableListOf<String>()

            val (engine, _) =
                createEngine(
                    bureauService,
                    denyListService,
                    grantService,
                    notifierService,
                    notificationLog,
                )

            val payload = defaultPayload(employmentType = ApplicantCategory.SELF_EMPLOYED)
            val petich = defaultPetich("cs-self-employed", payload)

            val r1 = engine.process(petich)
            val r2 =
                engine.process(
                    (r1 as PetichResult.ActionRequired).petich.copy(
                        resumePayload = ConfirmResumePayload(notifierService.lastSentCode!!),
                    ),
                )
            assertTrue(r2 is PetichResult.Success, "Expected Success, got $r2")

            val enriched = r2.petich.enrichedPayload as AccessScoringEnrichedPayload
            assertEquals(BigDecimal("15.63"), enriched.interestRate)
        }
}
