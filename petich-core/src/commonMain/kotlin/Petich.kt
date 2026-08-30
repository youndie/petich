package ru.workinprogress.petich

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration

@Serializable
abstract class PetichPayload

@Serializable
abstract class ResumePayload

@Serializable
abstract class EnrichedPayload {
    abstract fun merge(other: EnrichedPayload): EnrichedPayload
}

// @SerialName is load-bearing, not cosmetic. Without it the polymorphic discriminator is the
// fully qualified class name, which makes the STORAGE format depend on where the package lives:
// moving a module silently renders already-persisted rows unreadable. That is exactly what the
// petition -> petich rename would have done. A short name pins the format for good and decouples
// it from refactorings.
@Serializable
@SerialName("simple_enriched")
data class SimpleEnrichedPayload(
    val data: Map<String, String> = emptyMap(),
) : EnrichedPayload() {
    override fun merge(other: EnrichedPayload): EnrichedPayload =
        if (other is SimpleEnrichedPayload) {
            SimpleEnrichedPayload(this.data + other.data)
        } else {
            other
        }
}

enum class PetichStatus {
    DRAFT,
    PENDING_SIGNATURE,
    PROCESSING,
    COMPLETED,
    REJECTED,
    FAILED,
    COMPENSATING,
}

fun PetichStatus.isTerminal(): Boolean =
    this == PetichStatus.COMPLETED ||
        this == PetichStatus.REJECTED ||
        this == PetichStatus.FAILED

data class Petich(
    val id: String,
    val type: String,
    val currentPhase: PetichPhase = PetichPhase.ENRICHMENT,
    val currentInterceptorIndex: Int = 0,
    val status: PetichStatus,
    val payload: PetichPayload,
    val enrichedPayload: EnrichedPayload = SimpleEnrichedPayload(),
    val version: Long = 0L,
    val compensatingFromIndex: Int? = null,
    val resumePayload: ResumePayload? = null,
    // The instant after which a petich awaiting client action (PENDING_SIGNATURE) counts as
    // expired. Phase timeouts (see timeoutMs) bound the EXECUTION of an interceptor, not the wait
    // for a human: without this deadline a petich stuck awaiting a confirmation lives forever,
    // holding open the saga steps already performed — stock reserved, quota claimed.
    //
    // null means "no deadline", which is how every petich behaves until a TTL is configured (see
    // defaultSuspendTtl), so existing behaviour does not change by itself.
    val suspendedUntilEpochMs: Long? = null,
)

// A wall clock for deadlines. Passed in rather than System.currentTimeMillis(): commonMain of a
// KMP module cannot see java.* regardless of how many targets actually exist, and tests must be
// able to move time instead of depending on the real one.
//
// Wall clock specifically, not TimeSource.Monotonic (which OutboxRelayWorker uses for backoff): a
// deadline survives a process restart and is compared across rows in a database, and monotonic
// marks mean nothing outside a single process.
fun interface PetichClock {
    fun nowEpochMs(): Long
}

enum class PetichPhase {
    ENRICHMENT,
    VALIDATION,
    AUTHORIZATION,
    EXECUTION,
    POST_PROCESSING,
}

// The default timeout table. DEFAULTS, not engine constants: they are overridden through
// PetichEngineConfig because they differ between environments — external services answer more
// slowly on dev than in production.
val PetichPhase.timeoutMs
    get() =
        when (this) {
            PetichPhase.ENRICHMENT -> 1000L
            PetichPhase.VALIDATION -> 2000L
            PetichPhase.AUTHORIZATION -> 30000L
            PetichPhase.EXECUTION -> 10000L
            PetichPhase.POST_PROCESSING -> 2000L
        }

