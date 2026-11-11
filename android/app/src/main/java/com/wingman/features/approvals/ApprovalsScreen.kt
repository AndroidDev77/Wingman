package com.wingman.features.approvals

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.wingman.core.api.AgentActionFilter
import com.wingman.core.api.WingmanApi
import com.wingman.core.ui.UiState
import com.wingman.core.ui.compose.BannerTone
import com.wingman.core.ui.compose.ContactAvatar
import com.wingman.core.ui.compose.InfoBanner
import com.wingman.core.ui.compose.LocalAppContainer
import com.wingman.core.ui.compose.ScreenHeader
import com.wingman.core.ui.compose.SectionCard
import com.wingman.core.ui.compose.StatusPill
import com.wingman.core.ui.compose.UiStateContent
import com.wingman.core.ui.compose.wingmanViewModel
import com.wingman.features.automation.AutomationControls
import com.wingman.features.automation.AutomationState
import com.wingman.features.conversation.color
import com.wingman.navigation.ScreenContent
import com.wingman.ui.theme.Spacing
import com.wingman.ui.theme.WingmanTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * Approvals destination (WING-015): the approval queue, the pause / takeover controls and the
 * audit timeline. Compiled by CI's android job; NOT run on a device. All behavior lives in
 * ApprovalsState / AutomationState / AuditTimelineState / PendingBadgeState, which are JVM-tested.
 * Nothing here shows a state the server has not reported.
 */

/** Registered by the integrator: `.register(Destination.Approvals, ApprovalsScreenEntry)`. */
val ApprovalsScreenEntry: ScreenContent = { _, navigator -> ApprovalsScreen(onOpenConversation = { navigator.openConversation(it) }) }

/** The shell's badge holder, provided by `MainShell` so the screen can report fresh counts. */
val LocalPendingBadge = staticCompositionLocalOf<PendingBadgeState?> { null }

/** Approvals nav icon with the pending-approvals badge. */
@Composable
fun ApprovalsNavIcon(badge: BadgeCount?, icon: @Composable () -> Unit) {
    val label = badge?.label
    if (badge == null || label == null) {
        icon()
    } else {
        BadgedBox(
            badge = { Badge { Text(label) } },
            modifier = Modifier.semantics { contentDescription = badge.description },
        ) { icon() }
    }
}

enum class ApprovalsTab(val title: String) { QUEUE("To approve"), CONTROLS("Controls"), ACTIVITY("Activity") }

class ApprovalsViewModel(private val api: WingmanApi) : ViewModel() {
    val queue = ApprovalsState(viewModelScope, api)
    val automation = AutomationState(viewModelScope, api)

    private var timelineScope: CoroutineScope? = null
    private val _timeline = MutableStateFlow(newTimeline(AgentActionFilter()))
    val timeline: StateFlow<AuditTimelineState> = _timeline.asStateFlow()

    init {
        queue.load()
        automation.load()
        _timeline.value.load()
    }

    /** Shows the timeline for one conversation, or (null) for everything. */
    fun showTimeline(conversationId: String?) {
        val filter = AgentActionFilter(conversationId = conversationId)
        if (_timeline.value.filter == filter) return _timeline.value.refresh()
        _timeline.value = newTimeline(filter).also { it.load() }
    }

    private fun newTimeline(filter: AgentActionFilter): AuditTimelineState {
        timelineScope?.cancel()
        val scope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))
        timelineScope = scope
        return AuditTimelineState(scope, api, filter)
    }
}

