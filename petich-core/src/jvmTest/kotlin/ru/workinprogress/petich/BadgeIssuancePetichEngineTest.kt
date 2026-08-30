package ru.workinprogress.petich

import kotlinx.coroutines.runBlocking
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// --- Domain Setup ---

enum class BadgeMaterial { STANDARD, COATED, REINFORCED, MATTE }

data class BadgeIssuancePayload(
    val holderId: String,
    val badgeDesign: BadgeMaterial,
    val currency: String,
    val deliveryAddress: String,
) : PetichPayload()

data class BadgeIssuanceEnrichedPayload(
    val directoryRecordId: String? = null,
    val pan: String? = null,
    val cvv: String? = null,
    val virtualBadgeId: String? = null,
    val printingOrderId: String? = null,
    val deliveryTrackingId: String? = null,
    val confirmCodeCode: String? = null,
    val confirmCodeAttempts: Int = 0,
    val idempotencyKey: String? = null,
) : EnrichedPayload() {
    override fun merge(other: EnrichedPayload): EnrichedPayload =
        if (other is BadgeIssuanceEnrichedPayload) {
            copy(
                directoryRecordId = other.directoryRecordId ?: directoryRecordId,
                pan = other.pan ?: pan,
                cvv = other.cvv ?: cvv,
                virtualBadgeId = other.virtualBadgeId ?: virtualBadgeId,
                printingOrderId = other.printingOrderId ?: printingOrderId,
                deliveryTrackingId = other.deliveryTrackingId ?: deliveryTrackingId,
                confirmCodeCode = other.confirmCodeCode ?: confirmCodeCode,
                confirmCodeAttempts = other.confirmCodeAttempts.takeIf { it > 0 } ?: confirmCodeAttempts,
                idempotencyKey = other.idempotencyKey ?: idempotencyKey,
            )
        } else {
            this
        }
}

// --- Fakes ---

class FakeDirectoryService {
    val records = ConcurrentHashMap<String, String>()
    val closedRecords = mutableSetOf<String>()
    val operationLog = mutableListOf<String>()

    fun createBadgeRecord(holderId: String): String {
        val recordId = "ACC-${UUID.randomUUID().toString().take(8)}"
        records[recordId] = holderId
        operationLog.add("CREATE_RECORD: $holderId -> $recordId")
        return recordId
    }

    fun closeBadgeRecord(recordId: String) {
        records.remove(recordId)
        closedRecords.add(recordId)
        operationLog.add("CLOSE_RECORD: $recordId")
    }
}

class FakeCredentialService {
    data class VirtualBadge(
        val pan: String,
        val cvv: String,
        val blocked: Boolean = false,
    )

    val issuedBadges = ConcurrentHashMap<String, VirtualBadge>()
    val idempotencyRegistry = ConcurrentHashMap<String, String>()
    val operationLog = mutableListOf<String>()
    var callCount = 0
    var failAfterCreationOnAttempt: Int? = null

    fun issueVirtualBadge(
        recordId: String,
        idempotencyKey: String,
    ): Triple<String, String, String> {
        callCount++

        val existing = idempotencyRegistry[idempotencyKey]
        if (existing != null) {
            val badge = issuedBadges[existing]!!
            operationLog.add("ISSUE_BADGE_IDEMPOTENT_HIT: key=$idempotencyKey -> $existing")
            return Triple(existing, badge.pan, badge.cvv)
        }

        val badgeId = "BADGE-${UUID.randomUUID().toString().take(8)}"
        val pan = (1..16).map { (0..9).random() }.joinToString("")
        val cvv = (100..999).random().toString()
        issuedBadges[badgeId] = VirtualBadge(pan, cvv)
        idempotencyRegistry[idempotencyKey] = badgeId
        operationLog.add("ISSUE_BADGE: $recordId -> $badgeId key=$idempotencyKey")

        if (callCount == failAfterCreationOnAttempt) {
            operationLog.add(
                "ISSUE_BADGE_NETWORK_FAIL: attempt $callCount key=$idempotencyKey (badge $badgeId created server-side)",
            )
            throw RuntimeException("Network timeout after badge creation")
        }

        return Triple(badgeId, pan, cvv)
    }

    fun blockBadge(badgeId: String) {
        issuedBadges.computeIfPresent(badgeId) { _, badge -> badge.copy(blocked = true) }
        operationLog.add("BLOCK_BADGE: $badgeId")
    }
}

class FakePrintingFactory {
    val orders = ConcurrentHashMap<String, String>()
    val cancelledOrders = mutableSetOf<String>()
    val operationLog = mutableListOf<String>()
    var unavailableDesigns = mutableSetOf<BadgeMaterial>()

    fun submitOrder(
        badgeId: String,
        design: BadgeMaterial,
    ): String {
        if (design in unavailableDesigns) {
            operationLog.add("PRINTING_REJECT: design $design unavailable for $badgeId")
            throw RuntimeException("Design $design: plastic unavailable")
        }
        val orderId = "EMB-${UUID.randomUUID().toString().take(8)}"
        orders[orderId] = badgeId
        operationLog.add("PRINTING_ORDER: $badgeId -> $orderId design=$design")
        return orderId
    }