// Engine settings. Every default equals what used to be hardcoded, so existing code that creates
// a PetichEngine without a config behaves exactly as before.
data class PetichEngineConfig(
    val phaseTimeoutsMs: Map<PetichPhase, Long> = PetichPhase.entries.associateWith { it.timeoutMs },
    // How many times to retry the whole processing pass on a version conflict.
    val maxProcessAttempts: Int = 5,
    // How many times to retry a state write before giving up (see forceUpdateStateWithRetry).
    val maxStateUpdateAttempts: Int = 100,
    val retryBaseDelayMs: Long = 20,
    val retryJitterMs: Long = 50,
    // Separate from the forward-pass timeout: compensation usually takes longer, since it calls
    // the same external systems but in recovery mode. null means use the phase timeout.
    val compensationTimeoutsMs: Map<PetichPhase, Long>? = null,
    // The blanket deadline for suspended petiches awaiting client action. null (the default)
    // means no deadline, behaving exactly as before TTLs existed: defaults in this config never
    // change the behaviour of existing code. It is switched on deliberately, either by the
    // application or by a specific interceptor through Suspend(ttl = ...).
    val defaultSuspendTtl: Duration? = null,
    // Refuse to build an engine whose repository cannot store outbox events. false by default,
    // because the quiet degradation is the documented behaviour and an application that wants no
    // events must not have to configure their absence.
    //
    // Worth switching on by anything that wires the outbox to a message broker, and worth it at
    // construction rather than at the drop: by the time the first event is dropped the process is
    // in production, and the drop is invisible there. The mistake it refuses is not a typo — it is
    // a plain PetichRepository reaching a place that needed an outbox-aware one, which is easy
    // because :petich-postgres is outbox-aware while a test double or a hand-rolled repository is
    // not. Its counterpart in flight is PetichEngineMetrics.onDroppedEvents.
    val requireOutbox: Boolean = false,
) {
    init {
        require(maxProcessAttempts > 0) { "maxProcessAttempts must be positive" }
        require(maxStateUpdateAttempts > 0) { "maxStateUpdateAttempts must be positive" }
        require(retryJitterMs >= 0) { "retryJitterMs cannot be negative" }
        require(defaultSuspendTtl == null || defaultSuspendTtl > Duration.ZERO) {
            "defaultSuspendTtl must be positive"
        }
    }

    fun timeoutMs(phase: PetichPhase): Long = phaseTimeoutsMs[phase] ?: phase.timeoutMs

    fun compensationTimeoutMs(phase: PetichPhase): Long = compensationTimeoutsMs?.get(phase) ?: timeoutMs(phase)
}

sealed interface InterceptorResult {
    val enrichedPayload: EnrichedPayload? get() = null

    data class Proceed(
        override val enrichedPayload: EnrichedPayload? = null,
        val outboxEvents: List<OutboxEvent> = emptyList(),
    ) : InterceptorResult

    data class Suspend(
        val requiredAction: String,
        override val enrichedPayload: EnrichedPayload? = null,
        // How long to await client action AT THIS PARTICULAR STEP. null takes the blanket
        // deadline from PetichEngineConfig.defaultSuspendTtl: typing a one-time code and
        // approving a long-running request live on different time scales, and the interceptor
        // knows that, not the engine.
        val ttl: Duration? = null,
    ) : InterceptorResult

    data class Resuspend(
        val requiredAction: String,
        override val enrichedPayload: EnrichedPayload? = null,
        val ttl: Duration? = null,
    ) : InterceptorResult

    data class Reject(
        val reason: String,
    ) : InterceptorResult

    data class Compensate(
        val reason: String,
    ) : InterceptorResult
}

/**
 * The main contract for every business feature.
 */
interface PetichInterceptor<T : PetichPayload> {
    val phase: PetichPhase
    val priority: Int get() = 0 // Defaults to 0; the higher the number, the earlier it runs

    fun supports(payload: PetichPayload): Boolean

    suspend fun intercept(
        petich: Petich,
        payload: T,
    ): InterceptorResult

    suspend fun compensate(
        petich: Petich,
        payload: T,
    )