@Composable
fun ApprovalsScreen(onOpenConversation: (String) -> Unit) {
    val container = LocalAppContainer.current
    val vm = wingmanViewModel(key = "approvals") { ApprovalsViewModel(container.api) }
    val queue by vm.queue.state.collectAsStateWithLifecycle()
    val count by vm.queue.pendingCount.collectAsStateWithLifecycle()
    val controls by vm.automation.state.collectAsStateWithLifecycle()
    val timelineHolder by vm.timeline.collectAsStateWithLifecycle()
    val timeline by timelineHolder.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(ApprovalsTab.QUEUE) }

    val badge = LocalPendingBadge.current
    LaunchedEffect(count) { badge?.report(count) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Approvals", subtitle = "Review drafts before they go out, control automation and see what happened.")
        TabRow(selectedTabIndex = tab.ordinal, containerColor = MaterialTheme.colorScheme.surface) {
            ApprovalsTab.entries.forEach { t ->
                Tab(
                    selected = t == tab,
                    onClick = { tab = t },
                    text = {
                        val n = count?.label
                        Text(if (t == ApprovalsTab.QUEUE && n != null) "${t.title} ($n)" else t.title)
                    },
                )
            }
        }
        when (tab) {
            ApprovalsTab.QUEUE -> QueueContent(
                state = queue,
                actions = QueueActions(
                    onRefresh = vm.queue::refresh,
                    onLoadMore = vm.queue::loadMore,
                    onStartEdit = vm.queue::startEdit,
                    onCancelEdit = vm.queue::cancelEdit,
                    onEditText = vm.queue::setEditText,
                    onApprove = { vm.queue.approve(it) },
                    onReject = { vm.queue.reject(it) },
                    onCheckDelivery = vm.queue::checkDelivery,
                    onDismiss = vm.queue::dismiss,
                    onClearError = vm.queue::clearError,
                    onOpenConversation = onOpenConversation,
                    onHistory = { id ->
                        vm.showTimeline(id)
                        tab = ApprovalsTab.ACTIVITY
                    },
                ),
            )
            ApprovalsTab.CONTROLS -> AutomationControls(controls, vm.automation)
            ApprovalsTab.ACTIVITY -> TimelineContent(
                state = timeline,
                conversationName = timelineHolder.filter.conversationId?.let { id ->
                    (queue as? UiState.Content)?.data?.let { d ->
                        d.pending.firstOrNull { it.card.conversationId == id }?.card?.contactName
                            ?: d.decided.firstOrNull { it.conversationId == id }?.contactName
                    } ?: "this conversation"
                },
                onShowAll = { vm.showTimeline(null) },
                onRefresh = timelineHolder::refresh,
                onLoadMore = timelineHolder::loadMore,
            )
        }
    }
}

/** Queue callbacks, grouped so previews and UI tests can pass no-ops. */
data class QueueActions(
    val onRefresh: () -> Unit = {},
    val onLoadMore: () -> Unit = {},
    val onStartEdit: (ApprovalCard) -> Unit = {},
    val onCancelEdit: (String) -> Unit = {},
    val onEditText: (String, String) -> Unit = { _, _ -> },
    val onApprove: (String) -> Unit = {},
    val onReject: (String) -> Unit = {},
    val onCheckDelivery: (String) -> Unit = {},
    val onDismiss: (String) -> Unit = {},
    val onClearError: (String) -> Unit = {},
    val onOpenConversation: (String) -> Unit = {},
    val onHistory: (String) -> Unit = {},
)

