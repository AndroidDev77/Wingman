package com.wingman.core.ui

import com.wingman.core.api.ApiError
import com.wingman.core.api.ApiResult
import com.wingman.core.api.userMessage
import com.wingman.core.model.ErrorCode

/**
 * The one screen-state shape used by every ViewModel. Pure Kotlin so reducers are JVM-testable.
 * Network and server failures become [Error]; nothing in the UI layer throws.
 */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>

    data class Content<out T>(
        val data: T,
        /** True while a pull-to-refresh / reload runs over existing content. */
        val refreshing: Boolean = false,
        /** A refresh that failed while content was already shown; the old content stays visible. */
        val refreshError: UiError? = null,
    ) : UiState<T>

    data object Empty : UiState<Nothing>

    data class Error(val error: UiError) : UiState<Nothing>
}

/** UI-facing error: a user-safe message plus enough type info to choose an action (retry, sign in). */
data class UiError(
    val message: String,
    val code: ErrorCode? = null,
    val retryable: Boolean = true,
    val isNetwork: Boolean = false,
) {
    companion object {
        fun from(error: ApiError) = UiError(
            message = error.userMessage(),
            code = error.code,
            retryable = error.isRetryable,
            isNetwork = error is ApiError.Network,
        )
    }
}

/** Reduces an API result into a screen state. [isEmpty] decides when a success shows [UiState.Empty]. */
fun <T> ApiResult<T>.toUiState(isEmpty: (T) -> Boolean = { false }): UiState<T> = when (this) {
    is ApiResult.Success -> if (isEmpty(value)) UiState.Empty else UiState.Content(value)
    is ApiResult.Failure -> UiState.Error(UiError.from(error))
}

/**
 * Reduces a refresh result on top of the current state: a failed refresh over existing content
 * keeps the content and attaches [UiState.Content.refreshError] instead of blanking the screen.
 */
fun <T> UiState<T>.reduceRefresh(result: ApiResult<T>, isEmpty: (T) -> Boolean = { false }): UiState<T> {
    val next = result.toUiState(isEmpty)
    return if (this is UiState.Content && next is UiState.Error) {
        copy(refreshing = false, refreshError = next.error)
    } else {
        next
    }
}