    fun cancelOrder(orderId: String) {
        orders.remove(orderId)
        cancelledOrders.add(orderId)
        operationLog.add("PRINTING_CANCEL: $orderId")
    }
}

open class FakeDeliveryService {
    val deliveries = ConcurrentHashMap<String, String>()
    val cancelledDeliveries = mutableSetOf<String>()
    val operationLog = mutableListOf<String>()

    open fun scheduleDelivery(
        printingOrderId: String,
        address: String,
    ): String {
        val trackingId = "TRK-${UUID.randomUUID().toString().take(8)}"
        deliveries[trackingId] = address
        operationLog.add("SCHEDULE_DELIVERY: $printingOrderId -> $trackingId addr=$address")
        return trackingId
    }

    fun cancelDelivery(trackingId: String) {
        deliveries.remove(trackingId)
        cancelledDeliveries.add(trackingId)
        operationLog.add("CANCEL_DELIVERY: $trackingId")
    }
}

// --- Interceptors ---

abstract class BadgeIssuanceInterceptor : PetichInterceptor<BadgeIssuancePayload> {
    override fun supports(payload: PetichPayload) = payload is BadgeIssuancePayload

    override suspend fun compensate(
        petich: Petich,
        payload: BadgeIssuancePayload,
    ) {
    }
}

// ENRICHMENT: generate an idempotency key for the badge processor
class IdempotencyKeyInterceptor : BadgeIssuanceInterceptor() {
    override val phase = PetichPhase.ENRICHMENT
    override val priority = 10

    override suspend fun intercept(
        petich: Petich,
        payload: BadgeIssuancePayload,
    ): InterceptorResult {
        val enriched = petich.enrichedPayload as BadgeIssuanceEnrichedPayload
        if (enriched.idempotencyKey == null) {
            return InterceptorResult.Proceed(
                BadgeIssuanceEnrichedPayload(
                    idempotencyKey = "IDEM-${payload.holderId}-${payload.badgeDesign}-${payload.currency}",
                ),
            )
        }
        return InterceptorResult.Proceed()
    }
}

// VALIDATION: check that the customer is not blocked
class HolderStatusCheckInterceptor(
    private val blockedHolders: Set<String>,
) : BadgeIssuanceInterceptor() {
    override val phase = PetichPhase.VALIDATION
    override val priority = 10

    override suspend fun intercept(
        petich: Petich,
        payload: BadgeIssuancePayload,
    ): InterceptorResult {
        if (payload.holderId in blockedHolders) {
            return InterceptorResult.Reject("Holder is blocked")
        }
        return InterceptorResult.Proceed()
    }
}

// AUTHORIZATION: SMS CONFIRM_CODE confirmation
class BadgeConfirmCodeInterceptor(
    private val notifierService: FakeNotifierService,
) : BadgeIssuanceInterceptor() {
    override val phase = PetichPhase.AUTHORIZATION
    override val priority = 10

    override suspend fun intercept(
        petich: Petich,
        payload: BadgeIssuancePayload,
    ): InterceptorResult {
        val code = (100000..999999).random().toString()
        notifierService.send(code)
        return InterceptorResult.Suspend("SMS_CONFIRM_CODE", BadgeIssuanceEnrichedPayload(confirmCodeCode = code))
    }
}

class BadgeApprovalInterceptor : BadgeIssuanceInterceptor() {
    override val phase = PetichPhase.AUTHORIZATION
    override val priority = 0

    override suspend fun intercept(
        petich: Petich,
        payload: BadgeIssuancePayload,
    ): InterceptorResult {
        val enriched = petich.enrichedPayload as BadgeIssuanceEnrichedPayload
        if (enriched.confirmCodeCode == null) return InterceptorResult.Reject("No CONFIRM_CODE issued")
        if (enriched.confirmCodeAttempts >= 3) return InterceptorResult.Reject("Too many CONFIRM_CODE attempts")
        val providedCode = (petich.resumePayload as? ConfirmResumePayload)?.code
        if (providedCode == enriched.confirmCodeCode) return InterceptorResult.Proceed()

        return InterceptorResult.Resuspend(
            "SMS_CONFIRM_CODE",
            BadgeIssuanceEnrichedPayload(confirmCodeAttempts = enriched.confirmCodeAttempts + 1),
        )
    }
}

// EXECUTION step 1: open the record in the core facilitying system
class CreateDirectoryRecordInterceptor(
    private val directoryService: FakeDirectoryService,
) : BadgeIssuanceInterceptor() {
    override val phase = PetichPhase.EXECUTION
    override val priority = 40

    override suspend fun intercept(
        petich: Petich,
        payload: BadgeIssuancePayload,
    ): InterceptorResult {
        val recordId = directoryService.createBadgeRecord(payload.holderId)
        return InterceptorResult.Proceed(BadgeIssuanceEnrichedPayload(directoryRecordId = recordId))
    }

    override suspend fun compensate(
        petich: Petich,
        payload: BadgeIssuancePayload,
    ) {
        val enriched = petich.enrichedPayload as BadgeIssuanceEnrichedPayload
        if (enriched.directoryRecordId != null) {
            directoryService.closeBadgeRecord(enriched.directoryRecordId)
        }
    }
}

