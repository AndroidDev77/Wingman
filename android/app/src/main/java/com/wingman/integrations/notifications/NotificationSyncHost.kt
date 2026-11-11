package com.wingman.integrations.notifications

import com.wingman.core.api.WingmanApi
import com.wingman.core.model.Connection
import com.wingman.core.model.UtcTimestamps
import com.wingman.core.model.Verification
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

/*
 * I-033 (WING-027/028): the notification connector's host. Ties WING-017's listener core and reply
 * adapter to the backend: per-platform pairing, the ordered upload queue, outbox claims, the user's
 * toggles and the access check on resume. Pure Kotlin; `android/NotificationSyncRuntime` is the thin
 * Android holder. Every method that talks to the backend blocks: call it off the main thread.
 */

/** What one [NotificationSyncHost.sync] did. Counts only, never content. */
data class NotificationSyncReport(
    val enqueuedEvents: Int = 0,
    val purgedEvents: Int = 0,
    val enqueuedCapabilityChanges: Int = 0,
    val enqueuedOutcomes: Int = 0,
    val uploaded: Int = 0,
    val deadLettered: Int = 0,
    val uploadRetryReason: String? = null,
    val polls: Map<String, NotificationPollReport> = emptyMap(),
    /** False when notification access is off: nothing was claimed. */
    val accessActive: Boolean = true,
)

/** Result of a user toggle. [message] is plain language for the Settings screen. */
sealed interface NotificationToggleResult {
    data class Done(val message: String) : NotificationToggleResult
    data class Refused(val message: String) : NotificationToggleResult
    data class Failed(val message: String) : NotificationToggleResult
}

/**
 * @param api the backend client, or null while none is connected (envelopes stay queued, nothing is
 *   paired or claimed).
 */