    // An optional layer over compensate(): by default it simply delegates and emits no events,
    // so no existing override of compensate() needs touching (see OutboxEvent.kt). Compensating
    // interceptors that must reliably announce a rollback — "the reservation was released", say —
    // override this method instead
    // compensate().
    suspend fun compensateWithEvents(
        petich: Petich,
        payload: T,
    ): List<OutboxEvent> {
        compensate(petich, payload)
        return emptyList()
    }

    suspend fun tryIntercept(
        petich: Petich,
        payload: PetichPayload,
    ): InterceptorResult? =
        if (supports(payload)) {
            // supports() above has already checked the type; the compiler cannot see that link.
            @Suppress("UNCHECKED_CAST")
            withPayloadDiagnostics(payload) { intercept(petich, payload as T) }
        } else {
            null
        }

    suspend fun tryCompensate(
        petich: Petich,
        payload: PetichPayload,
    ): List<OutboxEvent> =
        if (supports(payload)) {
            @Suppress("UNCHECKED_CAST")
            withPayloadDiagnostics(payload) { compensateWithEvents(petich, payload as T) }
        } else {
            emptyList()
        }
}

// `payload as T` is unchecked at runtime (T is erased), so nothing stops an interceptor from
// lying in supports(): the ClassCastException surfaces later, from the implementation bridge, and
// reads in the log as an anonymous failure. The engine survives it either way — the petich goes to
// SystemFailure and the process stays up (see EngineDefectsTest) — but such a log cannot tell you
// which interceptor is at fault. All we do here is rename the error, keeping the original as the
// cause.
//
// One caveat: a CCE thrown by the interceptor's own business logic gets the same message. Hence
// the hedged wording, and hence the original exception is preserved in cause.
private inline fun <R> PetichInterceptor<*>.withPayloadDiagnostics(
    payload: PetichPayload,
    block: () -> R,
): R =
    try {
        block()
    } catch (e: ClassCastException) {
        throw IllegalStateException(
            "Interceptor ${this::class.simpleName} rejected payload ${payload::class.simpleName}: " +
                "its supports() probably returns true for someone else's type",
            e,
        )
    }

interface PetichRepository {
    suspend fun findById(id: String): Petich?

    suspend fun saveOrGet(petich: Petich): Petich

    suspend fun update(petich: Petich): Boolean
}

// An optional extension: an implementation able to write outbox events in the SAME SQL
// transaction as the petich update (see ExposedPetichRepository in :petich-postgres). PetichEngine
// checks `repository is OutboxAwarePetichRepository` at the persistence point and quietly degrades
// to a plain update(petich) when the repository does not support it, or when there are no events.
// Plain PetichRepository implementations therefore keep working without a single change.
interface OutboxAwarePetichRepository : PetichRepository {
    suspend fun update(
        petich: Petich,
        outboxEvents: List<OutboxEvent>,
    ): Boolean

    override suspend fun update(petich: Petich): Boolean = update(petich, emptyList())
}

// What expireSuspended did. Not a Boolean: "not found", "no longer waiting" and "deadline not
// reached yet" are three different situations, and a worker benefits from telling them apart in
// its logs.
sealed interface ExpireResult {
    data class Expired(
        val petichId: String,
    ) : ExpireResult

    data class NotSuspended(
        val status: PetichStatus,
    ) : ExpireResult

    data object NotExpiredYet : ExpireResult

    data object NotFound : ExpireResult
}

// The reason an expired petich goes to compensation. A constant rather than an inline literal:
// it is what distinguishes a deadline rollback from an interceptor rejection during an incident
// review.
const val EXPIRED_REASON: String = "Petich expired while waiting for the client"

