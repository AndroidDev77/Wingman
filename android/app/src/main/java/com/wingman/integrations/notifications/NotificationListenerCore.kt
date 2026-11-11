package com.wingman.integrations.notifications

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One locally recorded inbound event, waiting for the platform connector (WING-027..029) or the
 * integrator's uploader to turn it into a contract envelope ([NotificationContractMapping]).
 * Holds private text: kept only in the app-private state file, never logged ([toString] redacts).
 */
@Serializable
data class NotificationInboxEvent(
    @SerialName("local_id") val localId: String,
    val platform: String,
    val kind: NotificationEventKind,
    val identity: NotificationIdentity,
    @SerialName("external_conversation_id") val externalConversationId: String?,
    /** Platform message ID when exposed, else `notif:` + a content-derived hash (stable across reposts). */
    @SerialName("external_message_id") val externalMessageId: String,
    @SerialName("sender_label") val senderLabel: String?,
    val body: String?,
    val truncated: Boolean,
    @SerialName("content_hidden") val contentHidden: Boolean,
    @SerialName("occurred_at") val occurredAtMillis: Long,
    @SerialName("observed_at") val observedAtMillis: Long,
    /** Whether the notification carried a usable reply action when this event was seen. */
    @SerialName("can_reply") val canReply: Boolean,
    @SerialName("certificate_pinned") val certificatePinned: Boolean,
) {
    override fun toString(): String =
        "NotificationInboxEvent(id=$localId, platform=$platform, kind=$kind, identity=$identity, " +
            "conversation=${externalConversationId?.let { "#" + NotificationHashes.short(it) }}, msg=$externalMessageId, " +
            "sender=${redactedLength(senderLabel)}, body=${redactedLength(body)}, truncated=$truncated, hidden=$contentHidden, canReply=$canReply)"
}

/** A capability change the connector must report (`connection.capabilities_changed`). */
@Serializable
data class NotificationCapabilityChange(
    val platform: String,
    val reason: String,
    @SerialName("at") val atMillis: Long,
)

/** Per-notification flags reported to the caller and the UI (ticket: explicit can_read/can_reply). */
data class NotificationFlags(val canRead: Boolean, val canReply: Boolean)

/** What [NotificationListenerCore.onPosted] did with one notification. Never carries text. */
sealed interface PostedOutcome {
    /** Not on the allowlist (or no plugin): nothing was extracted. The common case. */
    data object NotAllowlisted : PostedOutcome

    /** Listener inactive: access revoked or never granted. */
    data object Inactive : PostedOutcome

    /** The user has not enabled reading for this platform. */
    data class PlatformDisabled(val platform: String) : PostedOutcome

    /** Allowlisted package, but [NotificationAppPlugin.isAuthentic] rejected it (lookalike, cert mismatch). */
    data class Rejected(val platform: String, val reason: String) : PostedOutcome

    /** The exact same post (key, post time, content) was already processed: a repost or a restart replay. */
    data class Duplicate(val platform: String) : PostedOutcome

    /** Group summaries repeat their children; they are skipped. */
    data class GroupSummarySkipped(val platform: String) : PostedOutcome

    /** Processed. [newEvents] were recorded; [repeatedEvents] were already known (an update re-listing old messages). */
    data class Processed(
        val platform: String,
        val flags: NotificationFlags,
        val newEvents: List<NotificationInboxEvent>,
        val repeatedEvents: Int,
        val replyActionRegistered: Boolean,
    ) : PostedOutcome
}

/**
 * The listener's brain, pure Kotlin. The Android `NotificationListenerService` forwards
 * posted/removed callbacks and access changes here; the core decides everything.
 *
 * Order for one posted notification:
 * 1. Inactive (no access) -> stop. Package not owned by a plugin -> stop, nothing read.
 * 2. Plugin authenticity check -> reject lookalikes. User has not enabled the platform -> stop.
 * 3. Snapshot dedup on `(key, postTime, contentHash)` (persisted): an exact repost or the replay of
 *    active notifications after a restart is dropped.
 * 4. Group summaries are skipped (their children carry the content).
 * 5. The plugin parses; each event is deduplicated on a content-derived message ID (persisted),
 *    so an update that re-lists earlier MessagingStyle messages records only the new ones.
 * 6. A reply action is registered in [LiveReplyActions] only for a LINKED [NotificationEventKind.MESSAGE]
 *    with a free-form RemoteInput. Its presence sets `can_reply`; it never authorizes a send.
 *
 * The core has NO reference to [NotificationReplyAdapter]: nothing inbound can trigger a reply.
 */
