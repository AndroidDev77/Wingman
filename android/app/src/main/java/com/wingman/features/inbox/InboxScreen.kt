package com.wingman.features.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import com.wingman.core.ui.compose.BannerTone
import com.wingman.core.ui.compose.ContactAvatar
import com.wingman.core.ui.compose.InfoBanner
import com.wingman.core.ui.compose.LocalAppContainer
import com.wingman.core.ui.compose.ScreenHeader
import com.wingman.core.ui.compose.StatusPill
import com.wingman.core.ui.compose.UiStateContent
import com.wingman.core.ui.compose.wingmanViewModel
import com.wingman.features.conversation.UiFixtures
import com.wingman.features.conversation.color
import com.wingman.navigation.ScreenContent
import com.wingman.ui.theme.Spacing
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
        ScreenHeader("Inbox", subtitle = "Your conversations. Tap one to read it or ask for a suggested reply.") {
            // Visible "Refresh" text (not just an icon): the emulator E2E taps it by its text.
            TextButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text("Refresh")
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.screen, vertical = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterMenu("Intent", InboxFilter.intentOptions, filter.intent, InboxFilter::intentLabel, onIntent)
            FilterMenu("Automation", InboxFilter.automationOptions, filter.automationMode, InboxFilter::automationLabel, onAutomation)
            if (filter.isActive) TextButton(onClick = onClearFilters) { Text("Clear filters") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        UiStateContent(state, onRetry = onRefresh, emptyMessage = filter.emptyMessage) { list ->
            InboxListView(list, onOpen, onLoadMore, refreshError = (state as? UiState.Content)?.refreshError?.message)
        }
    }
}

/** One compact chip per filter; the options open in a menu so the filters don't crowd the list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> FilterMenu(name: String, options: List<T?>, selected: T?, label: (T?) -> String, onSelect: (T?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected != null,
            onClick = { open = true },
            label = { Text(if (selected == null) name else label(selected)) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
            modifier = Modifier.semantics { contentDescription = "$name filter: ${label(selected)}" },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                    leadingIcon = if (option == selected) {
                        { Icon(Icons.Filled.Check, contentDescription = "Selected") }
                    } else {
                        null
                    },
                )
            }
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
                InfoBanner(
                    "Couldn't refresh: $refreshError",
                    tone = BannerTone.ERROR,
                    modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.sm),
                )
            }
        }
        if (!list.capabilitiesKnown) {
            item(key = "caps-unknown") {
                InfoBanner(
                    "Couldn't load connection capabilities. Platforms are shown as status unknown.",
                    tone = BannerTone.WARNING,
                    modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.sm),
                )
            }
        }
        items(list.rows, key = { it.key }) { row ->
            InboxRowItem(row, onClick = { onOpen(row.conversationId) })
            HorizontalDivider(Modifier.padding(start = 76.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
        item(key = "footer") {
            Box(Modifier.fillMaxWidth().padding(Spacing.lg), contentAlignment = Alignment.Center) {
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InboxRowItem(row: InboxRow, onClick: () -> Unit) {
    val unread = row.unreadBadge != null
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Open conversation", onClick = onClick)
            .padding(horizontal = Spacing.screen, vertical = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        ContactAvatar(row.contactName)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    row.contactName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    row.lastActivity,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    row.preview.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                row.unreadBadge?.let { Badge { Text(it) } }
            }
            FlowRow(
                Modifier.padding(top = Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                row.pendingApprovals?.let {
                    StatusPill(
                        it,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        icon = Icons.Filled.Edit,
                    )
                }
                StatusPill(row.platformBadge)
                Text(
                    row.availabilityLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = row.availabilityTone.color(),
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
            Text(
                listOf(row.stage, row.automation).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
