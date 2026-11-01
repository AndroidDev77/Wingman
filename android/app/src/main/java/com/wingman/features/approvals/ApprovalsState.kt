package com.wingman.features.approvals

import com.wingman.core.api.ApiResult
import com.wingman.core.api.ApprovalFilter
import com.wingman.core.api.IdempotencyKeyRegistry
import com.wingman.core.api.PageRequest
import com.wingman.core.api.RetryPolicy
import com.wingman.core.api.WingmanApi
import com.wingman.core.model.Approval
import com.wingman.core.model.ApprovalDecisionResult
import com.wingman.core.model.ApprovalStatus
import com.wingman.core.model.ErrorCode
import com.wingman.core.model.Message
import com.wingman.core.model.RejectRequest
import com.wingman.core.ui.ActionRunner
import com.wingman.core.ui.ActionState
import com.wingman.core.ui.CursorPager
import com.wingman.core.ui.PagedList
import com.wingman.core.ui.UiError
import com.wingman.core.ui.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

/** The user's editor for one pending draft. Survives failed attempts. */
data class DraftEdit(val editing: Boolean = false, val text: String? = null)

/**
 * Pure-Kotlin approval queue (WING-015). The androidx ViewModel passes `viewModelScope`; tests
 * pass a TestScope.
 *
 * Server is the source of truth:
 *  - approve/reject go through [ActionRunner]: a second tap while a decision is in flight is
 *    dropped, and every attempt of one decision (automatic or manual retry) reuses ONE key;
 *  - an approved draft is shown from the server's response: its message is `queued` and is shown
 *    as "Queued, not sent yet" until a later server read reports `sent` (see [checkDelivery]);
 *  - `409 approval_not_pending` re-reads the approval and explains what happened;
 *  - network and server errors leave the editor text untouched.
 */
