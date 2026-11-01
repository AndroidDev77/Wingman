package com.wingman.core.api

import com.wingman.core.api.http.HttpWingmanApi
import com.wingman.core.model.ConnectorEventIngestOutcome
import com.wingman.core.model.ContractFiles
import com.wingman.core.model.ErrorCode
import com.wingman.core.model.OutboxAuthorizationOrigin
import com.wingman.core.model.OutboxClaimRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

/** `ingestConnectorEvent` and `claimOutboxInstructions` (contract v1.1.0) over a local MockWebServer. */
class DeviceConnectorEndpointsTest {
    private lateinit var server: MockWebServer
    private lateinit var api: HttpWingmanApi
    private var token: String? = "test-jwt"

    private fun example(name: String) = File(ContractFiles.examplesDir(), name).readText()
    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private fun problem(name: String, code: Int) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/problem+json").setBody(example(name))

    private val envelope get() = Json.parseToJsonElement(example("event.sms_send_acknowledged.json")).jsonObject
    private val eventKey = IdempotencyKey("0192f3a0-0000-7000-8000-000000000311")
    private val connection = "0192f3a0-0000-7000-8000-0000000000a1"

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()
        api = HttpWingmanApi(server.url("/").toString(), tokenProvider = { token }, okHttpClient = client)
    }

    @After fun tearDown() = server.shutdown()

    @Test fun ingestPostsTheEnvelopeWithEventIdKeyAndBearer() = runTest {
        server.enqueue(json(example("ingest.send_acknowledged.response.json"), 202))
        val r = api.ingestConnectorEvent(connection, envelope, eventKey)
        val v = r.getOrNull()!!
        assertEquals(ConnectorEventIngestOutcome.ACCEPTED, v.outcome)
        assertEquals("0192f3a0-0000-7000-8000-0000000000d6", v.messageId)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/connections/$connection/events", req.path)
        assertEquals(eventKey.value, req.getHeader("Idempotency-Key"))
        assertEquals("Bearer test-jwt", req.getHeader("Authorization"))
        assertEquals(envelope, Json.parseToJsonElement(req.body.readUtf8()))
    }

    @Test fun ingestDuplicateIs200Success() = runTest {
        server.enqueue(json(example("ingest.duplicate_event.response.json")).setHeader("Idempotent-Replayed", "true"))
        val r = api.ingestConnectorEvent(connection, envelope, eventKey) as ApiResult.Success
        assertEquals(ConnectorEventIngestOutcome.DUPLICATE_EVENT, r.value.outcome)
        assertTrue(r.idempotentReplayed)
    }

    @Test fun ingestRefusesAKeyThatIsNotTheEventIdWithoutARequest() = runTest {
        val r = api.ingestConnectorEvent(connection, envelope, IdempotencyKey("some-other-key"))
        assertTrue(r.errorOrNull() is ApiError.Client)
        assertEquals(0, server.requestCount)
    }

    @Test fun ingest422And429AreTypedErrors() = runTest {
        server.enqueue(problem("problem.ingest_unmatched_send_result.json", 422))
        val e = api.ingestConnectorEvent(connection, envelope, eventKey).errorOrNull() as ApiError.Http
        assertEquals(422, e.status)
        assertEquals(ErrorCode.VALIDATION_ERROR, e.code)
        assertEquals("/payload/idempotency_key", e.problem!!.errors!!.single().pointer)

        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "17"))
        val rl = api.ingestConnectorEvent(connection, envelope, eventKey).errorOrNull() as ApiError.Http
        assertEquals(17L, rl.retryAfterSeconds)
        assertTrue(rl.isRetryable)
    }

    @Test fun ingestWithoutTokenSendsNoAuthorization() = runTest {
        token = null
        server.enqueue(MockResponse().setResponseCode(401))
        val e = api.ingestConnectorEvent(connection, envelope, eventKey).errorOrNull() as ApiError.Http
        assertEquals(401, e.status)
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test fun claimPostsTheRequestAndDecodesLeasedInstructions() = runTest {
        server.enqueue(json(example("outbox.claim.response.json")))
        val body = OutboxClaimRequest("6f1c2a7e-4b8d-4e2a-9c1f-3d5e7a9b0c12", 5)
        val r = api.claimOutboxInstructions(connection, body, IdempotencyKey("claim-0001")) as ApiResult.Success
        assertFalse(r.idempotentReplayed)
        val i = r.value.instructions.single()
        assertEquals("send:0192f3a0-0000-7000-8000-0000000000d6:1", i.idempotencyKey)
        assertEquals(OutboxAuthorizationOrigin.APPROVED_DRAFT, i.authorization.origin)
        assertEquals(Instant.parse("2026-10-31T19:07:00.512301Z"), i.leaseExpiresAt)
        assertEquals(Instant.parse("2026-10-31T19:05:00.512301Z"), r.value.serverTime)
        val req = server.takeRequest()
        assertEquals("/v1/connections/$connection/outbox/claim", req.path)
        assertEquals("claim-0001", req.getHeader("Idempotency-Key"))
        assertEquals(Json.parseToJsonElement(example("outbox.claim.request.json")), Json.parseToJsonElement(req.body.readUtf8()))
        // The redacting toString never leaks the text or the destination.
        assertFalse(i.toString().contains("Tonight"))
        assertFalse(i.toString().contains("+1555"))
    }

    @Test fun claimOmitsMaxInstructionsWhenNull() = runTest {
        server.enqueue(json(example("outbox.claim.empty.response.json")))
        val r = api.claimOutboxInstructions(connection, OutboxClaimRequest("6f1c2a7e-4b8d-4e2a-9c1f-3d5e7a9b0c12"), IdempotencyKey("claim-0002"))
        assertEquals(0, r.getOrNull()!!.instructions.size)
        assertEquals("""{"device_install_id":"6f1c2a7e-4b8d-4e2a-9c1f-3d5e7a9b0c12"}""", server.takeRequest().body.readUtf8())
    }

    @Test fun claimReplayIsFlaggedAndLostResponseIsANetworkError() = runTest {
        server.enqueue(json(example("outbox.claim.response.json")).setHeader("Idempotent-Replayed", "true"))
        val replay = api.claimOutboxInstructions(connection, OutboxClaimRequest("6f1c2a7e-4b8d-4e2a-9c1f-3d5e7a9b0c12"), IdempotencyKey("claim-0003"))
        assertTrue((replay as ApiResult.Success).idempotentReplayed)

        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val lost = api.claimOutboxInstructions(connection, OutboxClaimRequest("6f1c2a7e-4b8d-4e2a-9c1f-3d5e7a9b0c12"), IdempotencyKey("claim-0003"))
        assertTrue(lost.errorOrNull() is ApiError.Network)
    }

    @Test fun claim409InProgressCarriesRetryAfter() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(409).setHeader("Retry-After", "2").setHeader("Content-Type", "application/problem+json")
                .setBody("""{"type":"https://wingman.invalid/problems/idempotency_key_in_progress","title":"In progress","status":409,"code":"idempotency_key_in_progress"}"""),
        )
        val e = api.claimOutboxInstructions(connection, OutboxClaimRequest("6f1c2a7e-4b8d-4e2a-9c1f-3d5e7a9b0c12"), IdempotencyKey("claim-0004")).errorOrNull() as ApiError.Http
        assertEquals(ErrorCode.IDEMPOTENCY_KEY_IN_PROGRESS, e.code)
        assertEquals(2L, e.retryAfterSeconds)
    }
}