// EXECUTION step 2: generate SERIAL/PIN in the badge processor (with an idempotency key)
class IssueCredentialsInterceptor(
    private val credentialService: FakeCredentialService,
) : BadgeIssuanceInterceptor() {
    override val phase = PetichPhase.EXECUTION
    override val priority = 30

    override suspend fun intercept(
        petich: Petich,
        payload: BadgeIssuancePayload,
    ): InterceptorResult {
        val enriched = petich.enrichedPayload as BadgeIssuanceEnrichedPayload
        val (badgeId, pan, cvv) =
            credentialService.issueVirtualBadge(
                enriched.directoryRecordId!!,
                enriched.idempotencyKey!!,
            )
        return InterceptorResult.Proceed(
            BadgeIssuanceEnrichedPayload(virtualBadgeId = badgeId, pan = pan, cvv = cvv),
        )
    }

    override suspend fun compensate(
        petich: Petich,
        payload: BadgeIssuancePayload,
    ) {
        val enriched = petich.enrichedPayload as BadgeIssuanceEnrichedPayload
        if (enriched.virtualBadgeId != null) {
            credentialService.blockBadge(enriched.virtualBadgeId)
        }
    }
}

// EXECUTION step 3: request printing of the plastic
class PrintOrderInterceptor(
    private val factory: FakePrintingFactory,
) : BadgeIssuanceInterceptor() {
    override val phase = PetichPhase.EXECUTION
    override val priority = 20

    override suspend fun intercept(
        petich: Petich,
        payload: BadgeIssuancePayload,
    ): InterceptorResult {
        val enriched = petich.enrichedPayload as BadgeIssuanceEnrichedPayload
        val orderId = factory.submitOrder(enriched.virtualBadgeId!!, payload.badgeDesign)
        return InterceptorResult.Proceed(BadgeIssuanceEnrichedPayload(printingOrderId = orderId))
    }

    override suspend fun compensate(
        petich: Petich,
        payload: BadgeIssuancePayload,
    ) {
        val enriched = petich.enrichedPayload as BadgeIssuanceEnrichedPayload
        if (enriched.printingOrderId != null) {
            factory.cancelOrder(enriched.printingOrderId)
        }
    }
}

// EXECUTION step 4: hand over to the delivery service
class DeliveryDispatchInterceptor(
    private val deliveryService: FakeDeliveryService,
) : BadgeIssuanceInterceptor() {
    override val phase = PetichPhase.EXECUTION
    override val priority = 10

    override suspend fun intercept(
        petich: Petich,
        payload: BadgeIssuancePayload,
    ): InterceptorResult {
        val enriched = petich.enrichedPayload as BadgeIssuanceEnrichedPayload
        val trackingId = deliveryService.scheduleDelivery(enriched.printingOrderId!!, payload.deliveryAddress)
        return InterceptorResult.Proceed(BadgeIssuanceEnrichedPayload(deliveryTrackingId = trackingId))
    }

    override suspend fun compensate(
        petich: Petich,
        payload: BadgeIssuancePayload,
    ) {
        val enriched = petich.enrichedPayload as BadgeIssuanceEnrichedPayload
        if (enriched.deliveryTrackingId != null) {
            deliveryService.cancelDelivery(enriched.deliveryTrackingId)
        }
    }
}

// POST_PROCESSING: notify the customer
class BadgeIssuanceNotificationInterceptor(
    private val notificationLog: MutableList<String>,
) : BadgeIssuanceInterceptor() {
    override val phase = PetichPhase.POST_PROCESSING
    override val priority = 10

    override suspend fun intercept(
        petich: Petich,
        payload: BadgeIssuancePayload,
    ): InterceptorResult {
        val enriched = petich.enrichedPayload as BadgeIssuanceEnrichedPayload
        notificationLog.add(
            "BADGE_ISSUED: holder=${payload.holderId} pan=****${enriched.pan?.takeLast(
                4,
            )} tracking=${enriched.deliveryTrackingId}",
        )
        return InterceptorResult.Proceed()
    }
}

// --- Tests ---

