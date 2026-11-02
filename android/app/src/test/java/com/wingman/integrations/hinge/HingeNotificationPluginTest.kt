package com.wingman.integrations.hinge

import com.wingman.integrations.hinge.notifications.HingeNotificationPlugin
import com.wingman.integrations.notifications.AuthenticityVerdict
import com.wingman.integrations.notifications.NotificationEventKind
import com.wingman.integrations.notifications.NotificationIdentity
import com.wingman.integrations.notifications.NotificationPluginRegistry
import com.wingman.integrations.notifications.NotificationSnapshot
import com.wingman.integrations.notifications.NotificationSource
import com.wingman.integrations.notifications.SnapshotAction
import com.wingman.integrations.notifications.SnapshotMessage
import com.wingman.integrations.notifications.SnapshotRemoteInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic fixtures (no real names or messages). The SHAPES are assumptions until the WING-017
 * device run records real Hinge notifications; these tests pin the conservative rules.
 */
class HingeNotificationPluginTest {
    // WING-031: the experimental connector's test fakes (and their T0) moved to src/testExperimental;
    // this test runs in every build, so it keeps its own fixed instant (same value).
    private companion object {
        const val T0 = 1_793_600_000_000L // 2026-11-02
    }

    private val plugin = HingeNotificationPlugin()
    private val reply = SnapshotAction(0, "Reply", listOf(SnapshotRemoteInput("reply_text", true)), SnapshotAction.SEMANTIC_ACTION_REPLY)

    private fun snap(
        title: String? = null, text: String? = null, messages: List<SnapshotMessage> = emptyList(), shortcut: String? = null,
        category: String? = null, actions: List<SnapshotAction> = emptyList(), summary: Boolean = false, group: Boolean = false, pkg: String = "co.hinge.app",
    ) = NotificationSnapshot(
        key = "0|co.hinge.app|1|null|10001", packageName = pkg, postTimeMillis = T0, whenMillis = T0 - 10, title = title, text = text,
        messages = messages, shortcutId = shortcut, category = category, actions = actions, isGroupSummary = summary, isGroupConversation = group,
    )

    private fun chat(vararg m: Pair<String?, String?>) = m.map { (sender, text) -> SnapshotMessage(text, T0 - 5, sender, senderKey = sender?.let { "key-$it" }) }

    @Test fun `MessagingStyle with a conversation shortcut is a LINKED chat message with a reply action`() {
        val s = snap(title = "Riley", messages = chat("Riley" to "Coffee on Sunday?"), shortcut = "conv-abc", category = "msg", actions = listOf(reply))
        val e = plugin.parse(s).single()
        assertEquals(NotificationEventKind.MESSAGE, e.kind)
        assertEquals(NotificationIdentity.LINKED, e.identity)
        assertEquals("conv-abc", e.externalConversationId)
        assertEquals("Coffee on Sunday?", e.body)
        assertEquals(T0 - 5, e.occurredAtMillis)
        assertNotNull(plugin.replyAction(s))
    }

    @Test fun `without a stable id the sender is only a label - UNLINKED`() {
        val e = plugin.parse(snap(messages = chat("Riley" to "hi"), category = "msg")).single()
        assertEquals(NotificationIdentity.UNLINKED, e.identity)
        assertNull(e.externalConversationId)
        assertEquals("Riley", e.senderLabel)
    }

    @Test fun `two different senders in one notification are AMBIGUOUS, never linked`() {
        val e = plugin.parse(snap(messages = chat("Riley" to "a", "Sam" to "b"), shortcut = "conv-abc")).first()
        assertEquals(NotificationIdentity.AMBIGUOUS, e.identity)
        assertNull(e.externalConversationId)
    }

