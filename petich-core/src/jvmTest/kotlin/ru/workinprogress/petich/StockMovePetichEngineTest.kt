package ru.workinprogress.petich

import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// --- Domain Setup ---

data class StockMovePayload(
    val fromWarehouseId: String,
    val toWarehouseId: String,
    val amount: BigDecimal,
    val sku: String,
) : PetichPayload()

data class StockMoveEnrichedPayload(
    val overhead: BigDecimal = BigDecimal.ZERO,
    val finalAmount: BigDecimal = BigDecimal.ZERO,
    val reservationId: String? = null,
    val confirmCode: String? = null,
    val confirmAttempts: Int = 0,
) : EnrichedPayload() {
    override fun merge(other: EnrichedPayload): EnrichedPayload =
        if (other is StockMoveEnrichedPayload) {
            copy(
                overhead = other.overhead.takeIf { it > BigDecimal.ZERO } ?: overhead,
                finalAmount = other.finalAmount.takeIf { it > BigDecimal.ZERO } ?: finalAmount,
                reservationId = other.reservationId ?: reservationId,
                confirmCode = other.confirmCode ?: confirmCode,
                confirmAttempts = other.confirmAttempts.takeIf { it > 0 } ?: confirmAttempts,
            )
        } else {
            this
        }
}

// --- Fakes ---

class FakeInventoryService {
    val quantities = mutableMapOf<String, BigDecimal>()
    val reservations = mutableMapOf<String, Pair<String, BigDecimal>>()
    val operationLog = mutableListOf<String>()

    fun reserve(
        warehouseId: String,
        amount: BigDecimal,
        reservationId: String,
    ) {
        quantities[warehouseId] = quantities.getOrDefault(warehouseId, BigDecimal.ZERO) - amount
        reservations[reservationId] = warehouseId to amount
        operationLog.add("RESERVE: $warehouseId $amount $reservationId")
    }

    fun cancelReservation(reservationId: String) {
        val reservation = reservations.remove(reservationId) ?: return
        val (warehouseId, amount) = reservation
        quantities[warehouseId] = quantities.getOrDefault(warehouseId, BigDecimal.ZERO) + amount
        operationLog.add("CANCEL_RESERVATION: $reservationId")
    }

    fun withdraw(
        warehouseId: String,
        amount: BigDecimal,
        reservationId: String,
    ) {
        reservations.remove(reservationId)
        operationLog.add("WITHDRAW: $warehouseId $amount")
    }

    fun deposit(
        warehouseId: String,
        amount: BigDecimal,
    ) {
        quantities[warehouseId] = quantities.getOrDefault(warehouseId, BigDecimal.ZERO) + amount
        operationLog.add("DEPOSIT: $warehouseId $amount")
    }
}

class FakeNotifierService {
    var lastSentCode: String? = null

    fun send(code: String) {
        lastSentCode = code
    }
}

class FakePetichRepository : PetichRepository {
    private val petiches = ConcurrentHashMap<String, Petich>()

    override suspend fun findById(id: String): Petich? = petiches[id]

    override suspend fun saveOrGet(petich: Petich): Petich = petiches.putIfAbsent(petich.id, petich) ?: petich

    override suspend fun update(petich: Petich): Boolean {
        val current = petiches[petich.id] ?: return false
        if (current.version != petich.version - 1) return false
        petiches[petich.id] = petich
        return true
    }
}

// --- Interceptors ---

abstract class MoveInterceptor : PetichInterceptor<StockMovePayload> {
    override fun supports(payload: PetichPayload) = payload is StockMovePayload

    override suspend fun compensate(
        petich: Petich,
        payload: StockMovePayload,
    ) {
    }
}

class OverheadInterceptor : MoveInterceptor() {
    override val phase = PetichPhase.ENRICHMENT
    override val priority = 10

    override suspend fun intercept(
        petich: Petich,
        payload: StockMovePayload,
    ): InterceptorResult {
        val overhead = payload.amount.multiply(BigDecimal("0.01"))
        return InterceptorResult.Proceed(
            StockMoveEnrichedPayload(
                overhead = overhead,
                finalAmount = payload.amount + overhead,
            ),
        )
    }
}

