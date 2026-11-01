package com.wingman.core.api

import kotlinx.coroutines.delay

/** Bounded retry policy for one user action. Delays are exponential, capped, and honor `Retry-After`. */
data class RetryPolicy(
    val maxAttempts: Int = 3,
    val initialDelayMs: Long = 500,
    val maxDelayMs: Long = 5_000,
) {
    init {
        require(maxAttempts >= 1)
    }

    fun delayBefore(attempt: Int, error: ApiError): Long {
        val retryAfterMs = (error as? ApiError.Http)?.retryAfterSeconds?.times(1000)
        val backoff = (initialDelayMs shl (attempt - 2).coerceIn(0, 20)).coerceAtMost(maxDelayMs)
        return (retryAfterMs ?: backoff).coerceAtMost(maxDelayMs)
    }

    companion object {
        val NONE = RetryPolicy(maxAttempts = 1)
    }
}

/**
 * Runs one user action with the SAME idempotency key on every attempt (ADR 0004). Retries only
 * [ApiError.isRetryable] failures. On a final answer (success or non-retryable error) the key is
 * released so the next user action gets a fresh one; on exhausted retryable failures the key is
 * KEPT, so a later manual "Retry" still replays rather than duplicating the side effect.
 */
suspend fun <T> IdempotencyKeyRegistry.runAction(
    actionId: String,
    policy: RetryPolicy = RetryPolicy(),
    sleep: suspend (Long) -> Unit = { delay(it) },
    call: suspend (IdempotencyKey) -> ApiResult<T>,
): ApiResult<T> {
    val key = keyFor(actionId)
    var attempt = 1
    while (true) {
        val result = call(key)
        val error = result.errorOrNull()
        if (error == null || !error.isRetryable) {
            complete(actionId)
            return result
        }
        if (attempt >= policy.maxAttempts) return result
        attempt++
        sleep(policy.delayBefore(attempt, error))
    }
}
