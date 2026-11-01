package com.wingman.features.conversation

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.wingman.core.api.WingmanApi
import com.wingman.core.ui.UiState
import com.wingman.core.ui.compose.ErrorView
import com.wingman.core.ui.compose.LocalAppContainer
import com.wingman.core.ui.compose.UiStateContent
import com.wingman.core.ui.compose.wingmanViewModel
import com.wingman.navigation.Routes
import com.wingman.navigation.ScreenContent
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
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            val title = (state as? UiState.Content)?.data?.contactName ?: "Conversation"
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onRefresh) { Text("Refresh") }
        }
        UiStateContent(state, onRetry = onRetry, emptyMessage = "No messages yet.") { data ->
            Column(Modifier.fillMaxSize()) {
                Header(data)
                HorizontalDivider()
                MessageList(data, Modifier.weight(1f))
                HorizontalDivider()
                DraftArea(draft, guidance, onGuidance, onRequestDraft, onDismissDraft)
            }
        }
    }
}

@Composable
private fun Header(data: ConversationScreenData) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(data.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(data.availability.badge, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
            Text(data.availability.label, style = MaterialTheme.typography.labelSmall, color = data.availability.tone.color())
        }
        if (!data.availability.canSend || data.availability.unverified) {
            Text(data.availability.detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MessageList(data: ConversationScreenData, modifier: Modifier) {
    val listState = rememberLazyListState()
    // Open at the newest message. Keyed on the size so new messages scroll into view once.
    LaunchedEffect(data.rows.size) { if (data.rows.isNotEmpty()) listState.scrollToItem(data.rows.size - 1 + if (data.truncated) 1 else 0) }
    LazyColumn(state = listState, modifier = modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
        if (data.rows.isEmpty()) {
            item(key = "empty") { Text("No messages yet.", modifier = Modifier.padding(16.dp)) }
        }
        items(data.rows, key = { it.key }, contentType = { if (it.outbound) "out" else "in" }) { row ->
            Column {
                if (row.startsNewDay) {
                    Text(
                        row.dayLabel,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
                MessageBubble(row)
            }
        }
        if (data.truncated) {
            item(key = "truncated") {
                Text(
                    "Showing the first ${data.messageCount} messages. Newer messages weren't loaded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
    }
}

@Composable
fun MessageBubble(row: MessageRow) {
    Box(Modifier.fillMaxWidth().padding(vertical = 3.dp), contentAlignment = if (row.outbound) Alignment.CenterEnd else Alignment.CenterStart) {
        Card(Modifier.widthIn(max = 300.dp)) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(row.text, style = MaterialTheme.typography.bodyMedium)
                val meta = listOfNotNull(row.time, row.originNote).joinToString(" · ")
                Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                row.delivery?.let { Text(it.text, style = MaterialTheme.typography.labelSmall, color = it.tone.color()) }
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
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (draft) {
            DraftPanel.Idle -> Unit
            DraftPanel.Generating -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.padding(4.dp))
                Text("Asking Wingman for a suggestion…")
            }
            is DraftPanel.Failed -> Text(draft.error.message, color = MaterialTheme.colorScheme.error)
            is DraftPanel.Ready -> Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(draft.title, style = MaterialTheme.typography.titleSmall)
                    draft.suggestion?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodyLarge) } }
                    Text(draft.explanation, style = MaterialTheme.typography.bodySmall)
                    draft.approvalNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text(draft.notSentNote, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onDismissDraft) { Text("Dismiss") }
                }
            }
        }
        val error = DraftInput.guidanceError(guidance)
        OutlinedTextField(
            value = guidance,
            onValueChange = onGuidance,
            label = { Text("Guidance for the suggestion (optional)") },
            isError = error != null,
            supportingText = if (error != null) { { Text(error) } } else null,
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3,
        )
        Button(
            onClick = onRequestDraft,
            // ActionRunner also drops a second tap while in flight; disabling is just feedback.
            enabled = draft != DraftPanel.Generating && error == null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Suggest a reply") }
    }
}

/** Maps a [LabelTone] to theme colors only. */
@Composable
@ReadOnlyComposable
fun LabelTone.color(): Color = when (this) {
    LabelTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    LabelTone.PENDING -> MaterialTheme.colorScheme.secondary
    LabelTone.SUCCESS -> MaterialTheme.colorScheme.tertiary
    LabelTone.WARNING -> MaterialTheme.colorScheme.primary
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

@Preview(name = "Conversation: send unsupported")
@Composable private fun UnsupportedPreview() = ConversationPreview(UiFixtures.conversationUnsupported, UiFixtures.draftFailed)

@Preview(name = "Conversation: loading")
@Composable private fun LoadingPreview() = ConversationPreview(UiFixtures.conversationLoading)

@Preview(name = "Conversation: error")
@Composable private fun ErrorPreview() = ConversationPreview(UiFixtures.conversationError)