class PetichEngine(
    private val interceptors: List<PetichInterceptor<*>>,
    private val repository: PetichRepository,
    private val compensationFailureHandler: CompensationFailureHandler = NoOpCompensationFailureHandler(),
    private val config: PetichEngineConfig = PetichEngineConfig(),
    // Needed only for the deadlines of suspended petiches. The default throws: the clock is
    // consulted exactly when a TTL is actually configured, so existing code that never enabled a
    // TTL need not touch its constructor and will never hit this stub.
    private val clock: PetichClock = PetichClock { error("PetichClock is not set, yet a suspend TTL is enabled") },
    // Counters. A no-op by default: existing code pays nothing and changes nothing (see
    // PetichEngineMetrics on why they exist at all).
    private val metrics: PetichEngineMetrics = PetichEngineMetrics.NoOp,
) {
    init {
        // Deliberately a construction failure and not a warning. A warning about events that will
        // be dropped is read, if at all, in the logs of a process that is already serving traffic,
        // and it competes with everything else printed at startup; the whole difficulty with this
        // mistake is that nothing downstream of it looks wrong.
        require(!config.requireOutbox || repository is OutboxAwarePetichRepository) {
            "requireOutbox is set, but ${repository::class.simpleName} is not an " +
                "OutboxAwarePetichRepository: outbox events produced by interceptors would be " +
                "dropped and the sagas would still report success"
        }
    }

    // The per-petich lock is needed only while processing. We count references, because dropping
    // it "when done" is not enough: while one call holds the mutex, a second may already have
    // taken it from the map and be waiting on it, and removing the entry at that moment would hand
    // a third call a NEW mutex — letting two calls process one petich at the same time.
    private class LockEntry(
        val mutex: Mutex = Mutex(),
        var holders: Int = 0,
    )

    private val lockMapMutex = Mutex()
    private val petichLocks = mutableMapOf<String, LockEntry>()

    // Observability for tests: how many locks the engine holds right now. Not visible outside the
    // module (internal) — this is not part of the contract, only a way to prove locks get released.
    internal val activeLockCount: Int get() = petichLocks.size

    // The single persistence point that outbox events pass through. Degrades quietly to
    // repository.update(petich) when there are no events, or when the repository is not an
    // OutboxAwarePetichRepository (see the comment on the interface itself).
    //
    // The two ways of reaching the plain update are NOT the same event and are deliberately not
    // written as one condition. No events is nothing happening. Events with a repository that
    // cannot store them is a loss, and the only trace it leaves anywhere, since the write succeeds
    // and the saga completes exactly as it would have.
    private suspend fun updatePetich(
        petich: Petich,
        outboxEvents: List<OutboxEvent> = emptyList(),
    ): Boolean =
        when {
            outboxEvents.isEmpty() -> {
                repository.update(petich)
            }

            repository is OutboxAwarePetichRepository -> {
                repository.update(petich, outboxEvents)
            }

            else -> {
                metrics.onDroppedEvents(petich.type, outboxEvents.size)
                repository.update(petich)
            }
        }

    private suspend fun triggerCompensation(
        petich: Petich,
        reason: String,
        isSystemFailure: Boolean = false,
    ): PetichResult {
        metrics.onCompensation(petich.type, reason)
        val compensateFromIdx = petich.compensatingFromIndex ?: petich.currentInterceptorIndex
        // Via forceUpdateStateWithRetry rather than a plain update with the result discarded:
        // the "rollback started" mark is the most important write of the whole scenario. Losing it
        // to a version conflict would leave the engine compensating a petich that the database
        // still shows as executing.
        var currentPetich =
            forceUpdateStateWithRetry(
                petich,
                PetichStatus.COMPENSATING,
                petich.enrichedPayload,
                { it.copy(currentPhase = petich.currentPhase, compensatingFromIndex = compensateFromIdx) },
            )

        var compensationFailed = false

        withContext(NonCancellable) {
            val startPhaseOrdinal = currentPetich.currentPhase.ordinal

            for (phaseOrdinal in startPhaseOrdinal downTo 0) {
                val phase = PetichPhase.entries[phaseOrdinal]
                val phaseInterceptors =
                    interceptors
                        .filter { it.phase == phase && it.supports(currentPetich.payload) }
                        .sortedByDescending { it.priority }

                if (phaseInterceptors.isEmpty()) continue

                val startIndex =
                    if (phaseOrdinal == startPhaseOrdinal) {
                        compensateFromIdx - 1
                    } else {
                        phaseInterceptors.size - 1
                    }

                var rollbackIndex = startIndex
                while (rollbackIndex >= 0) {
                    val interceptor = phaseInterceptors[rollbackIndex]
                    try {
                        // Compensation is time-bounded too. It used to be called directly inside
                        // NonCancellable, so a hung network call in a compensation held the caller
                        // forever, and by construction nothing outside could cancel it.
                        // withTimeout inside NonCancellable does work: it is the outer job that is
                        // non-cancellable, while the timeout cancels its own child coroutine.
                        val compensationEvents =
                            withTimeout(config.compensationTimeoutMs(phase)) {
                                interceptor.tryCompensate(
                                    currentPetich,
                                    currentPetich.payload,
                                )
                            }
                        rollbackIndex--
                        currentPetich =
                            forceUpdateStateWithRetry(
                                currentPetich,
                                PetichStatus.COMPENSATING,
                                currentPetich.enrichedPayload,
                                { it.copy(compensatingFromIndex = rollbackIndex + 1, currentPhase = phase) },
                                outboxEvents = compensationEvents,
                            )
                    } catch (e: Exception) {
                        compensationFailureHandler.handle(e, currentPetich, interceptor)
                        compensationFailed = true
                        break
                    }
                }

                if (compensationFailed) break
            }

            if (!compensationFailed) {
                forceUpdateStateWithRetry(
                    currentPetich,
                    PetichStatus.FAILED,
                    currentPetich.enrichedPayload,
                    { it.copy(currentInterceptorIndex = 0, compensatingFromIndex = null) },
                )
            }
        }

        return if (isSystemFailure) {
            PetichResult.SystemFailure(reason)
        } else {
            PetichResult.Error(reason)
        }
    }

    // null when no TTL is configured either globally or at this step, in which case the petich
    // waits indefinitely, as it did before TTLs existed.
    private fun suspendDeadline(ttl: Duration?): Long? {
        val effective = ttl ?: config.defaultSuspendTtl ?: return null
        return clock.nowEpochMs() + effective.inWholeMilliseconds
    }

    // The entry point for the background sweeper of expired petiches (see
    // SuspendedPetichSweeper). A separate method rather than "just call process": process would
    // carry the saga forward, whereas an expired petich must be rolled back.
    //
    // It goes through the same mutex as ordinary processing. The race "the client confirms at the
    // exact moment the deadline passes" is resolved by one of the two arriving second and
    // seeing state that has already changed. That is why the state is re-read inside the lock and
    // every condition re-checked: the decision the worker made from its query results may be stale
    // by now.
    suspend fun expireSuspended(petichId: String): ExpireResult {
        val entry =
            lockMapMutex.withLock {
                petichLocks.getOrPut(petichId) { LockEntry() }.also { it.holders++ }
            }

        try {
            return entry.mutex.withLock {
                val petich = repository.findById(petichId) ?: return@withLock ExpireResult.NotFound
                val deadline = petich.suspendedUntilEpochMs
                when {
                    petich.status != PetichStatus.PENDING_SIGNATURE -> {
                        ExpireResult.NotSuspended(petich.status)
                    }

                    // The client answered in time and the petich went round again with a new
                    // deadline.
                    deadline == null || clock.nowEpochMs() < deadline -> {
                        ExpireResult.NotExpiredYet
                    }

                    else -> {
                        triggerCompensation(petich.copy(suspendedUntilEpochMs = null), EXPIRED_REASON)
                        ExpireResult.Expired(petichId)
                    }
                }
            }
        } finally {
            lockMapMutex.withLock {
                if (--entry.holders == 0) petichLocks.remove(petichId)
            }
        }
    }

    suspend fun process(petich: Petich): PetichResult {
        val entry =
            lockMapMutex.withLock {
                petichLocks.getOrPut(petich.id) { LockEntry() }.also { it.holders++ }
            }

        try {
            return entry.mutex.withLock {
                processWithRetry(petich)
            }
        } finally {
            lockMapMutex.withLock {
                if (--entry.holders == 0) petichLocks.remove(petich.id)
            }
        }
    }

    private suspend fun processWithRetry(petich: Petich): PetichResult {
        var currentAttempt = 0

        while (currentAttempt < config.maxProcessAttempts) {
            try {
                metrics.onProcessAttempt(petich.type)
                return doProcess(petich)
            } catch (e: OptimisticLockException) {
                currentAttempt++
                metrics.onOptimisticRetry(petich.type, currentAttempt)
                if (currentAttempt >= config.maxProcessAttempts) throw e

                val backoff =
                    (2.0.pow(currentAttempt) * config.retryBaseDelayMs).toLong() +
                        Random.nextLong(config.retryJitterMs + 1)
                delay(backoff)
            }
        }
        return PetichResult.SystemFailure("Max retries exceeded")
    }

    private suspend fun forceUpdateStateWithRetry(
        petich: Petich,
        status: PetichStatus,
        enrichedPayload: EnrichedPayload,
        additionalUpdates: (Petich) -> Petich = { it },
        outboxEvents: List<OutboxEvent> = emptyList(),
    ): Petich {
        // There is no "nothing to change" branch here, and cannot be: version is always
        // latest.version + 1 and no caller winds it back, so the former `updated == latest` check
        // was unreachable.
        repeat(config.maxStateUpdateAttempts) { attempt ->
            if (attempt > 0) metrics.onStateUpdateRetry(petich.type)
            val latest = repository.saveOrGet(petich)
            val updated =
                additionalUpdates(
                    latest.copy(
                        status = status,
                        enrichedPayload = enrichedPayload,
                        version = latest.version + 1,
                        // The deadline lives exactly as long as the petich waits for the client.
                        // Clearing it only in the Proceed branch is not enough: an interceptor that
                        // returned Suspend is deliberately NOT re-executed on resume (see
                        // ResumeInterceptorTest), so a petich that ran to completion would carry a
                        // stale deadline into a terminal status. additionalUpdates is applied
                        // afterwards and so remains the way to set a new deadline (see the Suspend
                        // and Resuspend branches).
                        suspendedUntilEpochMs =
                            latest.suspendedUntilEpochMs.takeIf {
                                status ==
                                    PetichStatus.PENDING_SIGNATURE
                            },
                    ),
                )
            if (updatePetich(updated, outboxEvents)) return updated
        }
        throw OptimisticLockException()
    }

    // The emergency transition to a terminal status. A separate method because this used to be a
    // direct repository.update(...) with the result discarded: on a version conflict the write was
    // silently lost, the client got a SystemFailure, and the petich stayed in an intermediate
    // status forever with nobody to pick it up.
    private suspend fun failTerminally(
        petich: Petich,
        details: String,
    ): PetichResult =
        try {
            forceUpdateStateWithRetry(petich, PetichStatus.FAILED, petich.enrichedPayload)
            PetichResult.SystemFailure(details)
        } catch (e: OptimisticLockException) {
            // The write failed even with retries. Staying silent about it would leave the
            // divergence between what the client was told and what the database holds unnoticed.
            PetichResult.SystemFailure("$details (could not persist FAILED status: version conflict)")
        }

    private suspend fun doProcess(petich: Petich): PetichResult {
        var currentPetich =
            repository
                .saveOrGet(petich)
                .copy(resumePayload = petich.resumePayload)
        var currentEnrichedPayload = currentPetich.enrichedPayload

        if (currentPetich.status == PetichStatus.COMPENSATING) {
            return triggerCompensation(currentPetich, "Resuming compensation")
        }

        if (currentPetich.status.isTerminal()) {
            return when (currentPetich.status) {
                PetichStatus.COMPLETED -> PetichResult.Success(currentPetich)

                PetichStatus.REJECTED -> PetichResult.Error("Petich was already rejected")

                // FAILED is as finished an outcome as REJECTED, and a repeat under the same id
                // must return what the pass that failed it returned. A saga rolled back through
                // Compensate returned Error, while its repeat returned SystemFailure — the same
                // outcome looking like a business rejection or a server fault depending on which
                // request number it was. A load test hit this first: a client repeating a request
                // with the same idempotency key after a rollback got a 500.
                //
                // SystemFailure keeps its meaning — "this call could not do the work". Here there
                // is no work by construction: the petich is terminal and the engine has nothing to
                // do.
                else -> PetichResult.Error("Petich has already failed")
            }
        }

        try {
            val startingPhaseIndex = currentPetich.currentPhase.ordinal
            val remainingPhases = PetichPhase.entries.drop(startingPhaseIndex)
            val initialPhase = currentPetich.currentPhase

            for (phase in remainingPhases) {
                if (currentPetich.currentPhase != phase) {
                    currentPetich = currentPetich.copy(currentPhase = phase, currentInterceptorIndex = 0)
                }

                val phaseInterceptors =
                    interceptors
                        .filter { it.phase == phase && it.supports(currentPetich.payload) }
                        .sortedByDescending { it.priority }

                val runPhase =
                    suspend {
                        var result: PetichResult? = null
                        val successfulInterceptors = mutableListOf<PetichInterceptor<*>>()

                        val startingInterceptorIndex =
                            if (phase == initialPhase) {
                                currentPetich.currentInterceptorIndex
                            } else {
                                0
                            }

                        for ((index, interceptor) in phaseInterceptors.withIndex()) {
                            if (index < startingInterceptorIndex) continue

                            val interceptorResult =
                                try {
                                    withTimeout(config.timeoutMs(phase)) {
                                        interceptor.tryIntercept(
                                            petich = currentPetich,
                                            payload = currentPetich.payload,
                                        )
                                    }
                                } catch (e: TimeoutCancellationException) {
                                    result =
                                        triggerCompensation(
                                            currentPetich,
                                            e.message ?: "Timeout",
                                            isSystemFailure = true,
                                        )
                                    break
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    result =
                                        triggerCompensation(
                                            currentPetich,
                                            e.message ?: "System Error",
                                            isSystemFailure = true,
                                        )
                                    break
                                }

                            interceptorResult?.enrichedPayload?.let {
                                currentEnrichedPayload = currentEnrichedPayload.merge(it)
                            }

                            when (interceptorResult) {
                                null -> {
                                    continue
                                }

                                is InterceptorResult.Reject -> {
                                    forceUpdateStateWithRetry(
                                        currentPetich,
                                        PetichStatus.REJECTED,
                                        currentEnrichedPayload,
                                    )
                                    result = PetichResult.Error(interceptorResult.reason)
                                    break
                                }

                                is InterceptorResult.Compensate -> {
                                    result =
                                        triggerCompensation(
                                            currentPetich,
                                            interceptorResult.reason,
                                        )
                                    break
                                }

                                is InterceptorResult.Suspend -> {
                                    metrics.onSuspend(currentPetich.type)
                                    val deadline = suspendDeadline(interceptorResult.ttl)
                                    val updated =
                                        forceUpdateStateWithRetry(
                                            currentPetich,
                                            PetichStatus.PENDING_SIGNATURE,
                                            currentEnrichedPayload,
                                            {
                                                it.copy(
                                                    currentInterceptorIndex = index + 1,
                                                    currentPhase = phase,
                                                    suspendedUntilEpochMs = deadline,
                                                )
                                            },
                                        )
                                    result =
                                        PetichResult.ActionRequired(
                                            interceptorResult.requiredAction,
                                            updated,
                                        )
                                    break
                                }

                                is InterceptorResult.Resuspend -> {
                                    metrics.onSuspend(currentPetich.type)
                                    // A bug found while integrating a wizard on top of the engine:
                                    // capturing `val updated` and using it below. Previously — and
                                    // unlike the Suspend branch above — the return value of
                                    // forceUpdateStateWithRetry was discarded here, and the OLD
                                    // currentPetich went into ActionRequired.petich. Persistence
                                    // was correct, but a caller reading result.petich.enrichedPayload
                                    // right after a Resuspend, as a wizard route needing the current
                                    // step does, saw a stale value.
                                    //
                                    // The deadline is counted afresh from EVERY resume: a client
                                    // who mistyped the code and enters it again is waiting on a new
                                    // step, not living out the remainder of the previous one.
                                    val deadline = suspendDeadline(interceptorResult.ttl)
                                    val updated =
                                        forceUpdateStateWithRetry(
                                            currentPetich,
                                            PetichStatus.PENDING_SIGNATURE,
                                            currentEnrichedPayload,
                                            {
                                                it.copy(
                                                    currentInterceptorIndex = index,
                                                    suspendedUntilEpochMs = deadline,
                                                )
                                            },
                                        )
                                    result =
                                        PetichResult.ActionRequired(
                                            interceptorResult.requiredAction,
                                            updated,
                                        )
                                    break
                                }

                                is InterceptorResult.Proceed -> {
                                    successfulInterceptors.add(interceptor)
                                    val updated =
                                        currentPetich.copy(
                                            currentInterceptorIndex = index + 1,
                                            enrichedPayload = currentEnrichedPayload,
                                            version = currentPetich.version + 1,
                                            // The petich has moved on and no longer awaits the
                                            // client; otherwise the sweeper would pick it up on a
                                            // stale deadline and roll back a saga already in
                                            // motion.
                                            suspendedUntilEpochMs = null,
                                        )
                                    if (!updatePetich(updated, interceptorResult.outboxEvents)) {
                                        throw OptimisticLockException()
                                    }
                                    currentPetich = updated
                                }
                            }
                        }
                        result
                    }

                val result = runPhase()
                if (result != null) return result
            }

            // We return exactly what was written. Previously the result carried a copy taken
            // BEFORE the version increment, so a caller reading result.petich saw something other
            // than what the database holds — the same class of defect already fixed in the
            // Resuspend branch.
            val completed =
                currentPetich.copy(
                    status = PetichStatus.COMPLETED,
                    enrichedPayload = currentEnrichedPayload,
                    version = currentPetich.version + 1,
                    // A finished petich is not waiting for the client. This is the only status
                    // write that bypasses forceUpdateStateWithRetry (which applies the same rule),
                    // so the deadline is cleared here too: otherwise a completed petich would carry
                    // a stale deadline into the database.
                    suspendedUntilEpochMs = null,
                )
            if (!repository.update(completed)) throw OptimisticLockException()
            return PetichResult.Success(completed)
        } catch (e: TimeoutCancellationException) {
            return failTerminally(currentPetich, "Timeout")
        } catch (e: CancellationException) {
            throw e
        } catch (e: OptimisticLockException) {
            throw e
        } catch (e: Exception) {
            return failTerminally(currentPetich, e.message ?: "Unknown error")
        }
    }
}

class OptimisticLockException : RuntimeException("Version conflict")

sealed interface PetichResult {
    data class Success(
        val petich: Petich,
    ) : PetichResult

    data class ActionRequired(
        val actionType: String,
        val petich: Petich,
    ) : PetichResult

    data class Error(
        val reason: String,
    ) : PetichResult

    data class SystemFailure(
        val details: String,
    ) : PetichResult
}

interface CompensationFailureHandler {
    suspend fun handle(
        e: Exception,
        petich: Petich,
        interceptor: PetichInterceptor<*>,
    )
}

class NoOpCompensationFailureHandler : CompensationFailureHandler {
    override suspend fun handle(
        e: Exception,
        petich: Petich,
        interceptor: PetichInterceptor<*>,
    ) {
    }
}