class StockEnrichmentInterceptor(
    private val inventoryService: FakeInventoryService,
) : MoveInterceptor() {
    override val phase = PetichPhase.ENRICHMENT
    override val priority = 5

    override suspend fun intercept(
        petich: Petich,
        payload: StockMovePayload,
    ): InterceptorResult {
        val enriched = petich.enrichedPayload as StockMoveEnrichedPayload
        val quantity = inventoryService.quantities.getOrDefault(payload.fromWarehouseId, BigDecimal.ZERO)
        if (quantity < enriched.finalAmount) return InterceptorResult.Reject("Insufficient stock")
        return InterceptorResult.Proceed()
    }
}

class CapacityCheckInterceptor : MoveInterceptor() {
    override val phase = PetichPhase.VALIDATION
    override val priority = 10

    override suspend fun intercept(
        petich: Petich,
        payload: StockMovePayload,
    ): InterceptorResult {
        if (payload.amount > BigDecimal(100000)) return InterceptorResult.Reject("Move limit exceeded")
        return InterceptorResult.Proceed()
    }
}

class PolicyCheckInterceptor(
    private val shouldBlock: Boolean,
) : MoveInterceptor() {
    override val phase = PetichPhase.VALIDATION
    override val priority = 5

    override suspend fun intercept(
        petich: Petich,
        payload: StockMovePayload,
    ): InterceptorResult {
        if (shouldBlock) return InterceptorResult.Compensate("Fraud detected")
        return InterceptorResult.Proceed()
    }
}

class ConfirmationCodeInterceptor(
    private val notifierService: FakeNotifierService,
) : MoveInterceptor() {
    override val phase = PetichPhase.AUTHORIZATION
    override val priority = 10

    override suspend fun intercept(
        petich: Petich,
        payload: StockMovePayload,
    ): InterceptorResult {
        val code = (100000..999999).random().toString()
        notifierService.send(code)
        return InterceptorResult.Suspend("CONFIRM_CODE", StockMoveEnrichedPayload(confirmCode = code))
    }
}

class ApprovalInterceptor : MoveInterceptor() {
    override val phase = PetichPhase.AUTHORIZATION
    override val priority = 0

    override suspend fun intercept(
        petich: Petich,
        payload: StockMovePayload,
    ): InterceptorResult {
        val enriched = petich.enrichedPayload as StockMoveEnrichedPayload
        if (enriched.confirmCode == null) return InterceptorResult.Reject("No CONFIRM issued")
        if (enriched.confirmAttempts >= 3) return InterceptorResult.Reject("Too many CONFIRM attempts")
        val provided = (petich.resumePayload as? ConfirmResumePayload)?.code
        if (provided == enriched.confirmCode) return InterceptorResult.Proceed()

        return InterceptorResult.Resuspend(
            "CONFIRM_CODE",
            StockMoveEnrichedPayload(
                confirmAttempts =
                    enriched.confirmAttempts + 1,
            ),
        )
    }
}

class StockReservationInterceptor(
    private val inventoryService: FakeInventoryService,
) : MoveInterceptor() {
    override val phase = PetichPhase.EXECUTION
    override val priority = 10

    override suspend fun intercept(
        petich: Petich,
        payload: StockMovePayload,
    ): InterceptorResult {
        val enriched = petich.enrichedPayload as StockMoveEnrichedPayload
        val reservationId = UUID.randomUUID().toString()
        inventoryService.reserve(payload.fromWarehouseId, enriched.finalAmount, reservationId)
        return InterceptorResult.Proceed(StockMoveEnrichedPayload(reservationId = reservationId))
    }

    override suspend fun compensate(
        petich: Petich,
        payload: StockMovePayload,
    ) {
        val enriched = petich.enrichedPayload as StockMoveEnrichedPayload
        if (enriched.reservationId != null) {
            inventoryService.cancelReservation(enriched.reservationId)
        }
    }
}

class WithdrawInterceptor(
    private val inventoryService: FakeInventoryService,
) : MoveInterceptor() {
    override val phase = PetichPhase.EXECUTION
    override val priority = 5

    override suspend fun intercept(
        petich: Petich,
        payload: StockMovePayload,
    ): InterceptorResult {
        // Withdraw just confirms the reservation. Quantity is already reduced in reserve().
        return InterceptorResult.Proceed()
    }

    override suspend fun compensate(
        petich: Petich,
        payload: StockMovePayload,
    ) {
        // No-op, reservation cancellation handled by StockReservationInterceptor
    }
}

