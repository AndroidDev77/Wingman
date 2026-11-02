package com.wingman.features.approvals

import com.wingman.core.api.AgentActionFilter
import com.wingman.core.api.WingmanApi
import com.wingman.core.model.Actor
import com.wingman.core.model.AgentAction
import com.wingman.core.model.AgentActionType
import com.wingman.core.model.ApprovalReason
import com.wingman.core.ui.CursorPager
import com.wingman.core.ui.PagedList
import com.wingman.core.ui.UiState
import com.wingman.features.conversation.LabelTone
import com.wingman.features.conversation.TimeFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.time.ZoneId
import java.util.Locale

/*
 * Audit timeline (WING-015) from `GET /v1/agent-actions`. Pure Kotlin, JVM-tested.
 *
 * Honesty rule: each entry says exactly what its type records and never more. `send_queued` is
 * "Queued, not sent yet"; `send_attempted` is "Handed to the messaging app, not confirmed";
 * `send_acknowledged` is "Sent (not yet confirmed delivered)"; only `delivery_confirmed` says
 * "Delivered". Labels never look at later or other entries, so nothing is inferred or upgraded.
 */

/** Where an entry sits on the send ladder. Used to check that labels never claim more than the type. */
enum class SendStage { NONE, QUEUED, ATTEMPTED, SENT, DELIVERED }

data class AuditLabel(val title: String, val tone: LabelTone, val stage: SendStage)

object AuditLabels {
    fun of(type: AgentActionType): AuditLabel = when (type) {
        AgentActionType.MESSAGE_RECEIVED -> AuditLabel("Message received", LabelTone.NEUTRAL, SendStage.NONE)
        AgentActionType.ANALYSIS_UPDATED -> AuditLabel("Conversation analyzed", LabelTone.NEUTRAL, SendStage.NONE)
        AgentActionType.DRAFT_GENERATED -> AuditLabel("Draft written (not sent)", LabelTone.NEUTRAL, SendStage.NONE)
        AgentActionType.APPROVAL_REQUESTED -> AuditLabel("Draft proposed for your approval", LabelTone.PENDING, SendStage.NONE)
        AgentActionType.APPROVAL_APPROVED -> AuditLabel("Draft approved (not sent yet)", LabelTone.PENDING, SendStage.NONE)
        AgentActionType.APPROVAL_REJECTED -> AuditLabel("Draft rejected (nothing sent)", LabelTone.NEUTRAL, SendStage.NONE)
        AgentActionType.APPROVAL_EXPIRED -> AuditLabel("Draft expired (nothing sent)", LabelTone.WARNING, SendStage.NONE)
        AgentActionType.APPROVAL_CANCELED -> AuditLabel("Draft canceled (nothing sent)", LabelTone.WARNING, SendStage.NONE)
        AgentActionType.POLICY_BLOCKED -> AuditLabel("Blocked by your settings (nothing sent)", LabelTone.WARNING, SendStage.NONE)
        AgentActionType.SEND_QUEUED -> AuditLabel("Queued, not sent yet", LabelTone.PENDING, SendStage.QUEUED)
        AgentActionType.SEND_ATTEMPTED -> AuditLabel("Handed to the messaging app, not confirmed yet", LabelTone.PENDING, SendStage.ATTEMPTED)
        AgentActionType.SEND_ACKNOWLEDGED -> AuditLabel("Sent (delivery not confirmed)", LabelTone.NEUTRAL, SendStage.SENT)
        AgentActionType.DELIVERY_CONFIRMED -> AuditLabel("Delivered", LabelTone.SUCCESS, SendStage.DELIVERED)
        AgentActionType.SEND_FAILED -> AuditLabel("Send attempt failed", LabelTone.ERROR, SendStage.NONE)
        AgentActionType.SEND_DEAD_LETTERED -> AuditLabel("Gave up sending (not sent)", LabelTone.ERROR, SendStage.NONE)
        AgentActionType.SEND_CANCELED -> AuditLabel("Send canceled (not sent)", LabelTone.WARNING, SendStage.NONE)
        AgentActionType.AUTOMATION_PAUSED -> AuditLabel("Automation paused", LabelTone.WARNING, SendStage.NONE)
        AgentActionType.AUTOMATION_RESUMED -> AuditLabel("Automation resumed", LabelTone.NEUTRAL, SendStage.NONE)
        AgentActionType.AUTOMATION_TAKEOVER -> AuditLabel("You took over", LabelTone.WARNING, SendStage.NONE)
        AgentActionType.PREFERENCES_UPDATED -> AuditLabel("Settings changed", LabelTone.NEUTRAL, SendStage.NONE)
        // Contract 1.2.0: a device lease lapsed with no result. The outcome is UNKNOWN: never "sent", never "failed".
        AgentActionType.LEASE_EXPIRED -> AuditLabel("Phone didn't confirm; outcome unknown", LabelTone.WARNING, SendStage.NONE)
        AgentActionType.CONNECTION_PAIRED -> AuditLabel("Messaging app connected", LabelTone.NEUTRAL, SendStage.NONE)
        AgentActionType.CONNECTION_UNPAIRED -> AuditLabel("Messaging app disconnected", LabelTone.WARNING, SendStage.NONE)
        // Contract 1.3.0 (ADR 0008). None of these is a send: a user's report is not an observed send,
        // and a queued swipe has not happened.
        AgentActionType.NOTICE_RECEIVED -> AuditLabel("Notification noted (no reply)", LabelTone.NEUTRAL, SendStage.NONE)
        AgentActionType.ASSISTED_DELIVERY_QUEUED -> AuditLabel("Draft ready for you to send yourself", LabelTone.PENDING, SendStage.NONE)
        AgentActionType.ASSISTED_DELIVERY_CONFIRMED -> AuditLabel("You reported sending the draft yourself (not confirmed)", LabelTone.NEUTRAL, SendStage.NONE)
        AgentActionType.ASSISTED_DELIVERY_DISMISSED -> AuditLabel("Draft dismissed (nothing sent)", LabelTone.NEUTRAL, SendStage.NONE)
        AgentActionType.ASSISTED_DELIVERY_CANCELED -> AuditLabel("Draft withdrawn (nothing sent)", LabelTone.WARNING, SendStage.NONE)
        AgentActionType.EXPERIMENTAL_CONSENT_RECORDED -> AuditLabel("Experimental connector turned on", LabelTone.WARNING, SendStage.NONE)
        AgentActionType.EXPERIMENTAL_CONSENT_REVOKED -> AuditLabel("Experimental connector turned off", LabelTone.WARNING, SendStage.NONE)
        AgentActionType.IDENTITY_LINK_CREATED -> AuditLabel("You linked two contacts", LabelTone.NEUTRAL, SendStage.NONE)
        AgentActionType.IDENTITY_LINK_DELETED -> AuditLabel("You unlinked two contacts", LabelTone.NEUTRAL, SendStage.NONE)
        AgentActionType.PROFILE_IMPORTED -> AuditLabel("Profile added for review", LabelTone.NEUTRAL, SendStage.NONE)
        AgentActionType.PROFILE_DECISION_RECORDED -> AuditLabel("Your choice saved (not swiped)", LabelTone.NEUTRAL, SendStage.NONE)
        AgentActionType.PROFILE_ACTION_QUEUED -> AuditLabel("Swipe queued (not done yet)", LabelTone.PENDING, SendStage.NONE)
    }

