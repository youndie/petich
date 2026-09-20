package io.github.youndie.petich.ktor

import io.github.youndie.petich.EnrichedPayload
import io.github.youndie.petich.Petich
import io.github.youndie.petich.PetichCheck
import io.github.youndie.petich.PetichCheckContext
import io.github.youndie.petich.PetichEngine
import io.github.youndie.petich.PetichPayload
import io.github.youndie.petich.PetichPhase
import io.github.youndie.petich.PetichRepository
import io.github.youndie.petich.PetichStatus
import io.github.youndie.petich.PetichStep
import io.github.youndie.petich.PetichStepContext
import io.github.youndie.petich.SimpleEnrichedPayload
import io.github.youndie.petich.petich
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Serializable
data class TestPayload(
    val data: String,
) : PetichPayload()

@Serializable
data class TestEnrichedPayload(
    val otpCode: String? = null,
    val otpAttempts: Int = 0,
    val processed: Boolean = false,
) : EnrichedPayload() {
    override fun merge(other: EnrichedPayload): EnrichedPayload =
        if (other is TestEnrichedPayload) {
            copy(
                otpCode = other.otpCode ?: otpCode,
                otpAttempts = other.otpAttempts.takeIf { it > 0 } ?: otpAttempts,
                processed = other.processed || processed,
            )
        } else {
            this
        }
}

class TestRepository : PetichRepository {
    private val petiches = mutableMapOf<String, Petich>()

    override suspend fun findById(id: String): Petich? = petiches[id]

    // Kotlin's, not java.util.Map's: putIfAbsent exists on the JVM only, and the fake is never
    // touched from two threads — testApplication drives one request at a time here.
    override suspend fun saveOrGet(petich: Petich): Petich = petiches.getOrPut(petich.id) { petich }

    override suspend fun update(petich: Petich): Boolean {
        val current = petiches[petich.id] ?: return false
        if (current.version != petich.version - 1) return false
        petiches[petich.id] = petich
        return true
    }
}

class ProceedInterceptor : PetichStep<TestPayload> {
    override suspend fun execute(
        ctx: PetichStepContext,
        payload: TestPayload,
    ) = ctx.enrich(TestEnrichedPayload(processed = true))

    override suspend fun compensate(
        ctx: PetichStepContext,
        payload: TestPayload,
    ) {}
}

class RejectInterceptor(
    private val reason: String,
) : PetichCheck<TestPayload> {
    override suspend fun check(
        ctx: PetichCheckContext,
        payload: TestPayload,
    ) = ctx.reject(reason)
}

class SuspendInterceptor : PetichStep<TestPayload> {
    override suspend fun execute(
        ctx: PetichStepContext,
        payload: TestPayload,
    ) {
        ctx.enrich(TestEnrichedPayload(otpCode = "123456"))
        ctx.suspendFor("SMS_OTP")
    }

    override suspend fun compensate(
        ctx: PetichStepContext,
        payload: TestPayload,
    ) {}
}

private val testSerializersModule =
    SerializersModule {
        polymorphic(PetichPayload::class) {
            subclass(TestPayload::class)
        }
        polymorphic(EnrichedPayload::class) {
            subclass(TestEnrichedPayload::class)
            subclass(SimpleEnrichedPayload::class)
        }
    }

private val testJson =
    Json {
        serializersModule = testSerializersModule
        ignoreUnknownKeys = true
    }

class PetichRoutingTest {
    /**
     * The routes under a saga of one type, whose members are given in the order they run.
     *
     * A list of interceptors used to be enough because each one answered for the payload it knew;
     * a definition is keyed by type, and the type is the one the request bodies below carry.
     */
    private fun ApplicationTestBuilder.configureApp(
        vararg members: Pair<String, Any>,
        repo: TestRepository = TestRepository(),
    ): TestRepository {
        install(ContentNegotiation) { json(testJson) }
        install(PetichFeature) {
            engine =
                PetichEngine(
                    repository = repo,
                    definitions =
                        listOf(
                            petich<TestPayload>("test") {
                                members.forEach { (key, member) ->
                                    when (member) {
                                        is PetichCheck<*> -> {
                                            @Suppress("UNCHECKED_CAST")
                                            validate(key, member as PetichCheck<TestPayload>)
                                        }

                                        is PetichStep<*> -> {
                                            @Suppress("UNCHECKED_CAST")
                                            authorize(key, member as PetichStep<TestPayload>)
                                        }

                                        else -> {
                                            error("not a member: $member")
                                        }
                                    }
                                }
                            },
                        ),
                )
            repository = repo
        }
        return repo
    }

