package com.wingman.features.assisted

import com.wingman.core.api.ApiError
import com.wingman.core.api.ApiResult
import com.wingman.core.api.AssistedDeliveryFilter
import com.wingman.core.api.IdempotencyKeyRegistry
import com.wingman.core.api.PageRequest
import com.wingman.core.api.WingmanApi
import com.wingman.core.api.userMessage
import com.wingman.core.model.ApprovalReason
import com.wingman.core.model.AssistedDelivery
import com.wingman.core.model.AssistedDeliveryConfirmRequest
import com.wingman.core.model.AssistedDeliveryStatus
import com.wingman.core.model.ErrorCode
import com.wingman.core.model.InboundEventKind
import com.wingman.core.model.InboundNotice
import com.wingman.core.model.RejectRequest
import com.wingman.core.model.SenderIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * WING-027/028: the assisted ("Send by hand") flow. Pure Kotlin, JVM-tested; AssistedScreen.kt is
 * the Compose layer. Wingman never transmits an assisted draft: the user copies it, opens the dating
 * app and sends it there, then may say "I sent it". That report is shown as "You sent this", never
 * as sent or delivered (ADR 0008 §6, `user_reported_sent`).
 */

/** What opening the platform app did. The Android layer launches the app; it never taps or types. */
sealed interface OpenAppResult {
    data object Opened : OpenAppResult
    data class Unavailable(val reason: String) : OpenAppResult
}

/** Plain-language labels. None of them says "sent" alone or "delivered". */
object AssistedCopy {
    const val TITLE = "Send by hand"
    const val EXPLANATION =
        "Wingman can't send on these apps. Copy a draft, open the app, send it yourself, then tap \"I sent it\" so Wingman " +
            "knows what you said. Wingman never taps, types or sends in the app."
    const val NOTICES_TITLE = "Notices (for information only)"
    const val NOTICES_EXPLANATION = "Matches, likes and expiry alerts from your notifications. Wingman doesn't reply to these."
    const val COPIED = "Copied. Paste it in the app."
    const val OPENED = "Opened the app. Find the conversation, paste and send."
    const val ALREADY_HANDLED = "This draft was already handled."

    fun statusLabel(s: AssistedDeliveryStatus): String = when (s) {
        AssistedDeliveryStatus.PENDING -> "Waiting for you to send it"
        AssistedDeliveryStatus.USER_REPORTED_SENT -> "You sent this"
        AssistedDeliveryStatus.DISMISSED -> "Dismissed"
        AssistedDeliveryStatus.CANCELED -> "No longer needed"
    }

    fun cancelLabel(reason: String?): String? = when (reason) {
        null -> null
        "superseded" -> "A newer message arrived, so this draft was replaced."
        "automation_takeover" -> "You took over this conversation."
        "connection_unavailable" -> "This app was disconnected."
        else -> "Canceled."
    }

    fun platformLabel(p: String): String = when (p) {
        "tinder" -> "Tinder"
        "bumble" -> "Bumble"
        "hinge" -> "Hinge"
        "imessage" -> "iMessage"
        else -> p
    }

    fun reasonLabel(r: ApprovalReason): String = when (r) {
        ApprovalReason.ASSISTED_MODE -> "You review every draft"
        ApprovalReason.SENSITIVE_CONTENT -> "Sensitive topic: check the wording"
        ApprovalReason.DATE_OR_COMMITMENT -> "Mentions a date or plan: confirm it's right"
        ApprovalReason.LOW_CONFIDENCE -> "Wingman isn't sure about this one"
        ApprovalReason.FIRST_MESSAGE -> "First message in this conversation"
        ApprovalReason.OUTSIDE_ACTIVE_HOURS -> "Outside your active hours"
        ApprovalReason.CAPABILITY_UNVERIFIED -> "This app's connection isn't verified"
        ApprovalReason.SEND_LIMIT_REACHED -> "Daily limit reached"
        ApprovalReason.USER_RULE -> "One of your rules applies"
        ApprovalReason.PAUSED -> "Automation is paused"
    }

    fun noticeKindLabel(n: InboundNotice): String = when (n.kind) {
        InboundEventKind.MATCH -> "New match"
        InboundEventKind.LIKE_COMMENT -> "Like or reaction"
        InboundEventKind.EXPIRY -> "Expiring"
        InboundEventKind.SYSTEM -> "App notice"
        InboundEventKind.UNKNOWN -> "Other notification"
        InboundEventKind.CHAT_MESSAGE -> "Message"
    }

