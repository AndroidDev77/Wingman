package com.wingman.core.api

import com.wingman.core.api.mock.MockFixtures
import com.wingman.core.api.mock.MockWingmanApi
import com.wingman.core.model.AgentActionType
import com.wingman.core.model.ApprovalStatus
import com.wingman.core.model.Capability
import com.wingman.core.model.ConnectionCreateRequest
import com.wingman.core.model.ConnectionStatus
import com.wingman.core.model.ConnectorCapabilities
import com.wingman.core.model.DeliveryState
import com.wingman.core.model.ErrorCode
import com.wingman.core.model.OutboxClaimRequest
import com.wingman.core.model.SendMessageRequest
import com.wingman.core.model.Verification
import com.wingman.core.model.CapabilitySet
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** [MockWingmanApi] pairing semantics mirror the backend (ADR 0007, D-016). */
class MockPairingTest {
    private val api = MockWingmanApi()
    private val install = "0192f3a0-0000-4000-8000-0000000000aa"
    private fun key(n: Int) = IdempotencyKey("pair-key-$n")

    private fun caps(send: Capability = Capability(Verification.UNVERIFIED, reportsDeliveryReceipts = true)) = ConnectorCapabilities(
        "connector_capabilities.v1", "sms", "0.1.0",
        CapabilitySet(
            read = Capability(Verification.UNVERIFIED), draft = Capability(Verification.UNSUPPORTED), send = send,
            swipe = Capability(Verification.UNSUPPORTED), calendarWrite = Capability(Verification.UNSUPPORTED),
        ),
        Instant.parse("2026-11-01T18:00:00Z"),
    )

    private fun sms(c: ConnectorCapabilities = caps(), installId: String? = install) = ConnectionCreateRequest("sms", "Pixel 8 SMS", installId, c)

    @Test fun createReplayAndAlreadyPaired() = runTest {
        val first = api.createConnection(sms(), key(1)) as ApiResult.Success
        assertFalse(first.idempotentReplayed)
        assertEquals(install, first.value.deviceInstallId)
        assertNotNull(first.value.pairedAt)
        val replay = api.createConnection(sms(), key(1)) as ApiResult.Success
        assertTrue(replay.idempotentReplayed)
        assertEquals(first.value, replay.value)
        val again = api.createConnection(sms(), key(2)) as ApiResult.Success
        assertFalse(again.idempotentReplayed)
        assertEquals("already paired: the existing connection, unchanged", first.value, again.value)
        assertEquals(ErrorCode.IDEMPOTENCY_KEY_MISMATCH, api.createConnection(sms().copy(displayName = "Other"), key(1)).errorOrNull()!!.code)
        assertEquals(1, api.listConnections().getOrNull()!!.items.count { it.deviceInstallId == install })
        assertEquals(1, api.listAgentActions().getOrNull()!!.items.count { it.type == AgentActionType.CONNECTION_PAIRED })
    }

    @Test fun clientClaimedVerifiedIsStoredUnverified() = runTest {
        val claimed = Capability(Verification.VERIFIED, evidence = "trust me", verifiedAt = Instant.parse("2026-11-01T18:00:00Z"), reportsDeliveryReceipts = true)
        val c = api.createConnection(sms(caps(send = claimed)), key(1)).getOrNull()!!
        val send = c.capabilities.capabilities.send
        assertEquals(Verification.UNVERIFIED, send.status)
        assertNull(send.evidence)
        assertNull(send.verifiedAt)
        assertEquals(MockWingmanApi.CLIENT_VERIFIED_NOTE, send.notes)
        assertEquals(true, send.reportsDeliveryReceipts)
        assertEquals(c, api.getConnection(c.id).getOrNull())
        // No capability anywhere is VERIFIED for a device-side pairing.
        val s = c.capabilities.capabilities
        assertTrue(listOf(s.read, s.draft, s.send, s.swipe, s.calendarWrite).none { it.status == Verification.VERIFIED })
    }

    @Test fun validationLikeTheBackend() = runTest {
        assertEquals(422, (api.createConnection(sms(installId = null), key(1)).errorOrNull() as ApiError.Http).status)
        assertEquals(422, (api.createConnection(ConnectionCreateRequest("sms", "x", install, null), key(2)).errorOrNull() as ApiError.Http).status)
        assertEquals(422, (api.createConnection(ConnectionCreateRequest("mock", "x", install, null), key(3)).errorOrNull() as ApiError.Http).status)
        assertEquals(422, (api.createConnection(sms(caps().copy(platform = "android_notification")), key(4)).errorOrNull() as ApiError.Http).status)
        val mock = api.createConnection(ConnectionCreateRequest("mock", "Simulator"), key(5)).getOrNull()!!
        assertEquals(Verification.VERIFIED, mock.capabilities.capabilities.send.status) // the simulator's own document
        assertNull(mock.deviceInstallId)
    }

    @Test fun getConnectionUnknownIs404() = runTest {
        assertEquals(404, (api.getConnection("0192f3a0-0000-7000-8000-0000000000ff").errorOrNull() as ApiError.Http).status)
        assertEquals(404, (api.unpairConnection("0192f3a0-0000-7000-8000-0000000000ff", key(9)).errorOrNull() as ApiError.Http).status)
    }

