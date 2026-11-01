package com.wingman.features.conversation

import com.wingman.core.model.ApprovalReason
import com.wingman.core.model.Draft
import com.wingman.core.model.DraftCreateRequest
import com.wingman.core.model.DraftDecision
import com.wingman.core.ui.ActionState
import com.wingman.core.ui.UiError

/*
 * Draft area display logic. A draft is an LLM suggestion (contract: "never sends"): this area
 * has no send action, and every state says so.
 */

object DraftInput {
    /** Contract `DraftCreateRequest.guidance` maxLength. */
    const val GUIDANCE_MAX = 500

    /** Blank guidance is sent as null; over-long guidance is rejected client-side. */
    fun request(guidance: String): DraftCreateRequest? {
        val trimmed = guidance.trim()
        if (trimmed.length > GUIDANCE_MAX) return null
        return DraftCreateRequest(guidance = trimmed.ifEmpty { null })
    }

    fun guidanceError(guidance: String): String? =
        if (guidance.trim().length > GUIDANCE_MAX) "Keep guidance under $GUIDANCE_MAX characters." else null
}

/** What the draft area shows. */
sealed interface DraftPanel {
    /** Nothing requested yet. */
    data object Idle : DraftPanel

    data object Generating : DraftPanel

    data class Failed(val error: UiError) : DraftPanel

    data class Ready(
        val draftId: String,
        /** Suggested reply text, editable/copyable by the user; null when the AI declined to suggest one. */
        val suggestion: String?,
        /** Headline, e.g. "Suggested reply" / "Wingman suggests not replying yet". */
        val title: String,
        /** The AI's question for the user (ask_user) or its rationale. */
        val explanation: String,
        val approvalNote: String?,
        /** Always shown: drafting never sends. */
        val notSentNote: String = NOT_SENT,
        /** True when the server replayed an earlier identical request (no new generation). */
        val replayed: Boolean,
    ) : DraftPanel

    companion object {
        const val NOT_SENT = "Suggestion only. Nothing has been sent."

        fun from(state: ActionState<Any?>): DraftPanel = when (state) {
            ActionState.Idle -> Idle
            ActionState.InFlight -> Generating
            is ActionState.Failed -> Failed(state.error)
            is ActionState.Done -> (state.value as? Draft)?.let { ready(it, state.replayed) } ?: Idle
        }

        fun ready(d: Draft, replayed: Boolean = false): Ready = Ready(
            draftId = d.id,
            suggestion = d.text?.takeIf { d.decision == DraftDecision.SEND_CANDIDATE },
            title = when (d.decision) {
                DraftDecision.SEND_CANDIDATE -> "Suggested reply"
                DraftDecision.NO_SEND -> "Wingman suggests not replying yet"
                DraftDecision.ASK_USER -> "Wingman needs your input"
            },
            explanation = when (d.decision) {
                DraftDecision.ASK_USER -> d.questionForUser ?: d.rationale
                else -> d.rationale
            },
            approvalNote = if (d.requiresApproval) {
                "Would need approval: " + d.approvalReasons.joinToString { reasonLabel(it) }.ifEmpty { "policy" }
            } else {
                null
            },
            replayed = replayed,
        )

        fun reasonLabel(r: ApprovalReason): String = when (r) {
            ApprovalReason.ASSISTED_MODE -> "assisted mode"
            ApprovalReason.SENSITIVE_CONTENT -> "sensitive content"
            ApprovalReason.DATE_OR_COMMITMENT -> "date or commitment"
            ApprovalReason.LOW_CONFIDENCE -> "low confidence"
            ApprovalReason.FIRST_MESSAGE -> "first message"
            ApprovalReason.OUTSIDE_ACTIVE_HOURS -> "outside active hours"
            ApprovalReason.CAPABILITY_UNVERIFIED -> "platform capability unverified"
            ApprovalReason.SEND_LIMIT_REACHED -> "send limit reached"
            ApprovalReason.USER_RULE -> "your rule"
            ApprovalReason.PAUSED -> "automation is paused"
        }
    }
}
