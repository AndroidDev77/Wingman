package com.wingman.integrations.notifications

/**
 * The platform half of a reply action: wraps the notification action's PendingIntent and its
 * RemoteInputs. Implemented on device by `android/AndroidReplyActionHandle`; tests use a fake.
 *
 * [fire] puts [text] into every free-form RemoteInput result of the action and sends the
 * PendingIntent. It returns [FireResult.Fired] when the PendingIntent was sent: that means the
 * app was handed the text, NOT that the message was sent or delivered.
 */
fun interface ReplyActionHandle {
    fun fire(text: String): FireResult
}

sealed interface FireResult {
    /** `PendingIntent.send` returned normally. */
    data object Fired : FireResult

    /** The PendingIntent was canceled (the app withdrew or replaced the notification): nothing was handed over. */
    data object Canceled : FireResult

    /** Anything else thrown before the PendingIntent was sent. [type] is an exception class name, never a message. */
    data class Error(val type: String) : FireResult
}

/**
 * A reply action seen on a live (posted, not yet removed) notification. Held only in memory: a
 * PendingIntent cannot outlive the process meaningfully, and after process death the listener
 * rebuilds this map from `getActiveNotifications()` when it reconnects.
 */
class LiveReplyAction(
    val notificationKey: String,
    val postTimeMillis: Long,
    val platform: String,
    /** Linked conversation the action replies into. Only LINKED message notifications register an action. */
    val externalConversationId: String,
    val actionIndex: Int,
    val remoteInputKeys: List<String>,
    val handle: ReplyActionHandle,
    val registeredAtMillis: Long,
) {
    override fun toString(): String =
        "LiveReplyAction(key=#${NotificationHashes.short(notificationKey)}, post=$postTimeMillis, platform=$platform, " +
            "conversation=#${NotificationHashes.short(externalConversationId)}, action=$actionIndex, inputs=${remoteInputKeys.size})"
}

/**
 * Bounded in-memory map of live reply actions, keyed by notification key (an update of the same
 * notification replaces its entry). Least-recently-registered entries are evicted beyond
 * [capacity]. An entry older than [maxAgeMillis] counts as stale: apps commonly cancel or
 * replace the PendingIntent, and a reply into an old notification may land in the wrong place.
 */
class LiveReplyActions(
    private val capacity: Int = 64,
    private val maxAgeMillis: Long = 6L * 60 * 60 * 1000,
) {
    init {
        require(capacity >= 1) { "capacity must be >= 1" }
        require(maxAgeMillis > 0) { "maxAgeMillis must be > 0" }
    }

    private val byKey = LinkedHashMap<String, LiveReplyAction>()

    @Synchronized
    fun put(action: LiveReplyAction) {
        byKey.remove(action.notificationKey)
        byKey[action.notificationKey] = action
        while (byKey.size > capacity) byKey.remove(byKey.keys.first())
    }

    @Synchronized
    fun remove(notificationKey: String): LiveReplyAction? = byKey.remove(notificationKey)

    @Synchronized
    fun clear() = byKey.clear()

    @Synchronized
    fun size(): Int = byKey.size

    /** Most recently posted live action for this platform and conversation, stale or not. */
    @Synchronized
    fun latestFor(platform: String, externalConversationId: String): LiveReplyAction? =
        byKey.values.filter { it.platform == platform && it.externalConversationId == externalConversationId }
            .maxByOrNull { it.postTimeMillis }

    fun isStale(action: LiveReplyAction, nowMillis: Long): Boolean = nowMillis - action.registeredAtMillis > maxAgeMillis
}
