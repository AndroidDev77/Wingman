package com.wingman.navigation

import com.wingman.core.api.ApiResult
import com.wingman.core.api.WingmanApi
import com.wingman.core.model.GlobalAutomationStatus
import com.wingman.core.model.User

/** What the shell's Dashboard placeholder shows: proves the API client is wired end to end. */
data class DashboardSummary(
    val user: User,
    val backendVersion: String,
    val globalAutomation: GlobalAutomationStatus,
    /** Pending approvals on the first page (badge; the contract has no total counts). */
    val pendingApprovals: Int,
    val morePendingApprovals: Boolean,
)

/** Pure loader: the first failing call decides the error. */
suspend fun loadDashboardSummary(api: WingmanApi): ApiResult<DashboardSummary> {
    val health = when (val r = api.getHealth()) {
        is ApiResult.Success -> r.value
        is ApiResult.Failure -> return r
    }
    val me = when (val r = api.getMe()) {
        is ApiResult.Success -> r.value
        is ApiResult.Failure -> return r
    }
    val automation = when (val r = api.getGlobalAutomationState()) {
        is ApiResult.Success -> r.value
        is ApiResult.Failure -> return r
    }
    val approvals = when (val r = api.listApprovals()) {
        is ApiResult.Success -> r.value
        is ApiResult.Failure -> return r
    }
    return ApiResult.Success(
        DashboardSummary(
            user = me,
            backendVersion = health.version,
            globalAutomation = automation.status,
            pendingApprovals = approvals.items.size,
            morePendingApprovals = approvals.nextCursor != null,
        ),
    )
}
