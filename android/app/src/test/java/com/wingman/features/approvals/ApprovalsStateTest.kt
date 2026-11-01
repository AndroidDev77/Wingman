package com.wingman.features.approvals

import com.wingman.core.api.IdempotencyKey
import com.wingman.core.api.RetryPolicy
import com.wingman.core.api.mock.MockFixtures
import com.wingman.core.model.ApprovalReason
import com.wingman.core.model.ApprovalStatus
import com.wingman.core.model.ApproveRequest
import com.wingman.core.model.DeliveryState
import com.wingman.core.model.ErrorCode
import com.wingman.core.model.MessageOrigin
import com.wingman.core.ui.UiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import com.wingman.features.conversation.settle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

/*
 * Approval queue behavior (WING-015). State holders run in `backgroundScope` (their `stateIn`
 * flows never complete); the mock API holds server state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalsStateTest {
    private val sam = MockFixtures.APPROVAL_SAM
    private val samDraft = "Welcome back! What was the highlight, the food or the views?"

    private fun TestScope.holder(api: RecordingApi, policy: RetryPolicy = RetryPolicy(maxAttempts = 1)) =
        ApprovalsState(backgroundScope, api, clock = { MockFixtures.NOW }, policy = policy, sleep = {}).also {
            it.load()
            settle()
        }

    private fun ApprovalsState.data() = (state.value as UiState.Content).data
    private fun ApprovalsState.item(id: String) = data().pending.first { it.card.approvalId == id }
    private fun ApprovalsState.decided(id: String) = data().decided.first { it.approvalId == id }

    @Test fun queueShowsConversationDraftReasonsAndAge() = runTest {
        val s = holder(RecordingApi())
        val item = s.item(sam).card
        assertEquals("Sam", item.contactName)
        assertEquals(MockFixtures.CONVERSATION_SAM, item.conversationId)
        assertEquals(samDraft, item.draftText)
        assertEquals(listOf(ReasonText.of(ApprovalReason.ASSISTED_MODE)), item.reasons)
        assertEquals("Waiting 3m", item.age) // created 18:01:10, now 18:05:00
        assertEquals("Expires in 23h", item.expiry)
        assertNull(s.item(MockFixtures.APPROVAL_JORDAN).card.expiry)
        assertEquals(listOf(sam, MockFixtures.APPROVAL_JORDAN), s.data().pending.map { it.card.approvalId }) // newest first
    }

    @Test fun rapidRepeatedApproveTapsSendOneRequestWithOneKey() = runTest {
        val api = RecordingApi()
        val s = holder(api)
        api.gate = CompletableDeferred()
        assertTrue(s.approve(sam))
        runCurrent()
        repeat(5) { assertFalse(s.approve(sam)) }
        assertFalse(s.reject(sam)) // no conflicting decision while one is in flight
        assertEquals(Decision.APPROVING, s.item(sam).inFlight)
        api.gate!!.complete(Unit)
        settle()
        assertEquals(1, api.calls.size)
        assertEquals(1, api.ops("approve").map { it.key }.distinct().size)
        // After the server's answer it's no longer pending: further taps send nothing.
        assertFalse(s.approve(sam))
        assertEquals(1, api.calls.size)
    }

    @Test fun retryAfterNetworkErrorReusesTheSameKeyAndKeepsTheEdit() = runTest {
        val api = RecordingApi()
        val s = holder(api)
        s.startEdit(s.item(sam).card)
        s.setEditText(sam, "Edited reply")
        api.script += RecordingApi.network()
        assertTrue(s.approve(sam)); settle()

        val failed = s.item(sam)
        assertTrue(failed.error!!.isNetwork)
        assertEquals("Edited reply", failed.editText)
        assertTrue(failed.editing)

        assertTrue(s.approve(sam)); settle() // manual retry
        val keys = api.ops("approve").map { it.key }
        assertEquals(2, keys.size)
        assertEquals(keys[0], keys[1])
        assertEquals(ApprovalStatus.APPROVED, s.data().decided.single().let { api.mock.getApproval(sam).getOrNull()!!.status })
    }

    @Test fun automaticRetriesReuseTheKey() = runTest {
        val api = RecordingApi()
        val s = holder(api, RetryPolicy(maxAttempts = 3))
        api.script += RecordingApi.network()
        api.script += RecordingApi.http(503)
        s.approve(sam); settle()
        val keys = api.ops("approve").map { it.key }
        assertEquals(3, keys.size)
        assertEquals(1, keys.distinct().size)
        assertEquals("Approved", s.decided(sam).title)
    }

    @Test fun editingAfterAFailureStartsANewKey() = runTest {
        val api = RecordingApi()
        val s = holder(api)
        s.startEdit(s.item(sam).card)
        s.setEditText(sam, "first")
        api.script += RecordingApi.network()
        s.approve(sam); settle()
        s.setEditText(sam, "second")
        s.approve(sam); settle()
        val calls = api.ops("approve")
        assertEquals(2, calls.size)
        // Same key with a different body would be 422 idempotency_key_mismatch.
        assertTrue(calls[0].key != calls[1].key)
        assertEquals(ApproveRequest("second"), calls[1].body)
    }

    @Test fun editThenApproveSendsTheEditedText() = runTest {
        val api = RecordingApi()
        val s = holder(api)
        s.startEdit(s.item(sam).card)
        s.setEditText(sam, "My own words")
        s.approve(sam); settle()
        assertEquals(ApproveRequest(editedText = "My own words"), api.ops("approve").single().body)
        val approval = api.mock.getApproval(sam).getOrNull()!!
        assertEquals("My own words", approval.finalText)
        assertEquals("My own words", s.decided(sam).finalText)
    }

    @Test fun uneditedApproveOmitsEditedText() = runTest {
        val api = RecordingApi()
        val s = holder(api)
        s.startEdit(s.item(sam).card) // opened but unchanged
        s.approve(sam); settle()
        assertEquals(ApproveRequest(editedText = null), api.ops("approve").single().body)
    }

    @Test fun blankEditIsNotSent() = runTest {
        val api = RecordingApi()
        val s = holder(api)
        s.startEdit(s.item(sam).card)
        s.setEditText(sam, "   ")
        settle()
        assertNotNull(s.item(sam).editError)
        assertFalse(s.approve(sam))
        assertTrue(api.calls.isEmpty())
    }

    @Test fun approvedDraftIsNeverShownAsSentBeforeTheServerSaysSo() = runTest {
        val api = RecordingApi()
        val s = holder(api)
        s.approve(sam); settle()
        val d = s.decided(sam)
        assertEquals("Approved", d.title)
        assertEquals("Queued, not sent yet", d.delivery!!.text)
        assertTrue(d.canCheckDelivery)

        // A status check before the connector acknowledges still says queued.
        s.checkDelivery(sam); settle()
        assertEquals("Queued, not sent yet", s.decided(sam).delivery!!.text)

        val messageId = d.messageId!!
        assertTrue(api.mock.simulateDelivery(messageId, DeliveryState.SENDING))
        assertEquals("Queued, not sent yet", s.decided(sam).delivery!!.text) // no re-read yet: nothing changes
        s.checkDelivery(sam); settle()
        assertEquals("Sending…", s.decided(sam).delivery!!.text)

        api.mock.simulateDelivery(messageId, DeliveryState.SENT)
        s.checkDelivery(sam); settle()
        assertEquals("Sent", s.decided(sam).delivery!!.text)

        api.mock.simulateDelivery(messageId, DeliveryState.DELIVERED)
        s.checkDelivery(sam); settle()
        assertEquals("Delivered", s.decided(sam).delivery!!.text)
        assertFalse(s.decided(sam).canCheckDelivery)
    }

    @Test fun failedStatusCheckKeepsLastKnownState() = runTest {
        val api = RecordingApi()
        val s = holder(api)
        s.approve(sam); settle()
        api.mock.simulateDelivery(s.decided(sam).messageId!!, DeliveryState.SENT)
        api.mock.failNextWith()
        s.checkDelivery(sam); settle()
        assertEquals("Queued, not sent yet", s.decided(sam).delivery!!.text)
        assertTrue(s.decided(sam).error!!.isNetwork)
    }

    @Test fun conflict409RefreshesTheItemAndExplains() = runTest {
        val api = RecordingApi()
        val s = holder(api)
        // Someone else approves it first (another device).
        api.mock.approveApproval(sam, ApproveRequest(), IdempotencyKey.random())
        s.startEdit(s.item(sam).card)
        s.setEditText(sam, "late edit")
        s.approve(sam); settle()

        assertTrue(s.data().pending.none { it.card.approvalId == sam })
        val d = s.decided(sam)
        assertEquals("Already approved", d.title)
        assertTrue(d.explanation.contains("not approved twice"))
        assertEquals("Queued, not sent yet", d.delivery!!.text) // its real message, from the server
        assertEquals(1, api.ops("approve").size)
    }

    @Test fun conflictOnACanceledDraftSaysNothingWasSent() = runTest {
        val api = RecordingApi()
        val s = holder(api)
        api.mock.takeoverContact(MockFixtures.CONTACT_SAM, com.wingman.core.model.AutomationActionRequest(), IdempotencyKey.random())
        s.approve(sam); settle()
        val d = s.decided(sam)
        assertEquals("Canceled", d.title)
        assertTrue(d.explanation.contains("Nothing was sent"))
        assertNull(d.delivery)
    }

    @Test fun serverErrorsAreShownAndKeepTheEdit() = runTest {
        for ((error, check) in listOf(
            RecordingApi.http(500) to { e: com.wingman.core.ui.UiError -> e.retryable && e.code == ErrorCode.INTERNAL_ERROR },
            RecordingApi.http(401) to { e: com.wingman.core.ui.UiError -> !e.retryable && e.message.contains("Sign in") },
            RecordingApi.http(422, ErrorCode.CAPABILITY_UNSUPPORTED) to { e: com.wingman.core.ui.UiError -> e.message.contains("Nothing was sent") },
        )) {
            val api = RecordingApi()
            val s = holder(api)
            s.startEdit(s.item(sam).card)
            s.setEditText(sam, "keep me")
            api.script += error
            s.approve(sam); settle()
            val item = s.item(sam)
            assertTrue("for $error got ${item.error}", check(item.error!!))
            assertEquals("keep me", item.editText)
            assertNull(item.inFlight)
        }
    }

    @Test fun conflict409FromTheServerIsExplainedWhileStillPending() = runTest {
        // A 409 whose re-read still shows pending (e.g. idempotency in progress elsewhere) keeps the item and the message.
        val api = RecordingApi()
        val s = holder(api)
        api.script += RecordingApi.http(409, ErrorCode.APPROVAL_NOT_PENDING)
        s.approve(sam); settle()
        val item = s.item(sam)
        assertTrue(item.error!!.message.contains("already handled"))
    }

    @Test fun rejectSendsNothing() = runTest {
        val api = RecordingApi()
        val s = holder(api)
        assertTrue(s.reject(MockFixtures.APPROVAL_JORDAN, "  not my style  "))
        settle()
        val d = s.decided(MockFixtures.APPROVAL_JORDAN)
        assertEquals("Rejected", d.title)
        assertNull(d.messageId)
        assertNull(d.delivery)
        assertEquals(com.wingman.core.model.RejectRequest("not my style"), api.ops("reject").single().body)
        val msgs = api.mock.listMessages(MockFixtures.CONVERSATION_JORDAN).getOrNull()!!.items
        assertTrue(msgs.none { it.origin == MessageOrigin.APPROVED_DRAFT })
    }

    @Test fun pendingCountFollowsTheQueue() = runTest {
        val api = RecordingApi()
        val s = holder(api)
        assertEquals(BadgeCount(2, more = false), s.pendingCount.value)
        s.approve(sam); settle()
        assertEquals(BadgeCount(1, more = false), s.pendingCount.value)
        s.reject(MockFixtures.APPROVAL_JORDAN); settle()
        assertEquals(BadgeCount(0, more = false), s.pendingCount.value)
    }

    @Test fun pagedQueueMarksMore() = runTest {
        val api = RecordingApi()
        val s = ApprovalsState(backgroundScope, api, clock = { MockFixtures.NOW }, pageSize = 1)
        s.load(); settle()
        assertEquals(BadgeCount(1, more = true), s.pendingCount.value)
        s.loadMore(); settle()
        assertEquals(BadgeCount(2, more = false), s.pendingCount.value)
    }

    @Test fun timingLabels() {
        val now = MockFixtures.NOW
        assertEquals("Waiting since just now", ApprovalTiming.age(now.plusSeconds(30), now))
        assertEquals("Waiting 2h", ApprovalTiming.age(now.minus(Duration.ofMinutes(150)), now))
        assertEquals("Waiting 3d", ApprovalTiming.age(now.minus(Duration.ofDays(3)), now))
        assertEquals("Expires in 45m", ApprovalTiming.expiry(now.plus(Duration.ofMinutes(45)), now))
        assertTrue(ApprovalTiming.expiry(now.minusSeconds(1), now)!!.startsWith("Expired"))
        assertTrue(ApprovalTiming.isExpired(now, now))
        assertNull(ApprovalTiming.expiry(null, now))
    }

    @Test fun everyReasonHasPlainText() {
        ApprovalReason.entries.forEach { assertTrue(ReasonText.of(it).endsWith(".")) }
        // D-015: `user_rule` also covers "paused".
        assertTrue(ReasonText.of(ApprovalReason.USER_RULE).contains("paused"))
    }
}
