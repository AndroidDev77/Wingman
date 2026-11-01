package com.wingman.features.connections

import com.wingman.core.api.mock.MockFixtures
import com.wingman.core.model.Capability
import com.wingman.core.model.ConnectionStatus
import com.wingman.core.model.ConnectionUnpairResult
import com.wingman.core.model.Verification
import com.wingman.features.conversation.settle
import com.wingman.integrations.sms.FakeClock
import com.wingman.integrations.sms.FakeSmsTransport
import com.wingman.integrations.sms.InMemorySmsStateStore
import com.wingman.integrations.sms.SmsConnectorHost
import com.wingman.integrations.sms.SmsDeviceState
import com.wingman.integrations.sms.SmsLocalStatus
import com.wingman.integrations.sms.SmsPairingKeys
import com.wingman.integrations.sms.SmsPairingTest
import com.wingman.integrations.sms.SmsPairingState
import com.wingman.integrations.sms.SmsConnectionScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SmsConnectionStateTest {
    private val clock = FakeClock()
    private var device = SmsDeviceState(hasTelephony = true, isDefaultSmsApp = true, receiveSmsGranted = true, readSmsGranted = true, sendSmsGranted = true)
    private val local = SmsLocalStatus(0, 0, null, null)

    private fun TestScope.holder(backend: SmsPairingTest.PairingBackend = SmsPairingTest.PairingBackend(), changes: MutableList<Unit> = mutableListOf()): Pair<SmsConnectionsState, SmsConnectorHost> {
        val host = SmsConnectorHost(InMemorySmsStateStore(), FakeSmsTransport(), clock).apply { connectBackend(backend) }
        return SmsConnectionsState(backgroundScope, host, { device }, "Pixel 8 SMS", clock, afterChange = { changes += Unit }) to host
    }

    @Test fun `unpair confirmation explains what is canceled, including the user's own queued sends`() {
        // ADR 0007: unpair cancels queued outbound messages of ANY origin and pending approvals; a leased send can't be recalled.
        val body = SmsConnectionCopy.UNPAIR_BODY
        assertTrue(body.contains("including ones you wrote or approved"))
        assertTrue(body.contains("drafts waiting for your approval"))
        assertTrue(body.contains("can't be recalled"))
        assertFalse(body.contains("Automated messages that are still queued"))
    }

    @Test fun `pair steps follow the role and permissions`() {
        assertEquals(SmsPairStep.READY, SmsConnectionPresenter.pairStep(device))
        assertEquals(SmsPairStep.NEEDS_ROLE, SmsConnectionPresenter.pairStep(device.copy(isDefaultSmsApp = false)))
        assertEquals(SmsPairStep.NEEDS_PERMISSIONS, SmsConnectionPresenter.pairStep(device.copy(sendSmsGranted = false)))
        assertEquals(SmsPairStep.NO_TELEPHONY, SmsConnectionPresenter.pairStep(device.copy(hasTelephony = false)))
    }

    @Test fun `pairing shows what the server stored - send UNVERIFIED, approval needed, autopilot unavailable`() = runTest {
        val changes = mutableListOf<Unit>()
        val (s, _) = holder(changes = changes)
        assertFalse(s.state.value.view.paired)
        s.pair()
        settle()
        val v = s.state.value.view
        assertTrue(v.paired)
        assertEquals("Connected", v.statusLabel)
        assertEquals(Verification.UNVERIFIED, v.capabilities.first { it.name == "Send replies" }.status)
        assertEquals(SmsConnectionCopy.SEND_UNVERIFIED, v.sendingNote)
        assertFalse(v.autopilotAvailable)
        assertEquals(1, changes.size)
        assertNull(s.state.value.busy)
    }

    @Test fun `autopilot is available only for a connected connection whose stored send is VERIFIED`() {
        val sms = MockFixtures.connections.first { it.platform == "sms" }
        assertFalse(SmsConnectionPresenter.autopilotAvailable(sms))
        val verified = sms.copy(capabilities = sms.capabilities.copy(capabilities = sms.capabilities.capabilities.copy(send = Capability(Verification.VERIFIED, evidence = "docs#run"))))
        assertTrue(SmsConnectionPresenter.autopilotAvailable(verified))
        assertFalse(SmsConnectionPresenter.autopilotAvailable(verified.copy(status = ConnectionStatus.DISCONNECTED)))
        assertFalse(SmsConnectionPresenter.autopilotAvailable(verified.copy(unpairedAt = MockFixtures.NOW)))
        assertFalse(SmsConnectionPresenter.autopilotAvailable(null))
        assertEquals(SmsConnectionCopy.SEND_UNSUPPORTED, SmsConnectionPresenter.sendingNote(verified.copy(status = ConnectionStatus.ERROR)))
    }

    @Test fun `paired without a loaded snapshot never implies autopilot`() {
        val v = SmsConnectionPresenter.view(SmsPairingState.Paired(SmsConnectionScope(MockFixtures.USER_ID, MockFixtures.CONNECTION_SMS), null), local, 0)
        assertFalse(v.autopilotAvailable)
        assertEquals(SmsConnectionCopy.SEND_UNVERIFIED, v.sendingNote)
    }

    @Test fun `pair is refused until role and permissions are in place`() = runTest {
        device = device.copy(isDefaultSmsApp = false)
        val backend = SmsPairingTest.PairingBackend()
        val (s, _) = holder(backend)
        s.pair()
        settle()
        assertTrue(backend.creates.isEmpty())
        assertEquals(SmsPairStep.NEEDS_ROLE, s.state.value.step)
    }

    @Test fun `unpair needs confirmation, then clears state and reports what was canceled`() = runTest {
        val changes = mutableListOf<Unit>()
        val backend = SmsPairingTest.PairingBackend()
        val (s, host) = holder(backend, changes)
        s.pair()
        settle()
        s.requestUnpair()
        assertTrue(s.state.value.confirmingUnpair)
        assertTrue("nothing sent before confirmation", backend.unpairs.isEmpty())
        s.cancelUnpair()
        assertFalse(s.state.value.confirmingUnpair)
        s.requestUnpair()
        s.confirmUnpair()
        settle()
        assertEquals(1, backend.unpairs.size)
        assertFalse(s.state.value.view.paired)
        assertNull(host.store.get(SmsPairingKeys.CONNECTION))
        assertNull(host.connector())
        assertNotNull(s.state.value.lastUnpair)
        assertEquals(2, changes.size)
    }

    @Test fun `startup check of a connection unpaired elsewhere shows the re-pair notice`() = runTest {
        val backend = SmsPairingTest.PairingBackend()
        val (s, host) = holder(backend)
        s.pair()
        settle()
        val id = host.store.get(SmsPairingKeys.CONNECTION)!!
        backend.mock.unpairConnection(id, com.wingman.core.api.IdempotencyKey("other-device-unpair"))
        s.verify()
        settle()
        val v = s.state.value.view
        assertFalse(v.paired)
        assertNotNull(v.notice)
        assertNull(host.connector())
    }

    @Test fun `unpair summary is plain language`() {
        val c = MockFixtures.connections.first()
        assertEquals("Disconnected.", SmsConnectionsState.unpairSummary(ConnectionUnpairResult(c, 0, 0, 0, 0)))
        assertEquals(
            "Disconnected. 1 queued message(s) canceled; 2 pending approval(s) canceled; 1 message(s) already being sent couldn't be recalled.",
            SmsConnectionsState.unpairSummary(ConnectionUnpairResult(c, 1, 1, 2, 1)),
        )
        assertTrue(SmsConnectionsState.unpairSummary(null).startsWith("Disconnected."))
    }

    @Test fun `last sync label`() {
        assertEquals("Never synced", SmsConnectionCopy.lastSync(null, 0))
        assertEquals("Last sync just now", SmsConnectionCopy.lastSync(1_000, 30_000))
        assertEquals("Last sync 5 min ago", SmsConnectionCopy.lastSync(0, 300_000))
    }
}