/** Stateless queue: previews and Compose UI tests drive it directly. */
@Composable
fun QueueContent(state: UiState<ApprovalsScreenData>, actions: QueueActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        ListToolbar("Drafts waiting for you", onRefresh = actions.onRefresh)
        UiStateContent(state, onRetry = actions.onRefresh, emptyMessage = "No drafts are waiting for your approval.") { data ->
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = Spacing.screen, vertical = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                (state as? UiState.Content)?.refreshError?.let { e ->
                    item(key = "refresh-error") { InfoBanner(e.message, tone = BannerTone.ERROR) }
                }
                items(data.decided, key = { "decided:${it.approvalId}" }) { DecidedCard(it, actions) }
                items(data.pending, key = { "pending:${it.card.approvalId}" }) { PendingCard(it, actions) }
                if (!data.endReached) {
                    item(key = "more") {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            when {
                                data.loadingMore -> CircularProgressIndicator(Modifier.padding(Spacing.sm))
                                data.loadMoreError != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(data.loadMoreError.message, color = MaterialTheme.colorScheme.error)
                                    TextButton(onClick = actions.onLoadMore) { Text("Retry") }
                                }
                                else -> TextButton(onClick = actions.onLoadMore) { Text("Load more") }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Title + "Refresh" row above a list. The text label stays visible: the emulator E2E taps it by text. */
@Composable
private fun ListToolbar(title: String, onRefresh: () -> Unit, extra: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = Spacing.screen, end = Spacing.sm, top = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        extra()
        TextButton(onClick = onRefresh) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.xs))
            Text("Refresh")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PendingCard(item: PendingItem, actions: QueueActions) {
    val card = item.card
    val busy = item.inFlight != null
    SectionCard {
        // Who and when.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            ContactAvatar(card.contactName, size = 40.dp)
            Column(Modifier.weight(1f)) {
                Text(card.contactName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(card.age, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { actions.onOpenConversation(card.conversationId) }) { Text("Open chat") }
        }
        card.expiry?.let { expiry ->
            val status = WingmanTheme.status
            StatusPill(
                expiry,
                containerColor = if (card.expired) MaterialTheme.colorScheme.errorContainer else status.warningContainer,
                contentColor = if (card.expired) MaterialTheme.colorScheme.onErrorContainer else status.onWarningContainer,
                icon = Icons.Filled.DateRange,
            )
        }

        // The draft itself: the most prominent thing on the card.
        if (item.editing) {
            // Local copy keeps typing smooth; every change is pushed to the holder, which sends it.
            var text by rememberSaveable(card.approvalId) { mutableStateOf(item.editText) }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; actions.onEditText(card.approvalId, it) },
                label = { Text("Your reply (this exact text is sent)") },
                isError = item.editError != null,
                supportingText = item.editError?.let { message -> @Composable { Text(message) } },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text("Draft reply", style = MaterialTheme.typography.labelMedium)
                    Text(card.draftText, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        // Why the user is being asked.
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Why Wingman asked you:", style = MaterialTheme.typography.labelLarge)
            }
            card.reasons.forEach {
                Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item.error?.let { e -> InfoBanner(e.message, tone = BannerTone.ERROR) }
        Text(
            "Nothing is sent until you approve.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Approve is the primary action; Edit is secondary; Reject is de-emphasized and red.
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Button(
                onClick = { actions.onApprove(card.approvalId) },
                enabled = item.canDecide,
                modifier = Modifier.heightIn(min = Spacing.touchTarget),
            ) {
                if (item.inFlight == Decision.APPROVING) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                    Text("  Approving…")
                } else {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.xs))
                    Text(if (item.editing) "Approve edited" else "Approve")
                }
            }
            if (item.editing) {
                OutlinedButton(onClick = { actions.onCancelEdit(card.approvalId) }, enabled = !busy, modifier = Modifier.heightIn(min = Spacing.touchTarget)) {
                    Text("Undo edit")
                }
            } else {
                OutlinedButton(onClick = { actions.onStartEdit(card) }, enabled = !busy, modifier = Modifier.heightIn(min = Spacing.touchTarget)) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.xs))
                    Text("Edit")
                }
            }
            TextButton(
                onClick = { actions.onReject(card.approvalId) },
                enabled = !busy,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.heightIn(min = Spacing.touchTarget),
            ) {
                Text(if (item.inFlight == Decision.REJECTING) "Rejecting…" else "Reject")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        TextButton(onClick = { actions.onHistory(card.conversationId) }) { Text("Activity for ${card.contactName}") }
    }
}

@Composable
private fun DecidedCard(item: DecidedItem, actions: QueueActions) {
    SectionCard(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${item.contactName}: ${item.title}", style = MaterialTheme.typography.titleSmall, color = item.tone.color(), modifier = Modifier.weight(1f))
            TextButton(onClick = { actions.onDismiss(item.approvalId) }) { Text("Dismiss") }
        }
        item.finalText?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Text(item.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Delivery comes only from the server's message state: "Queued, not sent yet" until it says otherwise.
        item.delivery?.let { Text("Status: ${it.text}", style = MaterialTheme.typography.labelLarge, color = it.tone.color()) }
        item.error?.let { InfoBanner(it.message, tone = BannerTone.ERROR) }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            if (item.checkingDelivery) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            if (item.canCheckDelivery) TextButton(onClick = { actions.onCheckDelivery(item.approvalId) }) { Text("Check status") }
            TextButton(onClick = { actions.onHistory(item.conversationId) }) { Text("Activity") }
        }
    }
}

