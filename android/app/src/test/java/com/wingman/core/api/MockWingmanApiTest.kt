package com.wingman.core.api

import com.wingman.core.api.mock.MockFixtures
import com.wingman.core.api.mock.MockWingmanApi
import com.wingman.core.model.AgentActionType
import com.wingman.core.model.ApprovalStatus
import com.wingman.core.model.ApproveRequest
import com.wingman.core.model.AutomationActionRequest
import com.wingman.core.model.AutomationMode
import com.wingman.core.model.AutomationSettings
import com.wingman.core.model.ContactAutomationStatus
import com.wingman.core.model.DeliveryState
import com.wingman.core.model.DraftCreateRequest
import com.wingman.core.model.ErrorCode
import com.wingman.core.model.GlobalAutomationStatus
import com.wingman.core.model.Intent
import com.wingman.core.model.IntentMode
import com.wingman.core.model.Message
import com.wingman.core.model.MessageOrigin
import com.wingman.core.model.PreferencesUpdate
import com.wingman.core.model.RejectRequest
import com.wingman.core.model.SendMessageRequest
import com.wingman.core.model.StyleProfile
import com.wingman.core.model.Verification
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MockWingmanApiTest {
    private val api = MockWingmanApi()

    @Test fun worksOfflineForReads() = runTest {
        assertEquals("ok", api.getHealth().getOrNull()!!.status)
        assertEquals(MockFixtures.USER_ID, api.getMe().getOrNull()!!.id)
        assertEquals(GlobalAutomationStatus.ACTIVE, api.getGlobalAutomationState().getOrNull()!!.status)
        assertEquals(3, api.listConnections().getOrNull()!!.items.size)
    }

    @Test fun automationIsOffByDefault() = runTest {
        val prefs = api.getPreferences().getOrNull()!!
        assertEquals(0, prefs.version)
        assertEquals(AutomationMode.MANUAL, prefs.automation.mode)
        assertNull(prefs.updatedAt)
    }

    @Test fun inboxIsNewestFirstAndFilterable() = runTest {
        val all = api.listConversations().getOrNull()!!.items
        assertEquals(all.sortedByDescending { it.lastActivityAt }, all)
        val sms = api.listConversations(filter = ConversationFilter(platform = "sms")).getOrNull()!!.items
        assertEquals(listOf("sms"), sms.map { it.platform }.distinct())
        val open = api.listConversations(filter = ConversationFilter(intent = IntentMode.OPEN)).getOrNull()!!.items
        assertEquals(listOf("Riley"), open.map { it.contact.displayName })
    }

    @Test fun longThreadPaginatesChronologicallyWithCursors() = runTest {
        val collected = mutableListOf<Message>()
        var page = PageRequest(limit = 50)
        var pages = 0
        while (true) {
            val p = api.listMessages(MockFixtures.CONVERSATION_RILEY, page).getOrNull()!!
            collected += p.items
            pages++
            page = PageRequest(p.nextCursor ?: break, 50)
        }
        assertEquals(3, pages)
        assertEquals(MockFixtures.LONG_THREAD_SIZE, collected.size)
        assertEquals(collected.sortedBy { it.createdAt }, collected)
        assertEquals(collected.size, collected.map { it.id }.toSet().size)
    }

    @Test fun invalidCursorIsValidationError() = runTest {
        val err = api.listConversations(PageRequest(cursor = "garbage")).errorOrNull()!!
        assertEquals(ErrorCode.VALIDATION_ERROR, err.code)
    }

    @Test fun unknownIdIsNotFound() = runTest {
        val missing = "0192f3a0-0000-7000-8000-00000000ffff"
        assertEquals(ErrorCode.NOT_FOUND, api.getConversation(missing).errorOrNull()!!.code)
        assertEquals(ErrorCode.NOT_FOUND, api.listMessages(missing).errorOrNull()!!.code)
        assertEquals(ErrorCode.NOT_FOUND, api.takeoverContact(missing, AutomationActionRequest(), IdempotencyKey.random()).errorOrNull()!!.code)
    }

    @Test fun detailCarriesAnalysisOrNull() = runTest {
        assertEquals(0.72, api.getConversation(MockFixtures.CONVERSATION_SAM).getOrNull()!!.analysis!!.confidence, 0.0)
        assertNull(api.getConversation(MockFixtures.CONVERSATION_JORDAN).getOrNull()!!.analysis)
    }

    @Test fun sendIsQueuedNeverSent() = runTest {
        val m = api.sendMessage(MockFixtures.CONVERSATION_SAM, SendMessageRequest("hello"), IdempotencyKey.random()).getOrNull()!!
        assertEquals(DeliveryState.QUEUED, m.deliveryState)
        assertEquals(MessageOrigin.USER_MANUAL, m.origin)
        assertNull(m.sentAt)
        assertTrue(api.listMessages(MockFixtures.CONVERSATION_SAM).getOrNull()!!.items.any { it.id == m.id })
    }

    @Test fun sendOnUnsupportedCapabilityIsTypedError() = runTest {
        val notif = MockFixtures.connections.first { it.platform == "android_notification" }
        assertEquals(Verification.UNSUPPORTED, notif.capabilities.capabilities.send.status)
        val err = api.sendMessage(MockFixtures.CONVERSATION_CASEY, SendMessageRequest("hi"), IdempotencyKey.random()).errorOrNull()!!
        assertEquals(ErrorCode.CAPABILITY_UNSUPPORTED, err.code)
        assertFalse(err.isRetryable)
    }

    @Test fun sameKeySameBodyReplaysWithoutSecondSideEffect() = runTest {
        val key = IdempotencyKey.random()
        val first = api.sendMessage(MockFixtures.CONVERSATION_SAM, SendMessageRequest("once"), key) as ApiResult.Success
        val second = api.sendMessage(MockFixtures.CONVERSATION_SAM, SendMessageRequest("once"), key) as ApiResult.Success
        assertFalse(first.idempotentReplayed)
        assertTrue(second.idempotentReplayed)
        assertEquals(first.value, second.value)
        val sends = api.listMessages(MockFixtures.CONVERSATION_SAM).getOrNull()!!.items.count { it.text == "once" }
        assertEquals(1, sends)
    }

    @Test fun sameKeyDifferentBodyIsMismatch() = runTest {
        val key = IdempotencyKey.random()
        api.sendMessage(MockFixtures.CONVERSATION_SAM, SendMessageRequest("a"), key)
        val err = api.sendMessage(MockFixtures.CONVERSATION_SAM, SendMessageRequest("b"), key).errorOrNull()!!
        assertEquals(ErrorCode.IDEMPOTENCY_KEY_MISMATCH, err.code)
    }

    @Test fun approveQueuesMessageAndSecondApproveIsNotPending() = runTest {
        val r = api.approveApproval(MockFixtures.APPROVAL_SAM, ApproveRequest(editedText = "Edited!"), IdempotencyKey.random()).getOrNull()!!
        assertEquals(ApprovalStatus.APPROVED, r.approval.status)
        assertEquals("Edited!", r.approval.finalText)
        assertEquals(DeliveryState.QUEUED, r.message!!.deliveryState)
        assertEquals(MessageOrigin.APPROVED_DRAFT, r.message!!.origin)
        assertEquals(r.message!!.id, r.approval.messageId)

        val again = api.approveApproval(MockFixtures.APPROVAL_SAM, ApproveRequest(), IdempotencyKey.random()).errorOrNull()!!
        assertEquals(ErrorCode.APPROVAL_NOT_PENDING, again.code)
        assertEquals(409, (again as ApiError.Http).status)

        assertEquals(listOf(MockFixtures.APPROVAL_JORDAN), api.listApprovals().getOrNull()!!.items.map { it.id })
        val audit = api.listAgentActions().getOrNull()!!.items.map { it.type }
        assertTrue(AgentActionType.APPROVAL_APPROVED in audit && AgentActionType.SEND_QUEUED in audit)
    }

    @Test fun rejectReturnsNullMessage() = runTest {
        val r = api.rejectApproval(MockFixtures.APPROVAL_JORDAN, RejectRequest("no"), IdempotencyKey.random()).getOrNull()!!
        assertEquals(ApprovalStatus.REJECTED, r.approval.status)
        assertNull(r.message)
        assertEquals(0, api.getConversation(MockFixtures.CONVERSATION_JORDAN).getOrNull()!!.pendingApprovalCount)
    }

    @Test fun takeoverCancelsPendingApprovals() = runTest {
        val s = api.takeoverContact(MockFixtures.CONTACT_SAM, AutomationActionRequest(), IdempotencyKey.random()).getOrNull()!!
        assertEquals(ContactAutomationStatus.TAKEN_OVER, s.status)
        assertEquals(1, s.canceledApprovalCount)
        assertEquals(ApprovalStatus.CANCELED, api.getApproval(MockFixtures.APPROVAL_SAM).getOrNull()!!.status)
        assertEquals(ContactAutomationStatus.TAKEN_OVER, api.getContactAutomationState(MockFixtures.CONTACT_SAM).getOrNull()!!.status)
        val conv = api.getConversation(MockFixtures.CONVERSATION_SAM).getOrNull()!!
        assertEquals(ContactAutomationStatus.TAKEN_OVER, conv.automation.contactStatus)
        assertEquals(0, conv.pendingApprovalCount)

        val resumed = api.resumeContactAutomation(MockFixtures.CONTACT_SAM, AutomationActionRequest(), IdempotencyKey.random()).getOrNull()!!
        assertEquals(ContactAutomationStatus.ACTIVE, resumed.status)
        assertEquals(0, resumed.canceledApprovalCount)
    }

    @Test fun globalPauseAndResume() = runTest {
        assertEquals(GlobalAutomationStatus.PAUSED, api.pauseAllAutomation(AutomationActionRequest(), IdempotencyKey.random()).getOrNull()!!.status)
        assertEquals(GlobalAutomationStatus.PAUSED, api.getGlobalAutomationState().getOrNull()!!.status)
        assertEquals(GlobalAutomationStatus.ACTIVE, api.resumeAllAutomation(AutomationActionRequest(), IdempotencyKey.random()).getOrNull()!!.status)
    }

    @Test fun preferencesVersioningAndIfMatch() = runTest {
        val update = PreferencesUpdate(Intent(IntentMode.CASUAL_DATING), StyleProfile(), AutomationSettings(AutomationMode.ASSISTED))
        val saved = api.putPreferences(update, ifMatchVersion = 0).getOrNull()!!
        assertEquals(1, saved.version)
        assertEquals(ErrorCode.PRECONDITION_FAILED, api.putPreferences(update, ifMatchVersion = 0).errorOrNull()!!.code)
    }

    @Test fun createDraftIsSuggestionOnly() = runTest {
        val before = api.listMessages(MockFixtures.CONVERSATION_SAM).getOrNull()!!.items.size
        val d = api.createDraft(MockFixtures.CONVERSATION_SAM, DraftCreateRequest(), IdempotencyKey.random()).getOrNull()!!
        assertEquals(MockFixtures.CONVERSATION_SAM, d.conversationId)
        assertEquals(before, api.listMessages(MockFixtures.CONVERSATION_SAM).getOrNull()!!.items.size)
        assertTrue(api.listApprovals().getOrNull()!!.items.none { it.draft.id == d.id })
    }

    @Test fun injectedFailuresSurfaceAsErrors() = runTest {
        api.failNextWith()
        assertTrue(api.getMe().errorOrNull() is ApiError.Network)
        assertTrue(api.getMe() is ApiResult.Success)
        api.failAllWith(MockWingmanApi.networkError())
        assertTrue(api.getHealth() is ApiResult.Failure)
        assertTrue(api.listConversations() is ApiResult.Failure)
        api.failAllWith(null)
        assertTrue(api.getHealth() is ApiResult.Success)
        assertEquals(listOf("getMe", "getMe", "getHealth", "listConversations", "getHealth"), api.calls)
    }

    @Test fun fixturesRespectContractInvariants() {
        for ((_, msgs) in MockFixtures.messages) for (m in msgs) {
            if (m.direction == com.wingman.core.model.MessageDirection.INBOUND) {
                assertEquals(DeliveryState.RECEIVED, m.deliveryState)
                assertEquals(MessageOrigin.CONTACT, m.origin)
            } else {
                assertTrue(m.deliveryState != DeliveryState.RECEIVED)
            }
            if (m.deliveryState in setOf(DeliveryState.QUEUED, DeliveryState.SENDING, DeliveryState.CANCELED)) {
                assertNull(m.sentAt); assertNull(m.deliveredAt)
            }
            if (m.deliveryState == DeliveryState.DELIVERED) assertTrue(m.sentAt != null && m.deliveredAt != null)
        }
        for (c in MockFixtures.connections) for (cap in listOf(c.capabilities.capabilities.read, c.capabilities.capabilities.send)) {
            if (cap.status == Verification.VERIFIED) assertTrue(cap.evidence != null && cap.verifiedAt != null)
        }
        val ids = MockFixtures.conversations.map { it.id } + MockFixtures.approvals.map { it.id }
        assertTrue(ids.all { com.wingman.navigation.Routes.isValidId(it) })
    }
}
