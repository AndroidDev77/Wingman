package com.wingman.integrations.bumble

import com.wingman.integrations.notifications.NotificationAppPlugin
import com.wingman.integrations.notifications.NotificationEventKind
import com.wingman.integrations.notifications.NotificationIdentity
import com.wingman.integrations.notifications.NotificationParsing
import com.wingman.integrations.notifications.NotificationSnapshot
import com.wingman.integrations.notifications.ParsedNotificationEvent
import com.wingman.integrations.notifications.SnapshotAction
import java.util.Locale

/*
 * WING-028: Bumble, NOTIFICATION-ASSISTED ONLY (D-023: the private-API path is BLOCKED).
 *
 * Pure Kotlin, no android.* imports. Every notification shape handled here is a SYNTHETIC
 * assumption: nobody has inspected a real Bumble notification yet (docs/integrations/bumble.md,
 * device steps N1-N13). Bumble's expiry and first-move rules are only what its notifications say:
 * Wingman keeps no timer of its own and never invents a first-message opportunity.
 */

/** What a Bumble notification is about. Only [MESSAGE] is a chat; everything else is a notice. */
enum class BumbleNotificationKind(val eventKind: NotificationEventKind) {
    MESSAGE(NotificationEventKind.MESSAGE),
    MATCH(NotificationEventKind.MATCH),
    /** "Your match expires", "time is running out", extend reminders. Never a chat. */
    EXPIRY(NotificationEventKind.EXPIRY),
    /**
     * Someone reacted to a message or photo. The contract has no reaction kind; it is reported as
     * `like_comment`, the closest non-chat kind, and is never a reply target.
     */
    REACTION(NotificationEventKind.LIKE),
    /** Beeline / admirers: someone liked the user. */
    LIKE(NotificationEventKind.LIKE),
    SYSTEM(NotificationEventKind.SYSTEM),
    UNKNOWN(NotificationEventKind.UNKNOWN),
}

/**
 * The Bumble [NotificationAppPlugin].
 *
 * - **Package** `com.bumble.app` (from the Play listing; UNVERIFIED until device step N1). Exact
 *   match only; lookalikes are rejected by the default authenticity check.
 * - **Signing certificate**: not pinned. TODO(WING-028 device run, step N10): put the recorded
 *   SHA-256 digests into [PINNED_CERT_SHA256].
 * - **Identity**: LINKED only with the shortcut ID or the single sender `Person` key of a
 *   one-to-one MessagingStyle conversation; otherwise UNLINKED (or AMBIGUOUS), never merged by name.
 * - **Kinds**: chat messages by structure first; then expiry (checked before match, because "your
 *   match expires" mentions a match), reactions, likes, matches (including "make the first move"
 *   prompts, which are not a chat), system, UNKNOWN.
 */
