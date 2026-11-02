package com.wingman.integrations.notifications

import com.wingman.core.model.OutboxAuthorizationOrigin
import com.wingman.core.model.OutboxInstruction
import com.wingman.core.model.OutboxInstructionState
import com.wingman.core.model.UtcTimestamps
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.util.UUID

/** The Wingman user and connection a platform's notification events belong to (set by pairing). */
data class NotificationConnectionScope(val userId: String, val connectionId: String) {
    init {
        require(UUID_RE.matches(userId)) { "userId must be a lowercase canonical UUID" }
        require(UUID_RE.matches(connectionId)) { "connectionId must be a lowercase canonical UUID" }
    }

    private companion object {
        val UUID_RE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    }
}

/**
 * THE ONLY PLACE where the notification foundation's types meet the wire contract.
 *
 * Today it targets frozen contract **v1.2.0** (`connector_event.v1`, `connector_capabilities.v1`,
 * `OutboxInstruction`). Contract **v1.3** (in progress in parallel) adds platforms
 * `tinder`/`bumble`/`hinge`, an event `kind`, `identity` (linked/unlinked/ambiguous),
 * `truncated`/`content_hidden`, `source = notification` and an assisted-delivery state. Until it is
 * merged:
 * - [receivedEnvelope] emits a v1.2-valid `message.received` for LINKED MESSAGE events only; every
 *   other kind or identity returns null and stays in the local inbox.
 * - [v13Fields] holds the proposed v1.3 fields, kept apart so the integrator can merge them into
 *   the payload (or the envelope) under the names v1.3 finally uses. Nothing else needs changing.
 * - [outcomeEnvelope] maps a FAILED reply to `message.send_failed`. A SUBMITTED reply returns null:
 *   v1.2's only success event is `message.send_acknowledged`, which drives `sent`, and a fired
 *   PendingIntent is NOT a send acknowledgement. It waits for v1.3's assisted-delivery state.
 * - `profile_read` has no slot in `connector_capabilities.v1` and is dropped by [capabilitiesDocument].
 */
object NotificationContractMapping {
    const val SOURCE_NOTIFICATION = "notification"

    /** `connector_event.v1` `message.received`, or null when v1.2 cannot represent the event faithfully. */
    fun receivedEnvelope(scope: NotificationConnectionScope, e: NotificationInboxEvent): JsonObject? {
        if (e.kind != NotificationEventKind.MESSAGE || e.identity != NotificationIdentity.LINKED) return null
        val conversation = e.externalConversationId ?: return null
        if (e.contentHidden || e.body == null) return null // v1.2 requires text and has no hidden flag
        return buildJsonObject {
            put("schema_version", "connector_event.v1")
            put("event_id", eventId(scope.userId, "message.received", e.platform, e.externalMessageId))
            put("type", "message.received")
            put("platform", e.platform)
            put("connection_id", scope.connectionId)
            put("user_id", scope.userId)
            put("external_message_id", e.externalMessageId)
            put("external_thread_id", conversation)
            put("occurred_at", ts(e.occurredAtMillis))
            put("observed_at", ts(e.observedAtMillis))
            putJsonObject("payload") {
                putJsonObject("sender") {
                    put("external_contact_id", conversation)
                    put("display_name", e.senderLabel)
                }
                put("text", e.body)
            }
        }
    }

    /** Proposed v1.3 fields for an inbound event (names follow the v1.3 brief; adjust when it merges). */
    fun v13Fields(e: NotificationInboxEvent): JsonObject = buildJsonObject {
        put("source", SOURCE_NOTIFICATION)
        put("kind", e.kind.name.lowercase())
        put("identity", e.identity.name.lowercase())
        put("truncated", e.truncated)
        put("content_hidden", e.contentHidden)
    }

    /** `message.send_failed` for a failed reply; null for SUBMITTED (see the object doc) and CLAIMED. */
    fun outcomeEnvelope(scope: NotificationConnectionScope, r: ReplyRecord, nowMillis: Long): JsonObject? {
        if (r.state != ReplyState.FAILED) return null
        val reason = r.failureReason ?: ReplyFailureReason.OUTCOME_UNKNOWN
        return buildJsonObject {
            put("schema_version", "connector_event.v1")
            put("event_id", eventId(scope.userId, "message.send_failed", r.platform, r.idempotencyKey))
            put("type", "message.send_failed")
            put("platform", r.platform)
            put("connection_id", scope.connectionId)
            put("user_id", scope.userId)
            put("external_message_id", JsonNull)
            put("occurred_at", ts(r.updatedAtMillis))
            put("observed_at", ts(nowMillis))
            putJsonObject("payload") {
                put("outbound_message_id", r.outboundMessageId)
                put("idempotency_key", r.idempotencyKey)
                putJsonObject("failure") {
                    put("code", failureCode(reason))
                    put("retryable", reason.retryable)
                    put("detail", "notification reply: ${reason.name.lowercase()}")
                }
            }
        }
    }