    @Test
    fun testCreatePetichHappyPath() =
        testApplication {
            configureApp("proceeds" to ProceedInterceptor())

            val response =
                client.post("/api/v1/petiches") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"id":"p1","type":"test","payload":{"type":"io.github.youndie.petich.ktor.TestPayload","data":"hello"}}""",
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = testJson.decodeFromString<PetichResponse>(response.bodyAsText())
            assertEquals("p1", body.id)
            assertEquals("COMPLETED", body.status)
        }

    @Test
    fun testCreatePetichValidationReject() =
        testApplication {
            configureApp("refuses" to RejectInterceptor("Bad request data"))

            val response =
                client.post("/api/v1/petiches") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"id":"p2","type":"test","payload":{"type":"io.github.youndie.petich.ktor.TestPayload","data":"bad"}}""",
                    )
                }

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            val body = testJson.decodeFromString<PetichResponse>(response.bodyAsText())
            assertEquals("p2", body.id)
            assertEquals("REJECTED", body.status)
            assertEquals("Bad request data", body.error)
        }

    @Test
    fun testCreatePetichSuspendReturns202() =
        testApplication {
            configureApp("waits" to SuspendInterceptor(), "proceeds" to ProceedInterceptor())

            val response =
                client.post("/api/v1/petiches") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"id":"p3","type":"test","payload":{"type":"io.github.youndie.petich.ktor.TestPayload","data":"hello"}}""",
                    )
                }

            assertEquals(HttpStatusCode.Accepted, response.status)
            val body = testJson.decodeFromString<PetichResponse>(response.bodyAsText())
            assertEquals("p3", body.id)
            assertEquals("PENDING_SIGNATURE", body.status)
            assertEquals("SMS_OTP", body.requiredAction)
        }

    @Test
    fun testGetPetichNotFound() =
        testApplication {
            configureApp("proceeds" to ProceedInterceptor())

            val response = client.get("/api/v1/petiches/nonexistent")

            assertEquals(HttpStatusCode.NotFound, response.status)
            val body = testJson.decodeFromString<ErrorResponse>(response.bodyAsText())
            assertEquals("Petich not found", body.error)
        }

    @Test
    fun testGetPetichAfterCreate() =
        testApplication {
            configureApp("proceeds" to ProceedInterceptor())

            client.post("/api/v1/petiches") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"id":"p4","type":"test","payload":{"type":"io.github.youndie.petich.ktor.TestPayload","data":"hello"}}""",
                )
            }

            val response = client.get("/api/v1/petiches/p4")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = testJson.decodeFromString<PetichResponse>(response.bodyAsText())
            assertEquals("p4", body.id)
            assertEquals("COMPLETED", body.status)
        }

    @Test
    fun testResumeNonexistentPetich() =
        testApplication {
            configureApp("proceeds" to ProceedInterceptor())

            val response =
                client.post("/api/v1/petiches/nonexistent/resume") {
                    contentType(ContentType.Application.Json)
                    setBody("""{}""")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun testResumeTerminalPetichReturnsConflict() =
        testApplication {
            configureApp("proceeds" to ProceedInterceptor())

            client.post("/api/v1/petiches") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"id":"p5","type":"test","payload":{"type":"io.github.youndie.petich.ktor.TestPayload","data":"hello"}}""",
                )
            }

            val response =
                client.post("/api/v1/petiches/p5/resume") {
                    contentType(ContentType.Application.Json)
                    setBody("""{}""")
                }

            assertEquals(HttpStatusCode.Conflict, response.status)
            val body = testJson.decodeFromString<ErrorResponse>(response.bodyAsText())
            assertNotNull(body.details)
            assertTrue(body.details.contains("COMPLETED"), "Should mention terminal status")
        }
}
