package com.wingman.features.conversation

import com.wingman.core.api.ApiResult
import com.wingman.core.api.PageRequest
import com.wingman.core.api.WingmanApi
import com.wingman.core.model.ConversationDetail
import com.wingman.core.model.Draft
import com.wingman.core.model.Message
import com.wingman.core.api.IdempotencyKeyRegistry
import com.wingman.core.ui.ActionRunner
import com.wingman.core.ui.ActionState
import com.wingman.core.ui.LoadableState
import com.wingman.core.ui.UiState
import com.wingman.features.inbox.InboxRows
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Everything the conversation screen renders, precomputed. */
data class ConversationScreenData(
    val conversationId: String,
    val contactName: String,
    val subtitle: String,
    val availability: PlatformAvailability,
    val rows: List<MessageRow>,
    /** True when the message page cap was hit: newer messages exist but were not loaded. */
    val truncated: Boolean,
) {
    val messageCount: Int get() = rows.size
}

/**
 * Loads a conversation's detail, its connection's capabilities and its messages.
 *
 * The contract pages messages oldest-first (`created_at` asc). A chat should open on the latest
 * message, so this follows `next_cursor` to the end, up to [maxMessagePages] pages of
 * [messagePageSize]; past that it shows what it has and sets [ConversationScreenData.truncated].
 */
suspend fun loadConversationScreen(
    api: WingmanApi,
    conversationId: String,
    now: Instant,
    zone: ZoneId,
    locale: Locale = Locale.getDefault(),
    messagePageSize: Int = 100,
    maxMessagePages: Int = 20,
): ApiResult<ConversationScreenData> {
    val detail: ConversationDetail = when (val r = api.getConversation(conversationId)) {
        is ApiResult.Success -> r.value
        is ApiResult.Failure -> return r
    }
    val messages = mutableListOf<Message>()
    var cursor: String? = null
    var truncated = true
    for (i in 0 until maxMessagePages) {
        when (val r = api.listMessages(conversationId, PageRequest(cursor, messagePageSize))) {
            is ApiResult.Failure -> return r
            is ApiResult.Success -> {
                messages += r.value.items
                cursor = r.value.nextCursor
            }
        }
        if (cursor == null) {
            truncated = false
            break
        }
    }
    // Capabilities are best-effort: a failure shows "Status unknown" (never "connected").
    val connection = (loadAllConnections(api) as? ApiResult.Success)?.value?.first?.find { it.id == detail.connectionId }
    val availability = Availability.of(detail.platform, connection)
    val inboxRow = InboxRows.row(detail.conversation, connection, now, zone, locale)
    return ApiResult.Success(
        ConversationScreenData(
            conversationId = detail.id,
            contactName = inboxRow.contactName,
            subtitle = listOf(inboxRow.stage, inboxRow.intent, inboxRow.automation).joinToString(" · "),
            availability = availability,
            rows = MessageRows.build(messages, zone, locale),
            truncated = truncated,
        ),
    )
}

/**
 * Pure-Kotlin conversation state holder: screen data via [LoadableState] and manual drafts via
 * [ActionRunner]. The action ID is per conversation, so a double tap on "Suggest a reply" while a
 * request is in flight is dropped: one request, one idempotency key, one draft.
 */
class ConversationState(
    private val scope: CoroutineScope,
    private val api: WingmanApi,
    val conversationId: String,
    private val clock: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val locale: Locale = Locale.getDefault(),
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Shared with [runner] so a guidance edit can release a key kept after a failed attempt (see [setGuidance]). */
    private val keys: IdempotencyKeyRegistry = IdempotencyKeyRegistry(),
    private val runner: ActionRunner = ActionRunner(scope, keys),
) {
    private val screen = LoadableState(scope) {
        withContext(computeDispatcher) { loadConversationScreen(api, conversationId, clock(), zone(), locale) }
    }
    val state: StateFlow<UiState<ConversationScreenData>> = screen.state

    private val _guidance = MutableStateFlow("")
    val guidance: StateFlow<String> = _guidance.asStateFlow()

    val draftActionId: String = "draft:$conversationId"

    val draft: StateFlow<DraftPanel> = runner.states
        .map { DraftPanel.from(it[draftActionId] ?: ActionState.Idle) }
        .stateIn(scope, SharingStarted.Eagerly, DraftPanel.Idle)

    fun load() = screen.load()
    fun refresh() = screen.refresh()

    /**
     * Updates guidance. If the previous attempt failed (its key is kept for a safe retry), a
     * different body under the same key would be `422 idempotency_key_mismatch`, so the edit
     * starts a new action with a new key. Ignored while a request is in flight.
     */
    fun setGuidance(text: String) {
        if (text == _guidance.value) return
        _guidance.value = text
        if (runner.stateOf(draftActionId) !is ActionState.InFlight) keys.complete(draftActionId)
    }

    /**
     * Requests a draft. Returns false when nothing was sent: a request is already in flight
     * (double tap) or the guidance is invalid. Never sends a message.
     */
    fun requestDraft(): Boolean {
        val body = DraftInput.request(_guidance.value) ?: return false
        return runner.submit<Draft>(draftActionId) { key -> api.createDraft(conversationId, body, key) }
    }

    /** Clears the draft panel (e.g. after the user copied the suggestion). */
    fun dismissDraft() = runner.reset(draftActionId)
}
