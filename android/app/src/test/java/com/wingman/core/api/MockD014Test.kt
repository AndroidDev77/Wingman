package com.wingman.core.api

import com.wingman.core.api.mock.MockFixtures
import com.wingman.core.api.mock.MockWingmanApi
import com.wingman.core.model.AgentActionType
import com.wingman.core.model.ApprovalStatus
import com.wingman.core.model.ApproveRequest
import com.wingman.core.model.AutomationActionRequest
import com.wingman.core.model.ContactAutomationStatus
import com.wingman.core.model.DeliveryState
import com.wingman.core.model.GlobalAutomationStatus
import com.wingman.core.model.Message
import com.wingman.core.model.SendMessageRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [MockWingmanApi] follows D-014 (pause stops automated sends only; takeover cancels everything automated). */
class MockD014Test {
    private val api = MockWingmanApi()
    private val sam = MockFixtures.CONTACT_SAM
    private val samConv = MockFixtures.CONVERSATION_SAM
    private fun k() = IdempotencyKey.random()
    private fun none() = AutomationActionRequest()

    private suspend fun state(id: String): DeliveryState =
        api.listMessages(samConv).getOrNull()!!.items.first { it.id == id }.deliveryState

    private suspend fun approveSam(): Message =
        api.approveApproval(MockFixtures.APPROVAL_SAM, ApproveRequest(), k()).getOrNull()!!.message!!

    @Test fun contactPauseCancelsQueuedAutopilotOnly() = runTest {
        val auto = api.queueAutopilot(samConv, "auto reply")!!
        val manual = api.sendMessage(samConv, SendMessageRequest("my own"), k()).getOrNull()!!
        val approved = approveSam()

        val s = api.pauseContactAutomation(sam, none(), k()).getOrNull()!!
        assertEquals(ContactAutomationStatus.PAUSED, s.status)
        assertEquals(0, s.canceledApprovalCount)
        assertEquals(1, s.canceledMessageCount)
        assertEquals(DeliveryState.CANCELED, state(auto.id))
        assertEquals(DeliveryState.QUEUED, state(manual.id))
        assertEquals(DeliveryState.QUEUED, state(approved.id)) // user-approved still goes
    }

    @Test fun pauseKeepsPendingApprovalsAndAllowsApprovingDuringPause() = runTest {
        api.pauseContactAutomation(sam, none(), k())
        assertEquals(ApprovalStatus.PENDING, api.getApproval(MockFixtures.APPROVAL_SAM).getOrNull()!!.status)
        val approved = approveSam()
        assertEquals(DeliveryState.QUEUED, approved.deliveryState)
        // And the user's own send works while paused.
        assertNotNull(api.sendMessage(samConv, SendMessageRequest("still me"), k()).getOrNull())
        // No new autopilot while paused.
        assertNull(api.queueAutopilot(samConv, "nope"))
    }

    @Test fun globalPauseCancelsAutopilotEverywhereAndKeepsApprovals() = runTest {
        val auto = api.queueAutopilot(samConv, "auto")!!
        val approved = approveSam()
        val g = api.pauseAllAutomation(none(), k()).getOrNull()!!
        assertEquals(GlobalAutomationStatus.PAUSED, g.status)
        assertEquals(DeliveryState.CANCELED, state(auto.id))
        assertEquals(DeliveryState.QUEUED, state(approved.id))
        assertEquals(ApprovalStatus.PENDING, api.getApproval(MockFixtures.APPROVAL_JORDAN).getOrNull()!!.status)
        assertEquals(GlobalAutomationStatus.PAUSED, api.getContactAutomationState(sam).getOrNull()!!.globalStatus)
        assertEquals(GlobalAutomationStatus.PAUSED, api.getConversation(samConv).getOrNull()!!.automation.globalStatus)
        assertNull(api.queueAutopilot(samConv, "nope"))

        api.resumeAllAutomation(none(), k())
        assertEquals(DeliveryState.CANCELED, state(auto.id)) // resume never resurrects
    }

