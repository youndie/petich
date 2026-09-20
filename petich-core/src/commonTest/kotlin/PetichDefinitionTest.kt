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
                authorize("confirm", Decides())
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
     * The rule used to be stated "through the verb that takes either kind", and there is no such
     * verb since B-39: `authorize` takes a check, so a member that acts can only be a `step`. What
     * the rule guards is unchanged — a check placed after something has acted would refuse while
     * keeping what ran — and it is now the only way the mistake can still be written.
     */
    @Test
    fun `a check after a member that acted is refused whichever phase it names`() {
        assertFailsWith<IllegalArgumentException> {
            petich<OrderPayload>("order") {
                step("hold-funds", Acts())
                validate("too-late", Decides())
            }
        }

        // And the legitimate order is accepted: every check first, then the members that act.
        val fine =
            petich<OrderPayload>("order") {
                authorize("policy", Decides())
                step("hold-funds", Acts())
            }
        assertEquals(listOf("policy", "hold-funds"), fine.members.map { it.key })
    }

    /**
     * B-40. The mistake this refuses is one that compiled, read top to bottom and ran bottom to top;
     * it cost a debugging pass in `SuspendedTtlTest` during B-33, where an AUTHORIZATION member
     * suspended before the EXECUTION member it was written below had run.
     *
     * Both members that act, so this is not the older check-after-step rule wearing a new message:
     * nothing here is a check, and before B-40 this definition was built without complaint.
     */
    @Test
    fun `a member that runs before the one declared above it is refused`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                petich<OrderPayload>("order") {
                    step("reserve", Acts())
                    announce("notify", Acts())
                    step("charge", Acts())
                }
            }

        assertTrue(failure.message?.contains("`charge`") == true, "the member that goes backwards: ${failure.message}")
        assertTrue(failure.message?.contains("`notify`") == true, "and the one before it: ${failure.message}")
        assertTrue(failure.message?.contains("EXECUTION") == true, "with both phases named: ${failure.message}")
        assertTrue(failure.message?.contains("POST_PROCESSING") == true, "both of them: ${failure.message}")
    }

    /**
     * The other half of the same rule, and the reason it is non-decreasing rather than increasing:
     * a saga is mostly several members of one phase, and their order is the whole point.
     */
    @Test
    fun `two members of one phase stay legal and keep the order they were written in`() {
        val definition =
            petich<OrderPayload>("order") {
                enrich("quote", Decides())
                enrich("fees", Decides())
                validate("limits", Decides())
                step("reserve", Acts())
                step("charge", Acts())
                announce("receipt", Acts())
                announce("ledger", Acts())
            }

        assertEquals(
            listOf("quote", "fees", "limits", "reserve", "charge", "receipt", "ledger"),
            definition.members.map { it.key },
        )
    }

    /**
     * The narrower rule that this one absorbed still says what it used to, because the reason is a
     * cost and not a restatement: a check refusing after an effect keeps what the effect did (B-20).
     * Absorbed, not dropped — two guards over one mistake hide which of them is load-bearing.
     */
    @Test
    fun `the refusal still says what a check placed too late would cost`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                petich<OrderPayload>("order") {
                    step("hold-funds", Acts())
                    validate("too-late", Decides())
                }
            }
        assertTrue(failure.message?.contains("keep what ran") == true, "${failure.message}")
        assertTrue(failure.message?.contains("PetichStep") == true, "and what to do instead: ${failure.message}")
    }

    /** And a definition that goes backwards by more than one phase is refused at the first step back. */
    @Test
    fun `the message names the pair that is wrong and not the whole definition`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                petich<OrderPayload>("order") {
                    validate("limits", Decides())
                    announce("notify", Acts())
                    enrich("quote", Decides())
                    step("charge", Acts())
                }
            }
        assertTrue(failure.message?.contains("`quote`") == true, "${failure.message}")
        assertTrue(failure.message?.contains("`notify`") == true, "${failure.message}")
        assertTrue(failure.message?.contains("`limits`") != true, "not the members that were fine: ${failure.message}")
        assertTrue(failure.message?.contains("`charge`") != true, "nor the one never reached: ${failure.message}")
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
