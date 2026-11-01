package com.wingman.core.api

import com.wingman.core.api.http.HttpWingmanApi
import com.wingman.core.model.ConnectionCreateRequest
import com.wingman.core.model.ConnectionStatus
import com.wingman.core.model.ContractFiles
import com.wingman.core.model.ErrorCode
import com.wingman.core.model.Verification
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** `createConnection`, `getConnection`, `unpairConnection` (contract 1.2.0) over a local MockWebServer. */
class PairingEndpointsTest {
    private lateinit var server: MockWebServer
    private lateinit var api: HttpWingmanApi

    private fun example(name: String) = File(ContractFiles.examplesDir(), name).readText()
    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private val request get() = WingmanJson.decodeFromString(ConnectionCreateRequest.serializer(), example("connection.create.sms.request.json"))
    private val key = IdempotencyKey("0192f3a0-0000-4000-8000-0000000000b1")
    private val id = "0192f3a0-0000-7000-8000-0000000000a3"

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()
        api = HttpWingmanApi(server.url("/").toString(), tokenProvider = { "test-jwt" }, okHttpClient = client)
    }

    @After fun tearDown() = server.shutdown()

    @Test fun createPostsTheContractBodyWithKeyAndBearer() = runTest {
        server.enqueue(json(example("connection.create.sms.response.json"), 201))
        val r = api.createConnection(request, key) as ApiResult.Success
        assertEquals(id, r.value.id)
        assertEquals("0192f3a0-0000-4000-8000-0000000000aa", r.value.deviceInstallId)
        assertFalse(r.idempotentReplayed)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/connections", req.path)
        assertEquals(key.value, req.getHeader("Idempotency-Key"))
        assertEquals("Bearer test-jwt", req.getHeader("Authorization"))
        // The body is exactly the contract example (no extra fields: the schema has additionalProperties: false).
        assertEquals(Json.parseToJsonElement(example("connection.create.sms.request.json")), Json.parseToJsonElement(req.body.readUtf8()))
    }

    @Test fun alreadyPaired200IsSuccessToo() = runTest {
        server.enqueue(json(example("connection.create.sms.response.json"), 200))
        val r = api.createConnection(request, key) as ApiResult.Success
        assertEquals(id, r.value.id)
    }

    @Test fun replayHeaderIsReported() = runTest {
        server.enqueue(json(example("connection.create.sms.response.json"), 201).setHeader("Idempotent-Replayed", "true"))
        assertTrue((api.createConnection(request, key) as ApiResult.Success).idempotentReplayed)
    }

    @Test fun claimedVerifiedResponseDecodesAsStoredUnverified() = runTest {
        server.enqueue(json(example("connection.create.claimed_verified.response.json"), 201))
        val send = api.createConnection(request, key).getOrNull()!!.capabilities.capabilities.send
        assertEquals(Verification.UNVERIFIED, send.status)
        assertTrue(send.notes!!.startsWith("Claimed VERIFIED by the client"))
    }

    @Test fun mockInProdIs422Typed() = runTest {
        server.enqueue(MockResponse().setResponseCode(422).setHeader("Content-Type", "application/problem+json").setBody(example("problem.connection_mock_in_prod.json")))
        val e = api.createConnection(ConnectionCreateRequest("mock", "Simulator"), key).errorOrNull() as ApiError.Http
        assertEquals(ErrorCode.VALIDATION_ERROR, e.code)
        assertFalse(e.isRetryable)
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).toString()
        assertEquals("""{"platform":"mock","display_name":"Simulator"}""", body)
    }

    @Test fun lostResponseIsARetryableNetworkError() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val e = api.createConnection(request, key).errorOrNull()
        assertTrue(e is ApiError.Network)
        assertTrue(e!!.isRetryable)
    }

    @Test fun getConnectionAndNotFound() = runTest {
        server.enqueue(json(example("connection.create.sms.response.json")))
        assertEquals(ConnectionStatus.CONNECTED, api.getConnection(id).getOrNull()!!.status)
        assertEquals("/v1/connections/$id", server.takeRequest().path)
        server.enqueue(
            MockResponse().setResponseCode(404).setHeader("Content-Type", "application/problem+json")
                .setBody("""{"type":"https://wingman.invalid/problems/not_found","title":"Not found","status":404,"code":"not_found"}"""),
        )
        assertEquals(404, (api.getConnection(id).errorOrNull() as ApiError.Http).status)
    }

    @Test fun unpairPostsAnEmptyObjectWithKey() = runTest {
        server.enqueue(json(example("connection.unpair.response.json")))
        val r = api.unpairConnection(id, key).getOrNull()!!
        assertEquals(ConnectionStatus.DISCONNECTED, r.connection.status)
        assertNotNull(r.connection.unpairedAt)
        assertFalse(r.connection.isPaired)
        assertEquals(Verification.UNSUPPORTED, r.connection.capabilities.capabilities.send.status)
        assertEquals(1, r.canceledMessageCount)
        assertEquals(1, r.canceledInstructionCount)
        assertEquals(1, r.canceledApprovalCount)
        assertEquals(0, r.inFlightMessageCount)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/connections/$id/unpair", req.path)
        assertEquals(key.value, req.getHeader("Idempotency-Key"))
        assertEquals("{}", req.body.readUtf8())
    }
}
