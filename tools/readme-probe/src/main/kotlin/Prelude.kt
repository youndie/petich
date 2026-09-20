@file:Suppress("unused", "UNUSED_PARAMETER")

package readme

import io.github.youndie.petich.OutboxEvent
import io.github.youndie.petich.Petich
import io.github.youndie.petich.PetichPayload
import io.github.youndie.petich.PetichStepContext
import kotlinx.serialization.Serializable

/**
 * What the page's examples lean on and the library does not provide.
 *
 * **Declarations only, deliberately.** The failure this probe exists to avoid is not "the examples
 * do not compile" — it is "the examples compile against something that is not petich". A stub with a
 * body invites the harness to grow an API of its own, and the day it did, the page would be checked
 * against a fiction. Everything here is a signature; nothing here is petich's.
 */
@Serializable
data class OrderPayload(
    val sku: String,
    val quantity: Int,
    val rideId: String = "",
    val paymentMethodId: String = "",
    val amount: Long = 0,
) : PetichPayload()

interface StockRepository {
    suspend fun reserve(
        sku: String,
        quantity: Int,
    )

    suspend fun reserve(
        key: String,
        sku: String,
        quantity: Int,
    )

    suspend fun release(
        sku: String,
        quantity: Int,
    )

    suspend fun releaseByKey(key: String)

    suspend fun has(
        sku: String,
        quantity: Int,
    ): Boolean
}

interface PaymentGateway {
    val refunded: List<String>

    suspend fun hold(
        key: String,
        paymentMethodId: String,
        amount: Long,
    ): Held

    suspend fun release(id: String)
}

class Held(
    val id: String,
)

interface OrderEvents {
    fun confirmed(
        sagaId: String,
        payload: OrderPayload,
    ): OutboxEvent
}

interface OfferBoard {
    suspend fun offer(
        key: String,
        rideId: String,
        driverId: String,
    )
}

interface SpendingLimits

class WithinLimits(
    limits: SpendingLimits,
) : io.github.youndie.petich.PetichCheck<OrderPayload> {
    override suspend fun check(
        ctx: io.github.youndie.petich.PetichCheckContext,
        payload: OrderPayload,
    ) = Unit
}

class HoldFunds(
    payments: PaymentGateway,
) : io.github.youndie.petich.PetichStep<OrderPayload> {
    override suspend fun execute(
        ctx: PetichStepContext,
        payload: OrderPayload,
    ) = Unit

    override suspend fun compensate(
        ctx: PetichStepContext,
        payload: OrderPayload,
    ) = Unit
}

class CaptureStep(
    payments: PaymentGateway,
) : io.github.youndie.petich.PetichStep<OrderPayload> {
    override suspend fun execute(
        ctx: PetichStepContext,
        payload: OrderPayload,
    ) = Unit

    override suspend fun compensate(
        ctx: PetichStepContext,
        payload: OrderPayload,
    ) = Unit
}

/** The names the page's statement-shaped examples use, in one scope so each block can be spliced. */
internal val stock: StockRepository get() = error("declaration only")
internal val payments: PaymentGateway get() = error("declaration only")
internal val events: OrderEvents get() = error("declaration only")
internal val limits: SpendingLimits get() = error("declaration only")
internal val board: OfferBoard get() = error("declaration only")
internal val saga: Petich get() = error("declaration only")
internal val payload: OrderPayload get() = error("declaration only")
internal val driverId: String get() = error("declaration only")

/**
 * What a `members` block is spliced into.
 *
 * **Open rather than abstract**, so a block that shows only one half of a step — the page does that
 * twice — still has to match the signature it overrides. An interface here would fail those blocks
 * for a reason that is about this harness rather than about the page, which is the one kind of red
 * this check must never produce.
 */
open class ReadmeStep : io.github.youndie.petich.PetichStep<OrderPayload> {
    override suspend fun execute(
        ctx: PetichStepContext,
        payload: OrderPayload,
    ) = Unit

    override suspend fun compensate(
        ctx: PetichStepContext,
        payload: OrderPayload,
    ) = Unit
}
