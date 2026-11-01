package com.wingman.integrations.sms

import com.wingman.core.api.mock.MockWingmanApi
import com.wingman.core.model.OutboxAuthorization
import com.wingman.core.model.OutboxAuthorizationOrigin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** I-020: the SMS runtime runs only against a paired connection, and unpair stops it. */
class SmsConnectorHostTest {
    private val clock = FakeClock()
    private val ready = SmsDeviceState(hasTelephony = true, isDefaultSmsApp = true, receiveSmsGranted = true, readSmsGranted = true, sendSmsGranted = true)

    private fun host(store: SmsStateStore = InMemorySmsStateStore(), transport: FakeSmsTransport = FakeSmsTransport()) =
        SmsConnectorHost(store, transport, clock)

    @Test fun `the runtime is inert while unpaired - no connector, no claims, no inbound recording`() {
        val backend = SmsPairingTest.PairingBackend()
        val h = host().apply { connectBackend(backend) }
        assertNull(h.connector())
        assertNull(h.resultSender())
        val r = h.sync(READY) as SmsHostSyncReport.Inactive
        assertNull("nothing queued, nothing to do", r.upload)
        assertTrue("no claim without a pairing", backend.claims.isEmpty())
        assertTrue(backend.mock.ingestedEvents.isEmpty())
        assertEquals("not paired; nothing to do", h.store.get(SmsPairingKeys.LAST_SYNC_SUMMARY))
        assertNotNull(h.localStatus().lastSyncAtMillis)
    }

    @Test fun `after pairing the connector uses the returned connection for ingest and claims`(): Unit = runBlocking {
        val backend = SmsPairingTest.PairingBackend()
        val h = host().apply { connectBackend(backend) }
        val connection = (h.pairing()!!.pair("Phone", SmsCapabilities.build(ready, clock.now)) as SmsPairResult.Paired).connection
        val c = h.connector()!!
        assertEquals(connection.id, c.scope.connectionId)
        c.inbound.onSmsReceived(sms())
        h.sync(READY)
        assertEquals(listOf(connection.id), backend.claims)
        val ingested = backend.mock.ingestedEvents.single()
        assertEquals(connection.id, ingested.str("connection_id"))
        assertEquals("drained", 0, h.localStatus().pendingEnvelopes)
    }

    @Test fun `unpair stops the poller and uploader for that connection, clears the id and keeps unsent envelopes`(): Unit = runBlocking {
        val backend = SmsPairingTest.PairingBackend()
        val h = host().apply { connectBackend(backend) }
        val p = h.pairing()!!
        val connection = (p.pair("Phone", SmsCapabilities.build(ready, clock.now)) as SmsPairResult.Paired).connection
        val before = h.connector()!!
        before.inbound.onSmsReceived(sms()) // queued, not yet uploaded
        p.unpair()
        assertNull("stored id cleared", h.store.get(SmsPairingKeys.CONNECTION))
        assertNull("runtime stopped", h.connector())
        assertEquals("kept for upload", 1, h.localStatus().pendingEnvelopes)
        backend.claims.clear()
        val r = h.sync(READY) as SmsHostSyncReport.Inactive
        assertTrue("no claims after unpair", backend.claims.isEmpty())
        // The leftover inbound envelope names the unpaired connection: 422, dead-lettered by the existing rules.
        assertEquals(1, r.upload!!.rejected)
        assertEquals(0, h.localStatus().pendingEnvelopes)
        assertEquals(1, h.localStatus().deadLetters)
        // A new SMS after unpair is not recorded for Wingman at all.
        assertNull(h.connector())
        assertEquals(connection.id, h.pairing()!!.retiredScope()!!.connectionId)
    }

