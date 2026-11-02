@file:UseSerializers(UtcInstantSerializer::class)

package com.wingman.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant

/*
 * Contract v1.3.0 (ADR 0008 §4 and §6) schemas used by the notification-assisted connectors
 * (WING-027 Tinder, WING-028 Bumble): assisted deliveries and inbound notices. Nullability follows
 * Primitives.kt: required-but-nullable fields have no Kotlin default. Every text field here is
 * private (draft text, notice text, sender labels), so [toString] redacts it.
 */

/**
 * `components.schemas.AssistedDelivery`: a draft-only instruction the dispatcher creates when a
 * connection cannot send. Wingman never transmits it; the user sends it by hand in the platform app
 * and may then report it with `confirmAssistedDelivery` (`user_reported_sent`, never `sent`).
 */
@Serializable
data class AssistedDelivery(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("contact_id") val contactId: String,
    @SerialName("connection_id") val connectionId: String,
    val platform: String,
    /** Platform thread to open, when known. */
    @SerialName("external_thread_id") val externalThreadId: String?,
    @SerialName("draft_id") val draftId: String,
    /** The suggested reply. Private. */
    val text: String,
    val reasons: List<ApprovalReason>,
    val status: AssistedDeliveryStatus,
    /** What the user reported sending; set only when [status] is USER_REPORTED_SENT. Private. */
    @SerialName("final_text") val finalText: String?,
    @SerialName("message_id") val messageId: String?,
    @SerialName("cancel_reason") val cancelReason: String?,
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("decided_at") val decidedAt: Instant?,
) {
    override fun toString(): String =
        "AssistedDelivery(id=$id, conversation=$conversationId, platform=$platform, status=$status, " +
            "text=<redacted ${text.length} chars>, reasons=$reasons, message=$messageId, cancel=$cancelReason)"
}

/** `components.schemas.AssistedDeliveryConfirmRequest`. Null [sentText] = the draft text as is. */
@Serializable
data class AssistedDeliveryConfirmRequest(
    @SerialName("sent_text") val sentText: String? = null,
) {
    init {
        require(sentText == null || sentText.length in 1..4000) { "sent_text must be 1..4000 chars" }
    }

    override fun toString(): String = "AssistedDeliveryConfirmRequest(sentText=${sentText?.let { "<redacted ${it.length} chars>" }})"
}

/**
 * `components.schemas.AssistedDeliveryResult`. [message] is the `assisted_draft` message in
 * `user_reported_sent` on confirm, and null on dismiss (required-but-nullable: no default).
 */
@Serializable
data class AssistedDeliveryResult(
    @SerialName("assisted_delivery") val assistedDelivery: AssistedDelivery,
    val message: Message?,
)

/**
 * `components.schemas.InboundNotice`: an inbound event shown but never drafted, replied to or merged
 * (matches, likes, expiry and system notices, unlinked or partial messages).
 */
@Serializable
data class InboundNotice(
    val id: String,
    @SerialName("connection_id") val connectionId: String,
    val platform: String,
    val kind: InboundEventKind,
    val identity: SenderIdentity,
    @SerialName("sender_label") val senderLabel: String?,
    /** As observed (possibly partial); empty when [contentHidden]. Private. */
    val text: String,
    val truncated: Boolean,
    @SerialName("content_hidden") val contentHidden: Boolean,
    @SerialName("external_message_id") val externalMessageId: String,
    @SerialName("external_conversation_id") val externalConversationId: String?,
    val source: ConnectionTransport?,
    @SerialName("occurred_at") val occurredAt: Instant,
    @SerialName("created_at") val createdAt: Instant,
) {
    override fun toString(): String =
        "InboundNotice(id=$id, platform=$platform, kind=$kind, identity=$identity, " +
            "sender=${senderLabel?.let { "<redacted ${it.length} chars>" }}, text=<redacted ${text.length} chars>, " +
            "truncated=$truncated, hidden=$contentHidden)"
}