class NotificationListenerCore(
    val registry: NotificationPluginRegistry,
    private val store: NotificationStateStore,
    val liveActions: LiveReplyActions,
    private val clock: NotificationClock = NotificationClock.SYSTEM,
    private val log: NotificationLog = NotificationLog.NONE,
    private val dedupRetentionMillis: Long = 7L * 24 * 60 * 60 * 1000,
) {
    val settings = NotificationSettings(store)
    val inbox = NotificationInbox(store)
    private val dedup = NotificationDedup(store, clock, dedupRetentionMillis)

    /** True while notification access is granted and the listener should process anything. */
    val isActive: Boolean get() = settings.accessGranted

    /**
     * The listener connected (access granted, or rebound after process death). Clears live
     * actions; the adapter then replays `getActiveNotifications()` through [onPosted], which
     * re-registers actions while snapshot dedup keeps already recorded events from repeating.
     */
    @Synchronized
    fun onAccessGranted() {
        val wasGranted = settings.accessGranted
        settings.accessGranted = true
        liveActions.clear()
        if (!wasGranted) reportCapabilityChange("access_granted")
        log.log("notif.access", mapOf("state" to "granted"))
    }

    /**
     * Access was revoked (or the system unbound the listener and the probe says access is gone).
     * Stops everything at once: no more processing, every live reply action is dropped, so no
     * reply can fire, and a capability downgrade is recorded for every registered platform.
     */
    @Synchronized
    fun onAccessRevoked() {
        val wasGranted = settings.accessGranted
        settings.accessGranted = false
        liveActions.clear()
        if (wasGranted) reportCapabilityChange("access_revoked")
        log.log("notif.access", mapOf("state" to "revoked"))
    }

    @Synchronized
    fun onPosted(source: NotificationSource, snapshot: NotificationSnapshot, replyHandle: (SnapshotAction) -> ReplyActionHandle): PostedOutcome {
        if (!isActive) return PostedOutcome.Inactive
        val plugin = registry.forPackage(source.packageName) ?: return PostedOutcome.NotAllowlisted
        val platform = plugin.platform
        val verdict = runCatching { plugin.isAuthentic(source, snapshot) }
            .getOrElse { AuthenticityVerdict.Rejected("authenticity_check_threw") }
        if (verdict is AuthenticityVerdict.Rejected) {
            log.log("notif.rejected", mapOf("platform" to platform, "reason" to verdict.reason))
            return PostedOutcome.Rejected(platform, verdict.reason)
        }
        val pinned = (verdict as AuthenticityVerdict.Authentic).certificatePinned
        if (!settings.readEnabled(platform)) return PostedOutcome.PlatformDisabled(platform)

        val snapshotKey = NotificationHashes.sha256Hex(platform, snapshot.key, snapshot.postTimeMillis.toString(), snapshot.contentHash)
        if (dedup.isClaimed(DEDUP_SNAPSHOT, snapshotKey)) {
            // Still (re)register the reply action: after a restart the live map is empty.
            val registered = registerReplyAction(plugin, snapshot, parseSafely(plugin, snapshot), replyHandle)
            log.log("notif.duplicate", mapOf("platform" to platform, "action_registered" to registered.toString()))
            return PostedOutcome.Duplicate(platform)
        }
        if (snapshot.isGroupSummary) {
            dedup.claim(DEDUP_SNAPSHOT, snapshotKey)
            log.log("notif.summary_skipped", mapOf("platform" to platform))
            return PostedOutcome.GroupSummarySkipped(platform)
        }

        val parsed = parseSafely(plugin, snapshot)
        val replyAction = plugin.replyAction(snapshot)
        if (replyAction != null) settings.markRemoteInputObserved(platform, clock.nowMillis())
        val registered = registerReplyAction(plugin, snapshot, parsed, replyHandle)

        val now = clock.nowMillis()
        val fresh = mutableListOf<NotificationInboxEvent>()
        var repeated = 0
        for (e in parsed) {
            val messageId = e.externalMessageId ?: derivedMessageId(platform, e)
            val eventKey = NotificationHashes.sha256Hex(platform, messageId)
            if (dedup.isClaimed(DEDUP_EVENT, eventKey)) {
                repeated++
                continue
            }
            val record = NotificationInboxEvent(
                localId = NotificationHashes.sha256Hex("local", platform, messageId).take(32),
                platform = platform,
                kind = e.kind,
                identity = e.identity,
                externalConversationId = e.externalConversationId,
                externalMessageId = messageId,
                senderLabel = e.senderLabel,
                body = e.body,
                truncated = e.truncated,
                contentHidden = e.contentHidden,
                occurredAtMillis = e.occurredAtMillis,
                observedAtMillis = now,
                canReply = registered && e.kind == NotificationEventKind.MESSAGE && e.identity == NotificationIdentity.LINKED,
                certificatePinned = pinned,
            )
            inbox.add(record) // keyed by localId: a crash before the claim below re-adds it once, not twice
            dedup.claim(DEDUP_EVENT, eventKey)
            fresh += record
        }
        // Claimed LAST: a process death above replays this post, and event dedup drops what was recorded.
        dedup.claim(DEDUP_SNAPSHOT, snapshotKey)
        val flags = NotificationFlags(canRead = parsed.isNotEmpty(), canReply = registered)
        log.log(
            "notif.processed",
            mapOf(
                "platform" to platform,
                "events_new" to fresh.size.toString(),
                "events_repeated" to repeated.toString(),
                "kinds" to fresh.joinToString(",") { it.kind.name.lowercase() },
                "can_read" to flags.canRead.toString(),
                "can_reply" to flags.canReply.toString(),
                "truncated" to fresh.count { it.truncated }.toString(),
                "hidden" to fresh.count { it.contentHidden }.toString(),
            ),
        )
        return PostedOutcome.Processed(platform, flags, fresh, repeated, registered)
    }

    /** The notification was dismissed, replied to or canceled by the app: its action is gone. */
    @Synchronized
    fun onRemoved(notificationKey: String) {
        if (liveActions.remove(notificationKey) != null) log.log("notif.action_removed", emptyMap())
    }

    /** Current capabilities of every registered platform (and only those). */
    fun capabilities(): List<NotificationPlatformCapabilities> = registry.platforms.map { capabilities(it) }

    fun capabilities(platform: String): NotificationPlatformCapabilities = ProfileReadCapability.apply(
        NotificationCapabilityModel.assess(
            platform,
            NotificationCapabilityInputs(
                pluginRegistered = registry.forPlatform(platform) != null,
                accessGranted = settings.accessGranted,
                readEnabledByUser = settings.readEnabled(platform),
                directReplyEnabledByUser = settings.directReplyEnabled(platform),
                remoteInputObserved = settings.remoteInputObserved(platform),
            ),
        ),
    )

    private fun reportCapabilityChange(reason: String) {
        val now = clock.nowMillis()
        for (p in registry.platforms) inbox.addCapabilityChange(NotificationCapabilityChange(p, reason, now))
    }

    private fun parseSafely(plugin: NotificationAppPlugin, snapshot: NotificationSnapshot): List<ParsedNotificationEvent> =
        runCatching { plugin.parse(snapshot) }.getOrElse {
            log.log("notif.parse_failed", mapOf("platform" to plugin.platform, "error" to it.javaClass.simpleName))
            emptyList()
        }

    private fun registerReplyAction(
        plugin: NotificationAppPlugin,
        snapshot: NotificationSnapshot,
        parsed: List<ParsedNotificationEvent>,
        replyHandle: (SnapshotAction) -> ReplyActionHandle,
    ): Boolean {
        if (snapshot.isGroupSummary) return false
        val action = runCatching { plugin.replyAction(snapshot) }.getOrNull() ?: return false
        if (!action.hasFreeFormRemoteInput || action.isContextual) return false
        // Only a chat message in ONE identified conversation can be replied into.
        val target = parsed.filter { it.kind == NotificationEventKind.MESSAGE && it.identity == NotificationIdentity.LINKED }
            .mapNotNull { it.externalConversationId }.distinct()
        if (target.size != 1) return false
        liveActions.put(
            LiveReplyAction(
                notificationKey = snapshot.key,
                postTimeMillis = snapshot.postTimeMillis,
                platform = plugin.platform,
                externalConversationId = target.single(),
                actionIndex = action.index,
                remoteInputKeys = action.remoteInputs.filter { it.allowFreeFormInput }.map { it.resultKey },
                handle = replyHandle(action),
                registeredAtMillis = clock.nowMillis(),
            ),
        )
        return true
    }

    companion object {
        const val DEDUP_SNAPSHOT = "snap"
        const val DEDUP_EVENT = "event"

        /**
         * Content-derived message ID: platform, conversation (or sender label when unlinked), kind,
         * source time and the body. The body is hashed, never embedded. Two different messages with
         * the same sender, text and timestamp collapse (preferred over a double reply, as for SMS).
         */
        fun derivedMessageId(platform: String, e: ParsedNotificationEvent): String =
            "notif:" + NotificationHashes.sha256Hex(
                platform,
                e.kind.name,
                e.externalConversationId ?: ("label:" + (e.senderLabel ?: "")),
                e.occurredAtMillis.toString(),
                e.body,
                e.contentHidden.toString(),
            ).take(40)
    }
}

