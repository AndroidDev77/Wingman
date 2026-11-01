package com.wingman.features.approvals

import com.wingman.core.model.Approval
import com.wingman.core.model.ApprovalReason
import com.wingman.core.model.ApprovalStatus
import com.wingman.core.model.ApproveRequest
import com.wingman.core.model.DeliveryState
import com.wingman.core.model.Message
import com.wingman.core.ui.UiError
import com.wingman.features.conversation.DeliveryLabel
import com.wingman.features.conversation.DeliveryLabels
import com.wingman.features.conversation.LabelTone
import java.time.Duration
import java.time.Instant

/*
 * Approval queue display logic (WING-015). Pure Kotlin, JVM-tested.
 *
 * Delivery truth: an approved draft is labeled from the server's `Message.delivery_state` only
 * (via [DeliveryLabels]), so it reads "Queued, not sent yet" until the server reports `sent`.
 */

/** Plain-language reasons a draft needs approval (contract `ApprovalReason`). */
object ReasonText {
    fun of(r: ApprovalReason): String = when (r) {
        ApprovalReason.ASSISTED_MODE -> "You asked to approve replies to this person before they go out (assisted mode)."
        ApprovalReason.SENSITIVE_CONTENT -> "It touches on something sensitive."
        ApprovalReason.DATE_OR_COMMITMENT -> "It suggests a date or makes a commitment."
        ApprovalReason.LOW_CONFIDENCE -> "Wingman isn't confident this reply is right."
        ApprovalReason.FIRST_MESSAGE -> "It would be the first message to this person."
        ApprovalReason.OUTSIDE_ACTIVE_HOURS -> "It's outside the hours you allowed automatic replies."
        ApprovalReason.CAPABILITY_UNVERIFIED -> "Sending on this app hasn't been verified, so Wingman never sends there on its own."
        ApprovalReason.SEND_LIMIT_REACHED -> "You've reached your daily limit for automatic replies."
        // D-015: until contract v1.2, an approval caused by a pause is reported as `user_rule`.
        ApprovalReason.USER_RULE -> "One of your rules asks for approval, or automation is paused."
    }

    fun all(reasons: List<ApprovalReason>): List<String> = reasons.distinct().map(::of)
}

/** Age and expiry of a pending approval. Timestamps are server time; [now] is the device clock. */
object ApprovalTiming {
    fun duration(d: Duration): String {
        val m = d.toMinutes()
        return when {
            m < 1 -> "less than a minute"
            m < 60 -> "${m}m"
            m < 60 * 24 -> "${d.toHours()}h"
            else -> "${d.toDays()}d"
        }
    }

    fun age(createdAt: Instant, now: Instant): String {
        val d = Duration.between(createdAt, now)
        return if (d.isNegative || d.toMinutes() < 1) "Waiting since just now" else "Waiting ${duration(d)}"
    }

    /** Null when the approval never expires. */
    fun expiry(expiresAt: Instant?, now: Instant): String? {
        expiresAt ?: return null
        val d = Duration.between(now, expiresAt)
        return if (d.isNegative || d.isZero) "Expired: approving it will be refused" else "Expires in ${duration(d)}"
    }

    fun isExpired(expiresAt: Instant?, now: Instant): Boolean = expiresAt != null && !expiresAt.isAfter(now)
}

/** Builds the approve body from the editor. */
object ApproveInput {
    /** Contract `ApproveRequest.edited_text` maxLength. */
    const val MAX = 4000

    /**
     * Unedited (or edited back to the draft text): `edited_text` is omitted and the draft is sent
     * as-is. Otherwise the edited text is exactly what is sent. Blank or too long: null (no request).
     */
    fun request(draftText: String?, editorText: String?): ApproveRequest? {
        if (editorText == null || editorText == draftText) {
            return if (draftText.isNullOrEmpty()) null else ApproveRequest(editedText = null)
        }
        if (editorText.isBlank() || editorText.length > MAX) return null
        return ApproveRequest(editedText = editorText)
    }

    fun error(editorText: String?): String? = when {
        editorText == null -> null
        editorText.isBlank() -> "The reply can't be empty."
        editorText.length > MAX -> "Keep the reply under $MAX characters."
        else -> null
    }
}

