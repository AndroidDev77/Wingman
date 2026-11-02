package com.wingman.integrations.tinder

import com.wingman.integrations.notifications.NotificationAppPlugin
import com.wingman.integrations.notifications.NotificationEventKind
import com.wingman.integrations.notifications.NotificationIdentity
import com.wingman.integrations.notifications.NotificationParsing
import com.wingman.integrations.notifications.NotificationSnapshot
import com.wingman.integrations.notifications.ParsedNotificationEvent
import com.wingman.integrations.notifications.SnapshotAction
import java.util.Locale

/*
 * WING-027: Tinder, NOTIFICATION-ASSISTED ONLY (D-023: the private-API path is BLOCKED).
 *
 * Pure Kotlin, no android.* imports. Every notification shape handled here is a SYNTHETIC
 * assumption: nobody has inspected a real Tinder notification yet (docs/integrations/tinder.md,
 * device steps N1-N13). Until the device run fills the matrix, read is UNVERIFIED and send is
 * UNSUPPORTED, so every Wingman draft for Tinder becomes an assisted delivery the user sends by hand.
 */

/** What a Tinder notification is about. Only [MESSAGE] is a chat; everything else is a notice. */
enum class TinderNotificationKind(val eventKind: NotificationEventKind) {
    MESSAGE(NotificationEventKind.MESSAGE),
    MATCH(NotificationEventKind.MATCH),
    LIKE(NotificationEventKind.LIKE),
    /** Reported as `like_comment` on the wire, like any like. Never a chat. */
    SUPER_LIKE(NotificationEventKind.LIKE),
    SYSTEM(NotificationEventKind.SYSTEM),
    UNKNOWN(NotificationEventKind.UNKNOWN),
}

/**
 * The Tinder [NotificationAppPlugin].
 *
 * - **Package** `com.tinder` (from the Play listing; UNVERIFIED until device step N1). Exact match
 *   only: `com.tinder.fake`, `com.tinderr`, `Com.Tinder` are rejected by the default authenticity check.
 * - **Signing certificate**: not pinned. TODO(WING-027 device run, step N10): put the SHA-256
 *   digests the debug inspector prints (`cert_sha256`) into [PINNED_CERT_SHA256].
 * - **Identity**: LINKED only with a stable conversation ID: the notification's shortcut ID, or the
 *   sender `Person` key of a one-to-one MessagingStyle conversation. A display name alone is never an
 *   identity (UNLINKED); several sender keys or names in one notification are AMBIGUOUS.
 * - **Kinds**: chat messages (structure first: MessagingStyle with incoming messages, or category
 *   `msg`), then matches, likes and super likes, system notices, and UNKNOWN. Keyword rules look at
 *   the title and channel first, the text last, and never at a MessagingStyle message body, so a
 *   match alert is never treated as a chat and a chat saying "match" is not reclassified.
 * - **Partial content**: an ellipsis marks `truncated`; a generic "new message" text marks
 *   `contentHidden` with no body.
 * - **Reply action**: only on a chat-message notification (the core further requires LINKED).
 */
