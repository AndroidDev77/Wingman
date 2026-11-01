package com.wingman.integrations.sms

import com.wingman.core.api.ApiError
import com.wingman.core.api.ApiResult
import com.wingman.core.api.IdempotencyKey
import com.wingman.core.api.WingmanApi
import com.wingman.core.api.mock.MockFixtures
import com.wingman.core.api.mock.MockWingmanApi
import com.wingman.core.model.Connection
import com.wingman.core.model.ConnectionCreateRequest
import com.wingman.core.model.ConnectionUnpairRequest
import com.wingman.core.model.ConnectionUnpairResult
import com.wingman.core.model.OutboxClaimRequest
import com.wingman.core.model.OutboxClaimResult
import com.wingman.core.model.Verification
import com.wingman.features.connections.SmsConnectionPresenter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * I-020 pairing, verification and unpairing over [MockWingmanApi] (which mirrors the backend's
 * ADR 0007 semantics), with a wrapper that can lose responses and records every pairing request.
 */
class SmsPairingTest {

    /** Delegates to the mock; [loseNextCreate] runs the request on the "server" and then drops the answer. */
    class PairingBackend(val mock: MockWingmanApi = MockWingmanApi()) : WingmanApi by mock {
        data class Create(val body: ConnectionCreateRequest, val key: String)

        val creates = mutableListOf<Create>()
        val unpairs = mutableListOf<Pair<String, String>>()
        val gets = mutableListOf<String>()
        val claims = mutableListOf<String>()
        var loseNextCreate = false
        var loseNextUnpair = false
        var getOverride: ((String) -> ApiResult<Connection>)? = null

        override suspend fun createConnection(body: ConnectionCreateRequest, key: IdempotencyKey): ApiResult<Connection> {
            creates += Create(body, key.value)
            val r = mock.createConnection(body, key)
            if (loseNextCreate) {
                loseNextCreate = false
                return ApiResult.Failure(ApiError.Network(IOException("response lost")))
            }
            return r
        }

        override suspend fun unpairConnection(connectionId: String, key: IdempotencyKey, body: ConnectionUnpairRequest): ApiResult<ConnectionUnpairResult> {
            unpairs += connectionId to key.value
            val r = mock.unpairConnection(connectionId, key, body)
            if (loseNextUnpair) {
                loseNextUnpair = false
                return ApiResult.Failure(ApiError.Network(IOException("response lost")))
            }
            return r
        }

        override suspend fun getConnection(connectionId: String): ApiResult<Connection> {
            gets += connectionId
            return getOverride?.invoke(connectionId) ?: mock.getConnection(connectionId)
        }

        override suspend fun claimOutboxInstructions(connectionId: String, body: OutboxClaimRequest, key: IdempotencyKey): ApiResult<OutboxClaimResult> {
            claims += connectionId
            return mock.claimOutboxInstructions(connectionId, body, key)
        }
    }

    private val readyDevice = SmsDeviceState(hasTelephony = true, isDefaultSmsApp = true, receiveSmsGranted = true, readSmsGranted = true, sendSmsGranted = true)
    private val clock = FakeClock()
    private var keyCounter = 0
    private fun manager(store: SmsStateStore, api: WingmanApi, onScope: (SmsConnectionScope?) -> Unit = {}) =
        SmsPairingManager(store, api, clock, newKey = { "pair-key-${++keyCounter}" }, onScopeChanged = onScope)

    private fun caps(): JsonObject = SmsCapabilities.build(readyDevice, clock.now)

    /** A modified client claiming `send: VERIFIED` with evidence. */
    private fun claimedVerifiedCaps(): JsonObject {
        val base = caps()
        return buildJsonObject {
            base.forEach { (k, v) -> if (k != "capabilities") put(k, v) }
            putJsonObject("capabilities") {
                base.getValue("capabilities").jsonObject.forEach { (k, v) -> if (k != "send") put(k, v) }
                putJsonObject("send") {
                    put("status", "VERIFIED")
                    put("evidence", "trust me")
                    put("verified_at", "2026-11-01T18:00:00Z")
                    put("reports_delivery_receipts", true)
                }
            }
        }
    }

    @Test fun `pair sends platform sms, the poller's install id and the capability document, and stores the id`(): Unit = runBlocking {
        val store = InMemorySmsStateStore()
        val backend = PairingBackend()
        val scopes = mutableListOf<SmsConnectionScope?>()
        val m = manager(store, backend) { scopes.add(it) }
        val r = m.pair("Pixel 8 SMS", caps()) as SmsPairResult.Paired
        val req = backend.creates.single().body
        assertEquals("sms", req.platform)
        assertEquals("Pixel 8 SMS", req.displayName)
        assertEquals(m.deviceInstallId(), req.deviceInstallId)
        assertEquals(store.get(SmsOutboxPoller.KEY_INSTALL_ID), req.deviceInstallId)
        assertEquals("sms", req.capabilities!!.platform)
        assertEquals(SmsConnectionScope(MockFixtures.USER_ID, r.connection.id), m.activeScope())
        assertEquals(listOf(SmsConnectionScope(MockFixtures.USER_ID, r.connection.id)), scopes)
        assertNull("attempt cleared after a final answer", m.pendingPairKey())
        // The poller built on the same store claims with the SAME install id.
        val poller = SmsOutboxPoller(m.activeScope()!!, store, backend, SmsSender(m.activeScope()!!, store, FakeSmsTransport(), SmsOutbox(store), clock), clock)
        assertEquals(req.deviceInstallId, poller.deviceInstallId())
    }