/** Durable first-writer-wins claims, pruned after the retention period. Keys are hashes only. */
class NotificationDedup(
    private val store: NotificationStateStore,
    private val clock: NotificationClock,
    private val retentionMillis: Long,
) {
    fun isClaimed(namespace: String, hash: String): Boolean = store.get("$PREFIX$namespace:$hash") != null

    @Synchronized
    fun claim(namespace: String, hash: String): Boolean {
        val k = "$PREFIX$namespace:$hash"
        if (store.get(k) != null) return false
        val now = clock.nowMillis()
        store.put(k, now.toString())
        prune(now)
        return true
    }

    private fun prune(now: Long) {
        for (k in store.keys(PREFIX)) {
            val at = store.get(k)?.toLongOrNull() ?: continue
            if (now - at > retentionMillis) store.remove(k)
        }
    }

    companion object {
        const val PREFIX = "dedup:"
    }
}

/**
 * Durable local queue of recorded inbound events, reply outcomes and capability changes, in
 * insertion order. Drained by whoever uploads them ([take] + [remove] after a confirmed upload).
 */
class NotificationInbox(private val store: NotificationStateStore) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun add(e: NotificationInboxEvent) {
        val k = EVENT_PREFIX + e.localId
        if (store.get(k) == null) store.put(k, json.encodeToString(NotificationInboxEvent.serializer(), e))
    }

    fun events(): List<NotificationInboxEvent> =
        store.keys(EVENT_PREFIX).mapNotNull { store.get(it) }.map { json.decodeFromString(NotificationInboxEvent.serializer(), it) }

    fun removeEvent(localId: String) = store.remove(EVENT_PREFIX + localId)

    fun addCapabilityChange(c: NotificationCapabilityChange) {
        store.put(CAP_PREFIX + c.platform + ":" + c.atMillis + ":" + c.reason, json.encodeToString(NotificationCapabilityChange.serializer(), c))
    }

    fun capabilityChanges(): List<NotificationCapabilityChange> =
        store.keys(CAP_PREFIX).mapNotNull { store.get(it) }.map { json.decodeFromString(NotificationCapabilityChange.serializer(), it) }

    fun clearCapabilityChanges() = store.keys(CAP_PREFIX).forEach { store.remove(it) }

    companion object {
        const val EVENT_PREFIX = "inbox:event:"
        const val CAP_PREFIX = "inbox:cap:"
    }
}
