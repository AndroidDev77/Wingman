package com.wingman.core.api

import com.wingman.core.api.http.HttpWingmanApi
import com.wingman.core.model.ConnectionSettingsUpdate
import com.wingman.core.model.ContractFiles
import com.wingman.core.model.ErrorCode
import com.wingman.core.model.ProfileActionExecution
import com.wingman.core.model.ProfileDecision
import com.wingman.core.model.ProfileDecisionRequest
import com.wingman.core.model.ProfileImportRequest
import com.wingman.core.model.ProfileProvider
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
import java.util.concurrent.TimeUnit

/** Contract 1.3.1 profile operations (WING-031) over MockWebServer, decoded from the contract examples. */
class ProfileEndpointsTest {
    private lateinit var server: MockWebServer
    private lateinit var api: HttpWingmanApi

    private fun example(name: String) = File(ContractFiles.examplesDir(), name).readText()
    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private fun problem(code: Int, wire: String, pointer: String, message: String) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/problem+json")
        .setBody("""{"type":"https://wingman.invalid/problems/$wire","title":"x","status":$code,"code":"$wire","errors":[{"pointer":"$pointer","message":"$message"}]}""")
    private val profileId = "0192f3a0-0000-7000-8000-000000000c01"
    private val actionId = "0192f3a0-0000-7000-8000-000000000c21"
    private val connectionId = "0192f3a0-0000-7000-8000-0000000000a5"
    private val key = IdempotencyKey("decide-3141-0001")

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        api = HttpWingmanApi(server.url("/").toString(), tokenProvider = { "test-jwt" }, okHttpClient = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build())
    }

    @After fun tearDown() = server.shutdown()

    @Test fun importPostsTheExampleBodyWithTheKey() = runTest {
        server.enqueue(json(example("profile.response.json"), 201))
        val body = Json.decodeFromString(ProfileImportRequest.serializer(), example("profile.import.request.json"))
        val r = api.importProfile(body, key) as ApiResult.Success
        assertEquals(ProfileProvider.HINGE, r.value.provider)
        assertFalse(r.value.toString().contains(r.value.bio ?: "never")) // bio redacted in toString
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/profiles", req.path)
        assertEquals(key.value, req.getHeader("Idempotency-Key"))
        assertEquals(Json.parseToJsonElement(example("profile.import.request.json")), Json.parseToJsonElement(req.body.readUtf8()))
    }

    @Test fun reviewQueueSendsIncludeExcludedOnlyWhenTrue() = runTest {
        server.enqueue(json(example("profiles.review_queue.page.json")))
        server.enqueue(json(example("profiles.review_queue.include_excluded.page.json")))
        val first = api.listProfileReviewQueue(PageRequest(limit = 20)).getOrNull()!!
        assertTrue(first.items.isNotEmpty())
        assertEquals("/v1/profiles/review-queue?limit=20", server.takeRequest().path)
        val all = api.listProfileReviewQueue(PageRequest(cursor = "c1"), ProfileQueueFilter(ProfileProvider.HINGE, includeExcluded = true)).getOrNull()!!
        assertEquals("/v1/profiles/review-queue?cursor=c1&provider=hinge&include_excluded=true", server.takeRequest().path)
        val excluded = all.items.last()
        assertEquals(listOf("smoking"), excluded.latestEvaluation!!.dealbreakersHit)
        assertEquals(0.0, excluded.latestEvaluation!!.score!!, 0.0)
        assertEquals(listOf("attributes/smoking"), excluded.latestEvaluation!!.reasons.first().evidence)
    }

    @Test fun decisionPostsBodyAndKeyAndComesBackNotExecutable() = runTest {
        server.enqueue(json(example("profile_action.recorded.response.json"), 201).setHeader("Idempotent-Replayed", "true"))
        val r = api.recordProfileDecision(profileId, ProfileDecisionRequest(ProfileDecision.LIKE, connectionId), key) as ApiResult.Success
        assertTrue(r.idempotentReplayed)
        assertEquals(ProfileActionExecution.NOT_EXECUTABLE, r.value.execution)
        assertNull(r.value.executedAt)
        val req = server.takeRequest()
        assertEquals("/v1/profiles/$profileId/decision", req.path)
        assertEquals(key.value, req.getHeader("Idempotency-Key"))
        assertEquals(Json.parseToJsonElement(example("profile.decision.request.json")).jsonObject.keys, Json.parseToJsonElement(req.body.readUtf8()).jsonObject.keys)
    }

    @Test fun executePostsAnEmptyBodyAndSurfacesTheGateRefusal() = runTest {
        server.enqueue(problem(422, "swipe_execution_disabled", "/connection_id", "swipe capability is not VERIFIED"))
        server.enqueue(json(example("profile_action.queued.response.json")))
        val refused = api.requestProfileActionExecution(actionId, key).errorOrNull() as ApiError.Http
        assertEquals(ErrorCode.SWIPE_EXECUTION_DISABLED, refused.code)
        assertEquals("swipe capability is not VERIFIED", refused.problem!!.errors!!.single().message)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/profile-actions/$actionId/execute", req.path)
        assertEquals("{}", req.body.readUtf8())
        assertEquals(key.value, req.getHeader("Idempotency-Key"))
        val queued = api.requestProfileActionExecution(actionId, key).getOrNull()!!
        assertEquals(ProfileActionExecution.QUEUED, queued.execution) // accepted, not executed
        assertNull(queued.executedAt)
    }

    @Test fun decisionRateLimitIsRetryableWithRetryAfter() = runTest {
        server.enqueue(problem(429, "rate_limited", "/", "slow down").setHeader("Retry-After", "12"))
        val e = api.recordProfileDecision(profileId, ProfileDecisionRequest(ProfileDecision.PASS), key).errorOrNull() as ApiError.Http
        assertEquals(ErrorCode.RATE_LIMITED, e.code)
        assertEquals(12L, e.retryAfterSeconds)
        assertTrue(e.isRetryable)
    }

    @Test fun connectionSettingsIsAPutWithoutAnIdempotencyKey() = runTest {
        server.enqueue(problem(422, "swipe_execution_disabled", "/connection_id", "swipe capability is not VERIFIED"))
        val e = api.putConnectionSettings(connectionId, ConnectionSettingsUpdate(true)).errorOrNull()!!
        assertEquals(ErrorCode.SWIPE_EXECUTION_DISABLED, e.code)
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/v1/connections/$connectionId/settings", req.path)
        assertNull(req.getHeader("Idempotency-Key"))
        assertEquals(Json.parseToJsonElement(example("connection.settings.put.request.json")).jsonObject.keys, Json.parseToJsonElement(req.body.readUtf8()).jsonObject.keys)
    }

    @Test fun aDroppedConnectionIsANetworkErrorNotACrash() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val e = api.recordProfileDecision(profileId, ProfileDecisionRequest(ProfileDecision.LIKE), key).errorOrNull()
        assertTrue(e is ApiError.Network)
    }
}
