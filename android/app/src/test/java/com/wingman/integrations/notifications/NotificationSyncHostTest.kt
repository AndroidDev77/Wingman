package com.wingman.integrations.notifications

import com.wingman.core.api.ApiError
import com.wingman.core.api.ApiResult
import com.wingman.core.api.IdempotencyKey
import com.wingman.core.api.WingmanApi
import com.wingman.core.api.mock.MockFixtures
import com.wingman.core.api.mock.MockWingmanApi
import com.wingman.core.model.Connection
import com.wingman.core.model.ConnectionCreateRequest
import com.wingman.core.model.ConnectionTransport
import com.wingman.core.model.ConnectionUnpairRequest
import com.wingman.core.model.ConnectionUnpairResult
import com.wingman.core.model.ConnectorEventIngestResult
import com.wingman.core.model.OutboxClaimRequest
import com.wingman.core.model.OutboxClaimResult
import com.wingman.core.model.Problem
import com.wingman.core.model.Verification
import com.wingman.integrations.bumble.BumbleNotificationPlugin
import com.wingman.integrations.tinder.TinderNotificationPlugin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * I-033: the notification upload/claim loop, pairing toggles and refreshAccess, over
 * [MockWingmanApi] (which mirrors the backend's pairing, ingest and claim rules) with a recording,
 * scriptable wrapper. Synthetic text only.
 */
class NotificationSyncHostTest {

    class Backend(val mock: MockWingmanApi = MockWingmanApi()) : WingmanApi by mock {
        val creates = mutableListOf<Pair<ConnectionCreateRequest, String>>()
        val unpairs = mutableListOf<String>()
        val ingests = mutableListOf<JsonObject>()
        val claims = mutableListOf<Pair<OutboxClaimRequest, String>>()
        val ingestScript = ArrayDeque<ApiResult<ConnectorEventIngestResult>?>()
        var failCreateWith: ApiResult.Failure? = null

        override suspend fun createConnection(body: ConnectionCreateRequest, key: IdempotencyKey): ApiResult<Connection> {
            creates += body to key.value
            failCreateWith?.let { failCreateWith = null; return it }
            return mock.createConnection(body, key)
        }

        override suspend fun unpairConnection(connectionId: String, key: IdempotencyKey, body: ConnectionUnpairRequest): ApiResult<ConnectionUnpairResult> {
            unpairs += connectionId
            return mock.unpairConnection(connectionId, key, body)
        }

        override suspend fun ingestConnectorEvent(connectionId: String, envelope: JsonObject, key: IdempotencyKey): ApiResult<ConnectorEventIngestResult> {
            ingests += envelope
            return ingestScript.removeFirstOrNull() ?: mock.ingestConnectorEvent(connectionId, envelope, key)
        }

        override suspend fun claimOutboxInstructions(connectionId: String, body: OutboxClaimRequest, key: IdempotencyKey): ApiResult<OutboxClaimResult> {
            claims += body to key.value
            return mock.claimOutboxInstructions(connectionId, body, key)
        }
    }

    private val reply = SnapshotAction(0, "Reply", listOf(SnapshotRemoteInput("reply_text", true)), SnapshotAction.SEMANTIC_ACTION_REPLY)

    inner class World(
        val store: NotificationStateStore = InMemoryNotificationStateStore(),
        val backend: Backend? = Backend(),
        val clock: NFakeClock = NFakeClock(),
        val log: RecordingLog = RecordingLog(),
        access: Boolean = true,
    ) {
        val core = NotificationListenerCore(
            NotificationPluginRegistry(listOf(TinderNotificationPlugin(), BumbleNotificationPlugin())), store, LiveReplyActions(), clock, log,
        )
        val adapter = NotificationReplyAdapter(core, store, clock, log)
        var n = 0
        val host = NotificationSyncHost(core, adapter, store, backend, clock, log, newKey = { "key-%08d".format(++n) })

        init {
            if (access) core.onAccessGranted()
            core.inbox.clearCapabilityChanges()
        }

        fun post(s: NotificationSnapshot) = core.onPosted(NotificationSource(s.packageName), s) { FakeHandle() }

        fun tinderChat(text: String, key: String, conv: String = "m1", at: Long = clock.now) = NotificationSnapshot(
            key = key, packageName = "com.tinder", postTimeMillis = at, whenMillis = at, title = "Synthetic Sam", text = text,
            messages = listOf(SnapshotMessage(text, at, "Synthetic Sam")), shortcutId = conv, category = "msg",
            template = "android.app.Notification\$MessagingStyle", actions = listOf(reply),
        )

        fun tinderNotice(title: String, text: String, key: String, at: Long = clock.now) = NotificationSnapshot(
            key = key, packageName = "com.tinder", postTimeMillis = at, whenMillis = at, title = title, text = text,
        )
    }

    private fun World.pairTinder(): NotificationToggleResult = host.setReadingEnabled("tinder", true, "Tinder notifications")

    private fun types(b: Backend) = b.ingests.map { it["type"]!!.jsonPrimitive.content }
    private fun kinds(b: Backend) = b.ingests.filter { it["type"]!!.jsonPrimitive.content == "message.received" }
        .map { it["payload"]!!.jsonObject["kind"]!!.jsonPrimitive.content }

    // ------------------------------------------------------------------ toggles gate pairing

    @Test fun turningReadingOnPairsWithNotificationTransportBeforeReading() {
        val w = World()
        assertFalse(w.core.settings.readEnabled("tinder"))
        val r = w.pairTinder()
        assertTrue(r.toString(), r is NotificationToggleResult.Done)
        val (body, _) = w.backend!!.creates.single()
        assertEquals("tinder", body.platform)
        assertEquals(ConnectionTransport.NOTIFICATION, body.transport)
        assertEquals(NotificationInstallId.getOrCreate(w.store), body.deviceInstallId)
        assertEquals(Verification.UNVERIFIED, body.capabilities!!.capabilities.read.status)
        assertEquals(Verification.UNSUPPORTED, body.capabilities!!.capabilities.send.status)
        assertEquals(Verification.UNSUPPORTED, body.capabilities!!.capabilities.swipe.status)
        assertTrue(w.core.settings.readEnabled("tinder"))
        assertTrue(w.host.isPaired("tinder"))
        assertFalse(w.host.isPaired("bumble"))
        assertEquals(ConnectionTransport.NOTIFICATION, w.host.pairingState("tinder")!!.transport)
        // Bumble pairs as its own connection with the SAME install ID.
        w.host.setReadingEnabled("bumble", true, "Bumble notifications")
        assertEquals(listOf("tinder", "bumble"), w.backend.creates.map { it.first.platform })
        assertEquals(1, w.backend.creates.map { it.first.deviceInstallId }.toSet().size)
    }

    @Test fun readingCannotTurnOnWithoutAccessOrBackendAndNothingIsRead() {
        val noAccess = World(access = false)
        assertEquals(NotificationToggleResult.Refused(NotificationSyncHost.NEEDS_ACCESS), noAccess.pairTinder())
        assertTrue(noAccess.backend!!.creates.isEmpty())
        assertFalse(noAccess.core.settings.readEnabled("tinder"))

        val noBackend = World(backend = null)
        assertEquals(NotificationToggleResult.Refused(NotificationSyncHost.NO_BACKEND), noBackend.pairTinder())
        assertFalse(noBackend.core.settings.readEnabled("tinder"))
        // Not enabled: a Tinder notification is not even parsed.
        assertEquals(PostedOutcome.PlatformDisabled("tinder"), noBackend.post(noBackend.tinderChat("x", "k1")))
        assertEquals(NotificationToggleResult.Refused(NotificationSyncHost.NO_PLUGIN), noBackend.host.setReadingEnabled("hinge", true, "x"))
    }

    @Test fun failedPairingLeavesReadingOffAndRetriesWithTheSameKey() {
        val w = World()
        w.backend!!.failCreateWith = ApiResult.Failure(ApiError.Network(IOException("offline")))
        assertTrue(w.pairTinder() is NotificationToggleResult.Failed)
        assertFalse(w.core.settings.readEnabled("tinder"))
        assertTrue(w.pairTinder() is NotificationToggleResult.Done)
        assertEquals(2, w.backend.creates.size)
        assertEquals(w.backend.creates[0].second, w.backend.creates[1].second) // same persisted key, no duplicate pairing
    }

    @Test fun turningReadingOffStopsAtOncePurgesAndUnpairs() {
        val w = World()
        w.pairTinder()
        w.post(w.tinderChat("synthetic one", "k1"))
        assertEquals(1, w.core.inbox.events().size)
        val r = w.host.setReadingEnabled("tinder", false, "x")
        assertTrue(r is NotificationToggleResult.Done)
        assertFalse(w.core.settings.readEnabled("tinder"))
        assertTrue(w.core.inbox.events().isEmpty())
        assertEquals(1, w.backend!!.unpairs.size)
        assertFalse(w.host.isPaired("tinder"))
        assertEquals(PostedOutcome.PlatformDisabled("tinder"), w.post(w.tinderChat("synthetic two", "k2")))
        w.host.sync()
        assertTrue(kinds(w.backend).isEmpty())
    }

    @Test fun directReplyStaysOffWhileSendIsNotVerified() {
        val w = World()
        w.pairTinder()
        assertFalse(w.host.directReplyAllowed("tinder"))
        assertEquals(NotificationToggleResult.Refused(NotificationSyncHost.DIRECT_REPLY_UNVERIFIED), w.host.setDirectReplyEnabled("tinder", true))
        assertFalse(w.core.settings.directReplyEnabled("tinder"))
        assertEquals(com.wingman.integrations.notifications.CapabilityStatus.UNSUPPORTED, w.core.capabilities("tinder").send.status)
    }

    // ------------------------------------------------------------------ upload loop

    @Test fun eventsUploadInOrderAsReceivedEnvelopes() {
        val w = World()
        w.pairTinder()
        w.post(w.tinderNotice("It's a Match!", "Say hi", "k1"))
        w.clock.now += 1_000
        w.post(w.tinderChat("synthetic hello", "k2"))
        w.clock.now += 1_000
        w.post(w.tinderNotice("Tinder", "Someone likes you", "k3"))
        val r = w.host.sync()
        assertEquals(3, r.enqueuedEvents)
        assertEquals(3, r.uploaded)
        assertEquals(listOf("match", "chat_message", "like_comment"), kinds(w.backend!!))
        val chat = w.backend.ingests[1]
        assertEquals("tinder", chat["platform"]!!.jsonPrimitive.content)
        assertEquals("shortcut:m1", chat["external_thread_id"]!!.jsonPrimitive.content)
        assertEquals("notification", chat["payload"]!!.jsonObject["source"]!!.jsonPrimitive.content)
        assertEquals("unlinked", w.backend.ingests[0]["payload"]!!.jsonObject["identity"]!!.jsonPrimitive.content)
        assertEquals(0, w.host.queue.size())
        assertTrue(w.core.inbox.events().isEmpty())
        // The match and the like became notices on the (mock) server, the chat a message.
        val notices = runBlocking { w.backend.mock.listInboundNotices() }.getOrNull()!!.items
        assertTrue(notices.any { it.kind == com.wingman.core.model.InboundEventKind.MATCH })
        assertTrue(notices.any { it.kind == com.wingman.core.model.InboundEventKind.LIKE_COMMENT })
    }

    @Test fun retryableFailureStopsHeadOfLineAndBacksOff() {
        val w = World()
        w.pairTinder()
        for (i in 1..3) {
            w.post(w.tinderChat("synthetic $i", "k$i", at = w.clock.now + i))
        }
        w.backend!!.ingestScript += ApiResult.Failure(ApiError.Network(IOException("offline")))
        val first = w.host.sync()
        assertEquals(1, w.backend.ingests.size) // stopped at the head
        assertEquals(3, w.host.queue.size())
        assertNotNull(first.uploadRetryReason)
        val headId = w.backend.ingests[0]["event_id"]!!.jsonPrimitive.content

        val waiting = w.host.sync()
        assertEquals("backing off", waiting.uploadRetryReason)
        assertEquals(1, w.backend.ingests.size)

        w.clock.now += 6_000 // past the first backoff step (5 s)
        w.host.sync()
        assertEquals(4, w.backend.ingests.size)
        assertEquals(headId, w.backend.ingests[1]["event_id"]!!.jsonPrimitive.content) // same envelope, same key, first
        assertEquals(listOf("synthetic 1", "synthetic 2", "synthetic 3"), w.backend.ingests.drop(1).map { it["payload"]!!.jsonObject["text"]!!.jsonPrimitive.content })
        assertEquals(0, w.host.queue.size())
    }

    @Test fun backoffGrowsAndHonoursRetryAfter() {
        val store = InMemoryNotificationStateStore()
        val clock = NFakeClock()
        val b = NotificationBackoff(store, "upload", clock)
        assertEquals(clock.now + 5_000, b.onRetryLater(null))
        assertEquals(clock.now + 10_000, b.onRetryLater(null))
        assertEquals(clock.now + 30_000, b.onRetryLater(30_000))
        b.onProgress()
        assertEquals(0, b.failures())
        assertFalse(b.isWaiting())
    }

    @Test fun rejectedEnvelopesAreDeadLetteredAndTheRestContinues() {
        val w = World()
        w.pairTinder()
        w.post(w.tinderChat("synthetic a", "k1", at = w.clock.now + 1))
        w.post(w.tinderChat("synthetic b", "k2", at = w.clock.now + 2))
        w.backend!!.ingestScript += ApiResult.Failure(ApiError.Http(422, Problem("t", "bad", 422, "validation_error")))
        val r = w.host.sync()
        assertEquals(1, r.deadLettered)
        assertEquals(1, r.uploaded)
        assertEquals(1, w.host.queue.deadLetterCount())
        assertEquals(0, w.host.queue.size())
        w.host.sync()
        assertEquals(2, w.backend.ingests.size) // never retried
    }

    @Test fun queueAndPairingSurviveARestart() {
        val store = InMemoryNotificationStateStore()
        val backend = Backend()
        val clock = NFakeClock()
        val a = World(store, backend, clock)
        a.pairTinder()
        val postedAt = clock.now
        a.post(a.tinderChat("synthetic before restart", "k1", at = postedAt))
        backend.ingestScript += ApiResult.Failure(ApiError.Network(IOException("offline")))
        a.host.sync()
        assertEquals(1, a.host.queue.size())
        val install = NotificationInstallId.getOrCreate(store)

        // "Process death": everything rebuilt from the same state file.
        val b = World(store, backend, clock)
        assertTrue(b.host.isPaired("tinder"))
        assertTrue(b.core.settings.readEnabled("tinder"))
        assertEquals(install, NotificationInstallId.getOrCreate(store))
        clock.now += 6_000
        val r = b.host.sync()
        assertEquals(1, r.uploaded)
        assertEquals(2, backend.ingests.size)
        assertEquals(backend.ingests[0]["event_id"], backend.ingests[1]["event_id"])
        // The same notification replayed after the restart is not recorded again.
        assertTrue(b.post(b.tinderChat("synthetic before restart", "k1", at = postedAt)) is PostedOutcome.Duplicate)
    }

    // ------------------------------------------------------------------ claims

    @Test fun claimedInstructionsGoToTheClaimExecutorWithThePairedInstall() {
        val w = World()
        w.pairTinder()
        val connection = w.host.pairingState("tinder")!!.id
        val key = w.backend!!.mock.queueOutboxInstruction(connection, "shortcut:m1", "synthetic reply")
        val r = w.host.sync()
        val poll = r.polls.getValue("tinder")
        assertEquals(1, poll.claimed)
        assertEquals(NotificationInstallId.getOrCreate(w.store), w.backend.claims.single().first.deviceInstallId)
        // Direct reply is off (send not VERIFIED): the executor refuses, nothing fires, failure is reported.
        val rec = w.adapter.record(key)!!
        assertEquals(ReplyState.FAILED, rec.state)
        assertEquals(ReplyFailureReason.DIRECT_REPLY_DISABLED, rec.failureReason)
        assertFalse(rec.actionFired)
        assertTrue("message.send_failed" in types(w.backend))
        assertNull(w.store.get(NotificationOutboxPoller.pendingClaimKey("tinder")))
        // Replay of the same claim answer never fires twice.
        w.host.sync()
        assertEquals(0, w.adapter.fireAttempts(key))
    }

    @Test fun claimsOnlyForPairedEnabledPlatforms() {
        val w = World()
        w.host.sync()
        assertTrue(w.backend!!.claims.isEmpty())
        w.pairTinder()
        w.host.sync()
        assertEquals(1, w.backend.claims.size)
    }

    // ------------------------------------------------------------------ refreshAccess

    @Test fun refreshAccessStopsEverythingWhenAccessWasRevoked() {
        val w = World()
        w.pairTinder()
        w.post(w.tinderChat("synthetic", "k1"))
        assertEquals(1, w.core.liveActions.size())
        w.host.sync()
        val claimsBefore = w.backend!!.claims.size
        val ingestsBefore = w.backend.ingests.size

        assertFalse(w.host.refreshAccess(granted = false))
        assertFalse(w.core.isActive)
        assertEquals(0, w.core.liveActions.size()) // no reply can fire
        assertEquals(PostedOutcome.Inactive, w.post(w.tinderChat("synthetic later", "k2")))
        val r = w.host.sync()
        assertFalse(r.accessActive)
        assertEquals(claimsBefore, w.backend.claims.size) // nothing claimed while revoked
        // The downgrade is reported: read UNSUPPORTED.
        val cap = w.backend.ingests.drop(ingestsBefore).single { it["type"]!!.jsonPrimitive.content == "connection.capabilities_changed" }
        val read = cap["payload"]!!.jsonObject["capabilities"]!!.jsonObject["capabilities"]!!.jsonObject["read"]!!.jsonObject
        assertEquals("UNSUPPORTED", read["status"]!!.jsonPrimitive.content)
        // A command that slipped through anyway is refused by the adapter.
        val out = w.adapter.reply(NotificationReplyCommand("tinder", N_INSTRUCTION, N_OUTBOUND, "send:$N_OUTBOUND:9", "shortcut:m1", "x", "user_manual", w.clock.now + 60_000))
        assertEquals(ReplyFailureReason.ACCESS_REVOKED, (out as ReplyOutcome.Failed).reason)
        // Granted again: refreshAccess with granted=true is a no-op (the listener reconnect re-activates).
        assertFalse(w.host.refreshAccess(granted = true))
    }

    // ------------------------------------------------------------------ privacy

    @Test fun noNotificationTextReachesTheLogs() {
        val w = World()
        w.pairTinder()
        val sentinel = "SENTINEL-PRIVATE-7731"
        w.post(w.tinderChat("$sentinel message", "k1"))
        w.post(w.tinderNotice("It's a Match! $sentinel", "$sentinel says hi", "k2"))
        w.backend!!.ingestScript += ApiResult.Failure(ApiError.Network(IOException("offline $sentinel")))
        w.host.sync()
        w.clock.now += 10_000
        w.host.sync()
        w.host.refreshAccess(false)
        w.host.sync()
        assertTrue(w.log.lines.isNotEmpty())
        assertTrue(w.log.lines.joinToString("\n"), w.log.lines.none { sentinel in it || "Synthetic Sam" in it })
        // Reports and pairing state print no text either.
        assertFalse(w.host.sync().toString().contains(sentinel))
    }

    @Test fun mockStillRejectsPairingWithoutTransport() = runBlocking {
        val api = MockWingmanApi()
        val caps = com.wingman.core.api.WingmanJson.decodeFromJsonElement(
            com.wingman.core.model.ConnectorCapabilities.serializer(),
            NotificationContractMapping.capabilitiesDocument(
                NotificationCapabilityModel.assess("tinder", NotificationCapabilityInputs(true, true, true, false, false)), 0,
            ),
        )
        val noTransport = ConnectionCreateRequest("tinder", "Tinder", "0192f3a0-0000-4000-8000-0000000000aa", caps)
        val r = api.createConnection(noTransport, IdempotencyKey("key-00000001"))
        assertEquals(422, (r.errorOrNull() as ApiError.Http).status)
        assertEquals(MockFixtures.USER_ID, api.getMe().getOrNull()!!.id)
    }
}
