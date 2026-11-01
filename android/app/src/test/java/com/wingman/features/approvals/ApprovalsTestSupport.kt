package com.wingman.features.approvals

import com.wingman.core.api.ApiError
import com.wingman.core.api.ApiResult
import com.wingman.core.api.IdempotencyKey
import com.wingman.core.api.WingmanApi
import com.wingman.core.api.mock.MockWingmanApi
import com.wingman.core.model.ApprovalDecisionResult
import com.wingman.core.model.ApproveRequest
import com.wingman.core.model.AutomationActionRequest
import com.wingman.core.model.ContactAutomationState
import com.wingman.core.model.ErrorCode
import com.wingman.core.model.GlobalAutomationState
import com.wingman.core.model.Problem
import com.wingman.core.model.RejectRequest
import kotlinx.coroutines.CompletableDeferred
import java.io.IOException

/** One recorded mutating call. */
data class Call(val op: String, val id: String?, val key: String, val body: Any?)

/**
 * Wraps [MockWingmanApi] (which holds the state), records every mutating call with its key and
 * body, and can script failures or hold a call open with a [gate] (to test rapid repeated taps
 * while a request is in flight).
 */
class RecordingApi(val mock: MockWingmanApi = MockWingmanApi()) : WingmanApi by mock {
    val calls = mutableListOf<Call>()

    /** Failures returned (in order) instead of calling the mock, one per call. */
    val script = ArrayDeque<ApiError>()

    /** When set, mutating calls suspend until it completes. */
    var gate: CompletableDeferred<Unit>? = null

    private suspend fun <T> record(op: String, id: String?, key: IdempotencyKey, body: Any?, real: suspend () -> ApiResult<T>): ApiResult<T> {
        calls += Call(op, id, key.value, body)
        gate?.await()
        script.removeFirstOrNull()?.let { return ApiResult.Failure(it) }
        return real()
    }

    override suspend fun approveApproval(approvalId: String, body: ApproveRequest, key: IdempotencyKey): ApiResult<ApprovalDecisionResult> =
        record("approve", approvalId, key, body) { mock.approveApproval(approvalId, body, key) }

    override suspend fun rejectApproval(approvalId: String, body: RejectRequest, key: IdempotencyKey): ApiResult<ApprovalDecisionResult> =
        record("reject", approvalId, key, body) { mock.rejectApproval(approvalId, body, key) }

    override suspend fun pauseContactAutomation(contactId: String, body: AutomationActionRequest, key: IdempotencyKey): ApiResult<ContactAutomationState> =
        record("pause", contactId, key, body) { mock.pauseContactAutomation(contactId, body, key) }

    override suspend fun resumeContactAutomation(contactId: String, body: AutomationActionRequest, key: IdempotencyKey): ApiResult<ContactAutomationState> =
        record("resume", contactId, key, body) { mock.resumeContactAutomation(contactId, body, key) }

    override suspend fun takeoverContact(contactId: String, body: AutomationActionRequest, key: IdempotencyKey): ApiResult<ContactAutomationState> =
        record("takeover", contactId, key, body) { mock.takeoverContact(contactId, body, key) }

    override suspend fun pauseAllAutomation(body: AutomationActionRequest, key: IdempotencyKey): ApiResult<GlobalAutomationState> =
        record("pause_all", null, key, body) { mock.pauseAllAutomation(body, key) }

    override suspend fun resumeAllAutomation(body: AutomationActionRequest, key: IdempotencyKey): ApiResult<GlobalAutomationState> =
        record("resume_all", null, key, body) { mock.resumeAllAutomation(body, key) }

    fun ops(op: String) = calls.filter { it.op == op }

    companion object {
        fun network(): ApiError = ApiError.Network(IOException("offline"))
        fun http(status: Int, code: ErrorCode? = null): ApiError = ApiError.Http(
            status,
            code?.let { Problem(type = "https://wingman.invalid/problems/${it.wire}", title = "t", status = status, code = it.wire) },
        )
    }
}