class BadgeIssuancePetichEngineTest {
    private fun createEngine(
        directoryService: FakeDirectoryService = FakeDirectoryService(),
        credentialService: FakeCredentialService = FakeCredentialService(),
        factory: FakePrintingFactory = FakePrintingFactory(),
        deliveryService: FakeDeliveryService = FakeDeliveryService(),
        notifierService: FakeNotifierService = FakeNotifierService(),
        notificationLog: MutableList<String> = mutableListOf(),
        blockedHolders: Set<String> = emptySet(),
    ): PetichEngine {
        val repo = FakePetichRepository()
        val interceptors =
            listOf(
                IdempotencyKeyInterceptor(),
                HolderStatusCheckInterceptor(blockedHolders),
                BadgeConfirmCodeInterceptor(notifierService),
                BadgeApprovalInterceptor(),
                CreateDirectoryRecordInterceptor(directoryService),
                IssueCredentialsInterceptor(credentialService),
                PrintOrderInterceptor(factory),
                DeliveryDispatchInterceptor(deliveryService),
                BadgeIssuanceNotificationInterceptor(notificationLog),
            )
        return PetichEngine(interceptors, repo)
    }

    private fun defaultPayload(
        holderId: String = "holder1",
        design: BadgeMaterial = BadgeMaterial.COATED,
        address: String = "1 Example Street, Springfield",
    ) = BadgeIssuancePayload(holderId, design, "RUB", address)

    private fun defaultPetich(
        id: String,
        payload: BadgeIssuancePayload = defaultPayload(),
    ) = Petich(
        id = id,
        type = "badge_issuance",
        status = PetichStatus.PROCESSING,
        payload = payload,
        enrichedPayload = BadgeIssuanceEnrichedPayload(),
    )

    // ==========================================
    // 1. Happy path — the full badge issuance cycle
    // ==========================================

    @Test
    fun testHappyPathBadgeIssuance() =
        runBlocking {
            val directoryService = FakeDirectoryService()
            val credentialService = FakeCredentialService()
            val factory = FakePrintingFactory()
            val deliveryService = FakeDeliveryService()
            val notifierService = FakeNotifierService()
            val notificationLog = mutableListOf<String>()

            val engine =
                createEngine(
                    directoryService,
                    credentialService,
                    factory,
                    deliveryService,
                    notifierService,
                    notificationLog,
                )

            val petich = defaultPetich("badge-happy")

            // 1. Process → SMS CONFIRM_CODE Suspend
            val r1 = engine.process(petich)
            assertTrue(r1 is PetichResult.ActionRequired, "Expected ActionRequired, got $r1")
            assertEquals("SMS_CONFIRM_CODE", r1.actionType)

            // 2. Confirm CONFIRM_CODE -> the whole Execution chain -> Success
            val r2 =
                engine.process(
                    r1.petich.copy(resumePayload = ConfirmResumePayload(notifierService.lastSentCode!!)),
                )
            assertTrue(r2 is PetichResult.Success, "Expected Success, got $r2")
            assertEquals(PetichStatus.COMPLETED, r2.petich.status)

            val enriched = r2.petich.enrichedPayload as BadgeIssuanceEnrichedPayload

            // All four Execution steps ran
            assertTrue(enriched.directoryRecordId != null, "Badge record should be created")
            assertTrue(enriched.pan != null && enriched.pan.length == 16, "SERIAL should be 16 digits")
            assertTrue(enriched.cvv != null && enriched.cvv.length == 3, "PIN should be 3 digits")
            assertTrue(enriched.virtualBadgeId != null, "Virtual badge should be issued")
            assertTrue(enriched.printingOrderId != null, "Printing order should be placed")
            assertTrue(enriched.deliveryTrackingId != null, "Delivery tracking should be assigned")

            // Check the logs of every system
            assertTrue(directoryService.operationLog.any { it.contains("CREATE_RECORD") })
            assertTrue(credentialService.operationLog.any { it.contains("ISSUE_BADGE:") })
            assertTrue(factory.operationLog.any { it.contains("PRINTING_ORDER") })
            assertTrue(deliveryService.operationLog.any { it.contains("SCHEDULE_DELIVERY") })
            assertTrue(notificationLog.any { it.contains("BADGE_ISSUED") })

            // The badge is active, not blocked
            val badge = credentialService.issuedBadges[enriched.virtualBadgeId]
            assertTrue(badge != null && !badge.blocked, "Badge should be active")
        }

    // ==========================================
    // 2. Idempotency — the network fails while the processor is answering, and a retry must not
    //    create a second badge
    // ==========================================

