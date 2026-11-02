package com.wingman.integrations.hinge.notifications

import com.wingman.integrations.notifications.NotificationAppPlugin
import com.wingman.integrations.notifications.NotificationEventKind
import com.wingman.integrations.notifications.NotificationIdentity
import com.wingman.integrations.notifications.NotificationParsing
import com.wingman.integrations.notifications.NotificationSnapshot
import com.wingman.integrations.notifications.ParsedNotificationEvent
import com.wingman.integrations.notifications.SnapshotAction

/**
 * WING-029 fallback: the WING-017 notification plugin for Hinge (`co.hinge.app`). It is the
 * notification-assisted path that ships regardless of the experimental connector (D-021 item 7);
 * it is NOT gated by the experimental flag and contains no unofficial-API code.
 *
 * **Every shape below is an assumption until the device run** (docs/integrations/notification_matrix.md,
 * dating_platform_matrix.md §8): no source documents what Hinge puts in its notifications. The
 * rules are deliberately conservative:
 *
 * - Likes, comments ("commented on", "liked your ... and said"), new matches, "Your Turn" style
 *   reminders and promotions are never [NotificationEventKind.MESSAGE] (ticket WING-029: they must
 *   not be conflated), so nothing ever auto-replies to them. Like/comment wording is checked
 *   BEFORE anything else, because a comment notification may use the message category.
 * - A chat message is [NotificationIdentity.LINKED] only when the notification carries a stable
 *   conversation id (the conversation shortcut id) AND uses `MessagingStyle` with a sender; a
 *   title-only label ("Alex") is [NotificationIdentity.UNLINKED] and never merged with anyone.
 * - A generic "new message" text means the content is hidden.
 * - A reply goes through a free-form `RemoteInput` action only, and only on a MessagingStyle chat
 *   notification ([replyAction]); without one there is no send path. A submitted reply stays
 *   UNVERIFIED (I-033: it ends as `lease_expired`, outcome unknown).
 * - Pure and total: never throws, never logs. Group summaries yield nothing (their children carry the content).
 */
class HingeNotificationPlugin : NotificationAppPlugin {
    override val platform: String = "hinge"
    override val packageNames: Set<String> = setOf(PACKAGE)

    override fun parse(snapshot: NotificationSnapshot): List<ParsedNotificationEvent> = runCatching { parseUnsafe(snapshot) }.getOrDefault(emptyList())

    private fun parseUnsafe(s: NotificationSnapshot): List<ParsedNotificationEvent> {
        if (s.isGroupSummary) return emptyList()
        val title = s.title?.trim().orEmpty()
        val text = (s.bigText ?: s.text)?.trim().orEmpty()
        val all = "$title\n$text"
        val at = NotificationParsing.occurredAt(s)

        notice(classifyNotice(all, s.category))?.let { kind ->
            return listOf(event(kind, null, labelFrom(title, kind), text.ifEmpty { null }, NotificationParsing.looksTruncated(text), false, at, NotificationIdentity.UNLINKED))
        }

        val incoming = NotificationParsing.incomingMessages(s)
        val conversation = s.shortcutId?.takeIf { it.isNotBlank() && it.length <= 200 }
        if (incoming.isNotEmpty()) {
            if (s.isGroupConversation) {
                // Hinge chats are one-to-one; a group shape is unexpected: keep it, never link it.
                return incoming.map { m -> event(NotificationEventKind.UNKNOWN, null, m.senderName, m.text, NotificationParsing.looksTruncated(m.text), m.text == null, NotificationParsing.occurredAt(s, m), NotificationIdentity.UNLINKED) }
            }
            val senders = incoming.mapNotNull { it.senderKey ?: it.senderName }.toSet()
            val identity = when {
                senders.size > 1 -> NotificationIdentity.AMBIGUOUS
                conversation != null -> NotificationIdentity.LINKED
                else -> NotificationIdentity.UNLINKED
            }
            return incoming.map { m ->
                val hidden = m.text.isNullOrBlank() || isGenericHidden(m.text)
                event(
                    NotificationEventKind.MESSAGE,
                    if (identity == NotificationIdentity.LINKED) conversation else null,
                    m.senderName,
                    if (hidden) null else m.text,
                    !hidden && NotificationParsing.looksTruncated(m.text),
                    hidden,
                    NotificationParsing.occurredAt(s, m),
                    identity,
                )
            }
        }

        if (title.isEmpty() && text.isEmpty()) return emptyList()
        if (isGenericHidden(text) || isGenericHidden(title)) {
            return listOf(event(NotificationEventKind.MESSAGE, null, null, null, false, true, at, NotificationIdentity.UNLINKED))
        }
        if (s.category == CATEGORY_MESSAGE && title.isNotEmpty() && text.isNotEmpty()) {
            // A plain (non-MessagingStyle) message: the title is only a label.
            return listOf(event(NotificationEventKind.MESSAGE, null, title, text, NotificationParsing.looksTruncated(text), false, at, NotificationIdentity.UNLINKED))
        }
        return listOf(event(NotificationEventKind.UNKNOWN, null, null, text.ifEmpty { null }, NotificationParsing.looksTruncated(text), false, at, NotificationIdentity.UNLINKED))
    }