    /** Why a chat message is only a notice (ADR 0008 §4). */
    fun noticeNote(n: InboundNotice): String? = when {
        n.kind != InboundEventKind.CHAT_MESSAGE -> null
        n.contentHidden -> "The app hid the text. Open the app to read it."
        n.truncated -> "Only part of the message was shown."
        n.identity == SenderIdentity.AMBIGUOUS -> "Couldn't tell which conversation this belongs to."
        n.identity != SenderIdentity.LINKED -> "Couldn't link this to a conversation, so Wingman won't draft a reply."
        else -> null
    }
}

/** One assisted delivery on screen. [text] is private: never log it. */
data class AssistedRow(
    val id: String,
    val platform: String,
    val platformLabel: String,
    val conversationId: String,
    val text: String,
    val reasons: List<String>,
    val status: AssistedDeliveryStatus,
    val statusLabel: String,
    /** Copy, Open, I sent it and Dismiss are offered only while pending. */
    val actionable: Boolean,
    val busy: Boolean = false,
    val note: String? = null,
) {
    override fun toString(): String = "AssistedRow(id=$id, platform=$platform, status=$status, text=<redacted ${text.length} chars>)"
}

/** One notice on screen: read-only, there is no reply action at all. */
data class NoticeRow(
    val id: String,
    val platformLabel: String,
    val kindLabel: String,
    val senderLabel: String?,
    val text: String,
    val note: String?,
) {
    override fun toString(): String = "NoticeRow(id=$id, kind=$kindLabel)"
}

data class AssistedUi(
    val loading: Boolean = true,
    val rows: List<AssistedRow> = emptyList(),
    val notices: List<NoticeRow> = emptyList(),
    val error: String? = null,
    val message: String? = null,
) {
    val pendingCount: Int get() = rows.count { it.status == AssistedDeliveryStatus.PENDING }
}

/**
 * State holder for the assisted screen.
 *
 * - [copy] and [open] are local only: they NEVER call `confirmAssistedDelivery`.
 * - [confirmSent] and [dismiss] use one idempotency key per action ([IdempotencyKeyRegistry]): a
 *   second tap while one runs is dropped, a retry after a network error reuses the key, and a
 *   `409 assisted_delivery_not_pending` reloads the list (it was handled elsewhere).
 * - A confirmed row reads "You sent this"; nothing here ever says "Delivered".
 */
