// The consumer's own code. It has to NAME the library's types rather than merely depend on it: a
// build that only declares a dependency proves the resolution, and this is meant to prove the klibs
// are usable from native code — that it compiles against them and that the linker is satisfied.
//
// TWO MODES, AND THE SECOND ONE NEEDS A DATABASE.
//
//  * with no PETICH_PROBE_POSTGRES_URL: resolve, compile, link, print. That is what CI runs on every
//    push, where there is no Postgres and none is wanted — the question there is whether a native
//    consumer can take what was just published;
//  * with the variable set: open the driver, create the schema and run a saga end to end. That is
//    B-09's acceptance, and it is the only thing that can answer whether the store WORKS rather than
//    whether it resolves.

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.postgres.PostgreSQL
import io.github.youndie.petich.EnrichedPayload
import io.github.youndie.petich.OutboxEvent
import io.github.youndie.petich.Petich
import io.github.youndie.petich.PetichClock
import io.github.youndie.petich.PetichEngine
import io.github.youndie.petich.PetichStep
import io.github.youndie.petich.PetichStepContext
import io.github.youndie.petich.petich
import io.github.youndie.petich.PetichPayload
import io.github.youndie.petich.PetichPhase
import io.github.youndie.petich.PetichResult
import io.github.youndie.petich.PetichStatus
import io.github.youndie.petich.SimpleEnrichedPayload
import io.github.youndie.petich.isTerminal
import io.github.youndie.petich.outbox.OutboxRecord
import io.github.youndie.petich.sqlx4k.postgres.PostgresOutboxStore
import io.github.youndie.petich.sqlx4k.postgres.PostgresPetichStore
import io.github.youndie.petich.sqlx4k.postgres.petichPostgresSchema
import io.github.youndie.petich.timeoutMs
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import platform.posix.getenv

// A payload of the consumer's own, which is how every application uses the engine.
@Serializable
@SerialName("probe_order")
private data class OrderPayload(
    val orderId: String,
) : PetichPayload()

/** The step that waits for a human — once, and the engine does not re-run it on the way back. */
private class AwaitConfirmation : PetichStep<OrderPayload> {
    var executions: Int = 0
        private set

    override suspend fun execute(
        ctx: PetichStepContext,
        payload: OrderPayload,
    ) {
        executions++
        ctx.suspendFor("AWAIT_CONFIRMATION")
    }

    override suspend fun compensate(
        ctx: PetichStepContext,
        payload: OrderPayload,
    ) = Unit
}

/**
 * The step after it, which is where the event comes from.
 *
 * WHY IT IS A SEPARATE STEP, and this was a finding rather than a design: the first version of this
 * probe emitted the event from the suspending step itself, on the resume pass. It never ran — the
 * engine resumes AFTER the step that suspended, deliberately, so that a saga which has already done
 * the work does not do it twice. The probe's expectation was wrong and the store was right, which is
 * the sort of thing only a run tells you.
 */
private class NotifyShipped : PetichStep<OrderPayload> {
    var executions: Int = 0
        private set

    override suspend fun execute(
        ctx: PetichStepContext,
        payload: OrderPayload,
    ) {
        executions++
        ctx.emit(event("shipped-${payload.orderId}", "order.shipped", payload.orderId))
    }

    override suspend fun compensate(
        ctx: PetichStepContext,
        payload: OrderPayload,
    ) = Unit
}

private fun event(
    id: String,
    type: String,
    orderId: String,
) = object : OutboxEvent {
    override val id: String = id
    override val type: String = type
    override val payload: String = """{"orderId":"$orderId"}"""
}

@OptIn(ExperimentalForeignApi::class)
private fun env(name: String): String? = getenv(name)?.toKString()

fun main() {
    val clock = PetichClock { 0L }
    val petich =
        Petich(
            id = "probe",
            type = "native-consumer-probe",
            status = PetichStatus.DRAFT,
            payload = OrderPayload("order-1"),
        )

    println(
        "petich resolved, compiled and linked for linuxX64: " +
            "${petich.id} phase=${petich.currentPhase} " +
            "terminal=${petich.status.isTerminal()} " +
            "enrichmentTimeoutMs=${PetichPhase.ENRICHMENT.timeoutMs} " +
            "clock=${clock.nowEpochMs()}",
    )

    val url = env("PETICH_PROBE_POSTGRES_URL")
    if (url == null) {
        println("PETICH_PROBE_POSTGRES_URL is not set: the saga was not run, only the link checked")
        return
    }

    runBlocking { runSaga(url) }
}

private suspend fun runSaga(url: String) {
    val db =
        PostgreSQL(
            url = url,
            username = env("PETICH_PROBE_POSTGRES_USER") ?: "petich",
            password = env("PETICH_PROBE_POSTGRES_PASSWORD") ?: "petich",
            options = ConnectionPool.Options.builder().maxConnections(4).build(),
        )

    val json =
        Json {
            serializersModule =
                SerializersModule {
                    polymorphic(PetichPayload::class) { subclass(OrderPayload::class) }
                    polymorphic(EnrichedPayload::class) { subclass(SimpleEnrichedPayload::class) }
                }
        }

    // The application applies the schema, the way its own migration would; the store never does.
    val tables = listOf("probe_petiches", "probe_outbox", "probe_keys", "probe_jobs")
    petichPostgresSchema(tables[0], tables[1], tables[2], tables[3]).forEach {
        db.execute(it).getOrThrow()
    }
    db.execute("TRUNCATE ${tables[0]}, ${tables[1]}").getOrThrow()

    val store = PostgresPetichStore(db, json, PetichClock { 1_700_000_000_000 }, tables[0], tables[1])
    val outbox = PostgresOutboxStore(db, tables[1])
    val await = AwaitConfirmation()
    val notify = NotifyShipped()
    val engine =
        PetichEngine(
            repository = store,
            definitions =
                listOf(
                    petich<OrderPayload>("order") {
                        step("await-confirmation", await)
                        announce("notify-shipped", notify)
                    },
                ),
        )

    val order =
        Petich(
            id = "order-1",
            type = "order",
            status = PetichStatus.PROCESSING,
            payload = OrderPayload("order-1"),
        )

    val first = engine.process(order)
    val suspended = store.findById("order-1")
    println("pass 1: result=${first::class.simpleName} stored=${suspended?.status} version=${suspended?.version}")

    val second = engine.process(suspended ?: error("the saga was not stored at all"))
    val finished = store.findById("order-1")
    val pending: List<OutboxRecord> = outbox.fetchPending()
    println("pass 2: result=${second::class.simpleName} stored=${finished?.status} version=${finished?.version}")
    println("outbox: ${pending.map { it.id to it.type }}")
    println("the waiting step ran ${await.executions} time(s), the notifying step ${notify.executions}")

    check(finished?.status == PetichStatus.COMPLETED) { "the saga did not complete: ${finished?.status}" }
    check(second is PetichResult.Success) { "the second pass was not a success: $second" }
    check(pending.size == 1) { "expected one outbox row, got ${pending.map { it.id }}" }
    check(await.executions == 1) { "the suspended step ran ${await.executions} times, not once" }
    println("SAGA OK: created, suspended, resumed, completed, and its event is in the outbox")
}
