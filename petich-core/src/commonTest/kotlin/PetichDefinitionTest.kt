package io.github.youndie.petich

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * B-28: the rules of the model are the builder's refusals, and they are tested as refusals.
 *
 * A definition that compiles is not the acceptance — the acceptance is that the three things the
 * model forbids cannot be written down.
 */
class PetichDefinitionTest {
    data class OrderPayload(
        val sku: String,
    ) : PetichPayload()

    private class Acts : PetichStep<OrderPayload> {
        override suspend fun execute(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) = Unit

        override suspend fun compensate(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) = Unit
    }

    /** No `compensate` to write, which is the point of the type and 44% of the old model's bulk. */
    private class Decides : PetichCheck<OrderPayload> {
        override suspend fun check(
            ctx: PetichCheckContext,
            payload: OrderPayload,
        ) = Unit
    }

    @Test
    fun `the order of a saga is the order it is written in`() {
        val definition =
            petich<OrderPayload>("order") {
                validate("limits", Decides())
                authorize("confirm", Acts())
                step("reserve-stock", Acts())
                step("charge", Acts())
                announce("notify", Acts())
            }

        assertEquals(
            listOf("limits", "confirm", "reserve-stock", "charge", "notify"),
            definition.members.map { it.key },
            "nothing sorts the list and nothing filters it - the order is the list's",
        )
        assertEquals("order", definition.type)
    }

    @Test
    fun `the definition reads back as the chain a person would otherwise reconstruct`() {
        val dump =
            petich<OrderPayload>("order") {
                validate("limits", Decides())
                step("reserve-stock", Acts())
                step("charge", Acts())
            }.describeChain()

        assertTrue(dump.contains("VALIDATION: limits (check)"), "a check is named as one: $dump")
        assertTrue(
            dump.contains("EXECUTION: reserve-stock -> charge"),
            "and the steps in the order they run: $dump",
        )
        assertTrue(dump.contains("ENRICHMENT: -"), "including the phases nothing sits in: $dump")
    }

    @Test
    fun `a check cannot be placed after a step that has already acted`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                petich<OrderPayload>("order") {
                    step("reserve-stock", Acts())
                    validate("too-late", Decides())
                }
            }

        assertTrue(
            failure.message?.contains("too-late") == true && failure.message?.contains("keep what ran") == true,
            "the refusal has to name the member and say what it would cost: ${failure.message}",
        )
    }

    /**
     * The same rule reached through the verb that takes both kinds. `authorize` accepting a step is
     * D3 — konekt holds money and suspends in one member — and a check after that step is still a
     * refusal that could keep what the step did.
     */
    @Test
    fun `the rule holds through the verb that takes either kind`() {
        assertFailsWith<IllegalArgumentException> {
            petich<OrderPayload>("order") {
                authorize("hold-funds", Acts())
                validate("too-late", Decides())
            }
        }

        // And the legitimate order is accepted: a check, then a step that acts, in one phase.
        val fine =
            petich<OrderPayload>("order") {
                authorize("policy", Decides())
                authorize("hold-funds", Acts())
            }
        assertEquals(listOf("policy", "hold-funds"), fine.members.map { it.key })
    }

    @Test
    fun `a key is declared once because it is the member's identity in the row`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                petich<OrderPayload>("order") {
                    step("charge", Acts())
                    step("charge", Acts())
                }
            }
        assertTrue(failure.message?.contains("twice") == true, "${failure.message}")

        assertFailsWith<IllegalArgumentException> {
            petich<OrderPayload>("order") { step("  ", Acts()) }
        }
    }

    @Test
    fun `a definition with no members is not a definition`() {
        assertFailsWith<IllegalArgumentException> { petich<OrderPayload>("order") { } }
        assertFailsWith<IllegalArgumentException> { petich<OrderPayload>("") { step("s", Acts()) } }
    }
}
