package ru.workinprogress.petich.ktor

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
import ru.workinprogress.petich.EnrichedPayload
import ru.workinprogress.petich.InterceptorResult
import ru.workinprogress.petich.Petich
import ru.workinprogress.petich.PetichEngine
import ru.workinprogress.petich.PetichInterceptor
import ru.workinprogress.petich.PetichPayload
import ru.workinprogress.petich.PetichPhase
import ru.workinprogress.petich.PetichRepository
import ru.workinprogress.petich.PetichStatus
import ru.workinprogress.petich.SimpleEnrichedPayload
import java.util.concurrent.ConcurrentHashMap
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

class ProceedInterceptor : PetichInterceptor<TestPayload> {
    override val phase = PetichPhase.EXECUTION
    override val priority = 10

    override fun supports(payload: PetichPayload) = payload is TestPayload

    override suspend fun intercept(
        petich: Petich,
        payload: TestPayload,
    ): InterceptorResult = InterceptorResult.Proceed(TestEnrichedPayload(processed = true))

    override suspend fun compensate(
        petich: Petich,
        payload: TestPayload,
    ) {}
}

class RejectInterceptor(
    private val reason: String,
) : PetichInterceptor<TestPayload> {
    override val phase = PetichPhase.VALIDATION
    override val priority = 10

    override fun supports(payload: PetichPayload) = payload is TestPayload

    override suspend fun intercept(
        petich: Petich,
        payload: TestPayload,
    ): InterceptorResult = InterceptorResult.Reject(reason)

    override suspend fun compensate(
        petich: Petich,
        payload: TestPayload,
    ) {}
}

class SuspendInterceptor : PetichInterceptor<TestPayload> {
    override val phase = PetichPhase.AUTHORIZATION
    override val priority = 10

    override fun supports(payload: PetichPayload) = payload is TestPayload

    override suspend fun intercept(
        petich: Petich,
        payload: TestPayload,
    ): InterceptorResult = InterceptorResult.Suspend("SMS_OTP", TestEnrichedPayload(otpCode = "123456"))

    override suspend fun compensate(
        petich: Petich,
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
    private fun ApplicationTestBuilder.configureApp(
        interceptors: List<PetichInterceptor<*>>,
        repo: TestRepository = TestRepository(),
    ): TestRepository {
        install(ContentNegotiation) { json(testJson) }
        install(PetichFeature) {
            engine = PetichEngine(interceptors, repo)
            repository = repo
        }
        return repo
    }

    @Test
    fun testCreatePetichHappyPath() =
        testApplication {
            configureApp(listOf(ProceedInterceptor()))

            val response =
                client.post("/api/v1/petiches") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"id":"p1","type":"test","payload":{"type":"ru.workinprogress.petich.ktor.TestPayload","data":"hello"}}""",
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
            configureApp(listOf(RejectInterceptor("Bad request data")))

            val response =
                client.post("/api/v1/petiches") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"id":"p2","type":"test","payload":{"type":"ru.workinprogress.petich.ktor.TestPayload","data":"bad"}}""",
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
            configureApp(listOf(SuspendInterceptor(), ProceedInterceptor()))

            val response =
                client.post("/api/v1/petiches") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"id":"p3","type":"test","payload":{"type":"ru.workinprogress.petich.ktor.TestPayload","data":"hello"}}""",
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
            configureApp(listOf(ProceedInterceptor()))

            val response = client.get("/api/v1/petiches/nonexistent")

            assertEquals(HttpStatusCode.NotFound, response.status)
            val body = testJson.decodeFromString<ErrorResponse>(response.bodyAsText())
            assertEquals("Petich not found", body.error)
        }

    @Test
    fun testGetPetichAfterCreate() =
        testApplication {
            configureApp(listOf(ProceedInterceptor()))

            client.post("/api/v1/petiches") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"id":"p4","type":"test","payload":{"type":"ru.workinprogress.petich.ktor.TestPayload","data":"hello"}}""",
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
            configureApp(listOf(ProceedInterceptor()))

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
            configureApp(listOf(ProceedInterceptor()))

            client.post("/api/v1/petiches") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"id":"p5","type":"test","payload":{"type":"ru.workinprogress.petich.ktor.TestPayload","data":"hello"}}""",
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