    /** Contract `send_failed_payload.failure.code` for each local reason. */
    fun failureCode(reason: ReplyFailureReason): String = when (reason) {
        ReplyFailureReason.ACCESS_REVOKED -> "permission_denied"
        ReplyFailureReason.PLATFORM_UNAVAILABLE,
        ReplyFailureReason.DIRECT_REPLY_DISABLED,
        ReplyFailureReason.ACTION_MISSING,
        ReplyFailureReason.ACTION_STALE,
        -> "capability_unsupported"
        ReplyFailureReason.ACTION_CANCELED -> "rejected_by_platform"
        ReplyFailureReason.FIRE_ERROR -> "transport_error"
        ReplyFailureReason.LEASE_DEADLINE_PASSED,
        ReplyFailureReason.OUTCOME_UNKNOWN,
        -> "timeout"
    }

    /** `connector_capabilities.v1` for one platform. `profile_read` is dropped (no v1 slot). */
    fun capabilitiesDocument(c: NotificationPlatformCapabilities, assessedAtMillis: Long): JsonObject = buildJsonObject {
        put("schema_version", "connector_capabilities.v1")
        put("platform", c.platform)
        put("connector_version", NOTIFICATION_CONNECTOR_VERSION)
        putJsonObject("capabilities") {
            put("read", capability(c.read, null))
            put("draft", capability(c.draft, null))
            put("send", capability(c.send, deliveryReceipts = false))
            put("swipe", capability(c.swipe, null))
            put("calendar_write", capability(c.calendarWrite, null))
        }
        put("assessed_at", ts(assessedAtMillis))
    }

    /**
     * The ONLY production constructor of [NotificationReplyCommand]: from an instruction the
     * dispatcher leased to this install. Null when the instruction is not ours to execute (other
     * connection, not leased, malformed). [anchorMillis] is the local time the claim was sent
     * (ADR 0006), [marginMillis] the safety margin.
     */
    fun replyCommandFrom(
        scope: NotificationConnectionScope,
        platform: String,
        i: OutboxInstruction,
        serverTime: Instant,
        anchorMillis: Long,
        marginMillis: Long = 15_000,
    ): NotificationReplyCommand? {
        if (i.connectionId != scope.connectionId || i.state != OutboxInstructionState.LEASED) return null
        val deadline = anchorMillis + (i.leaseExpiresAt.toEpochMilli() - serverTime.toEpochMilli()) - marginMillis
        val origin = when (i.authorization.origin) {
            OutboxAuthorizationOrigin.USER_MANUAL -> "user_manual"
            OutboxAuthorizationOrigin.APPROVED_DRAFT -> "approved_draft"
            OutboxAuthorizationOrigin.AUTOPILOT -> "autopilot"
        }
        return runCatching {
            NotificationReplyCommand(
                platform = platform,
                instructionId = i.id,
                outboundMessageId = i.outboundMessageId,
                idempotencyKey = i.idempotencyKey,
                externalConversationId = i.externalThreadId,
                text = i.text,
                authorizedBy = i.authorization.approvalId?.let { "$origin/$it" } ?: origin,
                leaseDeadlineMillis = deadline,
            )
        }.getOrNull()
    }

    private fun capability(s: CapabilityState, deliveryReceipts: Boolean?): JsonObject = buildJsonObject {
        // This path never claims VERIFIED by itself (D-016: verification is operator-only).
        val status = if (s.status == CapabilityStatus.VERIFIED) CapabilityStatus.UNVERIFIED else s.status
        put("status", status.name)
        s.note?.let { put("notes", it.take(500)) }
        if (deliveryReceipts != null && status != CapabilityStatus.UNSUPPORTED) put("reports_delivery_receipts", deliveryReceipts)
    }

    private fun ts(millis: Long): String = UtcTimestamps.format(Instant.ofEpochMilli(millis))

    private fun eventId(vararg parts: String): String =
        UUID.nameUUIDFromBytes(("wingman.notification.event|" + parts.joinToString("|")).toByteArray(Charsets.UTF_8)).toString()
}

/** What [NotificationClaimExecutor.execute] did with one claim answer. Counts only. */
data class NotificationClaimReport(val claimed: Int, val submitted: Int, val failed: Int, val replayed: Int, val ignored: Int)

/**
 * Executes the instructions of ONE claim answer (`claimOutboxInstructions`, ADR 0006) through the
 * reply adapter. This is the authorized dispatch path: the platform connector's poller (WING-027..029,
 * modelled on `SmsOutboxPoller`, which owns the persisted claim key) calls it with each answer.
 */
class NotificationClaimExecutor(
    private val scope: NotificationConnectionScope,
    private val platform: String,
    private val adapter: NotificationReplyAdapter,
    private val marginMillis: Long = 15_000,
) {
    fun execute(result: com.wingman.core.model.OutboxClaimResult, anchorMillis: Long): NotificationClaimReport {
        var submitted = 0
        var failed = 0
        var replayed = 0
        var ignored = 0
        for (i in result.instructions) {
            val command = NotificationContractMapping.replyCommandFrom(scope, platform, i, result.serverTime, anchorMillis, marginMillis)
            if (command == null) {
                ignored++
                continue
            }
            when (val o = adapter.reply(command)) {
                is ReplyOutcome.KeyConflict -> ignored++
                else -> when {
                    o.replayed -> replayed++
                    o is ReplyOutcome.Failed -> failed++
                    else -> submitted++
                }
            }
        }
        return NotificationClaimReport(result.instructions.size, submitted, failed, replayed, ignored)
    }
}