    @Test
    fun testIdempotencyOnProcessingNetworkFailure() =
        runBlocking {
            val directoryService = FakeDirectoryService()
            val credentialService = FakeCredentialService()
            // The network fails AFTER the processor has already created the badge on its side
            credentialService.failAfterCreationOnAttempt = 1
            val factory = FakePrintingFactory()
            val deliveryService = FakeDeliveryService()
            val notifierService = FakeNotifierService()
            val notificationLog = mutableListOf<String>()

            val engine =
                createEngine(
                    directoryService,
                    credentialService,
                    factory,
                    deliveryService,
                    notifierService,
                    notificationLog,
                )

            val petich = defaultPetich("badge-idempotent-1")

            // 1. CONFIRM_CODE
            val r1 = engine.process(petich)
            assertTrue(r1 is PetichResult.ActionRequired)

            // 2. First attempt — the processor created the badge but the network failed on the
            //    response -> SystemFailure
            val r2 =
                engine.process(
                    r1.petich.copy(resumePayload = ConfirmResumePayload(notifierService.lastSentCode!!)),
                )
            assertTrue(r2 is PetichResult.SystemFailure, "Expected SystemFailure on first attempt, got $r2")

            // The badge exists on the processor's side, but the customer does not know that
            assertTrue(
                credentialService.operationLog.any { it.contains("ISSUE_BADGE_NETWORK_FAIL") },
                "Should have logged network failure after badge creation",
            )
            assertEquals(
                1,
                credentialService.issuedBadges.size,
                "Badge was created server-side despite network failure",
            )

            // Compensation closed the record, since the engine does not know a badge was created
            assertTrue(directoryService.operationLog.any { it.contains("CLOSE_RECORD") })

            // A repeat application with a new petich id but the same customer -> the same
            // idempotency key
            credentialService.failAfterCreationOnAttempt = null
            val petich2 = defaultPetich("badge-idempotent-2")

            val r3 = engine.process(petich2)
            assertTrue(r3 is PetichResult.ActionRequired, "Expected CONFIRM_CODE suspend, got $r3")
            val r4 =
                engine.process(
                    r3.petich.copy(resumePayload = ConfirmResumePayload(notifierService.lastSentCode!!)),
                )
            assertTrue(r4 is PetichResult.Success, "Retry should succeed, got $r4")

            // The processor returned the same badge for that idempotency key instead of a duplicate
            assertTrue(
                credentialService.operationLog.any { it.contains("IDEMPOTENT_HIT") },
                "Should have hit idempotent path: ${credentialService.operationLog}",
            )
            assertEquals(
                1,
                credentialService.issuedBadges.size,
                "Only one badge should exist — idempotency prevented duplicate",
            )
        }

    // ==========================================
    // 3. Deep compensation — the plant is out of plastic in the requested design (step 3),
    //    rolling back steps 2 and 1
    // ==========================================

    @Test
    fun testPrintingFailureTriggersDeepCompensation() =
        runBlocking {
            val directoryService = FakeDirectoryService()
            val credentialService = FakeCredentialService()
            val factory = FakePrintingFactory()
            factory.unavailableDesigns.add(BadgeMaterial.REINFORCED)
            val deliveryService = FakeDeliveryService()
            val notifierService = FakeNotifierService()
            val notificationLog = mutableListOf<String>()

            val engine =
                createEngine(
                    directoryService,
                    credentialService,
                    factory,
                    deliveryService,
                    notifierService,
                    notificationLog,
                )

            val payload = defaultPayload(design = BadgeMaterial.REINFORCED)
            val petich = defaultPetich("badge-emboss-fail", payload)

            // 1. CONFIRM_CODE
            val r1 = engine.process(petich)
            assertTrue(r1 is PetichResult.ActionRequired)

            // 2. Execution: record opened (step 1), badge issued (step 2), printing failed
            //    (step 3) -> compensation
            val r2 =
                engine.process(
                    r1.petich.copy(resumePayload = ConfirmResumePayload(notifierService.lastSentCode!!)),
                )
            assertTrue(r2 is PetichResult.SystemFailure, "Expected SystemFailure, got $r2")

            // Step 3 failed
            assertTrue(
                factory.operationLog.any { it.contains("PRINTING_REJECT") },
                "Printing should have been rejected",
            )

            // Compensating step 2: the badge is blocked in the processor
            assertTrue(
                credentialService.operationLog.any { it.contains("BLOCK_BADGE") },
                "Virtual badge should be blocked: ${credentialService.operationLog}",
            )
            val blockedBadges = credentialService.issuedBadges.values.filter { it.blocked }
            assertEquals(1, blockedBadges.size, "Exactly one badge should be blocked")

            // Compensating step 1: the badge record is closed in the core facilitying system
            assertTrue(
                directoryService.operationLog.any { it.contains("CLOSE_RECORD") },
                "Badge record should be closed: ${directoryService.operationLog}",
            )
            assertTrue(directoryService.records.isEmpty(), "No active records should remain")

            // The delivery was NOT called: step 4 never ran
            assertTrue(deliveryService.operationLog.isEmpty(), "Delivery should not have been called")

            // No notification was sent
            assertTrue(notificationLog.isEmpty(), "No notification should be sent on failure")
        }

    // ==========================================
    // 4. A blocked customer — rejected at the VALIDATION stage
    // ==========================================

    @Test
    fun testBlockedHolderRejected() =
        runBlocking {
            val engine = createEngine(blockedHolders = setOf("bad-holder"))

            val payload = defaultPayload(holderId = "bad-holder")
            val petich = defaultPetich("badge-blocked", payload)

            val result = engine.process(petich)
            assertTrue(result is PetichResult.Error, "Expected Reject, got $result")
            assertEquals("Holder is blocked", result.reason)
        }

    // ==========================================
    // 5. A wrong CONFIRM_CODE followed by a correct one
    // ==========================================