    /** Replies only through a MessagingStyle chat notification that is not a like, comment or match. */
    override fun replyAction(snapshot: NotificationSnapshot): SnapshotAction? {
        if (snapshot.isGroupSummary || snapshot.isGroupConversation) return null
        if (NotificationParsing.incomingMessages(snapshot).isEmpty()) return null
        if (classifyNotice("${snapshot.title.orEmpty()}\n${(snapshot.bigText ?: snapshot.text).orEmpty()}", snapshot.category) != null) return null
        return snapshot.defaultReplyAction()
    }

    private enum class Notice { LIKE, COMMENT, MATCH, SYSTEM }

    private fun notice(n: Notice?): NotificationEventKind? = when (n) {
        Notice.LIKE -> NotificationEventKind.LIKE
        Notice.COMMENT -> NotificationEventKind.COMMENT
        Notice.MATCH -> NotificationEventKind.MATCH
        Notice.SYSTEM -> NotificationEventKind.SYSTEM
        null -> null
    }

    private fun classifyNotice(all: String, category: String?): Notice? = when {
        COMMENT.containsMatchIn(all) -> Notice.COMMENT
        LIKE.containsMatchIn(all) -> Notice.LIKE
        MATCH.containsMatchIn(all) -> Notice.MATCH
        SYSTEM.containsMatchIn(all) || category == "promo" || category == "recommendation" -> Notice.SYSTEM
        else -> null
    }

    /** Only the leading name of "Alex liked your photo" is kept as a label (never an identity). */
    private fun labelFrom(title: String, kind: NotificationEventKind): String? =
        if (kind == NotificationEventKind.SYSTEM) null else title.takeIf { it.isNotEmpty() && it.length <= 200 }

    private fun isGenericHidden(t: String?): Boolean = t != null && HIDDEN.matches(t.trim())

    private fun event(
        kind: NotificationEventKind, conversation: String?, label: String?, body: String?, truncated: Boolean,
        hidden: Boolean, at: Long, identity: NotificationIdentity,
    ) = ParsedNotificationEvent(
        kind = kind,
        externalConversationId = conversation,
        senderLabel = label?.take(200),
        body = if (hidden) null else body?.take(20_000),
        truncated = truncated,
        contentHidden = hidden,
        occurredAtMillis = at,
        identity = if (identity == NotificationIdentity.LINKED && conversation == null) NotificationIdentity.UNLINKED else identity,
    )

    companion object {
        const val PACKAGE = "co.hinge.app"
        const val CATEGORY_MESSAGE = "msg"
        private val COMMENT = Regex("\\bcommented\\b|\\bcomment(ed)? on\\b|\\band said\\b|replied to your (prompt|photo|video|answer)", RegexOption.IGNORE_CASE)
        private val LIKE = Regex("\\blikes? you\\b|\\bliked your\\b|\\bliked you\\b|\\bnew likes?\\b|\\bsent you a rose\\b|\\brose\\b", RegexOption.IGNORE_CASE)
        private val MATCH = Regex("\\bnew match\\b|\\bit'?s a match\\b|\\byou (have )?matched\\b|\\bmatched with you\\b", RegexOption.IGNORE_CASE)
        private val SYSTEM = Regex("\\byour turn\\b|\\bstandouts?\\b|\\bmost compatible\\b|\\bhinge\\+|\\bhingex\\b|\\bboost\\b|\\bprofile (is )?(under review|paused)\\b|\\bverify\\b", RegexOption.IGNORE_CASE)
        private val HIDDEN = Regex("^(you (have|got) a new message|new message|sent you a message|\\d+ new messages?)[.!]?$", RegexOption.IGNORE_CASE)
    }
}
