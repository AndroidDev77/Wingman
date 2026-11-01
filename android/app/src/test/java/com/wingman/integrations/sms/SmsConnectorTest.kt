package com.wingman.integrations.sms

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** End-to-end over the pure core: receive, upload, authorized reply, duplicates, restart, failures. */
class SmsConnectorTest {
    @get:Rule val tmp = TemporaryFolder()

    private val clock = FakeClock()

    private fun connector(file: File, transport: FakeSmsTransport, uploader: SmsEnvelopeUploader) =
        SmsConnector(SCOPE, FileSmsStateStore(file), transport, uploader, clock)

    @Test fun `receiving never sends (no autonomous reply)`() {
        val t = FakeSmsTransport()
        val c = connector(File(tmp.root, "s.json"), t, FakeSmsEnvelopeUploader())
        repeat(3) { c.inbound.onSmsReceived(sms()) }
        c.inbound.onSmsReceived(sms(body = "another"))
        c.maintain()
        assertEquals(0, t.submits.size)
    }

    @Test fun `demo flow - receive, upload, authorized reply, duplicate broadcast and restart, no double reply`() {
        val file = File(tmp.root, "s.json")
        val t = FakeSmsTransport()
        val up = FakeSmsEnvelopeUploader()
        val c1 = connector(file, t, up)

        assertTrue(c1.inbound.onSmsReceived(sms()) is SmsInboundOutcome.Accepted)
        assertTrue(c1.inbound.onSmsReceived(sms()) is SmsInboundOutcome.Duplicate)
        assertEquals(1, c1.maintain().accepted)
        assertEquals(listOf("message.received"), up.uploaded.map { it.str("type") })

        // Backend (dispatcher) authorizes one reply with key send:<id>.
        c1.sender.send(sendCommand(), READY)
        c1.sender.onPartSent("send:$OUTBOUND", 0, SmsSentResult.Ok)

        // Process restart. The OS redelivers the inbound SMS and the dispatcher re-sends the same command.
        val c2 = connector(file, t, up)
        assertTrue(c2.inbound.onSmsReceived(sms()) is SmsInboundOutcome.Duplicate)
        assertTrue(c2.sender.send(sendCommand(), READY).replayed)
        c2.maintain()

        assertEquals(1, t.submits.size)
        assertEquals(listOf("message.received", "message.send_acknowledged"), up.uploaded.map { it.str("type") })
    }

    @Test fun `without a backend client envelopes stay queued and nothing is claimed`() {
        val t = FakeSmsTransport()
        val c = connector(File(tmp.root, "s.json"), t, NoBackendUploader)
        c.inbound.onSmsReceived(sms())
        val report = c.maintain()
        assertEquals(0, report.accepted)
        assertTrue(report.retryReason!!.contains("no backend client"))
        assertEquals(1, c.outbox.size())
        assertEquals(null, c.poller)
        assertEquals("no backend connected", c.sync(READY, ignoreBackoff = true).poll.skippedReason)
        assertEquals(0, t.submits.size)
    }

    @Test fun `outbox keeps order, stops on retry, dead-letters rejections`() {
        val store = InMemorySmsStateStore()
        val outbox = SmsOutbox(store)
        val f = SmsEnvelopeFactory(SCOPE)
        val a = f.received(sms(body = "a"))
        val b = f.received(sms(body = "b"))
        val cc = f.received(sms(body = "c"))
        listOf(a, b, cc, a).forEach(outbox::enqueue)
        assertEquals(3, outbox.size())

        val up = FakeSmsEnvelopeUploader { e: JsonObject ->
            when (e.str("event_id")) {
                b.eventId -> SmsUploadResult.Rejected("422")
                cc.eventId -> SmsUploadResult.RetryLater("offline")
                else -> SmsUploadResult.Accepted
            }
        }
        val r = outbox.drain(up)
        assertEquals(SmsOutbox.DrainReport(1, 1, "offline"), r)
        assertEquals(1, outbox.size())
        assertEquals(1, outbox.deadLetterCount())
        up.next = { SmsUploadResult.Accepted }
        assertEquals(1, outbox.drain(up).accepted)
        assertEquals(listOf(a.eventId, cc.eventId), up.uploaded.map { it.str("event_id") })
    }

    @Test fun `event ids are deterministic so re-uploads are recognisable`() {
        val f = SmsEnvelopeFactory(SCOPE)
        assertEquals(f.received(sms()).eventId, f.received(sms(receivedAt = 9)).eventId)
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$").matches(f.received(sms()).eventId))
    }

    @Test fun `file store survives a reload and leaves no temp file`() {
        val file = File(tmp.root, "nested/dir/state.json")
        val s1 = FileSmsStateStore(file)
        s1.put("a", "1"); s1.put("b", "2"); s1.remove("a")
        val s2 = FileSmsStateStore(file)
        assertEquals(null, s2.get("a"))
        assertEquals("2", s2.get("b"))
        assertFalse(File(file.parentFile, "state.json.tmp").exists())
        assertEquals(listOf("b"), s2.keys(""))
    }

    @Test fun `received payload carries sender and text only`() {
        val e = SmsEnvelopeFactory(SCOPE).received(sms()).json
        assertEquals(setOf("sender", "text"), e["payload"]!!.jsonObject.keys)
    }
}
