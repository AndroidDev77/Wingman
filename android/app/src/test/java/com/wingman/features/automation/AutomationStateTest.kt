package com.wingman.features.automation

import com.wingman.core.api.IdempotencyKey
import com.wingman.core.api.RetryPolicy
import com.wingman.core.api.mock.MockFixtures
import com.wingman.core.model.AutomationActionRequest
import com.wingman.core.model.ContactAutomationStatus
import com.wingman.core.model.DeliveryState
import com.wingman.core.model.ErrorCode
import com.wingman.core.model.GlobalAutomationStatus
import com.wingman.core.ui.UiState
import com.wingman.features.approvals.RecordingApi
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

@OptIn(ExperimentalCoroutinesApi::class)
class AutomationStateTest {
    private val sam = MockFixtures.CONTACT_SAM

    private fun TestScope.holder(api: RecordingApi, policy: RetryPolicy = RetryPolicy(maxAttempts = 1)) =
        AutomationState(backgroundScope, api, policy = policy, sleep = {}).also {
            it.load()
            settle()
        }

    private fun AutomationState.data() = (state.value as UiState.Content).data
    private fun AutomationState.contact(id: String) = data().contacts.first { it.contactId == id }

    @Test fun loadsServerStatusForEveryContact() = runTest {
        val s = holder(RecordingApi())
        assertEquals(4, s.data().contacts.size)
        val c = s.contact(sam)
        assertEquals(ContactAutomationStatus.ACTIVE, c.status)
        assertEquals(listOf(ContactAction.PAUSE, ContactAction.TAKEOVER), c.actions)
        assertEquals(GlobalAutomationStatus.ACTIVE, s.data().global.status)
        assertFalse(s.data().global.switchOn)
    }

    @Test fun takeoverIsShownOnlyAfterServerConfirmation() = runTest {
        val api = RecordingApi()
        val s = holder(api)
        api.gate = CompletableDeferred()
        assertTrue(s.takeover(sam))
        runCurrent()
        val during = s.contact(sam)
        assertEquals(ContactAutomationStatus.ACTIVE, during.status) // still the server's last word
        assertFalse(during.takeoverSwitch)
        assertEquals(ContactAction.TAKEOVER, during.inFlight)
        assertEquals("Taking over…", during.inFlight!!.inFlightLabel)

        api.gate!!.complete(Unit); settle()
        val after = s.contact(sam)
        assertEquals(ContactAutomationStatus.TAKEN_OVER, after.status)
        assertTrue(after.takeoverSwitch)
        assertNull(after.inFlight)
        assertEquals("You've taken over. Canceled 1 draft waiting for approval.", after.confirmation)
        assertEquals(listOf(ContactAction.RESUME), after.actions)
    }

    @Test fun failedTakeoverKeepsTheOldStatus() = runTest {
        val api = RecordingApi()
        val s = holder(api)
        api.script += RecordingApi.network()
        s.takeover(sam); settle()
        val c = s.contact(sam)
        assertEquals(ContactAutomationStatus.ACTIVE, c.status)
        assertFalse(c.takeoverSwitch)
        assertTrue(c.error!!.isNetwork)
        assertNull(c.confirmation)
    }

    @Test fun pauseIsShownOnlyAfterConfirmationAndRapidTapsSendOneRequest() = runTest {
        val api = RecordingApi()
        val s = holder(api)
        api.gate = CompletableDeferred()
        assertTrue(s.pause(sam)); runCurrent()
        repeat(4) { assertFalse(s.pause(sam)) }
        assertFalse(s.takeover(sam)) // one action per contact at a time
        assertFalse(s.contact(sam).pausedSwitch)
        api.gate!!.complete(Unit); settle()
        assertEquals(1, api.calls.size)
        assertTrue(s.contact(sam).pausedSwitch)
        assertEquals(ContactAutomationStatus.PAUSED, s.contact(sam).status)
    }

