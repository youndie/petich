package io.github.youndie.petich

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Members that apply to every saga, and the two things that make them safe to have (B-30).
 *
 * The interceptor model's one genuine virtue was that limits and audit attached without editing
 * every saga. A model where each saga spells its own order loses that — and the obvious replacement
 * brings back the defect the whole stage removes, because a member mixed in at a phase boundary is
 * one that is not written where the saga is read.
 *
 * So the two properties are tested rather than asserted: a global is **visible** in the chain, in
 * the position it runs, and it is **covered by the fingerprint**, so adding one stops the sagas
 * already in flight past that point instead of quietly re-pointing them.
 */
class GlobalMemberTest {
    @Serializable
    private data class OrderPayload(
        val sku: String,
    ) : PetichPayload()

    private class Watches(
        private val name: String,
        private val log: MutableList<String>,
        private val refuse: String? = null,
    ) : PetichCheck<PetichPayload> {
        override suspend fun check(
            ctx: PetichCheckContext,
            payload: PetichPayload,
        ) {
            log.add("global:$name")
            refuse?.let { return ctx.reject(it) }
        }
    }

    private class Acts(
        private val name: String,
        private val log: MutableList<String>,
        private val suspendHere: Boolean = false,
    ) : PetichStep<OrderPayload> {
        override suspend fun execute(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            log.add("do:$name")
            if (suspendHere) ctx.suspendFor("CONFIRM", 5.minutes)
        }

        override suspend fun compensate(
            ctx: PetichStepContext,
            payload: OrderPayload,
        ) {
            log.add("undo:$name")
        }
    }

    private class RowRepository : OutboxAwarePetichRepository {
        var row: Petich? = null

        override suspend fun findById(id: String): Petich? = row?.takeIf { it.id == id }

        override suspend fun saveOrGet(petich: Petich): Petich {
            val existing = row
            if (existing != null && existing.id == petich.id) return existing
            row = petich
            return petich
        }

        override suspend fun update(
            petich: Petich,
            outboxEvents: List<OutboxEvent>,
        ): Boolean {
            val existing = row ?: return false
            if (petich.version != existing.version + 1) return false
            row = petich
            return true
        }
    }

    private fun definition(log: MutableList<String>) =
        petichDefinition<OrderPayload>("order") {
            validate("limits", CheckOf("limits", log))
            step("hold", Acts("hold", log, suspendHere = true))
            step("ship", Acts("ship", log))
        }

    private class CheckOf(
        private val name: String,
        private val log: MutableList<String>,
    ) : PetichCheck<OrderPayload> {
        override suspend fun check(
            ctx: PetichCheckContext,
            payload: OrderPayload,
        ) {
            log.add("check:$name")
        }
    }

    private fun engine(
        repository: PetichRepository,
        log: MutableList<String>,
        vararg globals: PetichGlobal,
    ) = PetichEngine(
        repository = repository,
        clock = PetichClock { 1_000L },
        definitions = listOf(definition(log)),
        globals = globals.toList(),
    )

    private fun row(id: String) =
        Petich(
            id = id,
            type = "order",
            status = PetichStatus.PROCESSING,
            payload = OrderPayload("sku-1"),
        )

    @Test
    fun `a global runs before the members its phase declares`() =
        runBlocking {
            val log = mutableListOf<String>()
            val global = PetichGlobal("fraud", PetichPhase.VALIDATION, Watches("fraud", log))

            engine(RowRepository(), log, global).process(row("p-order"))

            assertEquals(
                listOf("global:fraud", "check:limits", "do:hold"),
                log,
                "a limit that runs after the phase already acted is a limit that arrived too late",
            )
        }

    /**
     * The visibility half. Rendered where the saga is read, not in a table beside it.
     *
     * This also covers a defect found while implementing: `describeChain` resolved the chain by
     * PAYLOAD and a definition is resolved by TYPE, so for every saga on the definition model it
     * described the interceptor chain — the empty one — including inside the message a chain
     * mismatch prints.
     */
    @Test
    fun `a global is written into the chain where the saga is read`() {
        val log = mutableListOf<String>()
        val global = PetichGlobal("fraud", PetichPhase.VALIDATION, Watches("fraud", log))

        val chain = engine(RowRepository(), log, global).describeChain(OrderPayload("sku-1"), "order")

        assertTrue(
            chain.contains("VALIDATION: fraud (global check) -> limits"),
            "inline and in the position it runs, and named as a global: $chain",
        )
        assertTrue(
            chain.contains("EXECUTION: hold -> ship"),
            "and the definition's own members are still described: $chain",
        )
    }

    /**
     * The fingerprint half, and the case that is easy to miss: a global inserted before the current
     * position re-points every saga in flight. The AC asks for it proved across a resume.
     */
    @Test
    fun `a global added before a suspended saga's position stops it rather than moving it`() =
        runBlocking {
            val repository = RowRepository()
            val log = mutableListOf<String>()

            val before = engine(repository, log).process(row("p-order"))
            assertTrue(before is PetichResult.ActionRequired, "expected a suspension: $before")
            val ranBefore = log.toList()

            // The deploy: a global in a phase this saga has already walked through.
            val afterDeploy =
                engine(repository, log, PetichGlobal("fraud", PetichPhase.VALIDATION, Watches("fraud", log)))

            val result = afterDeploy.process(repository.row!!)

            assertTrue(result is PetichResult.SystemFailure, "expected a refusal: $result")
            assertTrue(
                result.details.contains("chain changed"),
                "the refusal has to say what is wrong: ${result.details}",
            )
            assertEquals(ranBefore, log, "and nothing may run while the position is ambiguous")
        }

    @Test
    fun `a global may refuse the saga it was never written into`() =
        runBlocking {
            val log = mutableListOf<String>()
            val global =
                PetichGlobal("fraud", PetichPhase.VALIDATION, Watches("fraud", log, refuse = "flagged"))

            val result = engine(RowRepository(), log, global).process(row("p-order"))

            assertTrue(result is PetichResult.Error, "a global check refuses like any other: $result")
            assertEquals(listOf("global:fraud"), log, "and nothing after it ran")
        }

    @Test
    fun `a global whose key collides with a declared member is refused at construction`() {
        val log = mutableListOf<String>()

        val failure =
            assertFailsWith<IllegalArgumentException> {
                engine(RowRepository(), log, PetichGlobal("limits", PetichPhase.VALIDATION, Watches("x", log)))
            }

        assertTrue(
            failure.message?.contains("limits") == true && failure.message?.contains("order") == true,
            "it has to name the key and the definition it collides with: ${failure.message}",
        )
    }
}