class AssistedState(
    private val scope: CoroutineScope,
    private val api: WingmanApi,
    private val clipboard: (String) -> Unit,
    private val openApp: (platform: String, externalThreadId: String?) -> OpenAppResult,
    private val keys: IdempotencyKeyRegistry = IdempotencyKeyRegistry(),
    private val onPendingCount: (Int, Boolean) -> Unit = { _, _ -> },
) {
    private val _state = MutableStateFlow(AssistedUi())
    val state: StateFlow<AssistedUi> = _state.asStateFlow()
    private val raw = LinkedHashMap<String, AssistedDelivery>()
    private val inFlight = HashSet<String>()
    private var loadJob: Job? = null

    fun load() {
        if (loadJob?.isActive == true) return
        _state.update { it.copy(loading = true, error = null) }
        loadJob = scope.launch {
            val deliveries = api.listAssistedDeliveries(PageRequest(limit = PAGE), AssistedDeliveryFilter(status = AssistedDeliveryStatus.PENDING))
            val notices = api.listInboundNotices(PageRequest(limit = PAGE))
            when (deliveries) {
                is ApiResult.Success -> {
                    // Keep rows the user just handled in this session (they show "You sent this").
                    val handled = raw.values.filter { it.status != AssistedDeliveryStatus.PENDING }
                    raw.clear()
                    (deliveries.value.items + handled).forEach { raw[it.id] = it }
                    onPendingCount(deliveries.value.items.size, deliveries.value.nextCursor != null)
                    _state.update {
                        it.copy(
                            loading = false, error = null, rows = rows(),
                            notices = (notices as? ApiResult.Success)?.value?.items?.map(::noticeRow) ?: it.notices,
                        )
                    }
                }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, error = deliveries.error.userMessage()) }
            }
        }
    }

    /** Copies the draft to the clipboard. Local only: never confirms. */
    fun copy(id: String) {
        val d = raw[id]?.takeIf { it.status == AssistedDeliveryStatus.PENDING } ?: return
        clipboard(d.text)
        _state.update { it.copy(message = AssistedCopy.COPIED) }
    }

    /** Opens the platform app (official launch intent). Local only: never confirms, never taps. */
    fun open(id: String) {
        val d = raw[id] ?: return
        val msg = when (val r = openApp(d.platform, d.externalThreadId)) {
            OpenAppResult.Opened -> AssistedCopy.OPENED
            is OpenAppResult.Unavailable -> r.reason
        }
        _state.update { it.copy(message = msg) }
    }

    /** "I sent it": the user's report, recorded as `user_reported_sent`. [editedText] if they changed it. */
    fun confirmSent(id: String, editedText: String? = null) {
        val body = AssistedDeliveryConfirmRequest(editedText?.trim()?.takeIf { it.isNotEmpty() && it != raw[id]?.text })
        decide(id, "confirm:$id") { key -> api.confirmAssistedDelivery(id, body, key) }
    }

    fun dismiss(id: String) = decide(id, "dismiss:$id") { key -> api.dismissAssistedDelivery(id, RejectRequest(), key) }

    private fun decide(
        id: String,
        action: String,
        call: suspend (com.wingman.core.api.IdempotencyKey) -> ApiResult<com.wingman.core.model.AssistedDeliveryResult>,
    ) {
        val d = raw[id] ?: return
        if (d.status != AssistedDeliveryStatus.PENDING) return
        if (!inFlight.add(id)) return // double tap: one request, one key
        val key = keys.keyFor(action)
        _state.update { it.copy(rows = rows(), message = null) }
        scope.launch {
            val r = call(key)
            inFlight.remove(id)
            when (r) {
                is ApiResult.Success -> {
                    keys.complete(action)
                    raw[id] = r.value.assistedDelivery
                    _state.update { it.copy(rows = rows()) }
                    reportCount()
                }
                is ApiResult.Failure -> {
                    val e = r.error
                    if (e.code == ErrorCode.ASSISTED_DELIVERY_NOT_PENDING || (e is ApiError.Http && e.status == 404)) {
                        keys.complete(action)
                        _state.update { it.copy(rows = rows(), message = AssistedCopy.ALREADY_HANDLED) }
                        raw.remove(id)
                        load()
                    } else {
                        if (!e.isRetryable && e !is ApiError.Network) keys.complete(action)
                        _state.update { it.copy(rows = rows(), message = "Couldn't save that: ${e.userMessage()} Try again.") }
                    }
                }
            }
        }
    }

    private fun reportCount() {
        onPendingCount(raw.values.count { it.status == AssistedDeliveryStatus.PENDING }, false)
    }

    private fun rows(): List<AssistedRow> = raw.values.sortedByDescending { it.createdAt }.map { d ->
        AssistedRow(
            id = d.id,
            platform = d.platform,
            platformLabel = AssistedCopy.platformLabel(d.platform),
            conversationId = d.conversationId,
            text = if (d.status == AssistedDeliveryStatus.USER_REPORTED_SENT) d.finalText ?: d.text else d.text,
            reasons = d.reasons.map(AssistedCopy::reasonLabel),
            status = d.status,
            statusLabel = AssistedCopy.statusLabel(d.status),
            actionable = d.status == AssistedDeliveryStatus.PENDING,
            busy = d.id in inFlight,
            note = AssistedCopy.cancelLabel(d.cancelReason),
        )
    }

    private fun noticeRow(n: InboundNotice) = NoticeRow(
        id = n.id,
        platformLabel = AssistedCopy.platformLabel(n.platform),
        kindLabel = AssistedCopy.noticeKindLabel(n),
        senderLabel = n.senderLabel,
        text = n.text,
        note = AssistedCopy.noticeNote(n),
    )

    companion object {
        const val PAGE = 50
    }
}

/** Pending assisted deliveries for a nav badge (first page; the contract has no totals). */
data class AssistedBadge(val count: Int, val more: Boolean) {
    val label: String? get() = when {
        count == 0 && !more -> null
        count > 99 -> "99+"
        more -> "$count+"
        else -> count.toString()
    }

    val description: String get() = when {
        label == null -> "No drafts waiting for you to send"
        count == 1 && !more -> "1 draft waiting for you to send"
        else -> "${label} drafts waiting for you to send"
    }
}

/** Reads the badge from `GET /v1/assisted-deliveries` (pending). A failed read keeps the last value. */
class AssistedBadgeState(private val scope: CoroutineScope, private val api: WingmanApi) {
    private val _badge = MutableStateFlow<AssistedBadge?>(null)
    val badge: StateFlow<AssistedBadge?> = _badge.asStateFlow()
    private var job: Job? = null

    fun refresh() {
        if (job?.isActive == true) return
        job = scope.launch {
            val r = api.listAssistedDeliveries(PageRequest(limit = AssistedState.PAGE), AssistedDeliveryFilter(status = AssistedDeliveryStatus.PENDING))
            if (r is ApiResult.Success) _badge.value = AssistedBadge(r.value.items.count { it.status == AssistedDeliveryStatus.PENDING }, r.value.nextCursor != null)
        }
    }

    fun report(count: Int, more: Boolean) {
        _badge.value = AssistedBadge(count, more)
    }
}