class NotificationSyncHost(
    val core: NotificationListenerCore,
    val adapter: NotificationReplyAdapter,
    val store: NotificationStateStore,
    private var api: WingmanApi? = null,
    private val clock: NotificationClock = NotificationClock.SYSTEM,
    private val log: NotificationLog = NotificationLog.NONE,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) {
    val queue = NotificationUploadQueue(store)
    private val uploadBackoff = NotificationBackoff(store, NotificationBackoff.UPLOAD, clock)
    private val lock = Any()

    @Synchronized
    fun connectBackend(newApi: WingmanApi?) {
        api = newApi
    }

    fun backendAvailable(): Boolean = api != null

    /** Platforms with a registered plugin, in registration order. */
    val platforms: List<String> get() = core.registry.platforms.toList()

    fun pairing(platform: String): NotificationPairingManager? {
        val client = api ?: return null
        if (core.registry.forPlatform(platform) == null) return null
        return NotificationPairingManager(store, client, platform, clock, newKey)
    }

    /** Runs [block] under the host lock: never during a sync round, a toggle or another block. */
    fun <T> exclusive(block: () -> T): T = synchronized(lock) { block() }

    /**
     * App resume / start: reconciles the system's notification access with the core. A revocation
     * stops everything at once: the core goes inactive (no reading, every live reply action dropped),
     * a capability downgrade is queued for each paired platform, and [sync] claims nothing until
     * access is back. Returns whether the listener is active.
     */
    fun refreshAccess(granted: Boolean): Boolean {
        // Not under the host lock: it runs on the main thread and must not wait for a network round.
        // The core's own lock makes the revocation atomic; a round in progress checks `isActive`
        // before claiming and the reply adapter re-checks it before firing.
        if (!granted && core.settings.accessGranted) {
            core.onAccessRevoked()
            log.log("notif.sync.access", mapOf("state" to "revoked"))
        }
        return core.isActive
    }

    /**
     * Turns reading of [platform] on (pairs first; reading starts only once paired) or off (reading
     * stops at once, unrecorded events and queued inbound envelopes are dropped, then the connection
     * is unpaired). Blocking.
     */
    fun setReadingEnabled(platform: String, enabled: Boolean, displayName: String): NotificationToggleResult = synchronized(lock) {
        if (core.registry.forPlatform(platform) == null) return NotificationToggleResult.Refused(NO_PLUGIN)
        if (enabled) enableReading(platform, displayName) else disableReading(platform)
    }

    private fun enableReading(platform: String, displayName: String): NotificationToggleResult {
        if (!core.isActive) return NotificationToggleResult.Refused(NEEDS_ACCESS)
        val pairing = pairing(platform) ?: return NotificationToggleResult.Refused(NO_BACKEND)
        val claim = pairingClaim(platform)
        return when (val r = kotlinx.coroutines.runBlocking { pairing.pair(displayName, claim) }) {
            is NotificationPairResult.Paired -> {
                core.settings.setReadEnabled(platform, true)
                log.log("notif.sync.paired", mapOf("platform" to platform, "replayed" to r.replayed.toString()))
                NotificationToggleResult.Done(if (r.replayed) "Already paired; reading is on." else "Paired. Wingman now reads this app's notifications.")
            }
            is NotificationPairResult.Failed -> NotificationToggleResult.Failed(
                "Pairing failed." + if (r.retryable) " Try again; it won't create a duplicate." else "",
            )
        }
    }

    private fun disableReading(platform: String): NotificationToggleResult {
        // Stop first: nothing more is read, and nothing can be replied to, whatever the server says.
        core.settings.setReadEnabled(platform, false)
        core.settings.setDirectReplyEnabled(platform, false)
        core.inbox.events().filter { it.platform == platform }.forEach { core.inbox.removeEvent(it.localId) }
        queue.purgeInbound(platform)
        val pairing = pairing(platform)
            ?: return NotificationToggleResult.Done("Reading is off. Wingman isn't connected to a server, so the pairing will be removed later.")
        return when (val r = kotlinx.coroutines.runBlocking { pairing.unpair() }) {
            is NotificationUnpairResult.Unpaired, NotificationUnpairResult.NotPaired -> NotificationToggleResult.Done("Reading is off and this app is disconnected.")
            is NotificationUnpairResult.Failed -> NotificationToggleResult.Failed("Reading is off, but disconnecting failed. Turn it off again to retry.")
        }
    }

    /**
     * Direct reply through the notification's RemoteInput. Refused unless the SERVER stores `send`
     * as VERIFIED for this connection (operator CLI with device evidence, D-016), which no Tinder or
     * Bumble connection has. Turning it on or off reports the new capability document.
     */
    fun setDirectReplyEnabled(platform: String, enabled: Boolean): NotificationToggleResult = synchronized(lock) {
        if (!enabled) {
            core.settings.setDirectReplyEnabled(platform, false)
            enqueueCapabilities(platform, "direct_reply_off")
            return NotificationToggleResult.Done("Direct reply is off.")
        }
        if (!directReplyAllowed(platform)) return NotificationToggleResult.Refused(DIRECT_REPLY_UNVERIFIED)
        core.settings.setDirectReplyEnabled(platform, true)
        enqueueCapabilities(platform, "direct_reply_on")
        NotificationToggleResult.Done("Direct reply is on. Every reply still needs your approval.")
    }

    /**
     * D-032: queues a `connection.capabilities_changed` for a paired [platform] because something
     * outside the notification path changed its document (the experimental screen reader turning
     * `profile_read` on or off). False when the platform is not paired. Uploaded by the next [sync].
     */
    fun reportCapabilities(platform: String, reason: String): Boolean = synchronized(lock) { enqueueCapabilities(platform, reason) }

    /** Direct reply may be offered only when the server-stored `send` of a paired connection is VERIFIED. */
    fun directReplyAllowed(platform: String): Boolean {
        val c: Connection = pairingState(platform) ?: return false
        return c.isPaired && c.capabilities.capabilities.send.status == Verification.VERIFIED
    }

    /** The stored connection of a paired platform (no network). */
    fun pairingState(platform: String): Connection? =
        NotificationPairingManager(store, null, platform, clock).let { m -> m.activeScope()?.let { m.storedConnection() } }

    fun isPaired(platform: String): Boolean = NotificationPairingManager(store, null, platform, clock).activeScope() != null

    fun notice(platform: String): String? = NotificationPairingManager(store, null, platform, clock).notice()

    /** Verifies every paired platform's connection (app start). Blocking. */
    fun verifyAll() = synchronized(lock) {
        for (p in platforms) {
            val m = pairing(p) ?: continue
            if (m.activeScope() == null) continue
            val r = kotlinx.coroutines.runBlocking { m.verify() }
            if (r is NotificationVerifyResult.NeedsRePair) core.settings.setReadEnabled(p, false)
        }
    }

    /**
     * One round: record -> upload -> claim -> upload outcomes.
     * 1. Recorded events of a paired platform become `message.received` envelopes in the queue (in
     *    order); events of a platform whose reading is off are dropped; others wait for pairing.
     * 2. Capability changes (access granted/revoked) become `connection.capabilities_changed`.
     * 3. Reply outcomes (WING-017 ledger) become `message.send_failed` (a SUBMITTED reply reports
     *    nothing, see NotificationContractMapping).
     * 4. The queue drains head-of-line with backoff and dead letters.
     * 5. Only while access is active: each paired, enabled platform claims its outbox.
     */
    fun sync(): NotificationSyncReport = synchronized(lock) {
        val (events, purged) = recordEvents()
        val caps = recordCapabilityChanges()
        var outcomes = recordOutcomes()
        val client = api ?: return NotificationSyncReport(events, purged, caps, outcomes, uploadRetryReason = "no backend client", accessActive = core.isActive)
        var drain = queue.drain(WingmanApiNotificationUploader(client), backoff = uploadBackoff)
        val polls = LinkedHashMap<String, NotificationPollReport>()
        if (core.isActive) {
            for (p in platforms) {
                if (!core.settings.readEnabled(p)) continue
                val scope = NotificationPairingManager(store, client, p, clock).activeScope() ?: continue
                val executor = NotificationClaimExecutor(scope, p, adapter)
                polls[p] = NotificationOutboxPoller(scope, p, store, client, executor, clock, newKey = newKey).poll()
            }
            if (polls.values.any { it.claimed > 0 }) {
                outcomes += recordOutcomes()
                val second = queue.drain(WingmanApiNotificationUploader(client), backoff = uploadBackoff)
                drain = NotificationUploadQueue.DrainReport(drain.accepted + second.accepted, drain.rejected + second.rejected, second.retryReason, second.nextAttemptAtMillis)
            }
        }
        val report = NotificationSyncReport(
            events, purged, caps, outcomes, drain.accepted, drain.rejected, drain.retryReason, polls, accessActive = core.isActive,
        )
        log.log(
            "notif.sync",
            mapOf(
                "events" to events.toString(), "purged" to purged.toString(), "caps" to caps.toString(), "outcomes" to outcomes.toString(),
                "uploaded" to drain.accepted.toString(), "dead" to drain.rejected.toString(), "active" to core.isActive.toString(),
                "claimed" to polls.values.sumOf { it.claimed }.toString(),
            ),
        )
        report
    }

    private fun recordEvents(): Pair<Int, Int> {
        var enqueued = 0
        var purged = 0
        for (e in core.inbox.events()) {
            val scope = activeScope(e.platform)
            when {
                scope != null && core.settings.readEnabled(e.platform) -> {
                    queue.enqueue(NotificationContractMapping.receivedEnvelope(scope, e))
                    core.inbox.removeEvent(e.localId)
                    enqueued++
                }
                !core.settings.readEnabled(e.platform) -> {
                    core.inbox.removeEvent(e.localId)
                    purged++
                }
                else -> Unit // reading on, pairing not finished: keep it
            }
        }
        return enqueued to purged
    }

    private fun recordCapabilityChanges(): Int {
        val changes = core.inbox.capabilityChanges()
        if (changes.isEmpty()) return 0
        var n = 0
        for (c in changes) if (enqueueCapabilities(c.platform, c.reason, c.atMillis)) n++
        core.inbox.clearCapabilityChanges()
        return n
    }

    private fun enqueueCapabilities(platform: String, reason: String, atMillis: Long = clock.nowMillis()): Boolean {
        val scope = activeScope(platform) ?: return false
        queue.enqueue(capabilitiesChangedEnvelope(scope, core.capabilities(platform), reason, atMillis, clock.nowMillis()))
        return true
    }

    private fun recordOutcomes(): Int {
        var n = 0
        for (r in adapter.unreported()) {
            val scope = activeScope(r.platform) ?: retiredScope(r.platform)
            if (scope == null) continue // no connection to report to yet: keep it unreported
            NotificationContractMapping.outcomeEnvelope(scope, r, clock.nowMillis())?.let {
                queue.enqueue(it)
                n++
            }
            adapter.markReported(r.idempotencyKey)
        }
        return n
    }

    private fun activeScope(platform: String) = NotificationPairingManager(store, null, platform, clock).activeScope()
    private fun retiredScope(platform: String) = NotificationPairingManager(store, null, platform, clock).retiredScope()

    /** The capability claim sent when pairing: assessed as if reading were already on. */
    fun pairingClaim(platform: String): JsonObject {
        val c = core.capabilities(platform)
        val assessed = NotificationCapabilityModel.assess(
            platform,
            NotificationCapabilityInputs(
                pluginRegistered = core.registry.forPlatform(platform) != null,
                accessGranted = core.settings.accessGranted,
                readEnabledByUser = true,
                directReplyEnabledByUser = core.settings.directReplyEnabled(platform),
                remoteInputObserved = core.settings.remoteInputObserved(platform),
            ),
        )
        return NotificationContractMapping.capabilitiesDocument(assessed.copy(profileRead = c.profileRead), clock.nowMillis())
    }

    companion object {
        const val NO_PLUGIN = "Wingman has no notification support for this app."
        const val NEEDS_ACCESS = "Turn on notification access for Wingman first."
        const val NO_BACKEND = "Wingman isn't connected to a server, so pairing isn't possible."
        const val DIRECT_REPLY_UNVERIFIED =
            "Direct reply stays off until replying from this app's notifications has been verified on a real device. " +
                "Until then Wingman drafts and you send."

        /** `connection.capabilities_changed` (contract 1.3.0); deterministic `event_id`. */
        fun capabilitiesChangedEnvelope(
            scope: NotificationConnectionScope,
            c: NotificationPlatformCapabilities,
            reason: String,
            atMillis: Long,
            nowMillis: Long,
        ): JsonObject = buildJsonObject {
            put("schema_version", "connector_event.v1")
            put(
                "event_id",
                UUID.nameUUIDFromBytes(
                    "wingman.notification.event|${scope.userId}|connection.capabilities_changed|${c.platform}|$reason|$atMillis".toByteArray(Charsets.UTF_8),
                ).toString(),
            )
            put("type", "connection.capabilities_changed")
            put("platform", c.platform)
            put("connection_id", scope.connectionId)
            put("user_id", scope.userId)
            put("external_message_id", JsonNull)
            put("occurred_at", UtcTimestamps.format(Instant.ofEpochMilli(atMillis)))
            put("observed_at", UtcTimestamps.format(Instant.ofEpochMilli(nowMillis)))
            put("payload", buildJsonObject { put("capabilities", NotificationContractMapping.capabilitiesDocument(c, nowMillis)) })
        }
    }
}
