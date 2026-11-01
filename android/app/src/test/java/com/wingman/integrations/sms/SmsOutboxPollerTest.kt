package com.wingman.integrations.sms

import com.wingman.core.api.ApiResult
import com.wingman.core.api.IdempotencyKey
import com.wingman.core.api.mock.MockFixtures
import com.wingman.core.api.mock.MockWingmanApi
import com.wingman.core.model.OutboxAuthorization
import com.wingman.core.model.OutboxAuthorizationOrigin
import com.wingman.core.model.OutboxInstructionState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

/** Claim poller: install ID, claim-key persistence and replay, lease deadline, one send per key, outcome envelopes. */
class SmsOutboxPollerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val clock = FakeClock()
    private val transport = FakeSmsTransport()
    private val backend = ScriptedSmsBackend()
    private var keyCounter = 0

    private class Rig(val store: SmsStateStore, val outbox: SmsOutbox, val sender: SmsSender, val poller: SmsOutboxPoller)

    private fun rig(store: SmsStateStore = InMemorySmsStateStore(), config: SmsPollerConfig = SmsPollerConfig()): Rig {
        val outbox = SmsOutbox(store)
        val sender = SmsSender(SCOPE, store, transport, outbox, clock)
        val poller = SmsOutboxPoller(SCOPE, store, backend, sender, clock, config, newKey = { "claim-key-${++keyCounter}" })
        return Rig(store, outbox, sender, poller)
    }

    @Test fun `device_install_id is a stable persisted UUID`() {
        val file = File(tmp.root, "s.json")
        val id = rig(FileSmsStateStore(file)).poller.deviceInstallId()
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$").matches(id))
        assertEquals(id, rig(FileSmsStateStore(file)).poller.deviceInstallId())
    }

    @Test fun `claims with install id, max_instructions and a fresh key per completed poll`() {
        val r = rig(config = SmsPollerConfig(maxInstructions = 3))
        r.poller.poll(READY)
        r.poller.poll(READY)
        assertEquals(listOf("claim-key-1", "claim-key-2"), backend.claims.map { it.key })
        assertEquals(CONNECTION, backend.claims[0].connectionId)
        assertEquals(r.poller.deviceInstallId(), backend.claims[0].body.deviceInstallId)
        assertEquals(3, backend.claims[0].body.maxInstructions)
        assertNull(r.poller.pendingClaimKey())
    }

    @Test fun `maps an instruction to one submit with authorizedBy from authorization`() {
        val r = rig()
        backend.claimScript += { ApiResult.Success(claimResult(instruction())) }
        val report = r.poller.poll(READY)
        assertEquals(SmsPollReport(claimed = 1, submitted = 1), report)
        assertEquals(listOf(FakeSmsTransport.Submit("+15550101234", "Yes! 8pm?", null, "send:$OUTBOUND:1", true)), transport.submits)
        assertEquals("approved_draft/0192f3a0-0000-7000-8000-0000000000e3", r.sender.record("send:$OUTBOUND:1")!!.authorizedBy)
        assertEquals("user_manual", SmsOutboxPoller.authorizedBy(instruction(authorization = OutboxAuthorization(OutboxAuthorizationOrigin.USER_MANUAL, null))))
        assertEquals("autopilot", SmsOutboxPoller.authorizedBy(instruction(authorization = OutboxAuthorization(OutboxAuthorizationOrigin.AUTOPILOT, null))))
    }

    @Test fun `lost claim response is recovered by replaying the persisted key, and sends once`() {
        val file = File(tmp.root, "s.json")
        val r1 = rig(FileSmsStateStore(file))
        // The server leased, but the response never arrived.
        backend.claimScript += { ScriptedSmsBackend.network() }
        val lost = r1.poller.poll(READY)
        assertEquals("network: IOException", lost.skippedReason)
        assertEquals("claim-key-1", r1.poller.pendingClaimKey())
        assertEquals(0, transport.submits.size)

        // Process death; the next poll (after the backoff) replays the same key and body.
        val r2 = rig(FileSmsStateStore(file))
        clock.now += 60_000
        backend.claimScript += { ApiResult.Success(claimResult(instruction()), idempotentReplayed = true) }
        val replayed = r2.poller.poll(READY)
        assertTrue(replayed.claimReplayed)
        assertEquals(1, replayed.submitted)
        assertEquals(listOf("claim-key-1", "claim-key-1"), backend.claims.map { it.key })
        assertEquals(backend.claims[0].body, backend.claims[1].body)
        assertNull(r2.poller.pendingClaimKey())
        assertEquals(1, transport.count("send:$OUTBOUND:1"))
    }

    @Test fun `process death after executing but before clearing the claim replays without a second send`() {
        val store = InMemorySmsStateStore()
        val r = rig(store)
        backend.claimScript += { ApiResult.Success(claimResult(instruction())) }
        r.poller.poll(READY)
        // Simulate death right before the pending claim was cleared: put it back.
        store.put(SmsOutboxPoller.KEY_PENDING_CLAIM, """{"key":"claim-key-1","device_install_id":"${r.poller.deviceInstallId()}","max_instructions":5,"first_sent_at":${clock.now}}""")
        backend.claimScript += { ApiResult.Success(claimResult(instruction()), idempotentReplayed = true) }
        val again = rig(store).poller.poll(READY)
        assertEquals(1, again.replayed)
        assertEquals(1, transport.submits.size)
    }

    @Test fun `5xx and 409 keep the claim key, 4xx rejection starts a new one`() {
        val r = rig()
        backend.claimScript += { ScriptedSmsBackend.http(503) }
        backend.claimScript += { ScriptedSmsBackend.http(409, "idempotency_key_in_progress", retryAfterSeconds = 1) }
        backend.claimScript += { ScriptedSmsBackend.http(422, "validation_error") }
        r.poller.poll(READY, ignoreBackoff = true)
        r.poller.poll(READY, ignoreBackoff = true)
        assertEquals("claim-key-1", r.poller.pendingClaimKey())
        r.poller.poll(READY, ignoreBackoff = true)
        assertNull(r.poller.pendingClaimKey())
        r.poller.poll(READY, ignoreBackoff = true)
        assertEquals(listOf("claim-key-1", "claim-key-1", "claim-key-1", "claim-key-2"), backend.claims.map { it.key })
    }

    @Test fun `claim backoff honors Retry-After and skips polls while waiting`() {
        val r = rig()
        backend.claimScript += { ScriptedSmsBackend.http(429, "rate_limited", retryAfterSeconds = 30) }
        assertEquals(clock.now + 30_000, r.poller.poll(READY).nextAttemptAtMillis)
        clock.now += 29_000
        assertEquals("backing off", r.poller.poll(READY).skippedReason)
        assertEquals(1, backend.claims.size)
        clock.now += 1_000
        r.poller.poll(READY)
        assertEquals(2, backend.claims.size)
    }

    @Test fun `stale pending claim key is not replayed forever`() {
        val r = rig()
        backend.claimScript += { ScriptedSmsBackend.network() }
        r.poller.poll(READY)
        clock.now += SmsOutboxPoller.STALE_CLAIM_MILLIS
        r.poller.poll(READY)
        assertEquals(listOf("claim-key-1", "claim-key-2"), backend.claims.map { it.key })
    }

    @Test fun `lease deadline passed - no radio submit, send_failed timeout retryable`() {
        val r = rig(config = SmsPollerConfig(deadlineMarginMillis = 15_000))
        // 120 s lease; the device is slow and the clock moves past the deadline during the claim.
        backend.claimScript += { clock.now += 106_000; ApiResult.Success(claimResult(instruction(leaseSeconds = 120))) }
        val report = r.poller.poll(READY)
        assertEquals(1, report.failedBeforeSend)
        assertEquals(0, transport.submits.size)
        val failed = r.outbox.pending().single()
        assertEquals("message.send_failed", failed.str("type"))
        val failure = failed["payload"]!!.jsonObject["failure"]!!.jsonObject
        assertEquals("timeout", failure.str("code"))
        assertTrue(failure["retryable"]!!.jsonPrimitive.boolean)
        assertEquals("send:$OUTBOUND:1", failed["payload"]!!.jsonObject.str("idempotency_key"))
        // The key is used up: a re-claim of the same instruction never reaches the radio.
        backend.claimScript += { ApiResult.Success(claimResult(instruction())) }
        assertEquals(1, r.poller.poll(READY).replayed)
        assertEquals(0, transport.submits.size)
    }

    @Test fun `deadline is computed from server_time, not from the device's absolute clock`() {
        // Device clock is 3 hours behind the server. lease_expires_at is in the device's "future",
        // but the lease length (120 s) is what counts.
        val serverNow = Instant.ofEpochMilli(clock.now).plusSeconds(3 * 3600)
        val r = rig(config = SmsPollerConfig(deadlineMarginMillis = 15_000))
        backend.claimScript += { clock.now += 104_000; ApiResult.Success(claimResult(instruction(serverNow = serverNow), serverNow = serverNow)) }
        assertEquals(1, r.poller.poll(READY).submitted) // 104 s < 120 - 15 s
        val r2 = rig(config = SmsPollerConfig(deadlineMarginMillis = 15_000))
        backend.claimScript += { clock.now += 105_000; ApiResult.Success(claimResult(instruction(outbound = OUTBOUND2, serverNow = serverNow), serverNow = serverNow)) }
        assertEquals(1, r2.poller.poll(READY).failedBeforeSend) // 105 s >= 105 s
    }

    @Test fun `replayed claim answer anchors the deadline at the first attempt, so an old lease is refused`() {
        val r = rig(config = SmsPollerConfig(deadlineMarginMillis = 15_000))
        backend.claimScript += { ScriptedSmsBackend.network() }
        r.poller.poll(READY)
        clock.now += 200_000 // the lease granted during the lost attempt has expired by now
        backend.claimScript += { ApiResult.Success(claimResult(instruction()), idempotentReplayed = true) }
        val report = r.poller.poll(READY, ignoreBackoff = true)
        assertEquals(1, report.failedBeforeSend)
        assertEquals(0, transport.submits.size)
    }

    @Test fun `duplicate instruction in one response and a re-claim give exactly one radio submit`() {
        val r = rig()
        backend.claimScript += { ApiResult.Success(claimResult(instruction(), instruction())) }
        backend.claimScript += { ApiResult.Success(claimResult(instruction())) }
        val first = r.poller.poll(READY)
        val second = r.poller.poll(READY)
        assertEquals(1, first.submitted)
        assertEquals(1, first.replayed)
        assertEquals(1, second.replayed)
        assertEquals(1, transport.count("send:$OUTBOUND:1"))
        assertEquals(1, r.sender.transportAttempts("send:$OUTBOUND:1"))
    }

    @Test fun `a retry instruction with a new key is a new attempt`() {
        val r = rig()
        backend.claimScript += { ApiResult.Success(claimResult(instruction(attempt = 1))) }
        backend.claimScript += { ApiResult.Success(claimResult(instruction(attempt = 2))) }
        r.poller.poll(READY)
        r.sender.onPartSent("send:$OUTBOUND:1", 0, SmsSentResult.RadioOff)
        r.poller.poll(READY)
        assertEquals(listOf("send:$OUTBOUND:1", "send:$OUTBOUND:2"), transport.submits.map { it.key })
    }

    @Test fun `instructions for another connection, not leased, or malformed are never executed`() {
        val r = rig()
        backend.claimScript += {
            ApiResult.Success(
                claimResult(
                    instruction(connectionId = "0192f3a0-0000-7000-8000-0000000000a2"),
                    instruction(outbound = OUTBOUND2, state = OutboxInstructionState.PENDING),
                    instruction(outbound = "not-a-uuid"),
                ),
            )
        }
        assertEquals(3, r.poller.poll(READY).ignored)
        assertEquals(0, transport.submits.size)
        assertEquals(0, r.outbox.size())
    }

    @Test fun `results flow back as envelopes through the uploader`() {
        val store = InMemorySmsStateStore()
        val connector = SmsConnector(SCOPE, store, transport, clock = clock, api = backend)
        backend.claimScript += { ApiResult.Success(claimResult(instruction())) }
        connector.sync(READY)
        connector.sender.onPartSent("send:$OUTBOUND:1", 0, SmsSentResult.Ok)
        connector.sender.onPartDelivered("send:$OUTBOUND:1", 0, SmsDeliveryStatus.DELIVERED)
        connector.sync(READY)
        val types = backend.ingests.map { it.envelope.str("type") }
        assertEquals(listOf("message.send_acknowledged", "message.delivery_receipt"), types)
        backend.ingests.forEach {
            assertEquals(it.envelope.str("event_id"), it.key)
            assertEquals(OUTBOUND, it.envelope["payload"]!!.jsonObject.str("outbound_message_id"))
        }
        assertEquals(0, connector.outbox.size())
    }

    @Test fun `refusal is uploaded in the same sync round`() {
        val connector = SmsConnector(SCOPE, InMemorySmsStateStore(), transport, clock = clock, api = backend)
        backend.claimScript += { ApiResult.Success(claimResult(instruction())) }
        val report = connector.sync(READY.copy(sendPermissionGranted = false))
        assertEquals(1, report.poll.failedBeforeSend)
        assertEquals(1, report.uploadAfter!!.accepted)
        assertEquals("message.send_failed", backend.ingests.single().envelope.str("type"))
    }

    @Test fun `incoming SMS never triggers a claim or a send`() {
        val connector = SmsConnector(SCOPE, InMemorySmsStateStore(), transport, clock = clock, api = backend)
        backend.claimScript += { ApiResult.Success(claimResult(instruction())) }
        repeat(3) { connector.inbound.onSmsReceived(sms(body = "hi $it", smscTs = 1_000L + it)) }
        connector.maintain()
        assertEquals(0, backend.claims.size)
        assertEquals(0, transport.submits.size)
        assertEquals(3, backend.ingests.size)
    }

    @Test fun `end to end against MockWingmanApi - dispatcher instruction, claim, send, ack ingested`() {
        val mock = MockWingmanApi(clock = { Instant.ofEpochMilli(clock.now) })
        val key = mock.queueOutboxInstruction(CONNECTION, "sms:+15550101234", "See you at 8")
        // This install is the one the fixture SMS connection is paired with (I-018).
        val store = InMemorySmsStateStore().apply { put(SmsInstallId.KEY, MockFixtures.SMS_DEVICE_INSTALL_ID) }
        val connector = SmsConnector(SCOPE, store, transport, clock = clock, api = mock)
        assertEquals(1, connector.sync(READY).poll.submitted)
        connector.sender.onPartSent(key, 0, SmsSentResult.Ok)
        assertEquals(1, connector.sync(READY).uploadBefore.accepted)
        assertEquals(listOf("message.send_acknowledged"), mock.ingestedEvents.map { it.str("type") })
        // A second install may not claim on this connection at all (I-018: 422), and this device's next claim is empty.
        val other = runBlocking {
            mock.claimOutboxInstructions(CONNECTION, com.wingman.core.model.OutboxClaimRequest("0192f3a0-0000-7000-8000-00000000ffff"), IdempotencyKey("other-device-claim"))
        }
        assertEquals(422, (other.errorOrNull() as com.wingman.core.api.ApiError.Http).status)
        assertEquals(0, connector.sync(READY).poll.claimed)
        assertEquals(1, transport.submits.size)
    }

    @Test fun `mock backend rejects an outcome for a never-leased instruction (dead letter)`() {
        val mock = MockWingmanApi(clock = { Instant.ofEpochMilli(clock.now) })
        val store = InMemorySmsStateStore()
        val outbox = SmsOutbox(store)
        val sender = SmsSender(SCOPE, store, transport, outbox, clock)
        sender.send(sendCommand(key = "send:$OUTBOUND:1"), READY) // demo-style send, no instruction
        sender.onPartSent("send:$OUTBOUND:1", 0, SmsSentResult.Ok)
        val r = outbox.drain(WingmanApiSmsEnvelopeUploader(mock))
        assertEquals(1, r.rejected)
        assertEquals(1, outbox.deadLetterCount())
        assertFalse(mock.ingestedEvents.isNotEmpty())
    }

    @Test fun `I-018 rejection of this install is definitive, backs off and never crash-loops`() {
        // A claim from an install the connection is not paired with (e.g. re-paired on another phone).
        val mock = MockWingmanApi(clock = { Instant.ofEpochMilli(clock.now) })
        mock.queueOutboxInstruction(CONNECTION, "sms:+15550101234", "not for this phone")
        val store = InMemorySmsStateStore().apply { put(SmsInstallId.KEY, "0192f3a0-0000-4000-8000-0000000000bb") }
        val outbox = SmsOutbox(store)
        val sender = SmsSender(SCOPE, store, transport, outbox, clock)
        val poller = SmsOutboxPoller(SCOPE, store, mock, sender, clock, newKey = { "claim-key-${++keyCounter}" })

        val first = poller.poll(READY)
        assertEquals("HTTP 422 validation_error", first.skippedReason)
        assertEquals(0, first.claimed)
        assertNull("definitive: the rejected key is dropped, never replayed", poller.pendingClaimKey())
        val next = first.nextAttemptAtMillis!!
        assertTrue(next > clock.now)
        // While backing off, no request is made at all.
        assertEquals("backing off", poller.poll(READY).skippedReason)
        assertEquals(1, mock.calls.count { it == "claimOutboxInstructions" })
        // Repeated rejections grow the backoff instead of hammering the server.
        clock.now = next
        val second = poller.poll(READY)
        assertEquals("HTTP 422 validation_error", second.skippedReason)
        assertNull(poller.pendingClaimKey())
        assertTrue("backoff grows: 5 s, then 10 s", second.nextAttemptAtMillis!! - clock.now >= 2 * 5_000)
        assertEquals(2, mock.calls.count { it == "claimOutboxInstructions" })
        assertEquals(0, transport.submits.size)
        // Through the connector as well: a sync round reports the rejection and does not throw.
        clock.now = second.nextAttemptAtMillis!!
        val connector = SmsConnector(SCOPE, store, transport, clock = clock, api = mock)
        assertEquals("HTTP 422 validation_error", connector.sync(READY).poll.skippedReason)
        assertEquals(0, transport.submits.size)
    }

    @Test fun `claim keys differ between installs`() {
        val a = rig().poller.deviceInstallId()
        val b = rig().poller.deviceInstallId()
        assertNotEquals(a, b)
        assertNotNull(a)
    }

    private companion object {
        const val OUTBOUND2 = "0192f3a0-0000-7000-8000-000000000402"
    }
}