/** A pending draft as shown in the queue. */
data class ApprovalCard(
    val approvalId: String,
    val conversationId: String,
    val contactId: String,
    val contactName: String,
    val draftText: String,
    val reasons: List<String>,
    val age: String,
    /** Null when the approval has no expiry. */
    val expiry: String?,
    val expired: Boolean,
) {
    companion object {
        fun of(a: Approval, contactName: String?, now: Instant) = ApprovalCard(
            approvalId = a.id,
            conversationId = a.conversationId,
            contactId = a.contactId,
            contactName = contactName ?: "Unknown contact",
            draftText = a.draft.text.orEmpty(),
            reasons = ReasonText.all(a.reasons),
            age = ApprovalTiming.age(a.createdAt, now),
            expiry = ApprovalTiming.expiry(a.expiresAt, now),
            expired = ApprovalTiming.isExpired(a.expiresAt, now),
        )
    }
}

/** Which decision is in flight for an item. */
enum class Decision { APPROVING, REJECTING }

/** One pending queue row: the card plus the user's editor and any failed attempt. */
data class PendingItem(
    val card: ApprovalCard,
    val editing: Boolean,
    /** The editor text; kept across network and server errors. */
    val editText: String,
    val editError: String?,
    val inFlight: Decision?,
    /** Last failed approve/reject. The editor text is untouched. */
    val error: UiError?,
) {
    val canDecide: Boolean get() = inFlight == null && editError == null
}

/**
 * A draft that is no longer pending, as the SERVER reports it. [delivery] comes only from a
 * server `Message`; when that message is unknown it is null and nothing is claimed.
 */
data class DecidedItem(
    val approvalId: String,
    val conversationId: String,
    val contactName: String,
    val title: String,
    val explanation: String,
    val tone: LabelTone,
    /** The text the server says it queued (`final_text`), if any. */
    val finalText: String?,
    val messageId: String?,
    val delivery: DeliveryLabel?,
    val checkingDelivery: Boolean,
    /** A failed status check; the last known state stays shown. */
    val error: UiError? = null,
    /** True when the server's delivery state can no longer change (delivered, dead-lettered, canceled). */
    val deliveryFinal: Boolean = false,
) {
    /** "Check status" is offered while there is a message whose state may still change. */
    val canCheckDelivery: Boolean get() = messageId != null && !checkingDelivery && !deliveryFinal
}

/** What the server told us about a decided approval, plus the latest known state of its message. */
data class Outcome(
    val approval: Approval,
    val message: Message?,
    /** True when we learned the outcome from a 409 `approval_not_pending` refresh, not from our own decision. */
    val fromConflict: Boolean,
    val checkingDelivery: Boolean = false,
    val error: UiError? = null,
) {
    fun display(contactName: String?): DecidedItem {
        val a = approval
        val (title, explanation, tone) = when (a.status) {
            ApprovalStatus.APPROVED -> Triple(
                if (fromConflict) "Already approved" else "Approved",
                if (fromConflict) {
                    "This draft was already approved (possibly from another device or an earlier attempt). It was not approved twice."
                } else if (message == null && a.messageId != null) {
                    "Approved. Tap \"Check status\" to see whether it has been sent."
                } else {
                    "Approved. It shows as sent only after the messaging app confirms it."
                },
                LabelTone.PENDING,
            )
            ApprovalStatus.REJECTED -> Triple(
                if (fromConflict) "Already rejected" else "Rejected",
                "Nothing was sent.",
                LabelTone.NEUTRAL,
            )
            ApprovalStatus.EXPIRED -> Triple("Expired", "This draft expired before it was approved. Nothing was sent.", LabelTone.WARNING)
            ApprovalStatus.CANCELED -> Triple(
                "Canceled",
                "Wingman canceled this draft: you took over the conversation or a newer message arrived. Nothing was sent.",
                LabelTone.WARNING,
            )
            ApprovalStatus.PENDING -> Triple("Still waiting", "This draft is still waiting for your approval.", LabelTone.PENDING)
        }
        return DecidedItem(
            approvalId = a.id,
            conversationId = a.conversationId,
            contactName = contactName ?: "Unknown contact",
            title = title,
            explanation = explanation,
            tone = tone,
            finalText = a.finalText,
            messageId = a.messageId ?: message?.id,
            delivery = message?.let { DeliveryLabels.of(it) },
            checkingDelivery = checkingDelivery,
            error = error,
            deliveryFinal = message?.deliveryState in TERMINAL,
        )
    }

    companion object {
        val TERMINAL = setOf(DeliveryState.DELIVERED, DeliveryState.DEAD_LETTERED, DeliveryState.CANCELED)
    }
}

/** The queue screen: pending drafts (newest first) and drafts handled in this session. */
data class ApprovalsScreenData(
    val pending: List<PendingItem>,
    val decided: List<DecidedItem>,
    val loadingMore: Boolean,
    val endReached: Boolean,
    val loadMoreError: UiError?,
) {
    val isEmpty: Boolean get() = pending.isEmpty() && decided.isEmpty()
}