    @Test fun retryAfterNetworkErrorReusesKeyButADifferentActionGetsANewOne() = runTest {
        val api = RecordingApi()
        val s = holder(api)
        api.script += RecordingApi.network()
        s.pause(sam); settle()
        s.pause(sam); settle()
        val pauses = api.ops("pause")
        assertEquals(2, pauses.size)
        assertEquals(pauses[0].key, pauses[1].key)

        api.script += RecordingApi.network()
        s.takeover(sam); settle()
        s.pause(sam); settle() // a different action after a failed takeover
        val takeover = api.ops("takeover").single()
        assertTrue(takeover.key != api.ops("pause").last().key)
    }

    @Test fun rejectedActionRereadsTheServerState() = runTest {
        val api = RecordingApi()
        val s = holder(api)
        // Server-side change the client hasn't seen.
        api.mock.takeoverContact(sam, AutomationActionRequest(), IdempotencyKey.random())
        api.script += RecordingApi.http(409, ErrorCode.AUTOMATION_PAUSED)
        s.pause(sam); settle()
        val c = s.contact(sam)
        assertNotNull(c.error)
        assertEquals(ContactAutomationStatus.TAKEN_OVER, c.status) // from the re-read
    }

    @Test fun globalPauseAndResumeShowOnlyConfirmedState() = runTest {
        val api = RecordingApi()
        val s = holder(api)
        api.gate = CompletableDeferred()
        assertTrue(s.pauseAll()); runCurrent()
        assertFalse(s.pauseAll())
        assertFalse(s.data().global.switchOn)
        assertEquals("Pausing everything…", s.data().global.inFlightLabel)
        api.gate!!.complete(Unit); settle()
        assertTrue(s.data().global.switchOn)
        assertEquals("Paused for everyone", s.data().global.label)
        assertEquals("Paused for everyone: no automatic replies", s.contact(MockFixtures.CONTACT_CASEY).statusLabel)

        api.gate = null
        api.script += RecordingApi.http(500)
        s.resumeAll(); settle()
        assertTrue(s.data().global.switchOn)
        assertNotNull(s.data().global.error)
        s.resumeAll(); settle()
        assertFalse(s.data().global.switchOn)
        val resumes = api.ops("resume_all")
        assertEquals(2, resumes.size)
        assertEquals(1, resumes.map { it.key }.distinct().size) // retried with the same key
    }

    @Test fun summariesComeFromServerCounts() {
        val base = com.wingman.core.model.ContactAutomationState(
            sam, ContactAutomationStatus.TAKEN_OVER, com.wingman.core.model.AutomationMode.ASSISTED,
            GlobalAutomationStatus.ACTIVE, null, canceledApprovalCount = 2, canceledMessageCount = 1,
        )
        assertEquals(
            "You've taken over. Canceled 2 drafts waiting for approval and 1 queued message.",
            ActionSummary.contact(ContactAction.TAKEOVER, base),
        )
        assertEquals("Paused.", ActionSummary.contact(ContactAction.PAUSE, base.copy(canceledApprovalCount = 0, canceledMessageCount = 0)))
        assertTrue(AutomationCopy.PAUSE.contains("still sends"))
        assertTrue(AutomationCopy.TAKEOVER.contains("cancels everything"))
    }

    @Test fun takeoverCancelsTheApprovedButUnsentDraft() = runTest {
        // End to end through the mock: approve, then take over before the connector picks it up.
        val api = RecordingApi()
        val s = holder(api)
        val approved = api.mock.approveApproval(MockFixtures.APPROVAL_SAM, com.wingman.core.model.ApproveRequest(), IdempotencyKey.random()).getOrNull()!!
        s.takeover(sam); settle()
        assertEquals("You've taken over. Canceled 1 queued message.", s.contact(sam).confirmation)
        val msg = api.mock.listMessages(MockFixtures.CONVERSATION_SAM).getOrNull()!!.items.first { it.id == approved.message!!.id }
        assertEquals(DeliveryState.CANCELED, msg.deliveryState)
    }
}
