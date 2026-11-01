package com.wingman.integrations.sms

import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SmsInboundTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun processor(store: SmsStateStore, clock: SmsClock = FakeClock()): Pair<SmsInboundProcessor, SmsOutbox> {
        val outbox = SmsOutbox(store)
        return SmsInboundProcessor(SCOPE, SmsInboundDedup(store, clock), outbox) to outbox
    }

    @Test fun `incoming sms maps to a message_received envelope`() {
        val (p, outbox) = processor(InMemorySmsStateStore())
        val outcome = p.onSmsReceived(sms()) as SmsInboundOutcome.Accepted
        val e = outcome.envelope.json
        assertEquals("connector_event.v1", e.str("schema_version"))
        assertEquals("message.received", e.str("type"))
        assertEquals("sms", e.str("platform"))
        assertEquals(USER, e.str("user_id"))
        assertEquals(CONNECTION, e.str("connection_id"))
        assertTrue(e.str("external_message_id")!!.startsWith("sms:in:"))
        assertEquals("sms:+15550101234", e.str("external_thread_id"))
        assertEquals("2026-10-31T19:00:00Z", e.str("occurred_at"))
        assertEquals("2026-10-31T19:00:01.500Z", e.str("observed_at"))
        val payload = e["payload"]!!.jsonObject
        assertEquals("+15550101234", payload["sender"]!!.jsonObject.str("external_contact_id"))
        assertEquals("Hey! Free tonight?", payload.str("text"))
        assertEquals(1, outbox.size())
    }

    @Test fun `duplicate broadcast is dropped and queues nothing`() {
        val (p, outbox) = processor(InMemorySmsStateStore())
        val first = p.onSmsReceived(sms())
        // Same PDU fields, observed again a bit later (e.g. SMS_DELIVER plus SMS_RECEIVED).
        val second = p.onSmsReceived(sms(receivedAt = 1_793_473_209_000L, from = "+15550101234"))
        assertTrue(first is SmsInboundOutcome.Accepted)
        assertTrue(second is SmsInboundOutcome.Duplicate)
        assertEquals(first.externalMessageId, second.externalMessageId)
        assertEquals(1, outbox.size())
    }

    @Test fun `redelivery after a process restart is dropped (file store, new instances)`() {
        val file = File(tmp.root, "sms-state.json")
        val (p1, _) = processor(FileSmsStateStore(file))
        assertTrue(p1.onSmsReceived(sms()) is SmsInboundOutcome.Accepted)
        // "Process death": everything is rebuilt from the file only.
        val (p2, outbox2) = processor(FileSmsStateStore(file))
        assertTrue(p2.onSmsReceived(sms()) is SmsInboundOutcome.Duplicate)
        assertEquals(1, outbox2.size())
    }

    @Test fun `crash between enqueue and claim re-enqueues the same event once`() {
        val store = InMemorySmsStateStore()
        val outbox = SmsOutbox(store)
        val envelope = SmsEnvelopeFactory(SCOPE).received(sms())
        outbox.enqueue(envelope) // the first run died right after this line
        val (p, _) = processor(store)
        val outcome = p.onSmsReceived(sms())
        assertTrue(outcome is SmsInboundOutcome.Accepted)
        assertEquals(1, outbox.size())
        assertEquals(envelope.eventId, (outcome as SmsInboundOutcome.Accepted).envelope.eventId)
    }

    @Test fun `different messages get different ids`() {
        val base = SmsIds.inboundExternalMessageId(sms())
        assertNotEquals(base, SmsIds.inboundExternalMessageId(sms(body = "other")))
        assertNotEquals(base, SmsIds.inboundExternalMessageId(sms(smscTs = 1_793_473_260_000L)))
        assertNotEquals(base, SmsIds.inboundExternalMessageId(sms(from = "+15550109999")))
        assertNotEquals(base, SmsIds.inboundExternalMessageId(sms(sub = 2)))
        // Receive time is NOT part of the identity.
        assertEquals(base, SmsIds.inboundExternalMessageId(sms(receivedAt = 1L)))
    }

    @Test fun `same message for a different user is not a duplicate`() {
        val store = InMemorySmsStateStore()
        val dedup = SmsInboundDedup(store, FakeClock())
        val id = SmsIds.inboundExternalMessageId(sms())
        assertTrue(dedup.claim(SCOPE, id))
        assertFalse(dedup.claim(SCOPE, id))
        assertTrue(dedup.claim(SmsConnectionScope("0192f3a0-0000-7000-8000-000000000002", CONNECTION), id))
    }

    @Test fun `old dedup claims are pruned after retention`() {
        val store = InMemorySmsStateStore()
        val clock = FakeClock()
        val dedup = SmsInboundDedup(store, clock, retentionMillis = 1000)
        assertTrue(dedup.claim(SCOPE, "a"))
        clock.now += 5000
        assertTrue(dedup.claim(SCOPE, "b"))
        assertFalse(dedup.isClaimed(SCOPE, "a"))
        assertTrue(dedup.isClaimed(SCOPE, "b"))
    }

    @Test fun `ids are safe to log and contain neither body nor number`() {
        val id = SmsIds.inboundExternalMessageId(sms())
        assertFalse(id.contains("Free"))
        assertFalse(id.contains("5550101234"))
        val s = sms().toString()
        assertFalse(s.contains("Free") || s.contains("555"))
        assertFalse(SmsEnvelopeFactory(SCOPE).received(sms()).toString().contains("Free"))
    }

    @Test fun `address normalization`() {
        assertEquals("+15550101234", SmsAddress.normalize(" +1 (555) 010-1234 "))
        assertEquals("5550101234", SmsAddress.normalize("555.010.1234"))
        assertEquals("ACME Bank", SmsAddress.normalize("ACME Bank"))
        assertEquals("+15550101234", SmsAddress.destinationFromThreadId("sms:+15550101234"))
        assertNull(SmsAddress.destinationFromThreadId("sms-thread-42"))
        assertNull(SmsAddress.destinationFromThreadId("sms:+1 555"))
        assertThrows(IllegalArgumentException::class.java) { SmsAddress.normalize("  ") }
    }

    @Test fun `scope and sms input validation`() {
        assertThrows(IllegalArgumentException::class.java) { SmsConnectionScope("not-a-uuid", CONNECTION) }
        assertThrows(IllegalArgumentException::class.java) { SmsConnectionScope(USER.uppercase(), CONNECTION) }
        assertThrows(IllegalArgumentException::class.java) { sms(from = " ") }
        assertThrows(IllegalArgumentException::class.java) { sms(body = "x".repeat(20_001)) }
    }

    @Test fun `multipart body and empty body map through`() {
        val (p, _) = processor(InMemorySmsStateStore())
        val long = "part one ".repeat(40)
        val e = (p.onSmsReceived(sms(body = long, parts = 3)) as SmsInboundOutcome.Accepted).envelope.json
        assertEquals(long, e["payload"]!!.jsonObject.str("text"))
        val empty = (p.onSmsReceived(sms(body = "", smscTs = 5)) as SmsInboundOutcome.Accepted).envelope.json
        assertEquals("", empty["payload"]!!.jsonObject.str("text"))
    }
}