    @Test fun `a send already on the radio still reports its result to the connection that leased it after unpair`(): Unit = runBlocking {
        val backend = SmsPairingTest.PairingBackend()
        val transport = FakeSmsTransport()
        val h = host(transport = transport).apply { connectBackend(backend) }
        val p = h.pairing()!!
        val connection = (p.pair("Phone", SmsCapabilities.build(ready, clock.now)) as SmsPairResult.Paired).connection
        val sendKey = backend.mock.queueOutboxInstruction(
            connection.id, "sms:+15550101234", "Yes! 8pm?",
            OutboxAuthorization(OutboxAuthorizationOrigin.USER_MANUAL, null),
        )
        val report = (h.sync(READY) as SmsHostSyncReport.Active).report
        assertEquals(1, report.poll.submitted)
        assertEquals(1, transport.count(sendKey))
        val unpaired = p.unpair() as SmsUnpairResult.Unpaired
        assertEquals("leased send counted as in flight, not recalled", 1, unpaired.result!!.inFlightMessageCount)
        // The radio's "sent" result arrives after the unpair.
        val sender = h.resultSender()!!
        sender.onPartSent(sendKey, 0, SmsSentResult.Ok)
        val leftovers = (h.sync(READY) as SmsHostSyncReport.Inactive).upload!!
        assertEquals("late outcome accepted by the unpaired connection", 1, leftovers.accepted)
        val ack = backend.mock.ingestedEvents.last()
        assertEquals("message.send_acknowledged", ack.str("type"))
        assertEquals(connection.id, ack.str("connection_id"))
        assertEquals(1, transport.count(sendKey))
    }

    @Test fun `re-pairing as a new connection drops the old claim key and builds a connector for the new id`(): Unit = runBlocking {
        val backend = SmsPairingTest.PairingBackend()
        val h = host().apply { connectBackend(backend) }
        val p = h.pairing()!!
        val first = (p.pair("Phone", SmsCapabilities.build(ready, clock.now)) as SmsPairResult.Paired).connection
        val c1 = h.connector()!!
        assertSame("cached while the scope is unchanged", c1, h.connector())
        p.unpair()
        val second = (p.pair("Phone", SmsCapabilities.build(ready, clock.now)) as SmsPairResult.Paired).connection
        assertEquals(second.id, h.connector()!!.scope.connectionId)
        assertTrue(first.id != second.id)
    }

    @Test fun `without a backend client there is no pairing manager and nothing is uploaded`() {
        val store = InMemorySmsStateStore()
        val h = host(store)
        assertNull(h.pairing())
        // Even a stored scope gives a connector, but with no poller and envelopes stay queued.
        store.put(SmsPairingKeys.USER, USER)
        store.put(SmsPairingKeys.CONNECTION, CONNECTION)
        val c = h.connector()!!
        assertNull(c.poller)
        c.inbound.onSmsReceived(sms())
        h.sync(READY)
        assertEquals(1, h.localStatus().pendingEnvelopes)
    }

    @Test fun `changing the backend client rebuilds the pairing manager, same client keeps it`() {
        val h = host()
        val a = MockWingmanApi()
        assertTrue(h.connectBackend(a))
        val p = h.pairing()
        assertTrue(!h.connectBackend(a))
        assertSame(p, h.pairing())
        assertTrue(h.connectBackend(MockWingmanApi()))
        assertTrue(p !== h.pairing())
    }

    @Test fun `a stale scope the server does not know is cleared by the startup check`(): Unit = runBlocking {
        // e.g. the debug harness's fixed demo scope against a real backend: getConnection 404 clears it.
        val backend = SmsPairingTest.PairingBackend()
        val h = host().apply { connectBackend(backend) }
        h.store.put(SmsPairingKeys.USER, USER)
        h.store.put(SmsPairingKeys.CONNECTION, "0192f3a0-0000-7000-8000-0000000000ff")
        assertNotNull(h.connector())
        val v = h.pairing()!!.verify()
        assertTrue(v is SmsVerifyResult.NeedsRePair)
        assertNull(h.connector())
        assertTrue(backend.claims.isEmpty())
    }
}
