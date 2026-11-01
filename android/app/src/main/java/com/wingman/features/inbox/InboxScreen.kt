package com.wingman.features.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.wingman.core.api.WingmanApi
import com.wingman.core.model.AutomationMode
import com.wingman.core.model.IntentMode
import com.wingman.core.ui.UiState
import com.wingman.core.ui.compose.LocalAppContainer
import com.wingman.core.ui.compose.UiStateContent
import com.wingman.core.ui.compose.wingmanViewModel
import com.wingman.features.conversation.UiFixtures
import com.wingman.features.conversation.color
import com.wingman.navigation.ScreenContent
import com.wingman.ui.theme.WingmanTheme

/*
 * UNVERIFIED (no Android SDK in the agent sandbox, I-001/I-008): this file has not been compiled.
 * All logic it renders lives in InboxModels.kt / InboxState.kt, which are JVM-tested.
 */

/** Registered by the integrator: `.register(Destination.Inbox, InboxScreenEntry)`. */
val InboxScreenEntry: ScreenContent = { _, navigator -> InboxScreen(onOpen = { navigator.openConversation(it) }) }

class InboxViewModel(api: WingmanApi) : ViewModel() {
    val holder = InboxState(viewModelScope, api)

    init {
        holder.load()
    }
}

@Composable
fun InboxScreen(onOpen: (conversationId: String) -> Unit) {
    val container = LocalAppContainer.current
    val vm = wingmanViewModel { InboxViewModel(container.api) }
    val state by vm.holder.state.collectAsStateWithLifecycle()
    val filter by vm.holder.filter.collectAsStateWithLifecycle()
    InboxContent(
        state = state,
        filter = filter,
        onOpen = onOpen,
        onRefresh = vm.holder::refresh,
        onLoadMore = vm.holder::loadMore,
        onIntent = vm.holder::setIntent,
        onAutomation = vm.holder::setAutomationMode,
        onClearFilters = vm.holder::clearFilters,
    )
}

/** Stateless inbox: previews and Compose UI tests drive it with [UiFixtures]. */
@Composable
fun InboxContent(
    state: UiState<InboxList>,
    filter: InboxFilter,
    onOpen: (String) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onIntent: (IntentMode?) -> Unit,
    onAutomation: (AutomationMode?) -> Unit,
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Inbox", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (filter.isActive) TextButton(onClick = onClearFilters) { Text("Clear filters") }
            TextButton(onClick = onRefresh) { Text("Refresh") }
        }
        FilterRow(InboxFilter.intentOptions, filter.intent, InboxFilter::intentLabel, onIntent)
        FilterRow(InboxFilter.automationOptions, filter.automationMode, InboxFilter::automationLabel, onAutomation)
        HorizontalDivider()
        UiStateContent(state, onRetry = onRefresh, emptyMessage = filter.emptyMessage) { list ->
            InboxListView(list, onOpen, onLoadMore, refreshError = (state as? UiState.Content)?.refreshError?.message)
        }
    }
}

@Composable
private fun <T> FilterRow(options: List<T?>, selected: T?, label: (T?) -> String, onSelect: (T?) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
        }
    }
}

@Composable
private fun InboxListView(list: InboxList, onOpen: (String) -> Unit, onLoadMore: () -> Unit, refreshError: String?) {
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 5
        }
    }
    // Re-keyed on the row count so a short page that still leaves us near the end loads the next
    // one. A failed page does not change the count, so it waits for the explicit Retry below.
    LaunchedEffect(nearEnd, list.rows.size) {
        if (nearEnd && !list.endReached && list.loadMoreError == null) onLoadMore()
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        if (refreshError != null) {
            item(key = "refresh-error") {
                Text(
                    "Couldn't refresh: $refreshError",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        if (!list.capabilitiesKnown) {
            item(key = "caps-unknown") {
                Text(
                    "Couldn't load connection capabilities. Platforms are shown as status unknown.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        items(list.rows, key = { it.key }) { row ->
            InboxRowItem(row, onClick = { onOpen(row.conversationId) })
            HorizontalDivider()
        }
        item(key = "footer") {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                when {
                    list.loadingMore -> CircularProgressIndicator()
                    list.loadMoreError != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(list.loadMoreError.message, color = MaterialTheme.colorScheme.error)
                        if (list.loadMoreError.retryable) TextButton(onClick = onLoadMore) { Text("Retry") }
                    }
                    else -> Unit
                }
            }
        }
    }
}

@Composable
fun InboxRowItem(row: InboxRow, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                row.contactName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(row.lastActivity, style = MaterialTheme.typography.labelSmall)
            row.unreadBadge?.let { Badge { Text(it) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(row.platformBadge, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
            Text(row.availabilityLabel, style = MaterialTheme.typography.labelSmall, color = row.availabilityTone.color())
        }
        row.preview?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            listOfNotNull(row.stage, row.automation, row.pendingApprovals).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InboxPreview(state: UiState<InboxList>, filter: InboxFilter = InboxFilter(), dark: Boolean = false) =
    WingmanTheme(darkTheme = dark) {
        Surface {
            InboxContent(state, filter, {}, {}, {}, {}, {}, {})
        }
    }

@Preview(name = "Inbox content") @Composable private fun InboxContentPreview() = InboxPreview(UiFixtures.inboxContent)
@Preview(name = "Inbox content dark") @Composable private fun InboxContentDarkPreview() = InboxPreview(UiFixtures.inboxContent, dark = true)
@Preview(name = "Inbox loading") @Composable private fun InboxLoadingPreview() = InboxPreview(UiFixtures.inboxLoading)
@Preview(name = "Inbox empty (filtered)") @Composable private fun InboxEmptyPreview() =
    InboxPreview(UiFixtures.inboxEmpty, InboxFilter(intent = IntentMode.SERIOUS))
@Preview(name = "Inbox error") @Composable private fun InboxErrorPreview() = InboxPreview(UiFixtures.inboxError)
