package com.wingman.core.api

import com.wingman.core.model.ErrorCode
import com.wingman.core.model.Problem

/**
 * Result of every [WingmanApi] call. The client never throws for HTTP, network or decoding
 * failures; it returns [ApiResult.Failure] so the UI can show an Error state instead of crashing.
 */
sealed interface ApiResult<out T> {
    data class Success<out T>(val value: T, val idempotentReplayed: Boolean = false) : ApiResult<T>
    data class Failure(val error: ApiError) : ApiResult<Nothing>

    fun getOrNull(): T? = (this as? Success)?.value
    fun errorOrNull(): ApiError? = (this as? Failure)?.error
}

inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(value), idempotentReplayed)
    is ApiResult.Failure -> this
}

/** Typed failure. Branch on [code] (contract `ErrorCode`), never on human-readable text (ADR 0002). */
sealed interface ApiError {
    /** The contract error code, when the server sent a known one. */
    val code: ErrorCode? get() = null

    /** True when retrying the same action with the SAME idempotency key is safe and may succeed. */
    val isRetryable: Boolean

    /** A non-2xx response. [problem] is null when the body was not valid problem+json. */
    data class Http(
        val status: Int,
        val problem: Problem?,
        val retryAfterSeconds: Long? = null,
    ) : ApiError {
        override val code: ErrorCode? get() = problem?.errorCode ?: fallbackCodeFor(status)

        override val isRetryable: Boolean
            get() = status >= 500 || status == 429 || code == ErrorCode.IDEMPOTENCY_KEY_IN_PROGRESS
    }

    /** No HTTP response at all: DNS, connect, timeout, TLS, connection reset. */
    data class Network(val cause: Throwable) : ApiError {
        override val isRetryable: Boolean get() = true
    }

    /** A 2xx response whose body did not match the contract. Not retryable: the server is wrong. */
    data class Decoding(val cause: Throwable) : ApiError {
        override val isRetryable: Boolean get() = false
    }

    /** A precondition checked on the client, e.g. an invalid idempotency key or base URL. */
    data class Client(val message: String) : ApiError {
        override val isRetryable: Boolean get() = false
    }

    companion object {
        /** ADR 0002: an unknown/missing `code` falls back to handling by HTTP status. */
        fun fallbackCodeFor(status: Int): ErrorCode? = when (status) {
            401 -> ErrorCode.UNAUTHENTICATED
            404 -> ErrorCode.NOT_FOUND
            412 -> ErrorCode.PRECONDITION_FAILED
            422 -> ErrorCode.VALIDATION_ERROR
            429 -> ErrorCode.RATE_LIMITED
            503 -> ErrorCode.UPSTREAM_UNAVAILABLE
            in 500..599 -> ErrorCode.INTERNAL_ERROR
            else -> null
        }
    }
}

/** Short, user-safe description. Never includes message bodies or tokens. */
fun ApiError.userMessage(): String = when (this) {
    is ApiError.Network -> "Can't reach Wingman. Check your connection and try again."
    is ApiError.Decoding -> "Wingman sent an unexpected response."
    is ApiError.Client -> message
    is ApiError.Http -> when (code) {
        ErrorCode.UNAUTHENTICATED -> "Please sign in again."
        ErrorCode.NOT_FOUND -> "This item no longer exists."
        ErrorCode.APPROVAL_NOT_PENDING -> "This draft was already handled."
        ErrorCode.AUTOMATION_PAUSED -> "Automation is paused for this contact."
        ErrorCode.CAPABILITY_UNSUPPORTED -> "This platform doesn't support that action."
        ErrorCode.CAPABILITY_UNVERIFIED -> "This platform's capability hasn't been verified yet."
        ErrorCode.CONNECTION_UNAVAILABLE -> "The messaging connection is unavailable."
        ErrorCode.RATE_LIMITED -> "Limit reached. Try again later."
        ErrorCode.IDEMPOTENCY_KEY_IN_PROGRESS -> "Still working on your previous request."
        ErrorCode.UPSTREAM_UNAVAILABLE -> "A service Wingman depends on is unavailable."
        else -> problem?.title ?: "Request failed ($status)."
    }
}