class DepositInterceptor(
    private val inventoryService: FakeInventoryService,
    private val shouldFail: Boolean,
) : MoveInterceptor() {
    override val phase = PetichPhase.EXECUTION
    override val priority = 1

    override suspend fun intercept(
        petich: Petich,
        payload: StockMovePayload,
    ): InterceptorResult {
        if (shouldFail) throw RuntimeException("Core hubing unavailable")
        val enriched = petich.enrichedPayload as StockMoveEnrichedPayload
        inventoryService.deposit(payload.toWarehouseId, enriched.finalAmount)
        return InterceptorResult.Proceed()
    }

    override suspend fun compensate(
        petich: Petich,
        payload: StockMovePayload,
    ) {
        val enriched = petich.enrichedPayload as StockMoveEnrichedPayload
        inventoryService.withdraw(payload.toWarehouseId, enriched.finalAmount, enriched.reservationId!!)
    }
}

class NotificationInterceptor(
    private val notificationLog: MutableList<String>,
) : MoveInterceptor() {
    override val phase = PetichPhase.POST_PROCESSING
    override val priority = 10

    override suspend fun intercept(
        petich: Petich,
        payload: StockMovePayload,
    ): InterceptorResult {
        val enriched = petich.enrichedPayload as StockMoveEnrichedPayload
        notificationLog.add("WITHDRAW: ${payload.fromWarehouseId} -${enriched.finalAmount}")
        notificationLog.add("DEPOSIT: ${payload.toWarehouseId} +${enriched.finalAmount}")
        return InterceptorResult.Proceed()
    }
}

// --- Tests ---

class StockMovePetichEngineTest {
    private fun createEngine(
        inventoryService: FakeInventoryService,
        notifierService: FakeNotifierService,
        notificationLog: MutableList<String>,
        shouldBlockFraud: Boolean = false,
        failDeposit: Boolean = false,
    ): Pair<PetichEngine, FakePetichRepository> {
        val repo = FakePetichRepository()
        val interceptors =
            listOf(
                OverheadInterceptor(),
                StockEnrichmentInterceptor(inventoryService),
                CapacityCheckInterceptor(),
                PolicyCheckInterceptor(shouldBlockFraud),
                ConfirmationCodeInterceptor(notifierService),
                ApprovalInterceptor(),
                StockReservationInterceptor(inventoryService),
                WithdrawInterceptor(inventoryService),
                DepositInterceptor(inventoryService, failDeposit),
                NotificationInterceptor(notificationLog),
            )
        return PetichEngine(interceptors, repo) to repo
    }

    @Test
    fun testHappyPathWithSmsConfirmation() =
        runBlocking {
            val inventoryService = FakeInventoryService()
            inventoryService.quantities["from1"] = BigDecimal("200000")
            inventoryService.quantities["to1"] = BigDecimal("0")
            val notifierService = FakeNotifierService()
            val notificationLog = mutableListOf<String>()

            val (engine, repo) =
                createEngine(inventoryService, notifierService, notificationLog)

            val payload = StockMovePayload("from1", "to1", BigDecimal("50000"), "RUB")
            val petich =
                Petich(
                    id = "p1",
                    type = "move",
                    status = PetichStatus.PROCESSING,
                    payload = payload,
                    enrichedPayload = StockMoveEnrichedPayload(),
                )

            // 1. Process -> Expect ActionRequired
            val result1 = engine.process(petich)
            assertTrue(result1 is PetichResult.ActionRequired, "Expected ActionRequired, but got $result1")
            assertEquals("CONFIRM_CODE", result1.actionType)

            // NOTE: the CONFIRM_CODE result carries the petich inside it
            val petichAfterActionRequired = result1.petich

            // 2. Process again -> Expect Success
            val result2 =
                engine.process(
                    petichAfterActionRequired.copy(
                        resumePayload = ConfirmResumePayload(notifierService.lastSentCode!!),
                    ),
                )
            assertTrue(result2 is PetichResult.Success, "Expected Success, but got: $result2")
            assertEquals(PetichStatus.COMPLETED, result2.petich.status)

            // Asserts
            assertEquals(
                BigDecimal("149500.00"),
                inventoryService.quantities["from1"],
                "Quantity should be restored",
            ) // 200000 - 50500 (amount + overhead)
            assertEquals(BigDecimal("50500.00"), inventoryService.quantities["to1"]) // 0 + 50500
            assertTrue(notificationLog.contains("WITHDRAW: from1 -50500.00"), "Notifications: $notificationLog")
            assertTrue(notificationLog.contains("DEPOSIT: to1 +50500.00"), "Notifications: $notificationLog")
        }

