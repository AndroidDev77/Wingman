package com.wingman.core.api.mock

import com.wingman.core.model.ApprovalReason
import com.wingman.core.model.AssistedDelivery
import com.wingman.core.model.AssistedDeliveryStatus
import com.wingman.core.model.ConnectionTransport
import com.wingman.core.model.InboundEventKind
import com.wingman.core.model.InboundNotice
import com.wingman.core.model.SenderIdentity
import java.time.Instant

/**
 * Contract 1.3.0 offline fixtures for the assisted flow (WING-027/028): one pending assisted
 * delivery and two notices, so mock builds show the "Send by hand" screen with content. All text
 * is synthetic. Kept apart from [MockFixtures] so the shared fixture file is untouched.
 */
object MockAssistedFixtures {
    private fun id(suffix: String) = "0192f3a0-0000-7000-8000-" + suffix.padStart(12, '0')
    private fun t(raw: String): Instant = Instant.parse(raw)

    /** A Tinder notification connection, as the server would store it after pairing. */
    val CONNECTION_TINDER = id("a27")
    val ASSISTED_PENDING = id("a2701")

    private val sam = MockFixtures.conversations.first { it.id == MockFixtures.CONVERSATION_SAM }

    val assistedDeliveries: List<AssistedDelivery> = listOf(
        AssistedDelivery(
            id = ASSISTED_PENDING, conversationId = sam.id, contactId = sam.contact.id, connectionId = CONNECTION_TINDER,
            platform = "tinder", externalThreadId = "shortcut:synthetic-thread-1", draftId = id("e27"),
            text = "Synthetic draft: that sounds like a great trip, what was the best meal?",
            reasons = listOf(ApprovalReason.ASSISTED_MODE), status = AssistedDeliveryStatus.PENDING,
            finalText = null, messageId = null, cancelReason = null,
            createdAt = t("2026-10-31T18:05:00Z"), decidedAt = null,
        ),
    )

    val inboundNotices: List<InboundNotice> = listOf(
        InboundNotice(
            id = id("f2701"), connectionId = CONNECTION_TINDER, platform = "tinder", kind = InboundEventKind.MATCH,
            identity = SenderIdentity.UNLINKED, senderLabel = null, text = "Synthetic: you have a new match",
            truncated = false, contentHidden = false, externalMessageId = "notif:synthetic-match-1",
            externalConversationId = null, source = ConnectionTransport.NOTIFICATION,
            occurredAt = t("2026-10-31T17:00:00Z"), createdAt = t("2026-10-31T17:00:02Z"),
        ),
        InboundNotice(
            id = id("f2801"), connectionId = id("a28"), platform = "bumble", kind = InboundEventKind.EXPIRY,
            identity = SenderIdentity.UNLINKED, senderLabel = null, text = "Synthetic: a match expires soon",
            truncated = false, contentHidden = false, externalMessageId = "notif:synthetic-expiry-1",
            externalConversationId = null, source = ConnectionTransport.NOTIFICATION,
            occurredAt = t("2026-10-31T16:00:00Z"), createdAt = t("2026-10-31T16:00:02Z"),
        ),
    )
}
