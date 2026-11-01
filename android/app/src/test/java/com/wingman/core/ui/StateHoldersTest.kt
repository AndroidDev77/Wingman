package com.wingman.core.ui

import com.wingman.core.api.ApiError
import com.wingman.core.api.ApiResult
import com.wingman.core.api.IdempotencyKey
import com.wingman.core.api.PageRequest
import com.wingman.core.api.RetryPolicy
import com.wingman.core.api.mock.MockFixtures
import com.wingman.core.api.mock.MockWingmanApi
import com.wingman.core.model.ApprovalDecisionResult
import com.wingman.core.model.ApprovalStatus
import com.wingman.core.model.ApproveRequest
import com.wingman.core.model.DeliveryState
import com.wingman.core.model.ErrorCode
import com.wingman.core.model.Message
import com.wingman.core.model.Page
import com.wingman.navigation.loadDashboardSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class StateHoldersTest {

    @Test fun reducers() {
        assertEquals(UiState.Content(1), ApiResult.Success(1).toUiState())
        assertEquals(UiState.Empty, ApiResult.Success(emptyList<Int>()).toUiState { it.isEmpty() })
        val err = ApiResult.Failure(ApiError.Network(IOException())).toUiState()
        assertTrue(err is UiState.Error && err.error.isNetwork && err.error.retryable)

        // A failed refresh keeps content visible.
        val kept = UiState.Content(5, refreshing = true).reduceRefresh(ApiResult.Failure(ApiError.Http(500, null)))
        assertTrue(kept is UiState.Content && kept.data == 5 && !kept.refreshing && kept.refreshError != null)
        // A failure with no content is a full Error.
        assertTrue(UiState.Loading.reduceRefresh(ApiResult.Failure(ApiError.Http(500, null))) is UiState.Error)
    }

    @Test fun loadableNetworkFailureIsErrorThenRetrySucceeds() = runTest {
        val api = MockWingmanApi()
        val holder = LoadableState(this) { api.getMe() }
        assertEquals(UiState.Loading, holder.state.value)
        api.failNextWith()
        holder.load(); advanceUntilIdle()
        val s = holder.state.value
        assertTrue("got $s", s is UiState.Error && s.error.isNetwork)
        holder.load(); advanceUntilIdle()
        assertTrue(holder.state.value is UiState.Content)
    }

    @Test fun loadableRefreshFailureKeepsContent() = runTest {
        val api = MockWingmanApi()
        val holder = LoadableState(this) { api.getMe() }
        holder.load(); advanceUntilIdle()
        api.failNextWith()
        holder.refresh(); advanceUntilIdle()
        val s = holder.state.value as UiState.Content
        assertEquals("Alex", s.data.displayName)
        assertTrue(s.refreshError!!.isNetwork)
    }

    @Test fun loadableEmpty() = runTest {
        val holder = LoadableState(this, isEmpty = { it: List<Int> -> it.isEmpty() }) { ApiResult.Success(emptyList()) }
        holder.load(); advanceUntilIdle()
        assertEquals(UiState.Empty, holder.state.value)
    }

    @Test fun pagerWalksLongThreadAndSurvivesMidStreamFailure() = runTest {
        val api = MockWingmanApi()
        val pager = CursorPager(this, pageSize = 50) { p: PageRequest -> api.listMessages(MockFixtures.CONVERSATION_RILEY, p) }
        pager.load(); advanceUntilIdle()
        assertEquals(50, (pager.state.value as UiState.Content).data.items.size)

        api.failNextWith()
        pager.loadMore(); advanceUntilIdle()
        val afterFail = (pager.state.value as UiState.Content).data
        assertEquals(50, afterFail.items.size)
        assertTrue(afterFail.loadMoreError!!.isNetwork)

        pager.loadMore(); advanceUntilIdle()
        pager.loadMore(); advanceUntilIdle()
        val done = (pager.state.value as UiState.Content).data
        assertEquals(MockFixtures.LONG_THREAD_SIZE, done.items.size)
        assertTrue(done.endReached)
        assertNull(done.loadMoreError)
        pager.loadMore(); advanceUntilIdle() // no-op at the end
        assertEquals(MockFixtures.LONG_THREAD_SIZE, (pager.state.value as UiState.Content).data.items.size)
    }

    @Test fun pagerEmptyAndError() = runTest {
        val empty = CursorPager(this) { _: PageRequest -> ApiResult.Success(Page(emptyList<Message>(), null)) }
        empty.load(); advanceUntilIdle()
        assertEquals(UiState.Empty, empty.state.value)

        val failing = CursorPager<Message>(this) { _: PageRequest -> ApiResult.Failure(ApiError.Http(401, null)) }
        failing.load(); advanceUntilIdle()
        val s = failing.state.value as UiState.Error
        assertEquals(ErrorCode.UNAUTHENTICATED, s.error.code)
        assertFalse(s.error.retryable)
    }

    @Test fun rapidRepeatedTapsSubmitOnceWithOneKey() = runTest {
        val api = MockWingmanApi(latencyMs = 100)
        val runner = ActionRunner(this, policy = RetryPolicy.NONE)
        val keys = mutableListOf<IdempotencyKey>()
        val action = "approve:${MockFixtures.APPROVAL_SAM}"
        val accepted = (1..5).map {
            runner.submit<ApprovalDecisionResult>(action) { k -> keys += k; api.approveApproval(MockFixtures.APPROVAL_SAM, ApproveRequest(), k) }
        }
        assertEquals(listOf(true, false, false, false, false), accepted)
        assertEquals(ActionState.InFlight, runner.stateOf(action))
        advanceUntilIdle()
        assertEquals(1, keys.size)
        assertEquals(listOf("approveApproval"), api.calls)
        val done = runner.stateOf(action) as ActionState.Done<*>
        val result = done.value as ApprovalDecisionResult
        // Only server-confirmed state is shown: queued, never sent.
        assertEquals(ApprovalStatus.APPROVED, result.approval.status)
        assertEquals(DeliveryState.QUEUED, result.message!!.deliveryState)
    }

    @Test fun actionRetriesNetworkFailureWithSameKeyAndReportsServerError() = runTest {
        val api = MockWingmanApi()
        val runner = ActionRunner(this, policy = RetryPolicy(maxAttempts = 3, initialDelayMs = 1), sleep = {})
        val keys = mutableListOf<IdempotencyKey>()
        api.failNextWith()
        runner.submit<ApprovalDecisionResult>("a") { k -> keys += k; api.approveApproval(MockFixtures.APPROVAL_SAM, ApproveRequest(), k) }
        advanceUntilIdle()
        assertEquals(2, keys.size)
        assertEquals(1, keys.toSet().size)
        assertTrue(runner.stateOf("a") is ActionState.Done<*>)

        // Approving again after it was decided: server error, surfaced, no crash.
        runner.reset("a")
        runner.submit<ApprovalDecisionResult>("b") { k -> api.approveApproval(MockFixtures.APPROVAL_SAM, ApproveRequest(), k) }
        advanceUntilIdle()
        val failed = runner.stateOf("b") as ActionState.Failed
        assertEquals(ErrorCode.APPROVAL_NOT_PENDING, failed.error.code)
    }

    @Test fun dashboardSummaryLoadsFromMockAndFailsCleanly() = runTest {
        val api = MockWingmanApi()
        val s = loadDashboardSummary(api).getOrNull()!!
        assertEquals("Alex", s.user.displayName)
        assertEquals(2, s.pendingApprovals)
        assertFalse(s.morePendingApprovals)

        api.failAllWith(MockWingmanApi.networkError())
        assertTrue(loadDashboardSummary(api).errorOrNull() is ApiError.Network)
    }
}
