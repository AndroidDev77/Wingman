package com.wingman.core.ui

import com.wingman.core.api.ApiResult
import com.wingman.core.api.IdempotencyKey
import com.wingman.core.api.IdempotencyKeyRegistry
import com.wingman.core.api.PageRequest
import com.wingman.core.api.RetryPolicy
import com.wingman.core.api.runAction
import com.wingman.core.model.Page
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * Pure-Kotlin state holders (kotlinx.coroutines only). An androidx ViewModel owns one and passes
 * `viewModelScope`; tests pass a TestScope. Feature tickets compose these instead of re-inventing
 * loading/error handling.
 */

/** Loads one value into a [StateFlow] of [UiState]. A second [load] while one is running is ignored. */
class LoadableState<T>(
    private val scope: CoroutineScope,
    private val isEmpty: (T) -> Boolean = { false },
    private val loader: suspend () -> ApiResult<T>,
) {
    private val _state = MutableStateFlow<UiState<T>>(UiState.Loading)
    val state: StateFlow<UiState<T>> = _state.asStateFlow()
    private var job: Job? = null

    /** Initial load, or retry from Error/Empty. Shows [UiState.Loading]. */
    fun load() {
        if (job?.isActive == true) return
        _state.value = UiState.Loading
        job = scope.launch { _state.value = loader().toUiState(isEmpty) }
    }

    /** Reload keeping current content visible; a failure becomes [UiState.Content.refreshError]. */
    fun refresh() {
        if (job?.isActive == true) return
        val current = _state.value
        if (current !is UiState.Content) return load()
        _state.value = current.copy(refreshing = true, refreshError = null)
        job = scope.launch { _state.value = _state.value.reduceRefresh(loader(), isEmpty) }
    }

    /** Replace content after a successful mutation (the server response is the source of truth). */
    fun set(value: T) {
        _state.value = if (isEmpty(value)) UiState.Empty else UiState.Content(value)
    }
}

/** Accumulated cursor-paginated list (ADR 0003). */
data class PagedList<T>(
    val items: List<T> = emptyList(),
    val nextCursor: String? = null,
    val endReached: Boolean = false,
    val loadingMore: Boolean = false,
    /** Failure while loading a later page; the items already loaded stay visible. */
    val loadMoreError: UiError? = null,
)

/**
 * Cursor pager: first page via [load] / [refresh] (restart without a cursor), next pages via
 * [loadMore]. Cursors are opaque and only ever come from the server's `next_cursor`.
 */
class CursorPager<T>(
    private val scope: CoroutineScope,
    private val pageSize: Int? = null,
    private val fetch: suspend (PageRequest) -> ApiResult<Page<T>>,
) {
    private val _state = MutableStateFlow<UiState<PagedList<T>>>(UiState.Loading)
    val state: StateFlow<UiState<PagedList<T>>> = _state.asStateFlow()
    private var job: Job? = null

    fun load() {
        job?.cancel()
        _state.value = UiState.Loading
        job = scope.launch {
            _state.value = fetch(PageRequest(null, pageSize)).toUiStateFirst()
        }
    }

    fun refresh() = load()

    fun loadMore() {
        val current = (_state.value as? UiState.Content)?.data ?: return
        if (current.endReached || current.loadingMore || job?.isActive == true) return
        val cursor = current.nextCursor ?: return
        _state.value = UiState.Content(current.copy(loadingMore = true, loadMoreError = null))
        job = scope.launch {
            _state.value = when (val r = fetch(PageRequest(cursor, pageSize))) {
                is ApiResult.Success -> UiState.Content(
                    current.copy(
                        items = current.items + r.value.items,
                        nextCursor = r.value.nextCursor,
                        endReached = r.value.nextCursor == null,
                        loadingMore = false,
                        loadMoreError = null,
                    ),
                )
                is ApiResult.Failure -> UiState.Content(current.copy(loadingMore = false, loadMoreError = UiError.from(r.error)))
            }
        }
    }

    private fun ApiResult<Page<T>>.toUiStateFirst(): UiState<PagedList<T>> = when (this) {
        is ApiResult.Success ->
            if (value.items.isEmpty() && value.nextCursor == null) UiState.Empty
            else UiState.Content(PagedList(value.items, value.nextCursor, endReached = value.nextCursor == null))
        is ApiResult.Failure -> UiState.Error(UiError.from(error))
    }
}

/** State of one in-flight user action (approve, reject, send, pause, takeover...). */
sealed interface ActionState<out T> {
    data object Idle : ActionState<Nothing>
    data object InFlight : ActionState<Nothing>
    data class Done<out T>(val value: T, val replayed: Boolean) : ActionState<T>
    data class Failed(val error: UiError) : ActionState<Nothing>
}

/**
 * Runs mutating user actions safely:
 *  - one [IdempotencyKey] per action ID, reused across automatic and manual retries (ADR 0004);
 *  - a second submit of the same action while it is [ActionState.InFlight] is dropped, so rapid
 *    repeated taps cannot double-send (WING-015);
 *  - the result is only what the server confirmed: callers render [ActionState.Done.value].
 */
class ActionRunner(
    private val scope: CoroutineScope,
    private val keys: IdempotencyKeyRegistry = IdempotencyKeyRegistry(),
    private val policy: RetryPolicy = RetryPolicy(),
    private val sleep: (suspend (Long) -> Unit)? = null,
) {
    private val _states = MutableStateFlow<Map<String, ActionState<Any?>>>(emptyMap())
    val states: StateFlow<Map<String, ActionState<Any?>>> = _states.asStateFlow()

    fun stateOf(actionId: String): ActionState<Any?> = _states.value[actionId] ?: ActionState.Idle

    /** Returns false (and does nothing) when the same action is already in flight. */
    fun <T> submit(
        actionId: String,
        onResult: (ActionState<T>) -> Unit = {},
        call: suspend (IdempotencyKey) -> ApiResult<T>,
    ): Boolean {
        while (true) {
            val current = _states.value
            if (current[actionId] is ActionState.InFlight) return false
            if (_states.compareAndSet(current, current + (actionId to ActionState.InFlight))) break
        }
        scope.launch {
            val result = if (sleep != null) keys.runAction(actionId, policy, sleep, call) else keys.runAction(actionId, policy, call = call)
            val next: ActionState<T> = when (result) {
                is ApiResult.Success -> ActionState.Done(result.value, result.idempotentReplayed)
                is ApiResult.Failure -> ActionState.Failed(UiError.from(result.error))
            }
            _states.update { it + (actionId to next) }
            onResult(next)
        }
        return true
    }

    fun reset(actionId: String) = _states.update { it - actionId }
}