class ApprovalsState(
    private val scope: CoroutineScope,
    private val api: WingmanApi,
    private val clock: () -> Instant = Instant::now,
    pageSize: Int = PAGE_SIZE,
    private val keys: IdempotencyKeyRegistry = IdempotencyKeyRegistry(),
    policy: RetryPolicy = RetryPolicy(),
    sleep: (suspend (Long) -> Unit)? = null,
) {
    private val runner = ActionRunner(scope, keys, policy, sleep)
    private val pager = CursorPager(scope, pageSize) { page -> api.listApprovals(page, ApprovalFilter(status = ApprovalStatus.PENDING)) }
    private val index = MutableStateFlow(ConversationIndex.EMPTY)
    private val edits = MutableStateFlow<Map<String, DraftEdit>>(emptyMap())

    /** Decided in this session (or found decided on a 409), newest decision first. */
    private val outcomes = MutableStateFlow<Map<String, Outcome>>(emptyMap())

    val state: StateFlow<UiState<ApprovalsScreenData>> =
        combine(pager.state, index, edits, runner.states, outcomes) { p, idx, e, actions, o -> reduce(p, idx, e, actions, o) }
            .stateIn(scope, SharingStarted.Eagerly, UiState.Loading)

    /** Pending drafts in the loaded pages, for the nav badge. Unknown (null) until the first page loads. */
    val pendingCount: StateFlow<BadgeCount?> = combine(state, pager.state) { s, p ->
        when (s) {
            is UiState.Content -> BadgeCount(s.data.pending.size, more = !s.data.endReached)
            UiState.Empty -> BadgeCount(0, more = false)
            else -> null
        }.takeIf { p !is UiState.Loading }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    fun load() {
        loadIndex()
        pager.load()
    }

    /** Reloads the pending queue. Edits of drafts that are still pending are kept. */
    fun refresh() {
        if (!index.value.complete) loadIndex()
        pager.refresh()
    }

    fun loadMore() = pager.loadMore()

    /* ------------------------------------------------------------ editing */

    fun startEdit(approval: ApprovalCard) = edits.update {
        val current = it[approval.approvalId]
        it + (approval.approvalId to DraftEdit(editing = true, text = current?.text ?: approval.draftText))
    }

    /** Back to the original draft text. */
    fun cancelEdit(approvalId: String) {
        if (isDeciding(approvalId)) return
        edits.update { it - approvalId }
        keys.complete(approveId(approvalId))
    }

    /**
     * Updates the editor. A different body under a key kept from a failed attempt would be
     * `422 idempotency_key_mismatch`, so an edit starts a new approve action (new key).
     * Ignored while a decision is in flight: what is being sent can't change under it.
     */
    fun setEditText(approvalId: String, text: String) {
        if (isDeciding(approvalId)) return
        val current = edits.value[approvalId] ?: DraftEdit(editing = true)
        if (current.text == text) return
        edits.update { it + (approvalId to current.copy(editing = true, text = text)) }
        keys.complete(approveId(approvalId))
    }

    /* ------------------------------------------------------------ decisions */

    /**
     * Approves the draft, with the editor text when it differs from the draft. Returns false
     * when nothing was sent: a decision for this draft is in flight or already made, or the
     * edited text is invalid.
     */
    fun approve(approvalId: String): Boolean {
        if (isDeciding(approvalId) || approvalId in outcomes.value) return false
        val approval = pendingApproval(approvalId) ?: return false
        val edit = edits.value[approvalId]
        val body = ApproveInput.request(approval.draft.text, edit?.text?.takeIf { edit.editing }) ?: return false
        keys.complete(rejectId(approvalId))
        return runner.submit<ApprovalDecisionResult>(approveId(approvalId), onResult = { onDecision(approvalId, it) }) { key ->
            api.approveApproval(approvalId, body, key)
        }
    }

    fun reject(approvalId: String, reason: String? = null): Boolean {
        if (isDeciding(approvalId) || approvalId in outcomes.value) return false
        pendingApproval(approvalId) ?: return false
        val body = RejectRequest(reason = reason?.trim()?.takeIf { it.isNotEmpty() }?.take(500))
        keys.complete(approveId(approvalId))
        return runner.submit<ApprovalDecisionResult>(rejectId(approvalId), onResult = { onDecision(approvalId, it) }) { key ->
            api.rejectApproval(approvalId, body, key)
        }
    }

    /**
     * Re-reads the delivery state of an approved draft's message from the server. The label
     * changes only to what the server reports.
     */
    fun checkDelivery(approvalId: String) {
        val outcome = outcomes.value[approvalId] ?: return
        val messageId = outcome.approval.messageId ?: outcome.message?.id ?: return
        if (outcome.checkingDelivery) return
        setOutcome(approvalId) { it.copy(checkingDelivery = true, error = null) }
        scope.launch {
            val r = findMessage(api, outcome.approval.conversationId, messageId)
            setOutcome(approvalId) { o ->
                when (r) {
                    is ApiResult.Success -> o.copy(message = r.value ?: o.message, checkingDelivery = false)
                    is ApiResult.Failure -> o.copy(checkingDelivery = false, error = UiError.from(r.error))
                }
            }
        }
    }

    /** Removes a handled draft from the list. */
    fun dismiss(approvalId: String) {
        outcomes.update { it - approvalId }
        runner.reset(approveId(approvalId))
        runner.reset(rejectId(approvalId))
    }

    /** Clears an item's error message (the editor text is kept). */
    fun clearError(approvalId: String) {
        if (runner.stateOf(approveId(approvalId)) is ActionState.Failed) runner.reset(approveId(approvalId))
        if (runner.stateOf(rejectId(approvalId)) is ActionState.Failed) runner.reset(rejectId(approvalId))
    }

    /* ------------------------------------------------------------ internals */

    private fun onDecision(approvalId: String, result: ActionState<ApprovalDecisionResult>) {
        when (result) {
            is ActionState.Done -> {
                edits.update { it - approvalId }
                // Replays carry the original server response; it is still the server's word.
                record(approvalId, Outcome(result.value.approval, result.value.message, fromConflict = false))
            }
            is ActionState.Failed -> when (result.error.code) {
                ErrorCode.APPROVAL_NOT_PENDING -> refreshAfterConflict(approvalId)
                // 404: gone (or another user's). Reload the queue; the edit is dropped with the item.
                ErrorCode.NOT_FOUND -> pager.refresh()
                else -> Unit // shown on the item; the editor text and (for retryable errors) the key are kept
            }
            else -> Unit
        }
    }

    /** 409 `approval_not_pending`: someone (or an earlier attempt) already decided it. Read what happened. */
    private fun refreshAfterConflict(approvalId: String) {
        scope.launch {
            when (val r = api.getApproval(approvalId)) {
                is ApiResult.Success -> {
                    val approval = r.value
                    if (approval.status == ApprovalStatus.PENDING) return@launch // keep the error; the user may retry
                    edits.update { it - approvalId }
                    val message = approval.messageId?.let { id ->
                        (findMessage(api, approval.conversationId, id) as? ApiResult.Success)?.value
                    }
                    record(approvalId, Outcome(approval, message, fromConflict = true))
                }
                is ApiResult.Failure -> Unit // the 409 explanation stays on the item
            }
        }
    }

    private fun record(approvalId: String, outcome: Outcome) {
        outcomes.update { LinkedHashMap<String, Outcome>().apply { put(approvalId, outcome); putAll(it - approvalId) } }
        runner.reset(approveId(approvalId))
        runner.reset(rejectId(approvalId))
    }

    private fun setOutcome(approvalId: String, change: (Outcome) -> Outcome) =
        outcomes.update { m -> m[approvalId]?.let { LinkedHashMap(m).apply { put(approvalId, change(it)) } } ?: m }

    private fun isDeciding(approvalId: String): Boolean =
        runner.stateOf(approveId(approvalId)) is ActionState.InFlight || runner.stateOf(rejectId(approvalId)) is ActionState.InFlight

    private fun pendingApproval(approvalId: String): Approval? =
        ((pager.state.value as? UiState.Content)?.data?.items)?.firstOrNull { it.id == approvalId && it.status == ApprovalStatus.PENDING }

    private fun loadIndex() {
        scope.launch { (ConversationIndex.load(api) as? ApiResult.Success)?.value?.let { index.value = it } }
    }

    private fun reduce(
        p: UiState<PagedList<Approval>>,
        idx: ConversationIndex,
        e: Map<String, DraftEdit>,
        actions: Map<String, ActionState<Any?>>,
        o: Map<String, Outcome>,
    ): UiState<ApprovalsScreenData> {
        val now = clock()
        val decided = o.values.map { it.display(idx.name(it.approval.conversationId)) }
        val list: PagedList<Approval> = when (p) {
            is UiState.Content -> p.data
            UiState.Empty -> PagedList(endReached = true)
            UiState.Loading -> return if (decided.isEmpty()) UiState.Loading else UiState.Content(data(emptyList(), decided, PagedList()))
            is UiState.Error -> return if (decided.isEmpty()) p else UiState.Content(data(emptyList(), decided, PagedList(loadMoreError = p.error)))
        }
        val pending = list.items
            .filter { it.status == ApprovalStatus.PENDING && it.id !in o }
            .distinctBy { it.id }
            .map { a ->
                val edit = e[a.id]
                val approveState = actions[approveId(a.id)]
                val rejectState = actions[rejectId(a.id)]
                val editText = edit?.text ?: a.draft.text.orEmpty()
                PendingItem(
                    card = ApprovalCard.of(a, idx.name(a.conversationId), now),
                    editing = edit?.editing == true,
                    editText = editText,
                    editError = if (edit?.editing == true) ApproveInput.error(editText) else null,
                    inFlight = when {
                        approveState is ActionState.InFlight -> Decision.APPROVING
                        rejectState is ActionState.InFlight -> Decision.REJECTING
                        else -> null
                    },
                    error = (approveState as? ActionState.Failed)?.error?.let(::explain)
                        ?: (rejectState as? ActionState.Failed)?.error?.let(::explain),
                )
            }
        val result = data(pending, decided, list)
        return if (result.isEmpty && list.endReached) UiState.Empty else UiState.Content(result)
    }

    private fun data(pending: List<PendingItem>, decided: List<DecidedItem>, list: PagedList<Approval>) = ApprovalsScreenData(
        pending = pending,
        decided = decided,
        loadingMore = list.loadingMore,
        endReached = list.endReached,
        loadMoreError = list.loadMoreError,
    )

    companion object {
        const val PAGE_SIZE = 30

        fun approveId(approvalId: String) = "approve:$approvalId"
        fun rejectId(approvalId: String) = "reject:$approvalId"

        /** Approval-specific wording for errors on a queue item. */
        fun explain(error: UiError): UiError = when (error.code) {
            ErrorCode.APPROVAL_NOT_PENDING -> error.copy(message = "This draft was already handled, so nothing more was sent. Checking what happened…")
            ErrorCode.UNAUTHENTICATED -> error.copy(message = "You've been signed out. Sign in again; your edits are kept here.")
            ErrorCode.CAPABILITY_UNSUPPORTED -> error.copy(message = "This messaging app can't send from Wingman. Nothing was sent.")
            ErrorCode.NOT_FOUND -> error.copy(message = "This draft no longer exists. Nothing was sent.")
            else -> if (error.retryable) error.copy(message = error.message + " Nothing was sent yet; your text is kept.") else error
        }
    }
}

/**
 * Finds one message by walking the conversation's pages (the contract has no get-message route).
 * Success(null) when it isn't found within [maxPages] pages.
 */
suspend fun findMessage(api: WingmanApi, conversationId: String, messageId: String, maxPages: Int = 20): ApiResult<Message?> {
    var cursor: String? = null
    for (i in 0 until maxPages) {
        when (val r = api.listMessages(conversationId, PageRequest(cursor, 100))) {
            is ApiResult.Failure -> return r
            is ApiResult.Success -> {
                r.value.items.firstOrNull { it.id == messageId }?.let { return ApiResult.Success(it) }
                cursor = r.value.nextCursor ?: return ApiResult.Success(null)
            }
        }
    }
    return ApiResult.Success(null)
}
