package com.wingman.features.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.wingman.core.api.WingmanApi
import com.wingman.core.ui.UiState
import com.wingman.core.ui.compose.BannerTone
import com.wingman.core.ui.compose.ContactAvatar
import com.wingman.core.ui.compose.EmptyState
import com.wingman.core.ui.compose.ErrorView
import com.wingman.core.ui.compose.InfoBanner
import com.wingman.core.ui.compose.SectionCard
import com.wingman.core.ui.compose.StatusPill
import com.wingman.core.ui.compose.LocalAppContainer
import com.wingman.core.ui.compose.UiStateContent
import com.wingman.core.ui.compose.wingmanViewModel
import com.wingman.navigation.Routes
import com.wingman.navigation.ScreenContent
import com.wingman.ui.theme.Spacing
import com.wingman.ui.theme.WingmanTheme

/*
 * UNVERIFIED (no Android SDK in the agent sandbox, I-001/I-008): this file has not been compiled.
 * All logic it renders lives in ConversationState.kt / MessageDisplay.kt / DraftModels.kt /
 * DisplayFormat.kt, which are JVM-tested. There is deliberately NO send button here: drafting
 * never sends (contract createDraft), and user-authored sends are out of WING-014's scope.
 */

/** Registered by the integrator: `.register(Destination.Conversation, ConversationScreenEntry)`. */
val ConversationScreenEntry: ScreenContent = { args, navigator ->
    val id = args.conversationId
    if (id != null && Routes.isValidId(id)) {
        ConversationScreen(conversationId = id, onBack = navigator::back)
    } else {
        ErrorView(com.wingman.core.ui.UiError("This conversation link is invalid.", retryable = false), onRetry = {})
    }
}

class ConversationViewModel(api: WingmanApi, conversationId: String) : ViewModel() {
    val holder = ConversationState(viewModelScope, api, conversationId)

    init {
        holder.load()
    }
}

@Composable
fun ConversationScreen(conversationId: String, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val vm = wingmanViewModel(key = "conversation:$conversationId") { ConversationViewModel(container.api, conversationId) }
    val state by vm.holder.state.collectAsStateWithLifecycle()
    val draft by vm.holder.draft.collectAsStateWithLifecycle()
    val guidance by vm.holder.guidance.collectAsStateWithLifecycle()
    ConversationContent(
        state = state,
        draft = draft,
        guidance = guidance,
        onBack = onBack,
        onRetry = vm.holder::load,
        onRefresh = vm.holder::refresh,
        onGuidance = vm.holder::setGuidance,
        onRequestDraft = { vm.holder.requestDraft() },
        onDismissDraft = vm.holder::dismissDraft,
    )
}