    @Test fun takeoverCancelsApprovalsApprovedButUnsentAndAutopilotButNotManual() = runTest {
        val approved = approveSam()
        val auto = api.queueAutopilot(samConv, "auto")!!
        val manual = api.sendMessage(samConv, SendMessageRequest("my own"), k()).getOrNull()!!
        // A new pending approval for Sam would also be canceled: re-check via Jordan's untouched one later.

        val s = api.takeoverContact(sam, none(), k()).getOrNull()!!
        assertEquals(ContactAutomationStatus.TAKEN_OVER, s.status)
        assertEquals(2, s.canceledMessageCount)
        assertEquals(DeliveryState.CANCELED, state(approved.id))
        assertEquals(DeliveryState.CANCELED, state(auto.id))
        assertEquals(DeliveryState.QUEUED, state(manual.id))
        assertEquals(ApprovalStatus.PENDING, api.getApproval(MockFixtures.APPROVAL_JORDAN).getOrNull()!!.status) // other contact
        // The user can still send manually while taken over.
        assertNotNull(api.sendMessage(samConv, SendMessageRequest("me again"), k()).getOrNull())

        val audit = api.listAgentActions(filter = AgentActionFilter(contactId = sam)).getOrNull()!!.items
        assertEquals(2, audit.count { it.type == AgentActionType.SEND_CANCELED && it.reasonCode == "automation_takeover" })
        assertTrue(audit.any { it.type == AgentActionType.AUTOMATION_TAKEOVER })
    }

    @Test fun takeoverCancelsPendingApprovalsWithAudit() = runTest {
        val s = api.takeoverContact(sam, none(), k()).getOrNull()!!
        assertEquals(1, s.canceledApprovalCount)
        assertEquals(0, s.canceledMessageCount)
        val audit = api.listAgentActions(filter = AgentActionFilter(contactId = sam)).getOrNull()!!.items
        assertTrue(audit.any { it.type == AgentActionType.APPROVAL_CANCELED && it.reasonCode == "automation_takeover" })
        // A repeated takeover reports what THIS call canceled (nothing), like the backend.
        val again = api.takeoverContact(sam, none(), k()).getOrNull()!!
        assertEquals(0, again.canceledApprovalCount)
        assertEquals(0, api.getContactAutomationState(sam).getOrNull()!!.canceledApprovalCount)
    }

    @Test fun messageAlreadySendingCannotBeRecalled() = runTest {
        val approved = approveSam()
        assertTrue(api.simulateDelivery(approved.id, DeliveryState.SENDING))
        val s = api.takeoverContact(sam, none(), k()).getOrNull()!!
        assertEquals(0, s.canceledMessageCount)
        assertEquals(DeliveryState.SENDING, state(approved.id))
    }

    @Test fun deliveryOnlyMovesForwardAndIsAudited() = runTest {
        val approved = approveSam()
        assertTrue(api.simulateDelivery(approved.id, DeliveryState.SENT))
        assertFalse(api.simulateDelivery(approved.id, DeliveryState.QUEUED))
        assertFalse(api.simulateDelivery(approved.id, DeliveryState.SENT))
        val m = api.listMessages(samConv).getOrNull()!!.items.first { it.id == approved.id }
        assertEquals(DeliveryState.SENT, m.deliveryState)
        assertNotNull(m.sentAt)
        assertNull(m.deliveredAt)
        val types = api.listAgentActions(filter = AgentActionFilter(conversationId = samConv)).getOrNull()!!.items.map { it.type }
        assertTrue(AgentActionType.SEND_ATTEMPTED in types && AgentActionType.SEND_ACKNOWLEDGED in types)
        assertFalse(AgentActionType.DELIVERY_CONFIRMED in types)

        api.takeoverContact(sam, none(), k())
        val canceled = canceledAutopilotForJordan()
        assertFalse(api.simulateDelivery(canceled, DeliveryState.SENT)) // canceled stays canceled
    }

    private suspend fun canceledAutopilotForJordan(): String {
        val auto = api.queueAutopilot(MockFixtures.CONVERSATION_JORDAN, "x")!!
        api.pauseContactAutomation(MockFixtures.CONTACT_JORDAN, none(), k())
        return auto.id
    }

    @Test fun auditIsSeededAndRedacted() = runTest {
        val items = api.listAgentActions().getOrNull()!!.items
        assertTrue(items.any { it.type == AgentActionType.APPROVAL_REQUESTED && it.approvalId == MockFixtures.APPROVAL_SAM && it.reasonCode == "assisted_mode" })
        approveSam()
        val approvedEntry = api.listAgentActions(filter = AgentActionFilter(type = AgentActionType.APPROVAL_APPROVED)).getOrNull()!!.items.single()
        assertEquals("false", approvedEntry.details["edited"].toString())
    }
}
