package com.wingman.integrations.notifications

import java.security.MessageDigest

/*
 * WING-017: generic notification-listener foundation for the notification-assisted path of the
 * Tinder (WING-027), Bumble (WING-028) and Hinge (WING-029) connectors.
 *
 * This top-level package is PURE KOTLIN: no android.* imports. The thin platform adapters live in
 * `integrations/notifications/android/`. They copy what they need out of a StatusBarNotification
 * into a [NotificationSnapshot] and hand it to [NotificationListenerCore].
 *
 * Privacy: notification titles, texts, sender names and reply texts are private. Nothing in this
 * package logs them, and `toString()` of every type that carries them redacts them. The only log
 * output is through [NotificationLog], which receives event names, enums, counts and hashes.
 */

/** Version of this connector implementation, published in `connector_capabilities.v1`. */
const val NOTIFICATION_CONNECTOR_VERSION: String = "0.1.0"

/** Wall clock, injectable for deterministic tests. */
fun interface NotificationClock {
    fun nowMillis(): Long

    companion object {
        val SYSTEM = NotificationClock { System.currentTimeMillis() }
    }
}

/**
 * Structured, redacted log sink. Implementations receive an event name and fields that are safe
 * to log: enums, counts, platform keys, hashed IDs. Callers in this package NEVER pass notification
 * text, titles, sender labels or reply text; `NotificationPrivacyTest` checks that with a sentinel.
 */
fun interface NotificationLog {
    fun log(event: String, fields: Map<String, String>)

    companion object {
        val NONE = NotificationLog { _, _ -> }
    }
}