    @Test fun `lost pair response is retried with the SAME key and body, creating one connection`(): Unit = runBlocking {
        val store = InMemorySmsStateStore()
        val backend = PairingBackend().apply { loseNextCreate = true }
        val m = manager(store, backend)
        val first = m.pair("Pixel 8 SMS", caps())
        assertTrue(first is SmsPairResult.Failed && first.retryable)
        assertNull(m.activeScope())
        assertEquals("pair-key-1", m.pendingPairKey())

        // Process restart: a new manager over the same store, and later capabilities (different assessed_at).
        clock.now += 60_000
        val r = manager(store, backend).pair("Pixel 8 SMS", caps()) as SmsPairResult.Paired
        assertEquals(2, backend.creates.size)
        assertEquals(backend.creates[0].key, backend.creates[1].key)
        assertEquals(backend.creates[0].body, backend.creates[1].body)
        assertTrue("replay of the original 201", r.replayed)
        assertEquals(1, backend.mock.listConnections().getOrNull()!!.items.count { it.platform == "sms" && it.deviceInstallId != null })
        assertEquals(r.connection.id, m.activeScope()!!.connectionId)
    }

    @Test fun `201 and the 200 already-paired answer both store the id`(): Unit = runBlocking {
        val backend = PairingBackend()
        val storeA = InMemorySmsStateStore()
        val a = manager(storeA, backend).pair("Phone", caps()) as SmsPairResult.Paired
        assertFalse(a.replayed)

        // Same install, local pairing state lost (e.g. the state file was cleared), new key: server answers 200 with the same connection.
        val storeB = InMemorySmsStateStore().apply { put(SmsInstallId.KEY, storeA.get(SmsInstallId.KEY)!!) }
        val b = manager(storeB, backend).pair("Phone", caps()) as SmsPairResult.Paired
        assertNotEquals(backend.creates[0].key, backend.creates[1].key)
        assertEquals(a.connection.id, b.connection.id)
        assertEquals(a.connection.id, storeB.get(SmsPairingKeys.CONNECTION))
    }

    @Test fun `a client-claimed VERIFIED is shown as the server stored it, and autopilot is unavailable`(): Unit = runBlocking {
        val store = InMemorySmsStateStore()
        val m = manager(store, PairingBackend())
        val r = m.pair("Phone", claimedVerifiedCaps()) as SmsPairResult.Paired
        val send = r.connection.capabilities.capabilities.send
        assertEquals(Verification.UNVERIFIED, send.status)
        assertNull(send.evidence)
        assertNull(send.verifiedAt)
        assertEquals(MockWingmanApi.CLIENT_VERIFIED_NOTE, send.notes)
        // The stored snapshot (what the screen shows) is the server's answer, not the claim.
        val shown = (m.state() as SmsPairingState.Paired).connection!!
        assertEquals(Verification.UNVERIFIED, shown.capabilities.capabilities.send.status)
        assertFalse(SmsConnectionPresenter.autopilotAvailable(shown))
    }

    @Test fun `a 422 drops the attempt so the next pair is a new action`(): Unit = runBlocking {
        val store = InMemorySmsStateStore()
        val backend = object : WingmanApi by MockWingmanApi() {
            override suspend fun createConnection(body: ConnectionCreateRequest, key: IdempotencyKey): ApiResult<Connection> =
                MockWingmanApi.problem(422, com.wingman.core.model.ErrorCode.VALIDATION_ERROR, "bad")
        }
        val r = manager(store, backend).pair("Phone", caps())
        assertTrue(r is SmsPairResult.Failed && !r.retryable)
        assertNull(manager(store, backend).pendingPairKey())
        assertNull(store.get(SmsPairingKeys.CONNECTION))
    }

    @Test fun `unpair calls the server, clears the stored id, retires the scope and keeps queued envelopes`(): Unit = runBlocking {
        val store = InMemorySmsStateStore()
        val backend = PairingBackend()
        val m = manager(store, backend)
        val paired = (m.pair("Phone", caps()) as SmsPairResult.Paired).connection
        store.put(SmsOutboxPoller.KEY_PENDING_CLAIM, "{}")
        val scope = m.activeScope()!!
        SmsOutbox(store).enqueue(SmsEnvelopeFactory(scope).received(sms()))
        val r = m.unpair() as SmsUnpairResult.Unpaired
        assertEquals(paired.id, backend.unpairs.single().first)
        assertNotNull(r.result)
        assertNull(m.activeScope())
        assertNull(store.get(SmsPairingKeys.SNAPSHOT))
        assertNull("claim key belongs to the old connection", store.get(SmsOutboxPoller.KEY_PENDING_CLAIM))
        assertEquals(scope, m.retiredScope())
        assertEquals("not-yet-uploaded envelopes are kept", 1, SmsOutbox(store).size())
        assertTrue(m.state() is SmsPairingState.Unpaired)
        assertEquals(SmsUnpairResult.NotPaired, m.unpair())
    }

