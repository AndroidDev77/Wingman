package com.wingman.features.inbox

import com.wingman.core.api.ConversationFilter
import com.wingman.core.model.AutomationMode
import com.wingman.core.model.Connection
import com.wingman.core.model.Conversation
import com.wingman.core.model.IntentMode
import com.wingman.core.ui.PagedList
import com.wingman.core.ui.UiError
import com.wingman.core.ui.UiState
import com.wingman.features.conversation.Availability
import com.wingman.features.conversation.LabelTone
import com.wingman.features.conversation.Labels
import com.wingman.features.conversation.TimeFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/*
 * Pure-Kotlin inbox display models and reducers (WING-014). The Compose screen only renders
 * [InboxRow]s; all formatting, availability and filter logic lives here and is JVM-tested.
 */

/** User-selected inbox filters. `null` means "any". Maps 1:1 to contract query parameters. */
data class InboxFilter(
    val intent: IntentMode? = null,
    val automationMode: AutomationMode? = null,
) {
    val isActive: Boolean get() = intent != null || automationMode != null

    fun toApi(): ConversationFilter = ConversationFilter(intent = intent, automationMode = automationMode)

    val emptyMessage: String
        get() = if (isActive) "No conversations match these filters." else "No conversations yet. Connect a platform to get started."

    companion object {
        /** Chip options, "Any" first. */
        val intentOptions: List<IntentMode?> = listOf(null) + IntentMode.entries
        val automationOptions: List<AutomationMode?> = listOf(null) + AutomationMode.entries

        fun intentLabel(mode: IntentMode?): String = mode?.let(Labels::intent) ?: "Any intent"
        fun automationLabel(mode: AutomationMode?): String = mode?.let(Labels::automationMode) ?: "Any automation"
    }
}

data class InboxRow(
    /** Stable LazyColumn key: the conversation ID. */
    val key: String,
    val conversationId: String,
    val contactName: String,
    /** Platform badge text (connection display name, or the opaque platform string). */
    val platformBadge: String,
    /** Availability from capability labels; e.g. "Send UNVERIFIED", "Can't send from Wingman". */
    val availabilityLabel: String,
    val availabilityTone: LabelTone,
    val canSend: Boolean,
    val lastActivity: String,
    val preview: String?,
    val stage: String,
    val intent: String,
    val automation: String,
    /** Null = no badge. */
    val unreadBadge: String?,
    /** e.g. "1 draft to review"; null when none. */
    val pendingApprovals: String?,
)

/** What the inbox list renders inside [UiState.Content]. */
data class InboxList(
    val rows: List<InboxRow>,
    val loadingMore: Boolean,
    val loadMoreError: UiError?,
    val endReached: Boolean,
    /** False when connections failed to load: badges then say "Status unknown" (never "connected"). */
    val capabilitiesKnown: Boolean,
)

object InboxRows {
    fun row(c: Conversation, connection: Connection?, now: Instant, zone: ZoneId, locale: Locale = Locale.getDefault()): InboxRow {
        val availability = Availability.of(c.platform, connection)
        return InboxRow(
            key = c.id,
            conversationId = c.id,
            contactName = c.contact.displayName.ifBlank { "Unknown contact" },
            platformBadge = availability.badge,
            availabilityLabel = availability.label,
            availabilityTone = availability.tone,
            canSend = availability.canSend,
            lastActivity = TimeFormat.relative(c.lastActivityAt, now, zone, locale),
            preview = c.lastMessagePreview?.takeIf { it.isNotBlank() },
            stage = Labels.stage(c.stage),
            intent = Labels.intent(c.effectiveIntent),
            automation = Labels.automationStatus(c.automation),
            unreadBadge = Labels.unreadBadge(c.unreadCount),
            pendingApprovals = when (val n = c.pendingApprovalCount) {
                0 -> null
                1 -> "1 draft to review"
                else -> "$n drafts to review"
            },
        )
    }

    /**
     * Reduces the pager state plus loaded connections into the screen state. Duplicate
     * conversation IDs across pages (possible if activity reorders the list between page loads)
     * keep their first occurrence, so LazyColumn keys stay unique.
     */
    fun reduce(
        pager: UiState<PagedList<Conversation>>,
        connections: List<Connection>?,
        now: Instant,
        zone: ZoneId,
        locale: Locale = Locale.getDefault(),
    ): UiState<InboxList> = when (pager) {
        UiState.Loading -> UiState.Loading
        UiState.Empty -> UiState.Empty
        is UiState.Error -> pager
        is UiState.Content -> {
            val byId = connections?.associateBy { it.id }.orEmpty()
            val rows = pager.data.items
                .distinctBy { it.id }
                .map { row(it, byId[it.connectionId], now, zone, locale) }
            UiState.Content(
                InboxList(
                    rows = rows,
                    loadingMore = pager.data.loadingMore,
                    loadMoreError = pager.data.loadMoreError,
                    endReached = pager.data.endReached,
                    capabilitiesKnown = connections != null,
                ),
                refreshing = pager.refreshing,
                refreshError = pager.refreshError,
            )
        }
    }
}
