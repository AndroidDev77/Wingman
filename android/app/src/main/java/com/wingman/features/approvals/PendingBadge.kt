package com.wingman.features.approvals

import com.wingman.core.api.ApiResult
import com.wingman.core.api.ApprovalFilter
import com.wingman.core.api.PageRequest
import com.wingman.core.api.WingmanApi
import com.wingman.core.model.ApprovalStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Pending approvals known to the client. The contract has no total count, so [more] says that
 * further pages exist and the badge shows "N+".
 */
data class BadgeCount(val count: Int, val more: Boolean) {
    init {
        require(count >= 0)
    }

    /** Badge text, or null when there is nothing to show. */
    val label: String?
        get() = when {
            count == 0 && !more -> null
            count > MAX_SHOWN -> "$MAX_SHOWN+"
            more -> "$count+"
            else -> count.toString()
        }

    /** Spoken description for accessibility. */
    val description: String
        get() = when {
            label == null -> "No drafts waiting for approval"
            more || count > MAX_SHOWN -> "More than ${minOf(count, MAX_SHOWN)} drafts waiting for approval"
            count == 1 -> "1 draft waiting for approval"
            else -> "$count drafts waiting for approval"
        }

    companion object {
        const val MAX_SHOWN = 99
    }
}

/**
 * Pending-approvals badge for the Approvals nav item. [refresh] reads the first page of
 * `GET /v1/approvals` (pending is the server default). A failed read keeps the last known count
 * rather than showing a misleading zero. The Approvals screen reports its own, fresher count
 * through [report] after each change.
 */
class PendingBadgeState(
    private val scope: CoroutineScope,
    private val api: WingmanApi,
    private val pageSize: Int = PAGE_SIZE,
) {
    private val _badge = MutableStateFlow<BadgeCount?>(null)
    val badge: StateFlow<BadgeCount?> = _badge.asStateFlow()
    private var job: Job? = null

    fun refresh() {
        if (job?.isActive == true) return
        job = scope.launch {
            when (val r = api.listApprovals(PageRequest(limit = pageSize), ApprovalFilter(status = ApprovalStatus.PENDING))) {
                is ApiResult.Success -> _badge.value = of(r.value.items.count { it.status == ApprovalStatus.PENDING }, r.value.nextCursor != null)
                is ApiResult.Failure -> Unit
            }
        }
    }

    /** A count the Approvals screen just read from the server. */
    fun report(count: BadgeCount?) {
        if (count != null) _badge.value = count
    }

    companion object {
        const val PAGE_SIZE = 50

        fun of(count: Int, more: Boolean) = BadgeCount(count, more)
    }
}