    @Test fun unpairCancelsQueuedWorkAndApprovalsKeepsLeasedInFlight() = runTest {
        val sms = MockFixtures.CONNECTION_SMS
        val conv = MockFixtures.connections.let { MockFixtures.conversations.first { c -> c.connectionId == sms } }
        val pendingBefore = api.listApprovals().getOrNull()!!.items.count { it.conversationId == conv.id }
        val queued = api.sendMessage(conv.id, SendMessageRequest("hi"), IdempotencyKey("send-key-01")).getOrNull()!!
        assertEquals(DeliveryState.QUEUED, queued.deliveryState)
        api.queueOutboxInstruction(sms, "sms:+15550101234", "leased one")
        api.claimOutboxInstructions(sms, OutboxClaimRequest(MockFixtures.SMS_DEVICE_INSTALL_ID, 1), IdempotencyKey("claim-key-1")).getOrNull()!! // the fixture's paired install (I-018)
        api.queueOutboxInstruction(sms, "sms:+15550101234", "pending one")

        val r = api.unpairConnection(sms, key(1)).getOrNull()!!
        assertEquals(ConnectionStatus.DISCONNECTED, r.connection.status)
        assertNotNull(r.connection.unpairedAt)
        assertEquals(Verification.UNSUPPORTED, r.connection.capabilities.capabilities.send.status)
        assertEquals(MockWingmanApi.UNPAIRED_NOTE, r.connection.capabilities.capabilities.read.notes)
        assertEquals(1, r.canceledMessageCount)
        assertEquals(1, r.canceledInstructionCount)
        assertEquals(pendingBefore, r.canceledApprovalCount)
        assertEquals(1, r.inFlightMessageCount)
        assertEquals(DeliveryState.CANCELED, api.listMessages(conv.id).getOrNull()!!.items.first { it.id == queued.id }.deliveryState)
        assertTrue(api.listApprovals(filter = ApprovalFilter(status = ApprovalStatus.PENDING)).getOrNull()!!.items.none { it.conversationId == conv.id })
        assertEquals(1, api.listAgentActions().getOrNull()!!.items.count { it.type == AgentActionType.CONNECTION_UNPAIRED })
        // Same key replays; a new key is a no-op with zero counts.
        assertTrue((api.unpairConnection(sms, key(1)) as ApiResult.Success).idempotentReplayed)
        val again = api.unpairConnection(sms, key(2)).getOrNull()!!
        assertEquals(listOf(0, 0, 0, 0), listOf(again.canceledMessageCount, again.canceledInstructionCount, again.canceledApprovalCount, again.inFlightMessageCount))
        // Sending on the unpaired connection is refused.
        assertEquals(ErrorCode.CAPABILITY_UNSUPPORTED, api.sendMessage(conv.id, SendMessageRequest("again"), IdempotencyKey("send-key-02")).errorOrNull()!!.code)
    }

    @Test fun claimIsBoundToThePairedInstall() = runTest {
        // I-018 / D-017, like the backend: only the paired install may claim; 422 at /device_install_id otherwise.
        fun rejected(r: ApiResult<*>) {
            val e = r.errorOrNull() as ApiError.Http
            assertEquals(422, e.status)
            assertEquals(ErrorCode.VALIDATION_ERROR, e.code)
            assertEquals("/device_install_id", e.problem!!.errors!!.single().pointer)
        }
        val c = api.createConnection(sms(), key(1)).getOrNull()!!
        api.queueOutboxInstruction(c.id, "sms:+15550101234", "only for the paired phone")
        val other = "0192f3a0-0000-4000-8000-0000000000bb"
        rejected(api.claimOutboxInstructions(c.id, OutboxClaimRequest(other, 1), IdempotencyKey("claim-foreign-1")))
        // The foreign attempt took nothing: the paired install still gets the instruction...
        val leased = api.claimOutboxInstructions(c.id, OutboxClaimRequest(install, 1), IdempotencyKey("claim-key-1")).getOrNull()!!
        assertEquals(1, leased.instructions.size)
        // ...and recovers its own lease with a new key; the foreign install cannot recover it.
        assertEquals(leased.instructions, api.claimOutboxInstructions(c.id, OutboxClaimRequest(install, 1), IdempotencyKey("claim-key-2")).getOrNull()!!.instructions)
        rejected(api.claimOutboxInstructions(c.id, OutboxClaimRequest(other, 1), IdempotencyKey("claim-foreign-2")))
        // An unpaired connection: not even the install it was paired with may claim.
        api.unpairConnection(c.id, key(2))
        rejected(api.claimOutboxInstructions(c.id, OutboxClaimRequest(install, 1), IdempotencyKey("claim-key-3")))
        // A backend-side connection (mock, no binding) still answers with an empty list.
        val sim = api.claimOutboxInstructions(MockFixtures.CONNECTION_MOCK, OutboxClaimRequest(other, 1), IdempotencyKey("claim-sim-1")).getOrNull()!!
        assertEquals(0, sim.instructions.size)
        // A device-side connection that was never paired (the notification fixture has no binding).
        rejected(api.claimOutboxInstructions(MockFixtures.CONNECTION_NOTIFICATION, OutboxClaimRequest(install, 1), IdempotencyKey("claim-notif-1")))
    }

    @Test fun unpairedConnectionAcceptsOnlySendOutcomes() = runTest {
        val c = api.createConnection(sms(), key(1)).getOrNull()!!
        api.unpairConnection(c.id, key(2))
        val inbound = buildJsonObject {
            put("event_id", "0192f3a0-0000-7000-8000-000000000999")
            put("user_id", MockFixtures.USER_ID)
            put("connection_id", c.id)
            put("platform", "sms")
            put("type", "message.received")
            put("external_message_id", "sms:in:x")
        }
        val e = api.ingestConnectorEvent(c.id, inbound, IdempotencyKey("0192f3a0-0000-7000-8000-000000000999")).errorOrNull() as ApiError.Http
        assertEquals(422, e.status)
        // After unpair, pairing the same install again creates a NEW connection.
        val again = api.createConnection(sms(), key(3)).getOrNull()!!
        assertNotEquals(c.id, again.id)
    }
}