@Composable
fun TimelineContent(
    state: UiState<AuditScreenData>,
    conversationName: String?,
    onShowAll: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        ListToolbar(
            if (conversationName != null) "Activity with $conversationName" else "All activity",
            onRefresh = onRefresh,
        ) {
            if (conversationName != null) TextButton(onClick = onShowAll) { Text("Show all") }
        }
        InfoBanner(
            "Exactly what Wingman's records show. \"Queued\" is not sent, and \"Sent\" is not delivered.",
            modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.xs),
        )
        UiStateContent(state, onRetry = onRefresh, emptyMessage = "No activity yet.") { data ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = Spacing.screen, vertical = Spacing.sm)) {
                items(data.rows, key = { it.key }) { row ->
                    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        Box(
                            Modifier.padding(top = 6.dp).size(10.dp).background(row.tone.color(), CircleShape),
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                            Text(row.title, style = MaterialTheme.typography.bodyLarge, color = row.tone.color())
                            Text(
                                listOfNotNull(row.time, row.actor, row.contactName).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            row.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            row.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                if (!data.endReached) {
                    item(key = "more") {
                        when {
                            data.loadingMore -> CircularProgressIndicator(Modifier.padding(Spacing.sm))
                            else -> Column {
                                data.loadMoreError?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
                                TextButton(onClick = onLoadMore) { Text(if (data.loadMoreError != null) "Retry" else "Load older") }
                            }
                        }
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ previews */

private val previewCard = ApprovalCard(
    approvalId = "a1", conversationId = "c1", contactId = "p1", contactName = "Sam",
    draftText = "Welcome back! What was the highlight, the food or the views?",
    reasons = listOf("You asked to approve replies to this person before they go out (assisted mode)."),
    age = "Waiting 3m", expiry = "Expires in 23h", expired = false,
)

@Preview(name = "Queue: pending and approved", heightDp = 800)
@Composable
private fun QueuePreview() = WingmanTheme {
    Surface {
        QueueContent(
            UiState.Content(
                ApprovalsScreenData(
                    pending = listOf(PendingItem(previewCard, editing = false, editText = previewCard.draftText, editError = null, inFlight = null, error = null)),
                    decided = listOf(
                        DecidedItem(
                            "a0", "c2", "Jordan", "Approved", "Approved. It shows as sent only after the messaging app confirms it.",
                            com.wingman.features.conversation.LabelTone.PENDING, "What are you up to this weekend?", "m1",
                            com.wingman.features.conversation.DeliveryLabel("Queued, not sent yet", com.wingman.features.conversation.LabelTone.PENDING, false),
                            checkingDelivery = false,
                        ),
                    ),
                    loadingMore = false, endReached = true, loadMoreError = null,
                ),
            ),
            QueueActions(),
        )
    }
}

@Preview(name = "Queue: editing after a network error", heightDp = 600)
@Composable
private fun QueueErrorPreview() = WingmanTheme(darkTheme = true) {
    Surface {
        QueueContent(
            UiState.Content(
                ApprovalsScreenData(
                    pending = listOf(
                        PendingItem(
                            previewCard, editing = true, editText = "My own words", editError = null, inFlight = null,
                            error = com.wingman.core.ui.UiError("Can't reach Wingman. Check your connection and try again. Nothing was sent yet; your text is kept.", isNetwork = true),
                        ),
                    ),
                    decided = emptyList(), loadingMore = false, endReached = true, loadMoreError = null,
                ),
            ),
            QueueActions(),
        )
    }
}

@Preview(name = "Queue: pending dark", heightDp = 700)
@Composable
private fun QueuePendingDarkPreview() = WingmanTheme(darkTheme = true) {
    Surface {
        QueueContent(
            UiState.Content(
                ApprovalsScreenData(
                    pending = listOf(PendingItem(previewCard, editing = false, editText = previewCard.draftText, editError = null, inFlight = null, error = null)),
                    decided = emptyList(), loadingMore = false, endReached = true, loadMoreError = null,
                ),
            ),
            QueueActions(),
        )
    }
}

@Preview(name = "Queue: empty")
@Composable
private fun QueueEmptyPreview() = WingmanTheme {
    Surface { QueueContent(UiState.Empty, QueueActions()) }
}

private val previewTimeline = AuditScreenData(
    rows = listOf(
        AuditRow("r2", "Sent (delivery not confirmed)", com.wingman.features.conversation.LabelTone.NEUTRAL, SendStage.SENT, "Wingman", "Sam", "10:42", "2m ago", null, null, "c1"),
        AuditRow("r1", "Draft approved (not sent yet)", com.wingman.features.conversation.LabelTone.PENDING, SendStage.NONE, "You", "Sam", "10:41", "3m ago", null, null, "c1"),
    ),
    loadingMore = false, endReached = true, loadMoreError = null,
)

@Preview(name = "Activity timeline")
@Composable
private fun TimelinePreview() = WingmanTheme {
    Surface { TimelineContent(UiState.Content(previewTimeline), conversationName = "Sam", onShowAll = {}, onRefresh = {}, onLoadMore = {}) }
}

@Preview(name = "Activity timeline dark")
@Composable
private fun TimelineDarkPreview() = WingmanTheme(darkTheme = true) {
    Surface { TimelineContent(UiState.Content(previewTimeline), conversationName = null, onShowAll = {}, onRefresh = {}, onLoadMore = {}) }
}