class TinderNotificationPlugin(
    override val trustedSigningCertSha256: Set<String> = PINNED_CERT_SHA256,
) : NotificationAppPlugin {

    override val platform: String = PLATFORM
    override val packageNames: Set<String> = setOf(PACKAGE)

    fun classify(s: NotificationSnapshot): TinderNotificationKind {
        if (NotificationParsing.incomingMessages(s).isNotEmpty()) return TinderNotificationKind.MESSAGE
        val headline = lower(s.title, s.conversationTitle, s.channelId)
        keywordKind(headline)?.let { return it }
        if (s.category == CATEGORY_MESSAGE) return TinderNotificationKind.MESSAGE
        val text = lower(s.bigText, s.text)
        if (HIDDEN_MESSAGE.matches(text.trim())) return TinderNotificationKind.MESSAGE
        keywordKind(text)?.let { return it }
        if (s.category in SYSTEM_CATEGORIES) return TinderNotificationKind.SYSTEM
        return TinderNotificationKind.UNKNOWN
    }

    override fun parse(snapshot: NotificationSnapshot): List<ParsedNotificationEvent> {
        if (snapshot.isGroupSummary) return emptyList()
        return when (val kind = classify(snapshot)) {
            TinderNotificationKind.MESSAGE -> messageEvents(snapshot)
            else -> noticeEvent(snapshot, kind)
        }
    }

    override fun replyAction(snapshot: NotificationSnapshot): SnapshotAction? =
        if (classify(snapshot) == TinderNotificationKind.MESSAGE) snapshot.defaultReplyAction() else null

    private fun messageEvents(s: NotificationSnapshot): List<ParsedNotificationEvent> {
        val (conversation, identity) = conversationOf(s)
        val incoming = NotificationParsing.incomingMessages(s)
        if (incoming.isNotEmpty()) {
            return incoming.map { m ->
                val raw = m.text?.trim()
                val hidden = raw.isNullOrEmpty() || HIDDEN_MESSAGE.matches(raw.lowercase(Locale.ROOT))
                event(
                    kind = NotificationEventKind.MESSAGE, conversation = conversation, identity = identity,
                    sender = label(m.senderName ?: s.conversationTitle ?: s.title),
                    body = if (hidden) null else raw, hidden = hidden,
                    at = NotificationParsing.occurredAt(s, m),
                )
            }
        }
        val raw = (s.bigText?.takeIf { it.isNotBlank() } ?: s.text)?.trim()
        val hidden = raw.isNullOrEmpty() || HIDDEN_MESSAGE.matches(raw.lowercase(Locale.ROOT))
        return listOf(
            event(
                kind = NotificationEventKind.MESSAGE, conversation = conversation, identity = identity,
                sender = label(s.conversationTitle ?: s.title), body = if (hidden) null else raw, hidden = hidden,
                at = NotificationParsing.occurredAt(s),
            ),
        )
    }

    /** Matches, likes, system and unknown notices: never LINKED, never a reply target. */
    private fun noticeEvent(s: NotificationSnapshot, kind: TinderNotificationKind): List<ParsedNotificationEvent> {
        val body = (s.bigText?.takeIf { it.isNotBlank() } ?: s.text?.takeIf { it.isNotBlank() } ?: s.title)?.trim()
            ?.takeIf { it.isNotEmpty() } ?: return emptyList()
        return listOf(
            event(
                kind = kind.eventKind, conversation = conversationOf(s).first, identity = NotificationIdentity.UNLINKED,
                sender = null, body = body, hidden = false, at = NotificationParsing.occurredAt(s),
            ),
        )
    }

    private fun event(
        kind: NotificationEventKind,
        conversation: String?,
        identity: NotificationIdentity,
        sender: String?,
        body: String?,
        hidden: Boolean,
        at: Long,
    ) = ParsedNotificationEvent(
        kind = kind,
        externalConversationId = conversation,
        externalMessageId = null, // Tinder exposes none in a notification; the core derives one.
        senderLabel = sender?.take(MAX_LABEL),
        body = body?.take(MAX_BODY),
        truncated = !hidden && NotificationParsing.looksTruncated(body),
        contentHidden = hidden,
        occurredAtMillis = at,
        identity = if (identity == NotificationIdentity.LINKED && conversation == null) NotificationIdentity.UNLINKED else identity,
    )

    private fun label(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() && !it.equals(APP_NAME, ignoreCase = true) }

    companion object {
        const val PLATFORM = "tinder"

        /** From the Play Store listing. UNVERIFIED until device step N1 confirms it. */
        const val PACKAGE = "com.tinder"
        const val APP_NAME = "Tinder"

        /** TODO(WING-027, device step N10): the digests recorded on a device. Empty = not pinned. */
        val PINNED_CERT_SHA256: Set<String> = emptySet()

        const val CATEGORY_MESSAGE = "msg"
        val SYSTEM_CATEGORIES = setOf("promo", "recommendation", "status", "sys", "service", "reminder", "event", "social")

        private const val MAX_LABEL = 200
        private const val MAX_BODY = 20_000
        private const val MAX_RAW_ID = 180

        private val SUPER_LIKE = Regex("""\bsuper ?lik(e|es|ed)\b""")
        private val LIKE = Regex("""\blik(e|es|ed) you\b|\bnew likes?\b|\blikes? (are )?waiting\b""")
        private val MATCH = Regex("""\bmatch(ed|es)?\b|\bit'?s a match\b""")

        /** Generic placeholder texts an app shows instead of the message (lock screen, privacy setting). */
        val HIDDEN_MESSAGE = Regex(
            """^((you (have|got|received) )?(a )?new message|sent you a (new )?message|\d+ new messages?|message hidden|contents? hidden)[.!]?$""",
        )

        private fun lower(vararg parts: String?): String = parts.filterNotNull().joinToString(" ").lowercase(Locale.ROOT)

        private fun keywordKind(text: String): TinderNotificationKind? = when {
            text.isBlank() -> null
            SUPER_LIKE.containsMatchIn(text) -> TinderNotificationKind.SUPER_LIKE
            LIKE.containsMatchIn(text) -> TinderNotificationKind.LIKE
            MATCH.containsMatchIn(text) -> TinderNotificationKind.MATCH
            else -> null
        }

        /**
         * Stable conversation identity, or none. Shortcut ID first; else the single sender `Person`
         * key of a one-to-one MessagingStyle conversation. Never a display name.
         */
        fun conversationOf(s: NotificationSnapshot): Pair<String?, NotificationIdentity> {
            s.shortcutId?.trim()?.takeIf { it.isNotEmpty() }?.let { id ->
                return if (id.length <= MAX_RAW_ID) "shortcut:$id" to NotificationIdentity.LINKED else null to NotificationIdentity.UNLINKED
            }
            val incoming = NotificationParsing.incomingMessages(s)
            if (s.isGroupConversation) return null to NotificationIdentity.AMBIGUOUS
            val keys = incoming.map { it.senderKey?.trim().orEmpty() }
            val distinctKeys = keys.filter { it.isNotEmpty() }.distinct()
            val distinctNames = incoming.mapNotNull { it.senderName?.trim() }.distinct()
            return when {
                distinctKeys.size > 1 || distinctNames.size > 1 -> null to NotificationIdentity.AMBIGUOUS
                distinctKeys.size == 1 && keys.all { it.isNotEmpty() } && distinctKeys.single().length <= MAX_RAW_ID ->
                    "person:${distinctKeys.single()}" to NotificationIdentity.LINKED
                else -> null to NotificationIdentity.UNLINKED
            }
        }
    }
}