    @Test
    fun testWrongConfirmCodeThenCorrectConfirmCode() =
        runBlocking {
            val directoryService = FakeDirectoryService()
            val credentialService = FakeCredentialService()
            val factory = FakePrintingFactory()
            val deliveryService = FakeDeliveryService()
            val notifierService = FakeNotifierService()
            val notificationLog = mutableListOf<String>()

            val engine =
                createEngine(
                    directoryService,
                    credentialService,
                    factory,
                    deliveryService,
                    notifierService,
                    notificationLog,
                )

            val petich = defaultPetich("badge-confirmCode-retry")

            val r1 = engine.process(petich)
            assertTrue(r1 is PetichResult.ActionRequired)
            assertEquals("SMS_CONFIRM_CODE", r1.actionType)

            // Wrong code -> Resuspend
            val r2 = engine.process(r1.petich.copy(resumePayload = ConfirmResumePayload("000000")))
            assertTrue(r2 is PetichResult.ActionRequired, "Expected Resuspend, got $r2")

            // Correct code -> Success
            val r3 =
                engine.process(
                    r2.petich.copy(resumePayload = ConfirmResumePayload(notifierService.lastSentCode!!)),
                )
            assertTrue(r3 is PetichResult.Success, "Expected Success, got $r3")
            assertEquals(PetichStatus.COMPLETED, r3.petich.status)
        }

    // ==========================================
    // 6. Exceeding the CONFIRM_CODE attempt limit
    // ==========================================

    @Test
    fun testMaxConfirmCodeAttemptsExceeded() =
        runBlocking {
            val notifierService = FakeNotifierService()
            val engine = createEngine(notifierService = notifierService)

            val petich = defaultPetich("badge-confirmCode-max")

            val r1 = engine.process(petich)
            assertTrue(r1 is PetichResult.ActionRequired)
            var current = r1.petich

            for (i in 1..3) {
                val r = engine.process(current.copy(resumePayload = ConfirmResumePayload("wrong_code")))
                assertTrue(r is PetichResult.ActionRequired, "Attempt $i: expected Resuspend, got $r")
                current = r.petich
            }

            val rFinal = engine.process(current.copy(resumePayload = ConfirmResumePayload("wrong_code")))
            assertTrue(rFinal is PetichResult.Error, "Expected Reject, got $rFinal")
            assertEquals("Too many CONFIRM_CODE attempts", rFinal.reason)
        }

    // ==========================================
    // 7. The delivery service fails (step 4), compensating steps 3, 2 and 1
    // ==========================================

    @Test
    fun testDeliveryFailureRollsBackAllPreviousSteps() =
        runBlocking {
            val directoryService = FakeDirectoryService()
            val credentialService = FakeCredentialService()
            val factory = FakePrintingFactory()
            val notifierService = FakeNotifierService()
            val notificationLog = mutableListOf<String>()

            // A delivery that always fails
            class FailingDeliveryService : FakeDeliveryService() {
                override fun scheduleDelivery(
                    printingOrderId: String,
                    address: String,
                ): String = throw RuntimeException("Delivery API unavailable")
            }

            val failingDelivery = FailingDeliveryService()

            val repo = FakePetichRepository()
            val interceptors =
                listOf(
                    IdempotencyKeyInterceptor(),
                    HolderStatusCheckInterceptor(emptySet()),
                    BadgeConfirmCodeInterceptor(notifierService),
                    BadgeApprovalInterceptor(),
                    CreateDirectoryRecordInterceptor(directoryService),
                    IssueCredentialsInterceptor(credentialService),
                    PrintOrderInterceptor(factory),
                    DeliveryDispatchInterceptor(failingDelivery),
                    BadgeIssuanceNotificationInterceptor(notificationLog),
                )
            val engine = PetichEngine(interceptors, repo)

            val petich = defaultPetich("badge-delivery-fail")

            val r1 = engine.process(petich)
            assertTrue(r1 is PetichResult.ActionRequired)
            val r2 =
                engine.process(
                    r1.petich.copy(resumePayload = ConfirmResumePayload(notifierService.lastSentCode!!)),
                )
            assertTrue(r2 is PetichResult.SystemFailure, "Expected SystemFailure, got $r2")

            // Compensating step 3: printing cancelled
            assertTrue(
                factory.operationLog.any { it.contains("PRINTING_CANCEL") },
                "Printing should be cancelled: ${factory.operationLog}",
            )

            // Compensating step 2: the badge is blocked
            assertTrue(
                credentialService.operationLog.any { it.contains("BLOCK_BADGE") },
                "Badge should be blocked: ${credentialService.operationLog}",
            )

            // Compensating step 1: the record is closed
            assertTrue(
                directoryService.operationLog.any { it.contains("CLOSE_RECORD") },
                "Record should be closed: ${directoryService.operationLog}",
            )
            assertTrue(directoryService.records.isEmpty())
        }

    // ==========================================
    // 8. Cross-phase compensation: a failure in EXECUTION rolls back interceptors from
    //    ENRICHMENT and VALIDATION
    // ==========================================