    fun actor(a: Actor): String = when (a) {
        Actor.USER -> "You"
        Actor.AGENT -> "Wingman"
        Actor.DISPATCHER -> "Wingman's sender"
        Actor.CONNECTOR -> "Messaging app"
        Actor.SYSTEM -> "System"
    }

    /** Readable reason code (an `ErrorCode`, `ApprovalReason`, connector failure code or cancel reason). */
    fun reason(code: String?): String? {
        code ?: return null
        ApprovalReason.entries.firstOrNull { it.name.equals(code, ignoreCase = true) }?.let { return ReasonText.of(it) }
        return when (code) {
            "automation_paused" -> "Because automation was paused."
            "automation_takeover" -> "Because you took over."
            "superseded" -> "A newer message replaced it."
            "outcome_unknown" -> "The phone never reported whether it sent, so Wingman won't retry it."
            "connection_unavailable" -> "Because the messaging app was disconnected."
            else -> "Reason: ${code.replace('_', ' ')}"
        }
    }

    /** Selected redacted details (counts, flags). Never message text: the server never sends any. */
    fun detail(a: AgentAction): String? {
        fun int(k: String) = (a.details[k] as? JsonPrimitive)?.intOrNull
        fun bool(k: String) = (a.details[k] as? JsonPrimitive)?.booleanOrNull
        val parts = buildList {
            if (a.type == AgentActionType.APPROVAL_APPROVED && bool("edited") == true) add("You edited the text")
            if (a.type == AgentActionType.SEND_QUEUED && bool("retry") == true) int("next_attempt")?.let { add("Retry, attempt $it") }
            if (a.type == AgentActionType.SEND_FAILED) {
                int("attempt")?.let { add("Attempt $it") }
                when (bool("retryable")) {
                    true -> add("will retry")
                    false -> add("won't retry")
                    null -> Unit
                }
            }
            if (a.type == AgentActionType.APPROVAL_REQUESTED && (a.details["checks"] as? kotlinx.serialization.json.JsonObject)
                    ?.get("paused")?.let { (it as? JsonPrimitive)?.booleanOrNull } == true
            ) add("Automation was paused")
            int("canceled_approvals")?.takeIf { it > 0 }?.let { add("canceled $it pending draft(s)") }
            int("canceled_messages")?.takeIf { it > 0 }?.let { add("canceled $it queued message(s)") }
            if (a.type in SCOPED && (a.details["scope"] as? JsonPrimitive)?.content == "global") add("for everyone")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    private val SCOPED = setOf(AgentActionType.AUTOMATION_PAUSED, AgentActionType.AUTOMATION_RESUMED)
}

data class AuditRow(
    val key: String,
    val title: String,
    val tone: LabelTone,
    val stage: SendStage,
    val actor: String,
    /** Who it was about, when known. */
    val contactName: String?,
    val time: String,
    val relative: String,
    val reason: String?,
    val detail: String?,
    val conversationId: String?,
)

object AuditRows {
    fun of(a: AgentAction, idx: ConversationIndex, now: java.time.Instant, zone: ZoneId, locale: Locale): AuditRow {
        val label = AuditLabels.of(a.type)
        return AuditRow(
            key = a.id,
            title = label.title,
            tone = label.tone,
            stage = label.stage,
            actor = AuditLabels.actor(a.actor),
            contactName = idx.name(a.conversationId) ?: idx.nameForContact(a.contactId),
            time = TimeFormat.messageTime(a.occurredAt, zone, locale),
            relative = TimeFormat.relative(a.occurredAt, now, zone, locale),
            reason = AuditLabels.reason(a.reasonCode),
            detail = AuditLabels.detail(a),
            conversationId = a.conversationId,
        )
    }

    /** Newest first (contract order), de-duplicated by ID. */
    fun build(items: List<AgentAction>, idx: ConversationIndex, now: java.time.Instant, zone: ZoneId, locale: Locale): List<AuditRow> =
        items.distinctBy { it.id }
            .sortedWith(compareByDescending<AgentAction> { it.occurredAt }.thenByDescending { it.id })
            .map { of(it, idx, now, zone, locale) }
}

data class AuditScreenData(val rows: List<AuditRow>, val loadingMore: Boolean, val endReached: Boolean, val loadMoreError: com.wingman.core.ui.UiError?)

/**
 * Audit timeline, global (no filter) or for one conversation or contact. Pages newest first.
 */
class AuditTimelineState(
    private val scope: CoroutineScope,
    private val api: WingmanApi,
    val filter: AgentActionFilter = AgentActionFilter(),
    private val clock: () -> java.time.Instant = java.time.Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val locale: Locale = Locale.getDefault(),
    pageSize: Int = PAGE_SIZE,
) {
    private val pager = CursorPager(scope, pageSize) { page -> api.listAgentActions(page, filter) }
    private val index = MutableStateFlow(ConversationIndex.EMPTY)

    val state: StateFlow<UiState<AuditScreenData>> = combine(pager.state, index) { p, idx -> reduce(p, idx) }
        .stateIn(scope, SharingStarted.Eagerly, UiState.Loading)

    fun load() {
        scope.launch { (ConversationIndex.load(api) as? com.wingman.core.api.ApiResult.Success)?.value?.let { index.value = it } }
        pager.load()
    }

    fun refresh() = pager.refresh()
    fun loadMore() = pager.loadMore()

    private fun reduce(p: UiState<PagedList<AgentAction>>, idx: ConversationIndex): UiState<AuditScreenData> = when (p) {
        UiState.Loading -> UiState.Loading
        UiState.Empty -> UiState.Empty
        is UiState.Error -> p
        is UiState.Content -> UiState.Content(
            AuditScreenData(
                rows = AuditRows.build(p.data.items, idx, clock(), zone(), locale),
                loadingMore = p.data.loadingMore,
                endReached = p.data.endReached,
                loadMoreError = p.data.loadMoreError,
            ),
            refreshing = p.refreshing,
            refreshError = p.refreshError,
        )
    }

    companion object {
        const val PAGE_SIZE = 50
    }
}
