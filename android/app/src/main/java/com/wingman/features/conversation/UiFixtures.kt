package com.wingman.features.conversation

import com.wingman.core.api.mock.MockFixtures
import com.wingman.core.model.DeliveryState
import com.wingman.core.model.Message
import com.wingman.core.model.MessageDirection
import com.wingman.core.model.MessageFailure
import com.wingman.core.model.MessageOrigin
import com.wingman.core.ui.UiError
import com.wingman.core.ui.UiState
import com.wingman.features.inbox.InboxList
import com.wingman.features.inbox.InboxRows
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/**
 * Deterministic screen states for Compose previews and Compose UI tests (WING-014 "UI test
 * fixtures for empty/loading/error states"). Pure Kotlin: built from [MockFixtures] with a fixed
 * clock, UTC and Locale.US, so every preview renders the same text on any device.
 */
object UiFixtures {
    private val zone: ZoneId = ZoneOffset.UTC
    private val locale: Locale = Locale.US
    private val connections = MockFixtures.connections.associateBy { it.id }

    /* ---------------------------------------------------------- inbox */

    val inboxLoading: UiState<InboxList> = UiState.Loading
    val inboxEmpty: UiState<InboxList> = UiState.Empty
    val inboxError: UiState<InboxList> =
        UiState.Error(UiError("Can't reach Wingman. Check your connection and try again.", retryable = true, isNetwork = true))

    val inboxContent: UiState<InboxList> = UiState.Content(
        InboxList(
            rows = MockFixtures.conversations.map { InboxRows.row(it, connections[it.connectionId], MockFixtures.NOW, zone, locale) },
            loadingMore = false,
            loadMoreError = null,
            endReached = true,
            capabilitiesKnown = true,
        ),
    )

    /* ---------------------------------------------------------- conversation */

    val conversationLoading: UiState<ConversationScreenData> = UiState.Loading
    val conversationError: UiState<ConversationScreenData> =
        UiState.Error(UiError("This item no longer exists.", retryable = false))

    /** One bubble per outbound delivery state, so previews show every label side by side. */
    val everyDeliveryState: List<Message> = DeliveryState.entries.mapIndexed { i, state ->
        val inbound = state == DeliveryState.RECEIVED
        val at = MockFixtures.NOW.minusSeconds((DeliveryState.entries.size - i) * 300L)
        Message(
            id = "0192f3a0-0000-7000-8003-" + i.toString().padStart(12, '0'),
            conversationId = MockFixtures.CONVERSATION_SAM,
            direction = if (inbound) MessageDirection.INBOUND else MessageDirection.OUTBOUND,
            origin = if (inbound) MessageOrigin.CONTACT else MessageOrigin.USER_MANUAL,
            text = "State: ${state.name.lowercase()}",
            deliveryState = state,
            attemptCount = if (inbound) 0 else 1,
            failure = if (state == DeliveryState.FAILED || state == DeliveryState.DEAD_LETTERED) {
                MessageFailure("transport_error", null, retryable = state == DeliveryState.FAILED)
            } else {
                null
            },
            createdAt = at,
            sentAt = if (state == DeliveryState.SENT || state == DeliveryState.DELIVERED) at.plusSeconds(1) else null,
            deliveredAt = if (state == DeliveryState.DELIVERED) at.plusSeconds(2) else null,
        )
    }

    fun conversation(conversationId: String, messages: List<Message>): UiState<ConversationScreenData> {
        val c = MockFixtures.conversations.first { it.id == conversationId }
        val connection = connections[c.connectionId]
        val row = InboxRows.row(c, connection, MockFixtures.NOW, zone, locale)
        return UiState.Content(
            ConversationScreenData(
                conversationId = c.id,
                contactName = row.contactName,
                subtitle = listOf(row.stage, row.intent, row.automation).joinToString(" · "),
                availability = Availability.of(c.platform, connection),
                rows = MessageRows.build(messages, zone, locale),
                truncated = false,
            ),
        )
    }

    /** Short thread on a send-VERIFIED (simulated) connection, with every delivery label. */
    val conversationDeliveryStates: UiState<ConversationScreenData> get() =
        conversation(MockFixtures.CONVERSATION_SAM, everyDeliveryState)

    /** 120-message thread ([MockFixtures.LONG_THREAD_SIZE]) for scroll/perf checks. */
    val conversationLong: UiState<ConversationScreenData> get() =
        conversation(MockFixtures.CONVERSATION_RILEY, MockFixtures.messages.getValue(MockFixtures.CONVERSATION_RILEY))

    /** Conversation on a connection whose `send` is UNSUPPORTED. */
    val conversationUnsupported: UiState<ConversationScreenData> get() =
        conversation(MockFixtures.CONVERSATION_CASEY, MockFixtures.messages.getValue(MockFixtures.CONVERSATION_CASEY))

    val draftReady: DraftPanel = DraftPanel.ready(
        MockFixtures.approvals.first().draft,
    )
    val draftFailed: DraftPanel = DraftPanel.Failed(UiError("A service Wingman depends on is unavailable."))
}
