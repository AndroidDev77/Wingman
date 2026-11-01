package com.wingman.core.api

import com.wingman.core.api.mock.MockWingmanApi
import com.wingman.core.model.ErrorCode
import com.wingman.core.model.Problem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class IdempotencyAndRetryTest {

    @Test fun keyFormatFollowsContract() {
        IdempotencyKey("abcd1234")
        IdempotencyKey("send:0192f3a0-0000-7000-8000-0000000000d3")
        IdempotencyKey("a".repeat(128))
        for (bad in listOf("short", "a".repeat(129), "has space1", "slash/in/it", "")) {
            assertThrows(bad, IllegalArgumentException::class.java) { IdempotencyKey(bad) }
        }
        val r = IdempotencyKey.random()
        assertTrue(IdempotencyKey.PATTERN.matches(r.value))
        assertNotEquals(r, IdempotencyKey.random())
    }

    @Test fun registryReusesKeyUntilComplete() {
        val reg = IdempotencyKeyRegistry()
        val k1 = reg.keyFor("approve:1")
        assertEquals(k1, reg.keyFor("approve:1"))
        assertNotEquals(k1, reg.keyFor("approve:2"))
        reg.complete("approve:1")
        assertNotEquals(k1, reg.keyFor("approve:1"))
    }

    private fun http(status: Int, code: ErrorCode? = null, retryAfter: Long? = null) = ApiResult.Failure(
        ApiError.Http(status, code?.let { Problem("t", "t", status, it.wire) }, retryAfter),
    )

    @Test fun retriesUseTheSameKeyAndHonorRetryAfter() = runTest {
        val reg = IdempotencyKeyRegistry()
        val seenKeys = mutableListOf<IdempotencyKey>()
        val sleeps = mutableListOf<Long>()
        val responses = ArrayDeque<ApiResult<String>>(
            listOf(
                ApiResult.Failure(ApiError.Network(IOException("reset"))),
                http(409, ErrorCode.IDEMPOTENCY_KEY_IN_PROGRESS, retryAfter = 2),
                ApiResult.Success("ok"),
            ),
        )
        val r = reg.runAction("send:1", RetryPolicy(maxAttempts = 3, initialDelayMs = 100, maxDelayMs = 5000), sleep = { sleeps += it }) { key ->
            seenKeys += key
            responses.removeFirst()
        }
        assertEquals("ok", r.getOrNull())
        assertEquals(3, seenKeys.size)
        assertEquals(1, seenKeys.toSet().size)
        assertEquals(listOf(100L, 2000L), sleeps)
        assertTrue("key released after final answer", reg.pending().isEmpty())
    }

    @Test fun nonRetryableErrorIsNotRetriedAndReleasesKey() = runTest {
        val reg = IdempotencyKeyRegistry()
        var calls = 0
        val r = reg.runAction<String>("approve:1", sleep = {}) { calls++; http(409, ErrorCode.APPROVAL_NOT_PENDING) }
        assertEquals(1, calls)
        assertEquals(ErrorCode.APPROVAL_NOT_PENDING, r.errorOrNull()!!.code)
        assertTrue(reg.pending().isEmpty())
    }

    @Test fun exhaustedRetryableFailureKeepsKeyForManualRetry() = runTest {
        val reg = IdempotencyKeyRegistry()
        val seen = mutableListOf<IdempotencyKey>()
        reg.runAction<String>("send:2", RetryPolicy(maxAttempts = 2, initialDelayMs = 1), sleep = {}) { seen += it; http(503) }
        assertEquals(setOf("send:2"), reg.pending())
        // The user taps Retry later: same action, same key, so the server can replay instead of sending twice.
        reg.runAction("send:2", RetryPolicy.NONE, sleep = {}) { seen += it; ApiResult.Success("ok") }
        assertEquals(1, seen.toSet().size)
        assertEquals(3, seen.size)
    }

    @Test fun backoffIsExponentialAndCapped() {
        val p = RetryPolicy(maxAttempts = 10, initialDelayMs = 500, maxDelayMs = 3000)
        val net = ApiError.Network(IOException())
        assertEquals(listOf(500L, 1000L, 2000L, 3000L, 3000L), (2..6).map { p.delayBefore(it, net) })
        assertEquals(3000L, p.delayBefore(2, ApiError.Http(429, null, retryAfterSeconds = 60)))
    }

    @Test fun retryableClassification() {
        assertTrue(ApiError.Network(IOException()).isRetryable)
        assertTrue(ApiError.Http(500, null).isRetryable)
        assertTrue(ApiError.Http(429, null).isRetryable)
        assertTrue(!ApiError.Http(422, null).isRetryable)
        assertTrue(!ApiError.Http(404, null).isRetryable)
        assertTrue(!ApiError.Decoding(RuntimeException()).isRetryable)
    }

    @Test fun fallbackCodesByStatus() {
        assertEquals(ErrorCode.UNAUTHENTICATED, ApiError.Http(401, null).code)
        assertEquals(ErrorCode.NOT_FOUND, ApiError.Http(404, Problem("t", "t", 404, "brand_new_code")).code)
        assertEquals(ErrorCode.UPSTREAM_UNAVAILABLE, ApiError.Http(503, null).code)
        assertEquals(null, ApiError.Http(418, null).code)
    }

    @Test fun userMessagesNeverEmpty() {
        val errors = ErrorCode.entries.map { ApiError.Http(400, Problem("t", "Title", 400, it.wire)) } +
            listOf(ApiError.Network(IOException()), ApiError.Decoding(RuntimeException()), ApiError.Client("x"), ApiError.Http(418, null))
        errors.forEach { assertTrue(it.userMessage().isNotBlank()) }
    }

    @Test fun pageRequestValidation() {
        PageRequest(null, 1); PageRequest("c", 100)
        assertThrows(IllegalArgumentException::class.java) { PageRequest(limit = 0) }
        assertThrows(IllegalArgumentException::class.java) { PageRequest(limit = 101) }
        assertThrows(IllegalArgumentException::class.java) { PageRequest(cursor = "") }
        assertThrows(IllegalArgumentException::class.java) { PageRequest(cursor = "x".repeat(513)) }
    }

    @Test fun mockReplayWorksThroughRunAction() = runTest {
        val api = MockWingmanApi()
        val reg = IdempotencyKeyRegistry()
        // First attempt "fails on the network" AFTER being injected; second attempt reuses the key.
        api.failNextWith()
        val r = reg.runAction("pause", RetryPolicy(maxAttempts = 2, initialDelayMs = 1), sleep = {}) {
            api.pauseAllAutomation(com.wingman.core.model.AutomationActionRequest(), it)
        }
        assertTrue(r is ApiResult.Success)
        assertEquals(listOf("pauseAllAutomation", "pauseAllAutomation"), api.calls)
    }
}
