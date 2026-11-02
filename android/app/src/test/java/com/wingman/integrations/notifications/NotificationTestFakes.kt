package com.wingman.integrations.notifications

/** Synthetic fixtures only: no real person's name or message. */
const val N_USER = "0192f3a0-0000-7000-8000-000000000001"
const val N_CONNECTION = "0192f3a0-0000-7000-8000-0000000000b1"
const val N_OUTBOUND = "0192f3a0-0000-7000-8000-000000000600"
const val N_INSTRUCTION = "0192f3a0-0000-7000-8000-000000000700"
val N_SCOPE = NotificationConnectionScope(N_USER, N_CONNECTION)

const val FAKE_PKG = "com.example.fakedating"
const val FAKE_PLATFORM = "fakedating"
const val GOOD_CERT = "aa11bb22cc33dd44ee55ff6600112233445566778899aabbccddeeff00112233"

class NFakeClock(var now: Long = 1_793_473_200_000L) : NotificationClock {
    override fun nowMillis(): Long = now
}

/** Records every log call so tests can scan for private text. */
class RecordingLog : NotificationLog {
    val lines = mutableListOf<String>()
    override fun log(event: String, fields: Map<String, String>) {
        lines += event + " " + fields.entries.joinToString(" ") { "${it.key}=${it.value}" }
    }
}

/** Counts fires; [result] decides what the platform reports. */
class FakeHandle(var result: FireResult = FireResult.Fired) : ReplyActionHandle {
    val fired = mutableListOf<String>()
    override fun fire(text: String): FireResult {
        fired += text
        return result
    }
}

/**
 * A fake dating-app plugin, shaped like what WING-027..029 will write:
 * - MessagingStyle with a shortcut ID -> LINKED MESSAGE events, one per incoming message.
 * - title "New match" -> MATCH; "liked you" in the text -> LIKE.
 * - text "You have a new message" -> MESSAGE with contentHidden, UNLINKED.
 * - plain title/text without shortcut -> UNLINKED MESSAGE.
 */
class FakeDatingPlugin(
    override val trustedSigningCertSha256: Set<String> = emptySet(),
    private val throwOnParse: Boolean = false,
) : NotificationAppPlugin {
    override val platform = FAKE_PLATFORM
    override val packageNames = setOf(FAKE_PKG)

    override fun parse(snapshot: NotificationSnapshot): List<ParsedNotificationEvent> {
        if (throwOnParse) error("boom")
        val text = snapshot.text
        if (snapshot.title == "New match") {
            return listOf(event(NotificationEventKind.MATCH, null, null, null, false, false, snapshot, NotificationIdentity.UNLINKED))
        }
        if (text != null && text.contains("liked you")) {
            return listOf(event(NotificationEventKind.LIKE, null, null, null, false, false, snapshot, NotificationIdentity.UNLINKED))
        }
        if (text == "You have a new message") {
            return listOf(event(NotificationEventKind.MESSAGE, null, snapshot.title, null, false, true, snapshot, NotificationIdentity.UNLINKED))
        }
        val incoming = NotificationParsing.incomingMessages(snapshot)
        if (incoming.isNotEmpty()) {
            val conv = snapshot.shortcutId
            return incoming.map { m ->
                ParsedNotificationEvent(
                    kind = NotificationEventKind.MESSAGE,
                    externalConversationId = conv,
                    senderLabel = m.senderName,
                    body = m.text,
                    truncated = NotificationParsing.looksTruncated(m.text),
                    contentHidden = m.text == null,
                    occurredAtMillis = NotificationParsing.occurredAt(snapshot, m),
                    identity = if (conv != null) NotificationIdentity.LINKED else NotificationIdentity.UNLINKED,
                )
            }
        }
        if (text == null && snapshot.title == null) return emptyList()
        return listOf(
            event(
                NotificationEventKind.MESSAGE, snapshot.shortcutId, snapshot.title, text,
                NotificationParsing.looksTruncated(text), text.isNullOrEmpty(), snapshot,
                if (snapshot.shortcutId != null) NotificationIdentity.LINKED else NotificationIdentity.UNLINKED,
            ),
        )
    }

    private fun event(
        kind: NotificationEventKind, conv: String?, sender: String?, body: String?, truncated: Boolean, hidden: Boolean,
        s: NotificationSnapshot, identity: NotificationIdentity,
    ) = ParsedNotificationEvent(kind, conv, null, sender, body, truncated, hidden, NotificationParsing.occurredAt(s), identity)
}

val REPLY_ACTION = SnapshotAction(
    index = 0, title = "Reply",
    remoteInputs = listOf(SnapshotRemoteInput("key_text_reply", allowFreeFormInput = true)),
    semanticAction = SnapshotAction.SEMANTIC_ACTION_REPLY,
)
val MARK_READ_ACTION = SnapshotAction(index = 1, title = "Mark as read")

fun chatSnapshot(
    key: String = "0|$FAKE_PKG|42|null|10123",
    pkg: String = FAKE_PKG,
    post: Long = 1_793_473_200_000L,
    shortcut: String? = "conv-abc",
    messages: List<SnapshotMessage> = listOf(SnapshotMessage("Hey! Free tonight?", 1_793_473_199_000L, "Sam")),
    actions: List<SnapshotAction> = listOf(REPLY_ACTION, MARK_READ_ACTION),
    summary: Boolean = false,
    title: String? = "Sam",
    text: String? = messages.lastOrNull()?.text,
) = NotificationSnapshot(
    key = key, packageName = pkg, postTimeMillis = post, whenMillis = post - 1_000,
    title = title, text = text, messages = messages, shortcutId = shortcut,
    groupKey = "chats", isGroupSummary = summary, category = "msg", channelId = "messages",
    template = "android.app.Notification\$MessagingStyle", actions = actions,
)

fun source(pkg: String = FAKE_PKG, certs: Set<String>? = null) = NotificationSource(pkg, certs)

class Harness(
    plugins: List<NotificationAppPlugin> = listOf(FakeDatingPlugin()),
    val store: NotificationStateStore = InMemoryNotificationStateStore(),
    val clock: NFakeClock = NFakeClock(),
    val log: RecordingLog = RecordingLog(),
    grantAccess: Boolean = true,
    enableRead: Boolean = true,
    enableReply: Boolean = true,
) {
    val handles = mutableListOf<FakeHandle>()
    val core = NotificationListenerCore(NotificationPluginRegistry(plugins), store, LiveReplyActions(), clock, log)
    val adapter = NotificationReplyAdapter(core, store, clock, log)

    init {
        if (grantAccess) core.onAccessGranted()
        if (enableRead) core.settings.setReadEnabled(FAKE_PLATFORM, true)
        if (enableReply) core.settings.setDirectReplyEnabled(FAKE_PLATFORM, true)
    }

    fun post(s: NotificationSnapshot = chatSnapshot(), src: NotificationSource = source(s.packageName)): PostedOutcome =
        core.onPosted(src, s) { FakeHandle().also { handles += it } }

    fun command(
        key: String = "send:$N_OUTBOUND:1",
        conv: String = "conv-abc",
        text: String = "Yes! 8pm?",
        deadline: Long = clock.now + 60_000,
    ) = NotificationReplyCommand(FAKE_PLATFORM, N_INSTRUCTION, N_OUTBOUND, key, conv, text, "approved_draft/approval-1", deadline)
}