    @Test fun `likes, comments, matches and reminders are kept apart from chat and never replyable`() {
        val cases = mapOf(
            snap(title = "Riley liked your photo", category = "social", actions = listOf(reply)) to NotificationEventKind.LIKE,
            snap(title = "New like", text = "Someone likes you") to NotificationEventKind.LIKE,
            snap(title = "Sam commented on your prompt", text = "Two truths and a lie: …", category = "msg", actions = listOf(reply)) to NotificationEventKind.COMMENT,
            snap(title = "Sam", text = "liked your photo and said: nice hat", category = "msg") to NotificationEventKind.COMMENT,
            snap(title = "It's a match!", text = "You matched with Alex") to NotificationEventKind.MATCH,
            snap(title = "Your Turn", text = "Alex is waiting for your reply") to NotificationEventKind.SYSTEM,
            snap(title = "Standouts", text = "New standouts are here", category = "promo") to NotificationEventKind.SYSTEM,
        )
        for ((s, kind) in cases) {
            val e = plugin.parse(s).single()
            assertEquals(s.title, kind, e.kind)
            assertEquals(NotificationIdentity.UNLINKED, e.identity)
            assertNull(plugin.replyAction(s))
        }
        // A comment that arrives as MessagingStyle is still a comment.
        val styled = snap(title = "Sam commented on your photo", messages = chat("Sam" to "nice hat"), shortcut = "conv-x", actions = listOf(reply))
        assertEquals(NotificationEventKind.COMMENT, plugin.parse(styled).single().kind)
        assertNull(plugin.replyAction(styled))
    }

    @Test fun `generic text means hidden content - no body, no reply`() {
        val e = plugin.parse(snap(title = "Hinge", text = "You have a new message")).single()
        assertEquals(NotificationEventKind.MESSAGE, e.kind)
        assertTrue(e.contentHidden)
        assertNull(e.body)
        assertEquals(NotificationIdentity.UNLINKED, e.identity)
    }

    @Test fun `an ellipsis marks a truncated preview`() {
        val e = plugin.parse(snap(messages = chat("Riley" to "So I was thinking we could…"), shortcut = "conv-abc")).single()
        assertTrue(e.truncated)
    }

    @Test fun `empty body in MessagingStyle is hidden, group summaries yield nothing, odd input never throws`() {
        assertTrue(plugin.parse(snap(messages = chat("Riley" to null), shortcut = "c")).single().contentHidden)
        assertTrue(plugin.parse(snap(title = "3 new messages", summary = true)).isEmpty())
        assertTrue(plugin.parse(snap()).isEmpty())
        assertEquals(NotificationEventKind.UNKNOWN, plugin.parse(snap(text = "Something new on Hinge")).single().kind)
        val group = plugin.parse(snap(messages = chat("Riley" to "x"), shortcut = "c", group = true)).single()
        assertEquals(NotificationEventKind.UNKNOWN, group.kind)
        assertEquals(NotificationIdentity.UNLINKED, group.identity)
    }

    @Test fun `no RemoteInput action means no send path`() {
        val s = snap(messages = chat("Riley" to "hi"), shortcut = "conv-abc", actions = listOf(SnapshotAction(0, "Open", emptyList())))
        assertEquals(NotificationEventKind.MESSAGE, plugin.parse(s).single().kind)
        assertNull(plugin.replyAction(s))
    }

    @Test fun `the same notification update parses identically (dedup is by content in the core)`() {
        val s = snap(messages = chat("Riley" to "hi"), shortcut = "conv-abc")
        assertEquals(plugin.parse(s).map { it.toString() }, plugin.parse(s).map { it.toString() })
    }

    @Test fun `exact package only, and the plugin registers cleanly`() {
        val registry = NotificationPluginRegistry(listOf(plugin))
        assertEquals(setOf("co.hinge.app"), registry.allowlist)
        assertTrue(plugin.isAuthentic(NotificationSource("co.hinge.app"), snap()) is AuthenticityVerdict.Authentic)
        for (fake in listOf("co.hinge.app.fake", "co.hinge", "Co.Hinge.App")) {
            assertFalse(plugin.isAuthentic(NotificationSource(fake), snap(pkg = fake)) is AuthenticityVerdict.Authentic)
        }
    }
}
