package com.wingman.core.api

import com.wingman.core.api.http.HttpWingmanApi
import com.wingman.core.model.AgentActionType
import com.wingman.core.model.ApprovalStatus
import com.wingman.core.model.AutomationActionRequest
import com.wingman.core.model.ContactAutomationStatus
import com.wingman.core.model.ContractFiles
import com.wingman.core.model.ErrorCode
import com.wingman.core.model.GlobalAutomationStatus
import com.wingman.core.model.ApproveRequest
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * WING-015 operations over real HTTP (MockWebServer): the agent-actions query, approval and
 * automation reads, and the 409 / 401 / 500 errors the approval queue handles.
 */
class ApprovalAutomationEndpointsTest {
    private lateinit var server: MockWebServer
    private lateinit var api: HttpWingmanApi

    private fun example(name: String) = File(ContractFiles.examplesDir(), name).readText()
    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private fun problem(code: String, status: Int) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/problem+json")
        .setBody("""{"type":"https://wingman.invalid/problems/$code","title":"t","status":$status,"code":"$code"}""")

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        api = HttpWingmanApi(server.url("/").toString(), tokenProvider = { "jwt" }, okHttpClient = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build())
    }

    @After fun tearDown() = server.shutdown()

    @Test fun agentActionsSendsFiltersAndDecodesTheContractExample() = runTest {
        server.enqueue(json(example("agent_actions.page.json")))
        val page = api.listAgentActions(
            PageRequest("c1", 25),
            AgentActionFilter(conversationId = "0192f3a0-0000-7000-8000-0000000000b1", contactId = "0192f3a0-0000-7000-8000-0000000000c1", type = AgentActionType.SEND_QUEUED),
        ).getOrNull()!!
        assertEquals(6, page.items.size)
        assertNull(page.nextCursor)
        val url = server.takeRequest().requestUrl!!
        assertEquals("/v1/agent-actions", url.encodedPath)
        assertEquals("c1", url.queryParameter("cursor"))
        assertEquals("25", url.queryParameter("limit"))
        assertEquals("0192f3a0-0000-7000-8000-0000000000b1", url.queryParameter("conversation_id"))
        assertEquals("0192f3a0-0000-7000-8000-0000000000c1", url.queryParameter("contact_id"))
        assertEquals("send_queued", url.queryParameter("type"))
    }

    @Test fun globalTimelineSendsNoFilters() = runTest {
        server.enqueue(json("""{"items":[],"next_cursor":null}"""))
        api.listAgentActions()
        val url = server.takeRequest().requestUrl!!
        assertEquals(emptySet<String>(), url.queryParameterNames)
    }

    @Test fun approvalAndAutomationReads() = runTest {
        val approvals = example("approvals.page.json")
        server.enqueue(json(approvals))
        server.enqueue(json("""{"status":"paused","changed_at":"2026-10-31T18:00:00Z"}"""))
        server.enqueue(json(example("automation.takeover.response.json")))
        val first = api.listApprovals(PageRequest(limit = 50), ApprovalFilter(status = ApprovalStatus.PENDING)).getOrNull()!!.items.first()
        assertTrue(first.reasons.isNotEmpty())
        assertEquals(GlobalAutomationStatus.PAUSED, api.getGlobalAutomationState().getOrNull()!!.status)
        assertEquals(ContactAutomationStatus.TAKEN_OVER, api.getContactAutomationState("0192f3a0-0000-7000-8000-0000000000c1").getOrNull()!!.status)
        assertEquals("50", server.takeRequest().requestUrl!!.queryParameter("limit"))
        assertEquals("/v1/automation", server.takeRequest().path)
        assertEquals("/v1/contacts/0192f3a0-0000-7000-8000-0000000000c1/automation", server.takeRequest().path)
    }

    @Test fun approvalNotPendingIs409NotRetryable() = runTest {
        server.enqueue(problem("approval_not_pending", 409))
        val err = api.approveApproval("0192f3a0-0000-7000-8000-0000000000f1", ApproveRequest(), IdempotencyKey.random()).errorOrNull() as ApiError.Http
        assertEquals(409, err.status)
        assertEquals(ErrorCode.APPROVAL_NOT_PENDING, err.code)
        assertFalse(err.isRetryable) // a retry can't change the answer: the client re-reads instead
    }

    @Test fun unauthenticatedAndServerErrors() = runTest {
        server.enqueue(problem("unauthenticated", 401))
        server.enqueue(problem("internal_error", 500))
        val e401 = api.takeoverContact("0192f3a0-0000-7000-8000-0000000000c1", AutomationActionRequest(), IdempotencyKey.random()).errorOrNull()!!
        assertEquals(ErrorCode.UNAUTHENTICATED, e401.code)
        assertFalse(e401.isRetryable)
        val e500 = api.resumeContactAutomation("0192f3a0-0000-7000-8000-0000000000c1", AutomationActionRequest(), IdempotencyKey.random()).errorOrNull()!!
        assertEquals(ErrorCode.INTERNAL_ERROR, e500.code)
        assertTrue(e500.isRetryable)
        assertEquals("/v1/contacts/0192f3a0-0000-7000-8000-0000000000c1/automation/takeover", server.takeRequest().path)
        assertEquals("/v1/contacts/0192f3a0-0000-7000-8000-0000000000c1/automation/resume", server.takeRequest().path)
    }

    @Test fun pauseContactAndResumeAllCarryKeysAndBodies() = runTest {
        server.enqueue(json(example("automation.takeover.response.json").replace("\"taken_over\"", "\"paused\"")))
        server.enqueue(json(example("automation.global_pause.response.json").replace("\"paused\"", "\"active\"")))
        val k1 = IdempotencyKey("pause-key-00001")
        val k2 = IdempotencyKey("resume-all-0001")
        assertEquals(ContactAutomationStatus.PAUSED, api.pauseContactAutomation("0192f3a0-0000-7000-8000-0000000000c1", AutomationActionRequest(), k1).getOrNull()!!.status)
        assertEquals(GlobalAutomationStatus.ACTIVE, api.resumeAllAutomation(AutomationActionRequest(), k2).getOrNull()!!.status)
        val p = server.takeRequest()
        assertEquals("/v1/contacts/0192f3a0-0000-7000-8000-0000000000c1/automation/pause", p.path)
        assertEquals("pause-key-00001", p.getHeader("Idempotency-Key"))
        assertEquals("{}", p.body.readUtf8())
        val r = server.takeRequest()
        assertEquals("/v1/automation/resume", r.path)
        assertEquals("resume-all-0001", r.getHeader("Idempotency-Key"))
    }
}
