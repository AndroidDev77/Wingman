package com.wingman.integrations.sms

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SmsSenderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val clock = FakeClock()
    private val transport = FakeSmsTransport()

    private fun sender(store: SmsStateStore = InMemorySmsStateStore(), t: SmsTransport = transport): Pair<SmsSender, SmsOutbox> {
        val outbox = SmsOutbox(store)
        return SmsSender(SCOPE, store, t, outbox, clock, sentResultTimeoutMillis = 60_000) to outbox
    }

    private fun SmsOutbox.types() = pending().map { it.str("type") }

    @Test fun `authorized send submits once and acknowledges on sent OK, not delivered`() {
        val (s, outbox) = sender()
        val out = s.send(sendCommand(), READY, subscriptionId = 1)
        assertTrue(out is SmsSendOutcome.Submitted)
        assertFalse(out.replayed)
        assertEquals(listOf(FakeSmsTransport.Submit("+15550101234", "Yes! 8pm?", 1, "send:$OUTBOUND", true)), transport.submits)
        assertTrue(outbox.types().isEmpty()) // submitted is not sent

        val r = s.onPartSent("send:$OUTBOUND", 0, SmsSentResult.Ok)!!
        assertEquals(SmsSendState.ACKNOWLEDGED, r.state)
        assertEquals(listOf("message.send_acknowledged"), outbox.types())
        val ack = outbox.pending().single()
        assertEquals("sms:out:send:$OUTBOUND", ack.str("external_message_id"))
        assertEquals("send:$OUTBOUND", ack["payload"]!!.jsonObject.str("idempotency_key"))

        val d = s.onPartDelivered("send:$OUTBOUND", 0, SmsDeliveryStatus.DELIVERED)!!
        assertEquals(SmsSendState.DELIVERED, d.state)
        assertEquals(listOf("message.send_acknowledged", "message.delivery_receipt"), outbox.types())
    }

    @Test fun `same key twice makes one transport attempt and replays`() {
        val (s, _) = sender()
        s.send(sendCommand(), READY)
        s.onPartSent("send:$OUTBOUND", 0, SmsSentResult.Ok)
        val again = s.send(sendCommand(), READY)
        assertTrue(again is SmsSendOutcome.Acknowledged)
        assertTrue(again.replayed)
        assertEquals(1, transport.count("send:$OUTBOUND"))
        assertEquals(1, s.transportAttempts("send:$OUTBOUND"))
    }

    @Test fun `replay while still submitted does not resend`() {
        val (s, _) = sender()
        s.send(sendCommand(), READY)
        val again = s.send(sendCommand(), READY)
        assertTrue(again is SmsSendOutcome.Submitted && again.replayed)
        assertEquals(1, transport.submits.size)
    }

    @Test fun `same key with different body is a conflict and sends nothing`() {
        val (s, _) = sender()
        s.send(sendCommand(), READY)
        val conflict = s.send(sendCommand(text = "Different text"), READY)
        assertTrue(conflict is SmsSendOutcome.KeyConflict)
        assertEquals(1, transport.submits.size)
    }

    @Test fun `restart with the same key never resends (file store, new instances)`() {
        val file = File(tmp.root, "state.json")
        val (s1, _) = sender(FileSmsStateStore(file))
        s1.send(sendCommand(), READY)
        // Process dies before any sent PendingIntent arrives. New process, same file.
        val t2 = FakeSmsTransport()
        val (s2, outbox2) = sender(FileSmsStateStore(file), t2)
        val again = s2.send(sendCommand(), READY)
        assertTrue(again.replayed)
        assertEquals(0, t2.submits.size)
        // The late PendingIntent result still lands on the persisted record.
        assertEquals(SmsSendState.ACKNOWLEDGED, s2.onPartSent("send:$OUTBOUND", 0, SmsSentResult.Ok)!!.state)
        assertEquals(listOf("message.send_acknowledged"), outbox2.types())
    }

    @Test fun `crash during submit is failed as timeout, not retryable, never resent`() {
        val file = File(tmp.root, "state.json")
        val store = FileSmsStateStore(file)
        // Simulate: the claim was persisted, then the process died inside transport.submit.
        val dying = object : SmsTransport {
            override fun submit(destination: String, text: String, subscriptionId: Int?, idempotencyKey: String, requestDeliveryReports: Boolean): Int =
                throw ProcessDeath()
        }
        val (s1, _) = sender(store, dying)
        assertThrows(ProcessDeath::class.java) { s1.send(sendCommand(), READY) }
        val (s2, outbox2) = sender(FileSmsStateStore(file))
        assertEquals(SmsSendState.CLAIMED, s2.record("send:$OUTBOUND")!!.state)
        assertTrue(s2.send(sendCommand(), READY).replayed)
        assertEquals(0, transport.submits.size)
        clock.now += 61_000
        assertEquals(listOf("send:$OUTBOUND"), s2.expireStale())
        val failed = s2.send(sendCommand(), READY) as SmsSendOutcome.Failed
        assertEquals(SmsFailureCode.TIMEOUT, failed.failure.code)
        assertFalse(failed.failure.retryable)
        assertEquals(listOf("message.send_failed"), outbox2.types())
        assertEquals(0, transport.submits.size)
    }

    /** An Error, so SmsSender's `catch (e: Exception)` cannot swallow it: models the process dying. */
    private class ProcessDeath : Error()

    @Test fun `airplane mode maps to not_connected and a retry needs a new key`() {
        val (s, outbox) = sender()
        s.send(sendCommand(), READY)
        val r = s.onPartSent("send:$OUTBOUND", 0, SmsSentResult.RadioOff)!!
        assertEquals(SmsSendState.FAILED, r.state)
        val failed = outbox.pending().single()
        assertEquals("message.send_failed", failed.str("type"))
        assertNull(failed.str("external_message_id"))
        val f = failed["payload"]!!.jsonObject["failure"]!!.jsonObject
        assertEquals("not_connected", f.str("code"))
        assertTrue(f["retryable"]!!.jsonPrimitive.boolean)
        // Same key: stored failure, no second attempt (D-006).
        val replay = s.send(sendCommand(), READY) as SmsSendOutcome.Failed
        assertTrue(replay.replayed)
        assertEquals(1, transport.submits.size)
        // Dispatcher retries with a fresh key: a new attempt.
        s.send(sendCommand(key = "send:$OUTBOUND:2"), READY)
        assertEquals(2, transport.submits.size)
    }

    @Test fun `failure code mapping`() {
        fun code(r: SmsSentResult) = SmsFailureMapper.fromSentResult(r)!!.let { it.code to it.retryable }
        assertNull(SmsFailureMapper.fromSentResult(SmsSentResult.Ok))
        assertEquals(SmsFailureCode.NOT_CONNECTED to true, code(SmsSentResult.NoService))
        assertEquals(SmsFailureCode.NOT_CONNECTED to true, code(SmsSentResult.RadioOff))
        assertEquals(SmsFailureCode.TRANSPORT_ERROR to true, code(SmsSentResult.GenericFailure))
        assertEquals(SmsFailureCode.TRANSPORT_ERROR to false, code(SmsSentResult.NullPdu))
        assertEquals(SmsFailureCode.REJECTED_BY_PLATFORM to true, code(SmsSentResult.LimitExceeded))
        assertEquals(SmsFailureCode.PERMISSION_DENIED to false, code(SmsSentResult.ShortCodeNeverAllowed))
        assertEquals(SmsFailureCode.TRANSPORT_ERROR to false, code(SmsSentResult.Other(99)))
        assertEquals(SmsFailureCode.PERMISSION_DENIED, SmsFailureMapper.fromSubmitException(SecurityException()).code)
        assertEquals(SmsFailureCode.TRANSPORT_ERROR, SmsFailureMapper.fromSubmitException(IllegalArgumentException()).code)
    }

    @Test fun `synchronous SecurityException is permission_denied and spends the key`() {
        val t = FakeSmsTransport(throwOnSubmit = SecurityException("no"))
        val (s, outbox) = sender(t = t)
        val out = s.send(sendCommand(), READY) as SmsSendOutcome.Failed
        assertEquals(SmsFailureCode.PERMISSION_DENIED, out.failure.code)
        assertEquals(listOf("message.send_failed"), outbox.types())
        assertTrue(s.send(sendCommand(), READY).replayed)
        assertEquals(1, t.submits.size)
    }

    @Test fun `preconditions fail without calling the transport`() {
        val (s, _) = sender()
        val noPerm = s.send(sendCommand(key = "k-perm-0001"), READY.copy(sendPermissionGranted = false)) as SmsSendOutcome.Failed
        assertEquals(SmsFailureCode.PERMISSION_DENIED, noPerm.failure.code)
        val noTel = s.send(sendCommand(key = "k-tele-0001"), READY.copy(hasTelephony = false)) as SmsSendOutcome.Failed
        assertEquals(SmsFailureCode.NOT_CONNECTED, noTel.failure.code)
        val unsup = s.send(sendCommand(key = "k-unsp-0001"), READY.copy(sendCapabilitySupported = false)) as SmsSendOutcome.Failed
        assertEquals(SmsFailureCode.CAPABILITY_UNSUPPORTED, unsup.failure.code)
        val badThread = s.send(sendCommand(key = "k-thrd-0001", thread = "sms-thread-42"), READY) as SmsSendOutcome.Failed
        assertEquals(SmsFailureCode.TRANSPORT_ERROR, badThread.failure.code)
        assertEquals(0, transport.submits.size)
        assertEquals(0, s.transportAttempts("k-perm-0001"))
        assertTrue(s.send(sendCommand(key = "k-perm-0001"), READY).replayed) // still one decision per key
        assertEquals(0, transport.submits.size)
    }

    @Test fun `multipart all ok acknowledges once after the last part`() {
        transport.parts = 3
        val (s, outbox) = sender()
        s.send(sendCommand(text = "x".repeat(400)), READY)
        s.onPartSent("send:$OUTBOUND", 0, SmsSentResult.Ok)
        s.onPartSent("send:$OUTBOUND", 2, SmsSentResult.Ok)
        assertTrue(outbox.types().isEmpty())
        s.onPartSent("send:$OUTBOUND", 1, SmsSentResult.Ok)
        s.onPartSent("send:$OUTBOUND", 1, SmsSentResult.Ok) // duplicate PendingIntent
        assertEquals(listOf("message.send_acknowledged"), outbox.types())
        s.onPartDelivered("send:$OUTBOUND", 0, SmsDeliveryStatus.DELIVERED)
        s.onPartDelivered("send:$OUTBOUND", 1, SmsDeliveryStatus.PENDING)
        s.onPartDelivered("send:$OUTBOUND", 1, SmsDeliveryStatus.DELIVERED)
        assertEquals(SmsSendState.ACKNOWLEDGED, s.record("send:$OUTBOUND")!!.state)
        s.onPartDelivered("send:$OUTBOUND", 2, SmsDeliveryStatus.DELIVERED)
        assertEquals(SmsSendState.DELIVERED, s.record("send:$OUTBOUND")!!.state)
        assertEquals(listOf("message.send_acknowledged", "message.delivery_receipt"), outbox.types())
    }

    @Test fun `multipart partial failure is failed and never retryable`() {
        transport.parts = 2
        val (s, outbox) = sender()
        s.send(sendCommand(), READY)
        s.onPartSent("send:$OUTBOUND", 0, SmsSentResult.Ok)
        val r = s.onPartSent("send:$OUTBOUND", 1, SmsSentResult.NoService)!!
        assertEquals(SmsSendState.FAILED, r.state)
        val f = outbox.pending().single()["payload"]!!.jsonObject["failure"]!!.jsonObject
        assertEquals("not_connected", f.str("code"))
        assertFalse(f["retryable"]!!.jsonPrimitive.boolean)
    }

    @Test fun `delivery report arriving before the last sent result is applied after ack`() {
        transport.parts = 2
        val (s, outbox) = sender()
        s.send(sendCommand(), READY)
        s.onPartSent("send:$OUTBOUND", 0, SmsSentResult.Ok)
        s.onPartDelivered("send:$OUTBOUND", 0, SmsDeliveryStatus.DELIVERED)
        s.onPartDelivered("send:$OUTBOUND", 1, SmsDeliveryStatus.DELIVERED)
        assertEquals(SmsSendState.SUBMITTED, s.record("send:$OUTBOUND")!!.state)
        s.onPartSent("send:$OUTBOUND", 1, SmsSentResult.Ok)
        assertEquals(SmsSendState.DELIVERED, s.record("send:$OUTBOUND")!!.state)
        assertEquals(listOf("message.send_acknowledged", "message.delivery_receipt"), outbox.types())
    }

    @Test fun `failed delivery report stays sent, no receipt`() {
        val (s, outbox) = sender()
        s.send(sendCommand(), READY)
        s.onPartSent("send:$OUTBOUND", 0, SmsSentResult.Ok)
        assertEquals(SmsSendState.DELIVERY_FAILED, s.onPartDelivered("send:$OUTBOUND", 0, SmsDeliveryStatus.FAILED)!!.state)
        assertEquals(listOf("message.send_acknowledged"), outbox.types())
    }

    @Test fun `late or unknown results are ignored`() {
        val (s, outbox) = sender()
        assertNull(s.onPartSent("unknown-key-1", 0, SmsSentResult.Ok))
        s.send(sendCommand(), READY)
        s.onPartSent("send:$OUTBOUND", 5, SmsSentResult.Ok) // out of range
        s.onPartSent("send:$OUTBOUND", 0, SmsSentResult.GenericFailure)
        s.onPartSent("send:$OUTBOUND", 0, SmsSentResult.Ok) // late contradicting result
        assertEquals(SmsSendState.FAILED, s.record("send:$OUTBOUND")!!.state)
        assertEquals(listOf("message.send_failed"), outbox.types())
    }

    @Test fun `missing sent result times out as unknown outcome`() {
        val (s, outbox) = sender()
        s.send(sendCommand(), READY)
        clock.now += 30_000
        assertTrue(s.expireStale().isEmpty())
        clock.now += 31_000
        assertEquals(listOf("send:$OUTBOUND"), s.expireStale())
        val f = outbox.pending().single()["payload"]!!.jsonObject["failure"]!!.jsonObject
        assertEquals("timeout", f.str("code"))
        assertFalse(f["retryable"]!!.jsonPrimitive.boolean)
        assertTrue(s.expireStale().isEmpty())
    }

    @Test fun `delivery tp-status decoding`() {
        assertEquals(SmsDeliveryStatus.DELIVERED, SmsDeliveryStatus.fromTpStatus(0))
        assertEquals(SmsDeliveryStatus.PENDING, SmsDeliveryStatus.fromTpStatus(0x20))
        assertEquals(SmsDeliveryStatus.FAILED, SmsDeliveryStatus.fromTpStatus(0x40))
        assertEquals(SmsDeliveryStatus.PENDING, SmsDeliveryStatus.fromTpStatus(-1))
    }

    @Test fun `ledger never stores the text or the number`() {
        val store = InMemorySmsStateStore()
        val (s, _) = sender(store)
        s.send(sendCommand(), READY)
        val raw = store.get(SmsSender.PREFIX + "send:$OUTBOUND")!!
        assertFalse(raw.contains("8pm"))
        assertTrue(raw.contains("\"authorized_by\""))
        assertFalse(sendCommand().toString().contains("8pm"))
    }

    @Test fun `command validation`() {
        assertThrows(IllegalArgumentException::class.java) { sendCommand(key = "short") }
        assertThrows(IllegalArgumentException::class.java) { sendCommand(key = "has space in it") }
        assertThrows(IllegalArgumentException::class.java) { sendCommand(text = "") }
        assertThrows(IllegalArgumentException::class.java) { sendCommand(outbound = "nope") }
        assertThrows(IllegalArgumentException::class.java) {
            SmsSendCommand(OUTBOUND, "send:$OUTBOUND", "sms:+1555", "hi", authorizedBy = " ")
        }
    }
}