class BumbleNotificationPlugin(
    override val trustedSigningCertSha256: Set<String> = PINNED_CERT_SHA256,
) : NotificationAppPlugin {

    override val platform: String = PLATFORM
    override val packageNames: Set<String> = setOf(PACKAGE)

    fun classify(s: NotificationSnapshot): BumbleNotificationKind {
        val headline = lower(s.title, s.conversationTitle, s.channelId)
        if (NotificationParsing.incomingMessages(s).isNotEmpty()) {
            // A MessagingStyle notification is a chat unless its HEADLINE says expiry or reaction;
            // message bodies are never keyword-matched.
            return when {
                EXPIRY.containsMatchIn(headline) -> BumbleNotificationKind.EXPIRY
                REACTION.containsMatchIn(headline) -> BumbleNotificationKind.REACTION
                else -> BumbleNotificationKind.MESSAGE
            }
        }
        keywordKind(headline)?.let { return it }
        if (s.category == CATEGORY_MESSAGE) return BumbleNotificationKind.MESSAGE
        val text = lower(s.bigText, s.text)
        if (HIDDEN_MESSAGE.matches(text.trim())) return BumbleNotificationKind.MESSAGE
        keywordKind(text)?.let { return it }
        if (s.category in SYSTEM_CATEGORIES) return BumbleNotificationKind.SYSTEM
        return BumbleNotificationKind.UNKNOWN
    }

    override fun parse(snapshot: NotificationSnapshot): List<ParsedNotificationEvent> {
        if (snapshot.isGroupSummary) return emptyList()
        return when (val kind = classify(snapshot)) {
            BumbleNotificationKind.MESSAGE -> messageEvents(snapshot)
            else -> noticeEvent(snapshot, kind)
        }
    }

    override fun replyAction(snapshot: NotificationSnapshot): SnapshotAction? =
        if (classify(snapshot) == BumbleNotificationKind.MESSAGE) snapshot.defaultReplyAction() else null

    private fun messageEvents(s: NotificationSnapshot): List<ParsedNotificationEvent> {
        val (conversation, identity) = conversationOf(s)
        val incoming = NotificationParsing.incomingMessages(s)
        if (incoming.isNotEmpty()) {
            return incoming.map { m ->
                val raw = m.text?.trim()
                val hidden = raw.isNullOrEmpty() || HIDDEN_MESSAGE.matches(raw.lowercase(Locale.ROOT))
                event(
                    NotificationEventKind.MESSAGE, conversation, identity,
                    label(m.senderName ?: s.conversationTitle ?: s.title), if (hidden) null else raw, hidden,
                    NotificationParsing.occurredAt(s, m),
                )
            }
        }
        val raw = (s.bigText?.takeIf { it.isNotBlank() } ?: s.text)?.trim()
        val hidden = raw.isNullOrEmpty() || HIDDEN_MESSAGE.matches(raw.lowercase(Locale.ROOT))
        return listOf(
            event(
                NotificationEventKind.MESSAGE, conversation, identity,
                label(s.conversationTitle ?: s.title), if (hidden) null else raw, hidden, NotificationParsing.occurredAt(s),
            ),
        )
    }

    /** Expiry, match, reaction, like, system and unknown notices: never LINKED, never replied to. */
    private fun noticeEvent(s: NotificationSnapshot, kind: BumbleNotificationKind): List<ParsedNotificationEvent> {
        val body = (s.bigText?.takeIf { it.isNotBlank() } ?: s.text?.takeIf { it.isNotBlank() } ?: s.title)?.trim()
            ?.takeIf { it.isNotEmpty() } ?: return emptyList()
        return listOf(
            event(kind.eventKind, conversationOf(s).first, NotificationIdentity.UNLINKED, null, body, false, NotificationParsing.occurredAt(s)),
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
        externalMessageId = null, // Bumble exposes none in a notification; the core derives one.
        senderLabel = sender?.take(MAX_LABEL),
        body = body?.take(MAX_BODY),
        truncated = !hidden && NotificationParsing.looksTruncated(body),
        contentHidden = hidden,
        occurredAtMillis = at,
        identity = if (identity == NotificationIdentity.LINKED && conversation == null) NotificationIdentity.UNLINKED else identity,
    )

    private fun label(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() && !it.equals(APP_NAME, ignoreCase = true) }

    companion object {
        const val PLATFORM = "bumble"

        /** From the Play Store listing. UNVERIFIED until device step N1 confirms it. */
        const val PACKAGE = "com.bumble.app"
        const val APP_NAME = "Bumble"

        /** TODO(WING-028, device step N10): the digests recorded on a device. Empty = not pinned. */
        val PINNED_CERT_SHA256: Set<String> = emptySet()

        const val CATEGORY_MESSAGE = "msg"
        val SYSTEM_CATEGORIES = setOf("promo", "recommendation", "status", "sys", "service", "reminder", "event", "social")

        private const val MAX_LABEL = 200
        private const val MAX_BODY = 20_000
        private const val MAX_RAW_ID = 180

        private val EXPIRY = Regex("""\bexpir(e|es|ed|ing|y)\b|\brunning out\b|\blast chance\b|\bhours? left\b|\bextend\b""")
        private val REACTION = Regex("""\breact(ed|ion|ions)?\b|\bliked your (message|photo)\b""")
        private val LIKE = Regex("""\blik(e|es|ed) you\b|\bbeeline\b|\badmirers?\b|\bnew likes?\b""")
        private val MATCH = Regex("""\bmatch(ed|es)?\b|\bit'?s a match\b|\bfirst move\b|\byour move\b|\bnew connection\b""")

        /** Generic placeholder texts shown instead of the message. */
        val HIDDEN_MESSAGE = Regex(
            """^((you (have|got|received) )?(a )?new message|sent you a (new )?message|\d+ new messages?|message hidden|contents? hidden)[.!]?$""",
        )

        private fun lower(vararg parts: String?): String = parts.filterNotNull().joinToString(" ").lowercase(Locale.ROOT)

        private fun keywordKind(text: String): BumbleNotificationKind? = when {
            text.isBlank() -> null
            EXPIRY.containsMatchIn(text) -> BumbleNotificationKind.EXPIRY
            REACTION.containsMatchIn(text) -> BumbleNotificationKind.REACTION
            LIKE.containsMatchIn(text) -> BumbleNotificationKind.LIKE
            MATCH.containsMatchIn(text) -> BumbleNotificationKind.MATCH
            else -> null
        }

        /** Same identity rule as Tinder: shortcut ID, else one sender `Person` key; never a name. */
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
