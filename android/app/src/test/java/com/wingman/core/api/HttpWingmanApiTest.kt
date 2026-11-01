package com.wingman.core.api

import com.wingman.core.api.http.HttpWingmanApi
import com.wingman.core.model.ApprovalStatus
import com.wingman.core.model.ApproveRequest
import com.wingman.core.model.AutomationActionRequest
import com.wingman.core.model.AutomationMode
import com.wingman.core.model.ContractFiles
import com.wingman.core.model.DeliveryState
import com.wingman.core.model.ErrorCode
import com.wingman.core.model.IntentMode
import com.wingman.core.model.RejectRequest
import com.wingman.core.model.SendMessageRequest
import kotlinx.coroutines.test.runTest
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
import java.util.concurrent.TimeUnit

/** Exercises the real Retrofit/OkHttp client against a local MockWebServer (no backend, no network). */
class HttpWingmanApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: HttpWingmanApi
    private var token: String? = "test-jwt"

    private fun example(name: String) = File(ContractFiles.examplesDir(), name).readText()
    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private fun problem(name: String, code: Int) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/problem+json").setBody(example(name))

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()
        // Base URL without trailing slash: normalizeBaseUrl must fix it.
        api = HttpWingmanApi(server.url("/").toString().trimEnd('/'), tokenProvider = { token }, okHttpClient = client)
    }

    @After fun tearDown() = server.shutdown()

    @Test fun healthIsUnversionedAndDecodes() = runTest {
        server.enqueue(json(example("health.response.json")))
        val r = api.getHealth()
        assertEquals("0.1.0", r.getOrNull()!!.version)
        assertEquals("/health", server.takeRequest().path)
    }

    @Test fun bearerTokenIsSentWhenPresent() = runTest {
        server.enqueue(json(example("me.response.json")))
        api.getMe()
        val req = server.takeRequest()
        assertEquals("/v1/me", req.path)
        assertEquals("Bearer test-jwt", req.getHeader("Authorization"))

        token = null
        server.enqueue(json(example("me.response.json")))
        api.getMe()
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test fun conversationListSendsCursorLimitAndFilters() = runTest {
        server.enqueue(json(example("conversations.page.json")))
        val r = api.listConversations(
            PageRequest(cursor = "abc", limit = 20),
            ConversationFilter(platform = "sms", intent = IntentMode.CASUAL_DATING, automationMode = AutomationMode.ASSISTED),
        )
        assertEquals("eyJrIjoiMjAyNi0xMC0zMVQxODowMTowMFoifQ", r.getOrNull()!!.nextCursor)
        val url = server.takeRequest().requestUrl!!
        assertEquals("/v1/conversations", url.encodedPath)
        assertEquals("abc", url.queryParameter("cursor"))
        assertEquals("20", url.queryParameter("limit"))
        assertEquals("sms", url.queryParameter("platform"))
        assertEquals("casual_dating", url.queryParameter("intent"))
        assertEquals("assisted", url.queryParameter("automation_mode"))
        assertNull(url.queryParameter("stage"))
    }

    @Test fun conversationDetailAndMessages() = runTest {
        val id = "0192f3a0-0000-7000-8000-0000000000b1"
        server.enqueue(json(example("conversation.detail.json")))
        server.enqueue(json(example("messages.page.json")))
        assertEquals(id, api.getConversation(id).getOrNull()!!.id)
        assertEquals(2, api.listMessages(id).getOrNull()!!.items.size)
        assertEquals("/v1/conversations/$id", server.takeRequest().path)
        assertEquals("/v1/conversations/$id/messages", server.takeRequest().path)
    }

    @Test fun approvalsListUsesStatusWireName() = runTest {
        server.enqueue(json(example("approvals.page.json")))
        api.listApprovals(filter = ApprovalFilter(status = ApprovalStatus.PENDING))
        assertEquals("pending", server.takeRequest().requestUrl!!.queryParameter("status"))
    }

    @Test fun approveSendsIdempotencyKeyAndBodyAndReportsReplay() = runTest {
        val key = IdempotencyKey("approve-key-0001")
        server.enqueue(json(example("approval.approve.response.json")).setHeader("Idempotent-Replayed", "true"))
        val r = api.approveApproval("0192f3a0-0000-7000-8000-0000000000f1", ApproveRequest(editedText = "Hi!"), key)
        val success = r as ApiResult.Success
        assertTrue(success.idempotentReplayed)
        assertEquals(DeliveryState.QUEUED, success.value.message!!.deliveryState)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/approvals/0192f3a0-0000-7000-8000-0000000000f1/approve", req.path)
        assertEquals("approve-key-0001", req.getHeader("Idempotency-Key"))
        assertEquals("""{"edited_text":"Hi!"}""", req.body.readUtf8())
    }

    @Test fun rejectAndAutomationPostsCarryKeys() = runTest {
        server.enqueue(json(example("approval.reject.response.json")))
        server.enqueue(json(example("automation.takeover.response.json")))
        server.enqueue(json(example("automation.global_pause.response.json")))
        val k = IdempotencyKey.random()
        assertNull(api.rejectApproval("0192f3a0-0000-7000-8000-0000000000f1", RejectRequest(), k).getOrNull()!!.message)
        api.takeoverContact("0192f3a0-0000-7000-8000-0000000000c1", AutomationActionRequest("busy"), k)
        api.pauseAllAutomation(AutomationActionRequest(), k)
        val reject = server.takeRequest()
        assertEquals("{}", reject.body.readUtf8()) // null optional fields are omitted
        assertEquals(k.value, reject.getHeader("Idempotency-Key"))
        val takeover = server.takeRequest()
        assertEquals("/v1/contacts/0192f3a0-0000-7000-8000-0000000000c1/automation/takeover", takeover.path)
        assertEquals("""{"reason":"busy"}""", takeover.body.readUtf8())
        assertEquals("/v1/automation/pause", server.takeRequest().path)
    }

    @Test fun problemJsonMapsToTypedError() = runTest {
        server.enqueue(problem("problem.capability_unsupported.json", 422))
        val r = api.sendMessage("0192f3a0-0000-7000-8000-0000000000b1", SendMessageRequest("hi"), IdempotencyKey.random())
        val err = r.errorOrNull() as ApiError.Http
        assertEquals(422, err.status)
        assertEquals(ErrorCode.CAPABILITY_UNSUPPORTED, err.code)
        assertFalse(err.isRetryable)
    }

    @Test fun conflictWithRetryAfterIsRetryable() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(409).setHeader("Retry-After", "3")
                .setBody("""{"type":"https://wingman.invalid/problems/idempotency_key_in_progress","title":"In progress","status":409,"code":"idempotency_key_in_progress"}"""),
        )
        val err = api.pauseAllAutomation(AutomationActionRequest(), IdempotencyKey.random()).errorOrNull() as ApiError.Http
        assertEquals(ErrorCode.IDEMPOTENCY_KEY_IN_PROGRESS, err.code)
        assertEquals(3L, err.retryAfterSeconds)
        assertTrue(err.isRetryable)
    }

    @Test fun nonProblemErrorBodyFallsBackToStatus() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("<html>nope</html>"))
        val err = api.getConversation("0192f3a0-0000-7000-8000-0000000000ff").errorOrNull() as ApiError.Http
        assertNull(err.problem)
        assertEquals(ErrorCode.NOT_FOUND, err.code)
        server.enqueue(MockResponse().setResponseCode(502))
        val e502 = api.getMe().errorOrNull() as ApiError.Http
        assertEquals(ErrorCode.INTERNAL_ERROR, e502.code)
        assertTrue(e502.isRetryable)
    }

    @Test fun malformedSuccessBodyIsDecodingError() = runTest {
        server.enqueue(json("""{"id":"x"}"""))
        assertTrue(api.getMe().errorOrNull() is ApiError.Decoding)
        server.enqueue(json("""{"status":"ok","version":"1","env":"dev","time":"2026-11-01T03:46:14+00:00"}"""))
        assertTrue("offset timestamp rejected", api.getHealth().errorOrNull() is ApiError.Decoding)
    }

    @Test fun connectionDropIsNetworkErrorNotCrash() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val err = api.getMe().errorOrNull()
        assertTrue("got $err", err is ApiError.Network)
        assertTrue(err!!.isRetryable)
    }

    @Test fun unreachableHostIsNetworkError() = runTest {
        val port = server.port
        server.shutdown()
        val dead = HttpWingmanApi("http://127.0.0.1:$port", okHttpClient = OkHttpClient.Builder().connectTimeout(1, TimeUnit.SECONDS).build())
        assertTrue(dead.getHealth().errorOrNull() is ApiError.Network)
        server = MockWebServer().also { it.start() } // for tearDown
    }
}