    @Test
    fun testFraudBlockTriggersCompensation() =
        runBlocking {
            val inventoryService = FakeInventoryService()
            inventoryService.quantities["from1"] = BigDecimal("200000")
            val notifierService = FakeNotifierService()
            val notificationLog = mutableListOf<String>()

            val (engine, _) = createEngine(inventoryService, notifierService, notificationLog, shouldBlockFraud = true)

            val payload = StockMovePayload("from1", "to1", BigDecimal("50000"), "RUB")
            val petich =
                Petich(
                    id = "p2",
                    type = "move",
                    status = PetichStatus.PROCESSING,
                    payload = payload,
                    enrichedPayload = StockMoveEnrichedPayload(),
                )

            val result = engine.process(petich)
            assertTrue(result is PetichResult.Error)
            assertEquals("Fraud detected", result.reason)
        }

    @Test
    fun testDepositFailureTriggersCompensation() =
        runBlocking {
            val inventoryService = FakeInventoryService()
            inventoryService.quantities["from1"] = BigDecimal("200000.00")
            inventoryService.quantities["to1"] = BigDecimal("0.00")
            val notifierService = FakeNotifierService()
            val notificationLog = mutableListOf<String>()

            // failDeposit = true
            val (engine, repo) =
                createEngine(
                    inventoryService,
                    notifierService,
                    notificationLog,
                    failDeposit = true,
                )

            val payload = StockMovePayload("from1", "to1", BigDecimal("50000"), "RUB")
            val petich =
                Petich(
                    id = "p4",
                    type = "move",
                    status = PetichStatus.PROCESSING,
                    payload = payload,
                    enrichedPayload = StockMoveEnrichedPayload(),
                )

            // 1. Process -> Expect ActionRequired
            val result1 = engine.process(petich)
            assertTrue(result1 is PetichResult.ActionRequired, "Expected ActionRequired, but got $result1")

            // NOTE: the CONFIRM_CODE result carries the petich inside it
            val petichAfterActionRequired = result1.petich

            // 2. Process again -> Fail in Execution phase
            val result2 =
                engine.process(
                    petichAfterActionRequired.copy(
                        resumePayload = ConfirmResumePayload(notifierService.lastSentCode!!),
                    ),
                )

            assertTrue(result2 is PetichResult.SystemFailure, "Expected SystemFailure, but got: $result2")

            // Asserts: Compensation should have happened
            assertEquals(BigDecimal("200000.00"), inventoryService.quantities["from1"], "Quantity should be restored")
            assertTrue(inventoryService.reservations.isEmpty(), "Reservation should be cancelled")
            assertTrue(
                inventoryService.operationLog.any { it.contains("CANCEL_RESERVATION") },
                "Log should contain CANCEL_RESERVATION",
            )
        }

