package com.wingman.features.inbox

import com.wingman.core.api.ApiResult
import com.wingman.core.api.WingmanApi
import com.wingman.core.model.AutomationMode
import com.wingman.core.model.Connection
import com.wingman.core.model.IntentMode
import com.wingman.core.ui.CursorPager
import com.wingman.core.ui.UiState
import com.wingman.features.conversation.loadAllConnections
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Pure-Kotlin inbox state holder. The androidx ViewModel ([InboxViewModel]) passes
 * `viewModelScope`; tests pass a TestScope's `backgroundScope`.
 *
 *  - Conversations come from [CursorPager] with the current [InboxFilter] as contract query params.
 *  - Connections are loaded once for platform availability. If they fail, rows say
 *    "Status unknown" and are never shown as connected.
 *  - Row mapping runs on [computeDispatcher] (Default on device), not the main thread.
 */
class InboxState(
    private val scope: CoroutineScope,
    private val api: WingmanApi,
    private val clock: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val locale: Locale = Locale.getDefault(),
    computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    pageSize: Int = PAGE_SIZE,
) {
    private val _filter = MutableStateFlow(InboxFilter())
    val filter: StateFlow<InboxFilter> = _filter.asStateFlow()

    /** null = not loaded or failed (availability then shows "Status unknown"). */
    private val connections = MutableStateFlow<List<Connection>?>(null)

    private val pager = CursorPager(scope, pageSize) { page -> api.listConversations(page, _filter.value.toApi()) }

    val state: StateFlow<UiState<InboxList>> =
        combine(pager.state, connections) { p, c -> InboxRows.reduce(p, c, clock(), zone(), locale) }
            .flowOn(computeDispatcher)
            .stateIn(scope, SharingStarted.Eagerly, UiState.Loading)

    /** Initial load. Also reloads connections if they failed earlier. */
    fun load() {
        loadConnections()
        pager.load()
    }

    /** Pull-to-refresh / Retry: restarts from the first page with the current filter. */
    fun refresh() {
        if (connections.value == null) loadConnections()
        pager.refresh()
    }

    /** Call when the list nears its end (or to retry a failed page). No-op at the end or while loading. */
    fun loadMore() = pager.loadMore()

    fun setIntent(intent: IntentMode?) = setFilter(_filter.value.copy(intent = intent))

    fun setAutomationMode(mode: AutomationMode?) = setFilter(_filter.value.copy(automationMode = mode))

    fun clearFilters() = setFilter(InboxFilter())

    /** A filter change restarts pagination (cursors are only valid for the query that produced them). */
    fun setFilter(next: InboxFilter) {
        if (next == _filter.value) return
        _filter.value = next
        pager.load()
    }

    private fun loadConnections() {
        scope.launch {
            connections.value = (loadAllConnections(api) as? ApiResult.Success)?.value?.first
        }
    }

    companion object {
        const val PAGE_SIZE = 30
    }
}