    @Test fun `a lost unpair response keeps the pairing and retries with the same key`(): Unit = runBlocking {
        val store = InMemorySmsStateStore()
        val backend = PairingBackend()
        val m = manager(store, backend)
        m.pair("Phone", caps())
        backend.loseNextUnpair = true
        assertTrue(m.unpair() is SmsUnpairResult.Failed)
        assertNotNull("still paired until the server confirms", m.activeScope())
        assertTrue(m.unpair() is SmsUnpairResult.Unpaired)
        assertEquals(backend.unpairs[0].second, backend.unpairs[1].second)
        assertNull(m.activeScope())
    }

    @Test fun `startup verify keeps a paired connection and refreshes its snapshot`(): Unit = runBlocking {
        val store = InMemorySmsStateStore()
        val backend = PairingBackend()
        val m = manager(store, backend)
        m.pair("Phone", caps())
        val r = m.verify()
        assertTrue(r is SmsVerifyResult.StillPaired)
        assertNotNull(m.activeScope())
    }

    @Test fun `startup verify on a 404 (unpaired and gone elsewhere) stops sync and asks to pair again`(): Unit = runBlocking {
        val store = InMemorySmsStateStore()
        val backend = PairingBackend()
        val m = manager(store, backend)
        m.pair("Phone", caps())
        backend.getOverride = { MockWingmanApi.problem(404, com.wingman.core.model.ErrorCode.NOT_FOUND, "Not found") }
        val r = manager(store, backend).verify()
        assertEquals(SmsVerifyResult.NeedsRePair(SmsPairingManager.NOTICE_NOT_FOUND), r)
        assertNull(m.activeScope())
        assertEquals(SmsPairingManager.NOTICE_NOT_FOUND, (m.state() as SmsPairingState.Unpaired).notice)
    }

    @Test fun `startup verify of a connection unpaired from another device asks to pair again, and pairing again makes a new connection`(): Unit = runBlocking {
        val store = InMemorySmsStateStore()
        val backend = PairingBackend()
        val m = manager(store, backend)
        val first = (m.pair("Phone", caps()) as SmsPairResult.Paired).connection
        backend.mock.unpairConnection(first.id, IdempotencyKey("elsewhere-unpair-1"))
        assertEquals(SmsVerifyResult.NeedsRePair(SmsPairingManager.NOTICE_UNPAIRED_ELSEWHERE), m.verify())
        assertNull(m.activeScope())
        val second = (m.pair("Phone", caps()) as SmsPairResult.Paired).connection
        assertNotEquals(first.id, second.id)
        assertEquals(second.id, m.activeScope()!!.connectionId)
        assertNull("notice cleared after re-pairing", (m.state() as SmsPairingState.Paired).let { store.get(SmsPairingKeys.NOTICE) })
    }

    @Test fun `startup verify offline keeps the stored id`(): Unit = runBlocking {
        val store = InMemorySmsStateStore()
        val backend = PairingBackend()
        val m = manager(store, backend)
        m.pair("Phone", caps())
        backend.getOverride = { ApiResult.Failure(ApiError.Network(IOException("offline"))) }
        assertTrue(m.verify() is SmsVerifyResult.Unknown)
        assertNotNull(m.activeScope())
    }

    @Test fun `a disconnected-but-not-unpaired connection is retired and can still be unpaired from the app`(): Unit = runBlocking {
        val store = InMemorySmsStateStore()
        val backend = PairingBackend()
        val m = manager(store, backend)
        val c = (m.pair("Phone", caps()) as SmsPairResult.Paired).connection
        backend.getOverride = { ApiResult.Success(c.copy(status = com.wingman.core.model.ConnectionStatus.DISCONNECTED)) }
        assertEquals(SmsVerifyResult.NeedsRePair(SmsPairingManager.NOTICE_DISCONNECTED), m.verify())
        val s = m.state() as SmsPairingState.Unpaired
        assertEquals(c.id, s.staleConnectionId)
        backend.getOverride = null
        assertTrue(m.unpair() is SmsUnpairResult.Unpaired)
        assertEquals(c.id, backend.unpairs.single().first)
        assertNull((m.state() as SmsPairingState.Unpaired).staleConnectionId)
    }

    @Test fun `display name from the device model`() {
        assertEquals("Google Pixel 8 SMS", SmsPairingManager.displayNameFor("Google", "Pixel 8"))
        assertEquals("Pixel 8 SMS", SmsPairingManager.displayNameFor(null, "Pixel 8"))
        assertEquals("Samsung SM-S911B SMS", SmsPairingManager.displayNameFor("samsung", "SM-S911B"))
        assertEquals("Android SMS", SmsPairingManager.displayNameFor(null, " "))
        assertTrue(SmsPairingManager.displayNameFor("x", "y".repeat(500)).length <= 120)
    }
}