    @Test
    fun testWrongConfirmThenCorrectConfirm() =
        runBlocking {
            val inventoryService = FakeInventoryService()
            inventoryService.quantities["from1"] = BigDecimal("200000")
            inventoryService.quantities["to1"] = BigDecimal("0")
            val notifierService = FakeNotifierService()
            val notificationLog = mutableListOf<String>()

            val (engine, _) =
                createEngine(inventoryService, notifierService, notificationLog)

            val payload = StockMovePayload("from1", "to1", BigDecimal("50000"), "RUB")
            val petich =
                Petich(
                    id = "p-confirm1",
                    type = "move",
                    status = PetichStatus.PROCESSING,
                    payload = payload,
                    enrichedPayload = StockMoveEnrichedPayload(),
                )

            // 1. Process → Suspend (CONFIRM sent)
            val r1 = engine.process(petich)
            assertTrue(r1 is PetichResult.ActionRequired, "Expected Suspend, got $r1")
            assertEquals("CONFIRM_CODE", r1.actionType)

            // 2. Resume with wrong code → Resuspend
            val r2 = engine.process(r1.petich.copy(resumePayload = ConfirmResumePayload("wrong_code")))
            assertTrue(r2 is PetichResult.ActionRequired, "Expected Resuspend, got $r2")

            // 3. Resume with correct code → Success
            val r3 =
                engine.process(
                    r2.petich.copy(
                        resumePayload = ConfirmResumePayload(notifierService.lastSentCode!!),
                    ),
                )
            assertTrue(r3 is PetichResult.Success, "Expected Success, got $r3")
            assertEquals(PetichStatus.COMPLETED, r3.petich.status)
        }

    @Test
    fun testMaxConfirmAttemptsExceeded() =
        runBlocking {
            val inventoryService = FakeInventoryService()
            inventoryService.quantities["from1"] = BigDecimal("200000")
            val notifierService = FakeNotifierService()
            val notificationLog = mutableListOf<String>()

            val (engine, _) = createEngine(inventoryService, notifierService, notificationLog)

            val payload = StockMovePayload("from1", "to1", BigDecimal("50000"), "RUB")
            val petich =
                Petich(
                    id = "p-confirm2",
                    type = "move",
                    status = PetichStatus.PROCESSING,
                    payload = payload,
                    enrichedPayload = StockMoveEnrichedPayload(),
                )

            // 1. Suspend (CONFIRM sent)
            val r1 = engine.process(petich)
            assertTrue(r1 is PetichResult.ActionRequired)
            var current = r1.petich

            // 2-4. Three wrong CONFIRM attempts → Resuspend each time
            for (i in 1..3) {
                val r = engine.process(current.copy(resumePayload = ConfirmResumePayload("wrong_code")))
                assertTrue(r is PetichResult.ActionRequired, "Attempt $i: expected Resuspend, got $r")
                current = r.petich
            }

            // 5. Fourth attempt → Reject (confirmAttempts >= 3)
            val rFinal = engine.process(current.copy(resumePayload = ConfirmResumePayload("wrong_code")))
            assertTrue(rFinal is PetichResult.Error, "Expected Reject after max attempts, got $rFinal")
            assertEquals("Too many CONFIRM attempts", rFinal.reason)
        }

    @Test
    fun testMoveLimitExceeded() =
        runBlocking {
            val inventoryService = FakeInventoryService()
            inventoryService.quantities["from1"] = BigDecimal("500000")
            val notifierService = FakeNotifierService()
            val notificationLog = mutableListOf<String>()

            val (engine, _) = createEngine(inventoryService, notifierService, notificationLog)

            val payload = StockMovePayload("from1", "to1", BigDecimal("150000"), "RUB")
            val petich =
                Petich(
                    id = "p-limit",
                    type = "move",
                    status = PetichStatus.PROCESSING,
                    payload = payload,
                    enrichedPayload = StockMoveEnrichedPayload(),
                )

            val result = engine.process(petich)
            assertTrue(result is PetichResult.Error, "Expected Reject, got $result")
            assertEquals("Move limit exceeded", result.reason)
        }

    @Test
    fun testInsufficientStock() =
        runBlocking {
            val inventoryService = FakeInventoryService()
            inventoryService.quantities["from1"] = BigDecimal("100")
            val notifierService = FakeNotifierService()
            val notificationLog = mutableListOf<String>()

            val (engine, _) = createEngine(inventoryService, notifierService, notificationLog)

            val payload = StockMovePayload("from1", "to1", BigDecimal("50000"), "RUB")
            val petich =
                Petich(
                    id = "p-stock",
                    type = "move",
                    status = PetichStatus.PROCESSING,
                    payload = payload,
                    enrichedPayload = StockMoveEnrichedPayload(),
                )

            val result = engine.process(petich)
            assertTrue(result is PetichResult.Error, "Expected Reject, got $result")
            assertEquals("Insufficient stock", result.reason)
        }

