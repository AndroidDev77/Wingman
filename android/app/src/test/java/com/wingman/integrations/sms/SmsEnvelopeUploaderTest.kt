package com.wingman.integrations.sms

import com.wingman.core.api.ApiError
import com.wingman.core.api.ApiResult
import com.wingman.core.model.ConnectorEventIngestOutcome
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** HTTP uploader: status mapping, dead letters, backoff with Retry-After, restart persistence, order. */
class SmsEnvelopeUploaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val clock = FakeClock()
    private val backend = ScriptedSmsBackend()
    private val uploader = WingmanApiSmsEnvelopeUploader(backend)
    private val factory = SmsEnvelopeFactory(SCOPE)

    private fun outboxWith(store: SmsStateStore, vararg bodies: String): Pair<SmsOutbox, List<SmsEnvelope>> {
        val outbox = SmsOutbox(store)
        val envelopes = bodies.mapIndexed { i, b -> factory.received(sms(body = b, smscTs = 1_793_473_200_000L + i)) }
        envelopes.forEach(outbox::enqueue)
        return outbox to envelopes
    }

    @Test fun `sends one request per envelope with Idempotency-Key = event_id and the envelope's connection`() {
        val (outbox, envs) = outboxWith(InMemorySmsStateStore(), "a", "b")
        val r = outbox.drain(uploader)
        assertEquals(2, r.accepted)
        assertEquals(envs.map { it.eventId }, backend.ingests.map { it.key })
        assertEquals(listOf(CONNECTION, CONNECTION), backend.ingests.map { it.connectionId })
        assertEquals(envs.map { it.json }, backend.ingests.map { it.envelope })
        assertEquals(0, outbox.size())
    }

    @Test fun `202 accepted and 200 duplicate_event or duplicate_message all remove the envelope`() {
        for (outcome in ConnectorEventIngestOutcome.entries) {
            val result = ScriptedSmsBackend.accepted("x", outcome)
            assertEquals(outcome.name, SmsUploadResult.Accepted, WingmanApiSmsEnvelopeUploader.map(result))
        }
    }

    @Test fun `status code mapping follows the contract`() {
        fun m(status: Int) = WingmanApiSmsEnvelopeUploader.map(ScriptedSmsBackend.http(status, "x"))
        for (s in listOf(400, 404, 422)) assertTrue("$s", m(s) is SmsUploadResult.Rejected)
        for (s in listOf(409, 429, 500, 502, 503, 504)) assertTrue("$s", m(s) is SmsUploadResult.RetryLater)
        // 401 (no token yet, I-011) and unclassified statuses never drop an envelope.
        for (s in listOf(401, 403, 412)) assertTrue("$s", m(s) is SmsUploadResult.RetryLater)
        assertTrue(WingmanApiSmsEnvelopeUploader.map(ScriptedSmsBackend.network()) is SmsUploadResult.RetryLater)
        assertTrue(WingmanApiSmsEnvelopeUploader.map(ApiResult.Failure(ApiError.Decoding(IllegalStateException()))) is SmsUploadResult.RetryLater)
        assertEquals(SmsUploadResult.RetryLater("HTTP 429 rate_limited", 7_000), WingmanApiSmsEnvelopeUploader.map(ScriptedSmsBackend.http(429, "rate_limited", 7)))
    }

    @Test fun `envelope without a usable event_id is dead-lettered without a request`() {
        val bad = JsonObject(factory.received(sms()).json + ("event_id" to JsonPrimitive("x")))
        assertTrue(uploader.upload(bad) is SmsUploadResult.Rejected)
        assertTrue(uploader.upload(JsonObject(emptyMap())) is SmsUploadResult.Rejected)
        assertEquals(0, backend.ingests.size)
    }

    @Test fun `rejections go to the dead-letter store, are never retried, and the rest continues`() {
        val (outbox, envs) = outboxWith(InMemorySmsStateStore(), "a", "b", "c")
        backend.ingestScript += { ScriptedSmsBackend.accepted(it.key) }
        backend.ingestScript += { ScriptedSmsBackend.http(422, "validation_error") }
        backend.ingestScript += { ScriptedSmsBackend.http(404, "not_found") }
        val r = outbox.drain(uploader)
        assertEquals(SmsOutbox.DrainReport(1, 2, null), r)
        assertEquals(0, outbox.size())
        assertEquals(listOf(envs[1].json, envs[2].json), outbox.deadLetters())
        outbox.drain(uploader)
        assertEquals(3, backend.ingests.size) // nothing re-sent
    }

    @Test fun `retryable failure stops the drain to keep order and backs off exponentially`() {
        val store = InMemorySmsStateStore()
        val (outbox, envs) = outboxWith(store, "a", "b", "c")
        val backoff = SmsBackoff(store, SmsBackoff.UPLOAD, clock, initialDelayMillis = 1_000, maxDelayMillis = 4_000)
        backend.ingestScript += { ScriptedSmsBackend.accepted(it.key) }
        backend.defaultIngest = { ScriptedSmsBackend.http(503) }

        val r1 = outbox.drain(uploader, backoff = backoff)
        assertEquals(1, r1.accepted)
        assertEquals(clock.now + 1_000, r1.nextAttemptAtMillis)
        assertEquals(listOf(envs[0].eventId, envs[1].eventId), backend.ingests.map { it.key }) // c never attempted

        // Still waiting: no request at all.
        clock.now += 999
        assertEquals("backing off", outbox.drain(uploader, backoff = backoff).retryReason)
        assertEquals(2, backend.ingests.size)

        clock.now += 1
        assertEquals(clock.now + 2_000, outbox.drain(uploader, backoff = backoff).nextAttemptAtMillis)
        clock.now += 2_000
        assertEquals(clock.now + 4_000, outbox.drain(uploader, backoff = backoff).nextAttemptAtMillis)
        clock.now += 4_000
        assertEquals("capped", clock.now + 4_000, outbox.drain(uploader, backoff = backoff).nextAttemptAtMillis)

        // Recovery: same envelope and key again, then the rest in order; backoff reset.
        backend.defaultIngest = { ScriptedSmsBackend.accepted(it.key) }
        clock.now += 4_000
        assertEquals(2, outbox.drain(uploader, backoff = backoff).accepted)
        assertEquals(listOf(envs[1].eventId, envs[2].eventId), backend.ingests.takeLast(2).map { it.key })
        assertEquals(0, backoff.failures())
        assertNull(backoff.nextAttemptAtMillis())
    }

    @Test fun `Retry-After overrides the exponential delay`() {
        val store = InMemorySmsStateStore()
        val (outbox, _) = outboxWith(store, "a")
        val backoff = SmsBackoff(store, SmsBackoff.UPLOAD, clock, initialDelayMillis = 1_000)
        backend.defaultIngest = { ScriptedSmsBackend.http(429, "rate_limited", retryAfterSeconds = 90) }
        assertEquals(clock.now + 90_000, outbox.drain(uploader, backoff = backoff).nextAttemptAtMillis)
        backend.defaultIngest = { ScriptedSmsBackend.http(409, "idempotency_key_in_progress", retryAfterSeconds = 2) }
        clock.now += 90_000
        assertEquals(clock.now + 2_000, outbox.drain(uploader, backoff = backoff).nextAttemptAtMillis)
    }

    @Test fun `queue and backoff survive a restart and nothing is lost or reordered`() {
        val file = File(tmp.root, "state.json")
        val store1 = FileSmsStateStore(file)
        val (outbox1, envs) = outboxWith(store1, "a", "b", "c")
        backend.ingestScript += { ScriptedSmsBackend.accepted(it.key) }
        backend.defaultIngest = { ScriptedSmsBackend.network() }
        outbox1.drain(uploader, backoff = SmsBackoff(store1, SmsBackoff.UPLOAD, clock))

        // "Process death": new store and outbox over the same file.
        val store2 = FileSmsStateStore(file)
        val outbox2 = SmsOutbox(store2)
        val backoff2 = SmsBackoff(store2, SmsBackoff.UPLOAD, clock)
        assertEquals(2, outbox2.size())
        assertEquals(1, backoff2.failures())
        assertTrue(backoff2.isWaiting())

        backend.defaultIngest = { ScriptedSmsBackend.accepted(it.key) }
        clock.now += 60_000
        assertEquals(2, outbox2.drain(uploader, backoff = backoff2).accepted)
        val accepted = backend.ingests.filterIndexed { i, _ -> i != 1 }.map { it.key } // index 1 was the network failure
        assertEquals(envs.map { it.eventId }, accepted)
    }

    @Test fun `per-thread order is preserved across retries even when envelopes interleave`() {
        val store = InMemorySmsStateStore()
        val outbox = SmsOutbox(store)
        val alice = (0 until 3).map { factory.received(sms(from = "+15550000001", body = "a$it", smscTs = 1_000L + it)) }
        val bob = (0 until 3).map { factory.received(sms(from = "+15550000002", body = "b$it", smscTs = 2_000L + it)) }
        listOf(alice[0], bob[0], alice[1], bob[1], alice[2], bob[2]).forEach(outbox::enqueue)
        var n = 0
        backend.defaultIngest = { if (++n % 3 == 0) ScriptedSmsBackend.http(500) else ScriptedSmsBackend.accepted(it.key) }
        repeat(10) { outbox.drain(uploader) }
        assertEquals(0, outbox.size())
        val firstAcceptance = backend.ingests.map { it.envelope }.distinct()
        fun order(thread: String) = firstAcceptance.filter { it.str("external_thread_id") == thread }.map { it.str("event_id") }
        assertEquals(alice.map { it.eventId }, order("sms:+15550000001"))
        assertEquals(bob.map { it.eventId }, order("sms:+15550000002"))
    }
}