    @Test
    fun testCrossPhaseCompensationRollsBackAllPhases() =
        runBlocking {
            val compensationLog = mutableListOf<String>()

            class TrackedEnrichmentInterceptor : BadgeIssuanceInterceptor() {
                override val phase = PetichPhase.ENRICHMENT
                override val priority = 1

                override suspend fun intercept(
                    petich: Petich,
                    payload: BadgeIssuancePayload,
                ): InterceptorResult {
                    compensationLog.add("ENRICHMENT_EXECUTED")
                    return InterceptorResult.Proceed()
                }

                override suspend fun compensate(
                    petich: Petich,
                    payload: BadgeIssuancePayload,
                ) {
                    compensationLog.add("ENRICHMENT_COMPENSATED")
                }
            }

            class TrackedValidationInterceptor : BadgeIssuanceInterceptor() {
                override val phase = PetichPhase.VALIDATION
                override val priority = 10

                override suspend fun intercept(
                    petich: Petich,
                    payload: BadgeIssuancePayload,
                ): InterceptorResult {
                    compensationLog.add("VALIDATION_EXECUTED")
                    return InterceptorResult.Proceed()
                }

                override suspend fun compensate(
                    petich: Petich,
                    payload: BadgeIssuancePayload,
                ) {
                    compensationLog.add("VALIDATION_COMPENSATED")
                }
            }

            class FailingExecutionInterceptor : BadgeIssuanceInterceptor() {
                override val phase = PetichPhase.EXECUTION
                override val priority = 10

                override suspend fun intercept(
                    petich: Petich,
                    payload: BadgeIssuancePayload,
                ): InterceptorResult = throw RuntimeException("Execution failure")
            }

            val repo = FakePetichRepository()
            val interceptors =
                listOf(
                    IdempotencyKeyInterceptor(),
                    TrackedEnrichmentInterceptor(),
                    TrackedValidationInterceptor(),
                    FailingExecutionInterceptor(),
                )
            val engine = PetichEngine(interceptors, repo)

            val petich = defaultPetich("badge-cross-phase")
            val result = engine.process(petich)
            assertTrue(result is PetichResult.SystemFailure, "Expected SystemFailure, got $result")

            assertTrue(compensationLog.contains("ENRICHMENT_EXECUTED"))
            assertTrue(compensationLog.contains("VALIDATION_EXECUTED"))
            assertTrue(
                compensationLog.contains("ENRICHMENT_COMPENSATED"),
                "ENRICHMENT should be compensated: $compensationLog",
            )
            assertTrue(
                compensationLog.contains("VALIDATION_COMPENSATED"),
                "VALIDATION should be compensated: $compensationLog",
            )
        }

    // ==========================================
    // 9. Idempotency when reprocessing a completed application
    // ==========================================

    @Test
    fun testReprocessCompletedPetichIsIdempotent() =
        runBlocking {
            val directoryService = FakeDirectoryService()
            val credentialService = FakeCredentialService()
            val factory = FakePrintingFactory()
            val deliveryService = FakeDeliveryService()
            val notifierService = FakeNotifierService()
            val notificationLog = mutableListOf<String>()

            val engine =
                createEngine(
                    directoryService,
                    credentialService,
                    factory,
                    deliveryService,
                    notifierService,
                    notificationLog,
                )

            val petich = defaultPetich("badge-idempotent-reprocess")

            val r1 = engine.process(petich)
            assertTrue(r1 is PetichResult.ActionRequired)
            val r2 =
                engine.process(
                    r1.petich.copy(resumePayload = ConfirmResumePayload(notifierService.lastSentCode!!)),
                )
            assertTrue(r2 is PetichResult.Success)

            val recordsAfterFirst = directoryService.records.size
            val badgesAfterFirst = credentialService.issuedBadges.size

            // Reprocessing the same completed petich
            val r3 = engine.process(r2.petich)
            assertTrue(r3 is PetichResult.Success, "Reprocess should return Success, got $r3")
            assertEquals(recordsAfterFirst, directoryService.records.size, "No new records on reprocess")
            assertEquals(badgesAfterFirst, credentialService.issuedBadges.size, "No new badges on reprocess")
        }

    // ==========================================
    // 10. Every badge design goes through all the steps
    // ==========================================

    @Test
    fun testDifferentBadgeMaterialsProcessCorrectly() =
        runBlocking {
            for (design in listOf(BadgeMaterial.STANDARD, BadgeMaterial.COATED, BadgeMaterial.MATTE)) {
                val directoryService = FakeDirectoryService()
                val credentialService = FakeCredentialService()
                val factory = FakePrintingFactory()
                val deliveryService = FakeDeliveryService()
                val notifierService = FakeNotifierService()

                val engine =
                    createEngine(
                        directoryService,
                        credentialService,
                        factory,
                        deliveryService,
                        notifierService,
                    )

                val payload = defaultPayload(design = design)
                val petich = defaultPetich("badge-design-${design.name}", payload)

                val r1 = engine.process(petich)
                assertTrue(r1 is PetichResult.ActionRequired, "Design $design: expected CONFIRM_CODE suspend, got $r1")
                val r2 =
                    engine.process(
                        r1.petich.copy(resumePayload = ConfirmResumePayload(notifierService.lastSentCode!!)),
                    )
                assertTrue(r2 is PetichResult.Success, "Design $design should succeed, got $r2")

                assertTrue(
                    factory.operationLog.any { it.contains("design=$design") },
                    "Printing should use design $design: ${factory.operationLog}",
                )
            }
        }