    @Test
    fun testCrossPhaseCompensationRollsBackAllPhases() =
        runBlocking {
            val compensationLog = mutableListOf<String>()

            class TrackedEnrichmentInterceptor : MoveInterceptor() {
                override val phase = PetichPhase.ENRICHMENT
                override val priority = 1

                override suspend fun intercept(
                    petich: Petich,
                    payload: StockMovePayload,
                ): InterceptorResult {
                    compensationLog.add("ENRICHMENT_EXECUTED")
                    return InterceptorResult.Proceed()
                }

                override suspend fun compensate(
                    petich: Petich,
                    payload: StockMovePayload,
                ) {
                    compensationLog.add("ENRICHMENT_COMPENSATED")
                }
            }

            class TrackedValidationInterceptor : MoveInterceptor() {
                override val phase = PetichPhase.VALIDATION
                override val priority = 10

                override suspend fun intercept(
                    petich: Petich,
                    payload: StockMovePayload,
                ): InterceptorResult {
                    compensationLog.add("VALIDATION_EXECUTED")
                    return InterceptorResult.Proceed()
                }

                override suspend fun compensate(
                    petich: Petich,
                    payload: StockMovePayload,
                ) {
                    compensationLog.add("VALIDATION_COMPENSATED")
                }
            }

            class FailingExecutionInterceptor : MoveInterceptor() {
                override val phase = PetichPhase.EXECUTION
                override val priority = 10

                override suspend fun intercept(
                    petich: Petich,
                    payload: StockMovePayload,
                ): InterceptorResult = throw RuntimeException("Execution failure")
            }

            val inventoryService = FakeInventoryService()
            inventoryService.quantities["from1"] = BigDecimal("200000")
            val repo = FakePetichRepository()
            val interceptors =
                listOf(
                    OverheadInterceptor(),
                    StockEnrichmentInterceptor(inventoryService),
                    TrackedEnrichmentInterceptor(),
                    TrackedValidationInterceptor(),
                    FailingExecutionInterceptor(),
                )
            val engine = PetichEngine(interceptors, repo)

            val payload = StockMovePayload("from1", "to1", BigDecimal("50000"), "RUB")
            val petich =
                Petich(
                    id = "p-cross",
                    type = "move",
                    status = PetichStatus.PROCESSING,
                    payload = payload,
                    enrichedPayload = StockMoveEnrichedPayload(),
                )

            val result = engine.process(petich)
            assertTrue(result is PetichResult.SystemFailure, "Expected SystemFailure, got $result")

            assertTrue(
                compensationLog.contains("ENRICHMENT_EXECUTED"),
                "Enrichment should have executed",
            )
            assertTrue(
                compensationLog.contains("VALIDATION_EXECUTED"),
                "Validation should have executed",
            )
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
            val inventoryService = FakeInventoryService()
            inventoryService.quantities["from1"] = BigDecimal("200000")
            inventoryService.quantities["to1"] = BigDecimal("0")
            val notifierService = FakeNotifierService()
            val notificationLog = mutableListOf<String>()

            val (engine, _) =
                createEngine(inventoryService, notifierService, notificationLog)

            val payload = StockMovePayload("from1", "to1", BigDecimal("50000"), "RUB")
            val petich =
                Petich(
                    id = "p-idem",
                    type = "move",
                    status = PetichStatus.PROCESSING,
                    payload = payload,
                    enrichedPayload = StockMoveEnrichedPayload(),
                )

            val r1 = engine.process(petich)
            val r2 =
                engine.process(
                    (r1 as PetichResult.ActionRequired).petich.copy(
                        resumePayload = ConfirmResumePayload(notifierService.lastSentCode!!),
                    ),
                )
            assertTrue(r2 is PetichResult.Success)

            val quantityAfterFirst = inventoryService.quantities["from1"]

            // Reprocess the same completed petich
            val r3 = engine.process(r2.petich)
            assertTrue(r3 is PetichResult.Success, "Reprocess should return Success, got $r3")
            assertEquals(
                quantityAfterFirst,
                inventoryService.quantities["from1"],
                "Quantity should not change on reprocess",
            )
        }
}