/** Stateless conversation screen: previews and Compose UI tests drive it with [UiFixtures]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationContent(
    state: UiState<ConversationScreenData>,
    draft: DraftPanel,
    guidance: String,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onGuidance: (String) -> Unit,
    onRequestDraft: () -> Unit,
    onDismissDraft: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val data = (state as? UiState.Content)?.data
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    if (data != null) ContactAvatar(data.contactName, size = 36.dp)
                    Column {
                        Text(
                            data?.contactName ?: "Conversation",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (data != null) {
                            Text(
                                data.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            },
            actions = {
                IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        )
        UiStateContent(state, onRetry = onRetry, emptyMessage = "No messages yet.") { content ->
            Column(Modifier.fillMaxSize()) {
                Header(content)
                MessageList(content, Modifier.weight(1f))
                DraftArea(draft, guidance, onGuidance, onRequestDraft, onDismissDraft)
            }
        }
    }
}

@Composable
private fun Header(data: ConversationScreenData) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            StatusPill(data.availability.badge)
            Text(data.availability.label, style = MaterialTheme.typography.labelMedium, color = data.availability.tone.color())
        }
        if (!data.availability.canSend || data.availability.unverified) {
            InfoBanner(data.availability.detail, tone = if (data.availability.tone == LabelTone.ERROR) BannerTone.ERROR else BannerTone.WARNING)
        }
    }
}

@Composable
private fun MessageList(data: ConversationScreenData, modifier: Modifier) {
    val listState = rememberLazyListState()
    // Open at the newest message. Keyed on the size so new messages scroll into view once.
    LaunchedEffect(data.rows.size) { if (data.rows.isNotEmpty()) listState.scrollToItem(data.rows.size - 1 + if (data.truncated) 1 else 0) }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm),
    ) {
        if (data.rows.isEmpty()) {
            item(key = "empty") { EmptyState("No messages yet.") }
        }
        items(data.rows, key = { it.key }, contentType = { if (it.outbound) "out" else "in" }) { row ->
            Column {
                if (row.startsNewDay) DayDivider(row.dayLabel)
                MessageBubble(row)
            }
        }
        if (data.truncated) {
            item(key = "truncated") {
                InfoBanner(
                    "Showing the first ${data.messageCount} messages. Newer messages weren't loaded.",
                    tone = BannerTone.ERROR,
                    modifier = Modifier.padding(vertical = Spacing.sm),
                )
            }
        }
    }
}

@Composable
private fun DayDivider(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = Spacing.md), contentAlignment = Alignment.Center) {
        StatusPill(label, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    }
}

/**
 * One message. Inbound bubbles sit on the left in a neutral surface color; outbound bubbles sit on
 * the right in the primary container color. Time, origin and delivery share one metadata line.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MessageBubble(row: MessageRow) {
    val outbound = row.outbound
    val container = if (outbound) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val onContainer = if (outbound) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val corner = 18.dp
    val tail = 4.dp
    val shape = if (outbound) {
        RoundedCornerShape(topStart = corner, topEnd = corner, bottomStart = corner, bottomEnd = tail)
    } else {
        RoundedCornerShape(topStart = corner, topEnd = corner, bottomStart = tail, bottomEnd = corner)
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xxs)
            .padding(start = if (outbound) 48.dp else 0.dp, end = if (outbound) 0.dp else 48.dp),
        contentAlignment = if (outbound) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Surface(shape = shape, color = container, contentColor = onContainer, modifier = Modifier.widthIn(max = 320.dp)) {
            Column(
                Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                horizontalAlignment = if (outbound) Alignment.End else Alignment.Start,
            ) {
                Text(row.text, style = MaterialTheme.typography.bodyLarge)
                val metaColor = onContainer.copy(alpha = 0.78f)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
                ) {
                    Text(row.time, style = MaterialTheme.typography.labelSmall, color = metaColor)
                    row.originNote?.let {
                        Text("·", style = MaterialTheme.typography.labelSmall, color = metaColor)
                        Text(it, style = MaterialTheme.typography.labelSmall, color = metaColor)
                    }
                    row.delivery?.let {
                        Text("·", style = MaterialTheme.typography.labelSmall, color = metaColor)
                        Text(
                            it.text,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (it.tone == LabelTone.NEUTRAL) metaColor else it.tone.color(),
                        )
                    }
                }
                row.failureNote?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun DraftArea(
    draft: DraftPanel,
    guidance: String,
    onGuidance: (String) -> Unit,
    onRequestDraft: () -> Unit,
    onDismissDraft: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            when (draft) {
                DraftPanel.Idle -> Unit
                DraftPanel.Generating -> Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Asking Wingman for a suggestion…", style = MaterialTheme.typography.bodyMedium)
                }
                is DraftPanel.Failed -> InfoBanner(draft.error.message, tone = BannerTone.ERROR)
                is DraftPanel.Ready -> SectionCard(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Text(draft.title, style = MaterialTheme.typography.titleSmall)
                    draft.suggestion?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodyLarge) } }
                    Text(draft.explanation, style = MaterialTheme.typography.bodySmall)
                    draft.approvalNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(draft.notSentNote, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = onDismissDraft) { Text("Dismiss") }
                    }
                }
            }
            val error = DraftInput.guidanceError(guidance)
            OutlinedTextField(
                value = guidance,
                onValueChange = onGuidance,
                label = { Text("Guidance for the suggestion (optional)") },
                placeholder = { Text("e.g. keep it short, ask about their weekend") },
                isError = error != null,
                supportingText = if (error != null) { { Text(error) } } else null,
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
            )
            Button(
                onClick = onRequestDraft,
                // ActionRunner also drops a second tap while in flight; disabling is just feedback.
                enabled = draft != DraftPanel.Generating && error == null,
                modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget),
            ) { Text("Suggest a reply") }
        }
    }
}

/** Maps a [LabelTone] to theme colors only. */
@Composable
@ReadOnlyComposable
fun LabelTone.color(): Color = when (this) {
    LabelTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    LabelTone.PENDING -> MaterialTheme.colorScheme.secondary
    LabelTone.SUCCESS -> WingmanTheme.status.success
    LabelTone.WARNING -> WingmanTheme.status.warning
    LabelTone.ERROR -> MaterialTheme.colorScheme.error
}

@Composable
private fun ConversationPreview(state: UiState<ConversationScreenData>, draft: DraftPanel = DraftPanel.Idle, dark: Boolean = false) =
    WingmanTheme(darkTheme = dark) {
        Surface { ConversationContent(state, draft, "", {}, {}, {}, {}, {}, {}) }
    }

@Preview(name = "Conversation: delivery states", heightDp = 900)
@Composable private fun DeliveryStatesPreview() = ConversationPreview(UiFixtures.conversationDeliveryStates, UiFixtures.draftReady)

@Preview(name = "Conversation: 120 messages", heightDp = 900)
@Composable private fun LongThreadPreview() = ConversationPreview(UiFixtures.conversationLong, dark = true)

@Preview(name = "Conversation: delivery states dark", heightDp = 900)
@Composable private fun DeliveryStatesDarkPreview() = ConversationPreview(UiFixtures.conversationDeliveryStates, UiFixtures.draftReady, dark = true)

@Preview(name = "Conversation: send unsupported")
@Composable private fun UnsupportedPreview() = ConversationPreview(UiFixtures.conversationUnsupported, UiFixtures.draftFailed)

@Preview(name = "Conversation: loading")
@Composable private fun LoadingPreview() = ConversationPreview(UiFixtures.conversationLoading)

@Preview(name = "Conversation: error")
@Composable private fun ErrorPreview() = ConversationPreview(UiFixtures.conversationError)