/** Hashing helpers. Hashes are safe to log and to use as keys; the inputs are not. */
object NotificationHashes {
    fun sha256Hex(vararg parts: String?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach {
            // Length-prefix every part (and mark null) so ("ab","c") and ("a","bc") never collide.
            val bytes = (it ?: "").toByteArray(Charsets.UTF_8)
            digest.update((if (it == null) "n" else bytes.size.toString()).toByteArray(Charsets.UTF_8))
            digest.update(':'.code.toByte())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Short hash for log fields (16 hex chars). */
    fun short(value: String?): String = sha256Hex(value).take(16)
}

/** One message inside a `Notification.MessagingStyle` notification (`EXTRA_MESSAGES`). */
class SnapshotMessage(
    /** `MessagingStyle.Message.getText()`; null when the app put none. Private. */
    val text: String?,
    /** `getTimestamp()` in epoch millis. */
    val timestampMillis: Long,
    /** Sender display name (`getSenderPerson().name` or legacy `getSender()`); null = the device user. Private. */
    val senderName: String?,
    /** `Person.getKey()` when the app set one: a candidate stable sender identity. */
    val senderKey: String? = null,
    /** True when the message carries data (an image etc.) instead of or besides text. */
    val hasData: Boolean = false,
) {
    override fun toString(): String =
        "SnapshotMessage(text=${redactedLength(text)}, ts=$timestampMillis, sender=${redactedLength(senderName)}, " +
            "senderKey=${senderKey?.let { "#" + NotificationHashes.short(it) }}, hasData=$hasData)"
}

/** One `RemoteInput` of a notification action. Only structure: the key and the input mode. */
data class SnapshotRemoteInput(
    val resultKey: String,
    val allowFreeFormInput: Boolean,
    val choiceCount: Int = 0,
)

/**
 * One notification action. [title] is the app's button label (e.g. "Reply"), which is UI text,
 * not message content; the inspector still scrubs names out of it before logging.
 */
data class SnapshotAction(
    /** Index in `Notification.actions`; how the adapter finds the PendingIntent again. */
    val index: Int,
    val title: String?,
    val remoteInputs: List<SnapshotRemoteInput> = emptyList(),
    /** `Notification.Action.getSemanticAction()` (API 28+); 1 = SEMANTIC_ACTION_REPLY. */
    val semanticAction: Int = SEMANTIC_ACTION_NONE,
    /** `isContextual()` (API 29+): system-generated smart action, never used for replies. */
    val isContextual: Boolean = false,
) {
    /** A free-form text RemoteInput: the only kind this foundation will ever fill. */
    val hasFreeFormRemoteInput: Boolean
        get() = remoteInputs.any { it.allowFreeFormInput && it.resultKey.isNotBlank() }

    override fun toString(): String =
        "SnapshotAction(index=$index, title=${redactedLength(title)}, remoteInputs=$remoteInputs, " +
            "semantic=$semanticAction, contextual=$isContextual)"

    companion object {
        const val SEMANTIC_ACTION_NONE = 0
        const val SEMANTIC_ACTION_REPLY = 1
    }
}

/**
 * Pure-Kotlin copy of what a plugin may look at in one posted notification
 * (`StatusBarNotification` + `Notification.extras`). The adapter builds it; plugins parse it.
 *
 * All text fields are private and redacted by [toString].
 */
class NotificationSnapshot(
    /** `StatusBarNotification.getKey()`: stable for one notification across its updates. */
    val key: String,
    val packageName: String,
    /** `StatusBarNotification.getPostTime()`: changes on every post or update. */
    val postTimeMillis: Long,
    /** `Notification.when`; 0 when the app did not set it. */
    val whenMillis: Long,
    /** `EXTRA_TITLE`. */
    val title: String?,
    /** `EXTRA_TEXT`. */
    val text: String?,
    /** `EXTRA_BIG_TEXT`. */
    val bigText: String? = null,
    /** `EXTRA_SUB_TEXT`. */
    val subText: String? = null,
    /** `EXTRA_CONVERSATION_TITLE`. */
    val conversationTitle: String? = null,
    /** `EXTRA_IS_GROUP_CONVERSATION` (API 28+). */
    val isGroupConversation: Boolean = false,
    /** `EXTRA_MESSAGES`, oldest first. */
    val messages: List<SnapshotMessage> = emptyList(),
    /** `Notification.getShortcutId()`: conversation shortcut; a candidate stable conversation ID. */
    val shortcutId: String? = null,
    /** `Notification.getGroup()`. */
    val groupKey: String? = null,
    /** `FLAG_GROUP_SUMMARY`. */
    val isGroupSummary: Boolean = false,
    /** `Notification.category`, e.g. `msg`, `social`, `promo`. */
    val category: String? = null,
    /** `Notification.getChannelId()`. */
    val channelId: String? = null,
    /** `EXTRA_TEMPLATE`, e.g. `android.app.Notification$MessagingStyle`. */
    val template: String? = null,
    /** True when the app posted a `publicVersion` (its own lock-screen redaction). Informational. */
    val hasPublicVersion: Boolean = false,
    val actions: List<SnapshotAction> = emptyList(),
) {
    /** Hash of everything that is content (titles, texts, messages). Part of the dedup key. */
    val contentHash: String by lazy {
        NotificationHashes.sha256Hex(
            title, text, bigText, subText, conversationTitle,
            *messages.flatMap { listOf(it.text, it.timestampMillis.toString(), it.senderName, it.senderKey) }.toTypedArray(),
        )
    }

    /** The action a plugin would reply through by default: free-form RemoteInput, not contextual, REPLY first. */
    fun defaultReplyAction(): SnapshotAction? {
        val candidates = actions.filter { it.hasFreeFormRemoteInput && !it.isContextual }
        return candidates.firstOrNull { it.semanticAction == SnapshotAction.SEMANTIC_ACTION_REPLY } ?: candidates.firstOrNull()
    }

    override fun toString(): String =
        "NotificationSnapshot(key=#${NotificationHashes.short(key)}, pkg=$packageName, post=$postTimeMillis, " +
            "title=${redactedLength(title)}, text=${redactedLength(text)}, messages=${messages.size}, " +
            "summary=$isGroupSummary, actions=${actions.size})"
}

/**
 * Who posted the notification, as the adapter could establish it. [signingCertSha256] holds the
 * lowercase hex SHA-256 digests of the posting package's signing certificates, or null when they
 * could not be read (package not visible, API limits). It is evidence, never private text.
 */
data class NotificationSource(
    val packageName: String,
    val signingCertSha256: Set<String>? = null,
)

/** What a notification is about. Plugins must keep likes, matches and messages apart (WING-029). */
enum class NotificationEventKind {
    /** An incoming chat message in an existing conversation. The only kind a reply may target. */
    MESSAGE,
    MATCH,
    LIKE,
    /** A comment on a profile item (Hinge) that is not yet a chat. */
    COMMENT,
    /** The conversation or match is about to expire (Bumble). */
    EXPIRY,
    SYSTEM,
    /** Anything the plugin could not classify. Kept, never treated as a message. */
    UNKNOWN,
}

/**
 * How sure the plugin is about WHO the event is from.
 * - [LINKED]: a stable platform identifier (conversation ID, shortcut ID or Person key) ties it to
 *   one conversation.
 * - [UNLINKED]: only a display label; the user must link it. Never auto-merged with another
 *   conversation that has the same label.
 * - [AMBIGUOUS]: the plugin saw signs that the label maps to more than one conversation.
 */
enum class NotificationIdentity { LINKED, UNLINKED, AMBIGUOUS }

/**
 * One event a plugin extracted from a snapshot. Private fields are redacted by [toString].
 *
 * @property externalConversationId stable platform conversation ID, when the notification
 *   exposes one (shortcut ID, a deep-link thread ID). Required for [NotificationIdentity.LINKED].
 * @property externalMessageId platform message ID when exposed; otherwise the core derives one.
 * @property body null when the content is hidden or absent.
 * @property truncated the app shortened the text (ellipsis, "…and 3 more").
 * @property contentHidden the app replaced the content with a generic text ("You have a new message").
 * @property occurredAtMillis the source timestamp (message time, else `when`, else post time).
 */
class ParsedNotificationEvent(
    val kind: NotificationEventKind,
    val externalConversationId: String?,
    val externalMessageId: String? = null,
    val senderLabel: String?,
    val body: String?,
    val truncated: Boolean,
    val contentHidden: Boolean,
    val occurredAtMillis: Long,
    val identity: NotificationIdentity,
) {
    init {
        require(identity != NotificationIdentity.LINKED || !externalConversationId.isNullOrBlank()) {
            "a LINKED event needs an externalConversationId"
        }
        require(externalConversationId == null || externalConversationId.length in 1..200) { "externalConversationId length" }
        require(externalMessageId == null || externalMessageId.length in 1..200) { "externalMessageId length" }
        require(body == null || body.length <= 20_000) { "body exceeds the contract's 20000 chars" }
        require(senderLabel == null || senderLabel.length <= 200) { "senderLabel exceeds 200 chars" }
    }

    override fun toString(): String =
        "ParsedNotificationEvent(kind=$kind, conversation=${externalConversationId?.let { "#" + NotificationHashes.short(it) }}, " +
            "sender=${redactedLength(senderLabel)}, body=${redactedLength(body)}, truncated=$truncated, " +
            "hidden=$contentHidden, at=$occurredAtMillis, identity=$identity)"
}

internal fun redactedLength(value: String?): String = if (value == null) "null" else "<redacted ${value.length} chars>"
