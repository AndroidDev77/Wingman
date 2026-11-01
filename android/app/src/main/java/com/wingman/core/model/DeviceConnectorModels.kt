@file:UseSerializers(UtcInstantSerializer::class)

package com.wingman.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant

/*
 * Contract v1.1.0 (ADR 0006) device-connector schemas: event ingest and the leased device outbox.
 * Only what `ingestConnectorEvent` and `claimOutboxInstructions` need. Pure Kotlin.
 * Nullability follows the rules in Primitives.kt.
 */

/** `components.schemas.ConnectorEventIngestOutcome`. */
@Serializable
enum class ConnectorEventIngestOutcome {
    /** First time this event was seen (HTTP 202). */
    @SerialName("accepted") ACCEPTED,
    /** This `event_id` was already ingested; nothing changed (HTTP 200). */
    @SerialName("duplicate_event") DUPLICATE_EVENT,
    /** Same `(user_id, platform, external_message_id)` already stored under another `event_id` (HTTP 200). */
    @SerialName("duplicate_message") DUPLICATE_MESSAGE,
}

/** `components.schemas.ConnectorEventIngestResult`: the body of both the 202 and the 200 answer. */
@Serializable
data class ConnectorEventIngestResult(
    @SerialName("event_id") val eventId: String,
    val outcome: ConnectorEventIngestOutcome,
    /** Null for connection events. Required-but-nullable: no default. */
    @SerialName("message_id") val messageId: String?,
    @SerialName("recorded_at") val recordedAt: Instant,
)

/** `components.schemas.OutboxInstructionState`. A claimed [OutboxInstruction] is always [LEASED]. */
@Serializable
enum class OutboxInstructionState {
    @SerialName("pending") PENDING,
    @SerialName("leased") LEASED,
    @SerialName("completed") COMPLETED,
    @SerialName("expired") EXPIRED,
    @SerialName("canceled") CANCELED,
}

/** `OutboxAuthorization.origin`: the outbound `MessageOrigin` values (never `contact`). */
@Serializable
enum class OutboxAuthorizationOrigin {
    @SerialName("user_manual") USER_MANUAL,
    @SerialName("approved_draft") APPROVED_DRAFT,
    @SerialName("autopilot") AUTOPILOT,
}

/**
 * `components.schemas.OutboxAuthorization`. Informational for the device's local audit; the device
 * cannot widen or change it. [approvalId] is set exactly when [origin] is `approved_draft`.
 */
@Serializable
data class OutboxAuthorization(
    val origin: OutboxAuthorizationOrigin,
    @SerialName("approval_id") val approvalId: String?,
)

/**
 * `components.schemas.OutboxInstruction`: one leased send attempt. [text] and [externalThreadId]
 * are private, so [toString] redacts them. Never log them.
 */
@Serializable
data class OutboxInstruction(
    val id: String,
    @SerialName("connection_id") val connectionId: String,
    @SerialName("outbound_message_id") val outboundMessageId: String,
    @SerialName("idempotency_key") val idempotencyKey: String,
    val attempt: Int,
    val state: OutboxInstructionState,
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("leased_at") val leasedAt: Instant,
    @SerialName("lease_expires_at") val leaseExpiresAt: Instant,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("external_thread_id") val externalThreadId: String,
    val text: String,
    val authorization: OutboxAuthorization,
) {
    override fun toString(): String =
        "OutboxInstruction(id=$id, connection=$connectionId, outbound=$outboundMessageId, key=$idempotencyKey, " +
            "attempt=$attempt, state=$state, leaseExpiresAt=$leaseExpiresAt, thread=<redacted>, " +
            "text=<redacted ${text.length} chars>, origin=${authorization.origin})"
}

/** `components.schemas.OutboxClaimRequest`. Unknown properties are rejected by the server, so only these two. */
@Serializable
data class OutboxClaimRequest(
    @SerialName("device_install_id") val deviceInstallId: String,
    /** 1..20; null lets the server default to 1. */
    @SerialName("max_instructions") val maxInstructions: Int? = null,
) {
    init {
        require(maxInstructions == null || maxInstructions in 1..20) { "max_instructions must be 1..20" }
    }
}

/** `components.schemas.OutboxClaimResult`. */
@Serializable
data class OutboxClaimResult(
    val instructions: List<OutboxInstruction>,
    /** Server clock at lease time; the device computes its deadline relative to it (ADR 0006). */
    @SerialName("server_time") val serverTime: Instant,
)