    // ==========================================
    // 11. Execution steps compensate in reverse order
    // ==========================================

    @Test
    fun testExecutionCompensationOrder() =
        runBlocking {
            val compensationLog = mutableListOf<String>()
            val directoryService = FakeDirectoryService()
            val credentialService = FakeCredentialService()
            val factory = FakePrintingFactory()
            val notifierService = FakeNotifierService()

            class TrackedCreateRecordInterceptor : BadgeIssuanceInterceptor() {
                override val phase = PetichPhase.EXECUTION
                override val priority = 40

                override suspend fun intercept(
                    petich: Petich,
                    payload: BadgeIssuancePayload,
                ): InterceptorResult {
                    val recordId = directoryService.createBadgeRecord(payload.holderId)
                    compensationLog.add("STEP1_RECORD_EXECUTED")
                    return InterceptorResult.Proceed(BadgeIssuanceEnrichedPayload(directoryRecordId = recordId))
                }

                override suspend fun compensate(
                    petich: Petich,
                    payload: BadgeIssuancePayload,
                ) {
                    compensationLog.add("STEP1_RECORD_COMPENSATED")
                }
            }

            class TrackedIssueBadgeInterceptor : BadgeIssuanceInterceptor() {
                override val phase = PetichPhase.EXECUTION
                override val priority = 30

                override suspend fun intercept(
                    petich: Petich,
                    payload: BadgeIssuancePayload,
                ): InterceptorResult {
                    val enriched = petich.enrichedPayload as BadgeIssuanceEnrichedPayload
                    val (badgeId, pan, cvv) =
                        credentialService.issueVirtualBadge(
                            enriched.directoryRecordId!!,
                            enriched.idempotencyKey!!,
                        )
                    compensationLog.add("STEP2_BADGE_EXECUTED")
                    return InterceptorResult.Proceed(
                        BadgeIssuanceEnrichedPayload(virtualBadgeId = badgeId, pan = pan, cvv = cvv),
                    )
                }

                override suspend fun compensate(
                    petich: Petich,
                    payload: BadgeIssuancePayload,
                ) {
                    compensationLog.add("STEP2_BADGE_COMPENSATED")
                }
            }

            class TrackedPrintingInterceptor : BadgeIssuanceInterceptor() {
                override val phase = PetichPhase.EXECUTION
                override val priority = 20

                override suspend fun intercept(
                    petich: Petich,
                    payload: BadgeIssuancePayload,
                ): InterceptorResult {
                    compensationLog.add("STEP3_PRINTING_EXECUTED")
                    return InterceptorResult.Proceed()
                }

                override suspend fun compensate(
                    petich: Petich,
                    payload: BadgeIssuancePayload,
                ) {
                    compensationLog.add("STEP3_PRINTING_COMPENSATED")
                }
            }

            class FailingStep4Interceptor : BadgeIssuanceInterceptor() {
                override val phase = PetichPhase.EXECUTION
                override val priority = 10

                override suspend fun intercept(
                    petich: Petich,
                    payload: BadgeIssuancePayload,
                ): InterceptorResult = throw RuntimeException("Step 4 failed")
            }

            val repo = FakePetichRepository()
            val interceptors =
                listOf(
                    IdempotencyKeyInterceptor(),
                    TrackedCreateRecordInterceptor(),
                    TrackedIssueBadgeInterceptor(),
                    TrackedPrintingInterceptor(),
                    FailingStep4Interceptor(),
                )
            val engine = PetichEngine(interceptors, repo)

            val petich = defaultPetich("badge-comp-order")
            val result = engine.process(petich)
            assertTrue(result is PetichResult.SystemFailure)

            // Execution steps ran in forward order, by descending priority
            val execOrder = compensationLog.filter { it.contains("EXECUTED") }
            assertEquals(
                listOf("STEP1_RECORD_EXECUTED", "STEP2_BADGE_EXECUTED", "STEP3_PRINTING_EXECUTED"),
                execOrder,
                "Execution should be in priority order",
            )

            // Compensation in reverse order
            val compOrder = compensationLog.filter { it.contains("COMPENSATED") }
            assertEquals(
                listOf("STEP3_PRINTING_COMPENSATED", "STEP2_BADGE_COMPENSATED", "STEP1_RECORD_COMPENSATED"),
                compOrder,
                "Compensation should be in reverse order",
            )
        }
}
