package com.wingman.integrations.tinder

import com.wingman.integrations.notifications.AuthenticityVerdict
import com.wingman.integrations.notifications.InMemoryNotificationStateStore
import com.wingman.integrations.notifications.LiveReplyActions
import com.wingman.integrations.notifications.NotificationEventKind
import com.wingman.integrations.notifications.NotificationIdentity
import com.wingman.integrations.notifications.NotificationListenerCore
import com.wingman.integrations.notifications.NotificationPluginRegistry
import com.wingman.integrations.notifications.NotificationSnapshot
import com.wingman.integrations.notifications.NotificationSource
import com.wingman.integrations.notifications.PostedOutcome
import com.wingman.integrations.notifications.ReplyActionHandle
import com.wingman.integrations.notifications.FireResult
import com.wingman.integrations.notifications.SnapshotAction
import com.wingman.integrations.notifications.SnapshotMessage
import com.wingman.integrations.notifications.SnapshotRemoteInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SYNTHETIC notification shapes only (no real Tinder notification has been inspected yet). */
class TinderNotificationPluginTest {
    private val plugin = TinderNotificationPlugin()
    private val t0 = 1_793_473_200_000L

    private val reply = SnapshotAction(0, "Reply", listOf(SnapshotRemoteInput("reply_text", true)), SnapshotAction.SEMANTIC_ACTION_REPLY)

    private fun snap(
        title: String? = null,
        text: String? = null,
        bigText: String? = null,
        messages: List<SnapshotMessage> = emptyList(),
        shortcut: String? = null,
        category: String? = null,
        channel: String? = null,
        group: Boolean = false,
        summary: Boolean = false,
        actions: List<SnapshotAction> = emptyList(),
        pkg: String = TinderNotificationPlugin.PACKAGE,
        key: String = "0|com.tinder|1|null|10",
        post: Long = t0,
    ) = NotificationSnapshot(
        key = key, packageName = pkg, postTimeMillis = post, whenMillis = post - 500, title = title, text = text, bigText = bigText,
        isGroupConversation = group, messages = messages, shortcutId = shortcut, isGroupSummary = summary, category = category,
        channelId = channel, template = if (messages.isNotEmpty()) "android.app.Notification\$MessagingStyle" else null, actions = actions,
    )

    private fun msg(text: String?, sender: String? = "Synthetic Sam", key: String? = null, at: Long = t0 - 1_000) =
        SnapshotMessage(text, at, sender, key)

    @Test fun messagingStyleChatWithShortcutIsALinkedMessage() {
        val e = plugin.parse(snap(title = "Synthetic Sam", messages = listOf(msg("Hi there, synthetic hello")), shortcut = "match-123", category = "msg")).single()
        assertEquals(NotificationEventKind.MESSAGE, e.kind)
        assertEquals(NotificationIdentity.LINKED, e.identity)
        assertEquals("shortcut:match-123", e.externalConversationId)
        assertEquals("Synthetic Sam", e.senderLabel)
        assertEquals("Hi there, synthetic hello", e.body)
        assertEquals(t0 - 1_000, e.occurredAtMillis)
        assertFalse(e.truncated)
        assertFalse(e.contentHidden)
    }

    @Test fun eachIncomingMessageIsOneEventAndOwnMessagesAreSkipped() {
        val events = plugin.parse(
            snap(messages = listOf(msg("one", at = t0 - 3_000), msg("mine", sender = null, at = t0 - 2_000), msg("two", at = t0 - 1_000)), shortcut = "m1"),
        )
        assertEquals(listOf("one", "two"), events.map { it.body })
    }

    @Test fun plainMessageWithoutStableIdIsUnlinked() {
        val e = plugin.parse(snap(title = "Synthetic Alex", text = "synthetic plain text", category = "msg")).single()
        assertEquals(NotificationEventKind.MESSAGE, e.kind)
        assertEquals(NotificationIdentity.UNLINKED, e.identity)
        assertNull(e.externalConversationId)
        assertEquals("Synthetic Alex", e.senderLabel)
    }

    @Test fun matchLikeSuperLikeSystemAndUnknownAreNotChats() {
        val cases = listOf(
            snap(title = "It's a Match!", text = "You and Synthetic Kim liked each other") to TinderNotificationKind.MATCH,
            snap(title = "You have a new match", text = "Say hi") to TinderNotificationKind.MATCH,
            snap(title = "Tinder", text = "Someone likes you! Find out who") to TinderNotificationKind.LIKE,
            snap(title = "You got a Super Like", text = "Swipe to see who") to TinderNotificationKind.SUPER_LIKE,
            snap(title = "Boost now", text = "Be seen by more people", category = "promo") to TinderNotificationKind.SYSTEM,
            snap(title = "Hello", text = "synthetic") to TinderNotificationKind.UNKNOWN,
        )
        for ((s, expected) in cases) {
            assertEquals(s.title, expected, plugin.classify(s))
            val e = plugin.parse(s).single()
            assertEquals(expected.eventKind, e.kind)
            assertNotEquals(NotificationEventKind.MESSAGE, e.kind)
            assertEquals(NotificationIdentity.UNLINKED, e.identity) // never a reply target
            assertNull(e.senderLabel)
        }
    }

    @Test fun superLikeAndLikeBothMapToTheLikeWireKind() {
        assertEquals(NotificationEventKind.LIKE, TinderNotificationKind.SUPER_LIKE.eventKind)
        assertEquals(NotificationEventKind.LIKE, TinderNotificationKind.LIKE.eventKind)
    }

    @Test fun aChatThatMentionsAMatchStaysAChat() {
        val s = snap(title = "Synthetic Sam", messages = listOf(msg("so glad we matched, it's a match made in heaven")), shortcut = "m9")
        assertEquals(TinderNotificationKind.MESSAGE, plugin.classify(s))
    }

    @Test fun genericTextIsHiddenContentWithNoBody() {
        for (text in listOf("You have a new message", "New message", "sent you a message", "3 new messages")) {
            val e = plugin.parse(snap(title = "Tinder", text = text)).single()
            assertEquals(text, NotificationEventKind.MESSAGE, e.kind)
            assertTrue(text, e.contentHidden)
            assertNull(e.body)
            assertNull(e.senderLabel) // the app name is not a sender
            assertEquals(NotificationIdentity.UNLINKED, e.identity)
        }
        val ms = plugin.parse(snap(messages = listOf(msg("New message")), shortcut = "m1")).single()
        assertTrue(ms.contentHidden)
        assertNull(ms.body)
    }

    @Test fun ellipsisMarksTruncated() {
        val e = plugin.parse(snap(messages = listOf(msg("this is a long synthetic message that got cut…")), shortcut = "m1")).single()
        assertTrue(e.truncated)
        val plain = plugin.parse(snap(title = "Synthetic Sam", text = "short preview...", category = "msg")).single()
        assertTrue(plain.truncated)
        val full = plugin.parse(snap(title = "Synthetic Sam", text = "short preview...", bigText = "short preview and the rest", category = "msg")).single()
        assertFalse(full.truncated)
        assertEquals("short preview and the rest", full.body)
    }

    @Test fun ambiguousIdentitiesAreNeverMerged() {
        // Same display name, different shortcut IDs: two different conversations.
        val a = plugin.parse(snap(title = "Alex", messages = listOf(msg("a", sender = "Alex")), shortcut = "match-a")).single()
        val b = plugin.parse(snap(title = "Alex", messages = listOf(msg("b", sender = "Alex")), shortcut = "match-b")).single()
        assertNotEquals(a.externalConversationId, b.externalConversationId)
        // Same display name, no stable ID: no conversation at all (never keyed by the name).
        val c = plugin.parse(snap(title = "Alex", text = "c", category = "msg")).single()
        assertNull(c.externalConversationId)
        assertEquals(NotificationIdentity.UNLINKED, c.identity)
        // Two senders in one notification without a shortcut: ambiguous.
        val d = plugin.parse(snap(messages = listOf(msg("x", sender = "Alex"), msg("y", sender = "Jo")))).first()
        assertEquals(NotificationIdentity.AMBIGUOUS, d.identity)
        assertNull(d.externalConversationId)
        // Two sender keys: ambiguous.
        val e = plugin.parse(snap(messages = listOf(msg("x", key = "k1"), msg("y", key = "k2")))).first()
        assertEquals(NotificationIdentity.AMBIGUOUS, e.identity)
        // Group conversation without a shortcut: ambiguous.
        assertEquals(NotificationIdentity.AMBIGUOUS, plugin.parse(snap(messages = listOf(msg("x", key = "k1")), group = true)).single().identity)
    }

    @Test fun oneToOnePersonKeyLinksTheConversation() {
        val e = plugin.parse(snap(messages = listOf(msg("x", key = "person-1"), msg("y", key = "person-1")))).first()
        assertEquals(NotificationIdentity.LINKED, e.identity)
        assertEquals("person:person-1", e.externalConversationId)
        // A key on only some messages is not enough.
        assertEquals(NotificationIdentity.UNLINKED, plugin.parse(snap(messages = listOf(msg("x", key = "p"), msg("y")))).first().identity)
    }

    @Test fun groupSummariesAndEmptyNotificationsYieldNothing() {
        assertTrue(plugin.parse(snap(title = "Tinder", text = "2 new messages", summary = true)).isEmpty())
        assertTrue(plugin.parse(snap()).isEmpty())
    }

    @Test fun replyActionOnlyOnChatNotifications() {
        assertEquals(reply, plugin.replyAction(snap(messages = listOf(msg("hi")), shortcut = "m1", actions = listOf(reply))))
        assertNull(plugin.replyAction(snap(title = "It's a Match!", text = "Say hi", actions = listOf(reply))))
        assertNull(plugin.replyAction(snap(title = "You got a Super Like", actions = listOf(reply))))
    }

    @Test fun exactPackageOnlyLookalikesRejected() {
        for (bad in listOf("com.tinder.fake", "com.tinderr", "Com.Tinder", "com.tinder.lite", "tinder", "com.tinder ")) {
            val v = plugin.isAuthentic(NotificationSource(bad), snap(pkg = bad))
            assertTrue(bad, v is AuthenticityVerdict.Rejected)
        }
        assertEquals(AuthenticityVerdict.Authentic(false), plugin.isAuthentic(NotificationSource("com.tinder"), snap()))
        // Snapshot claiming another package than the poster: rejected.
        assertTrue(plugin.isAuthentic(NotificationSource("com.tinder"), snap(pkg = "com.tinder.fake")) is AuthenticityVerdict.Rejected)
    }

    @Test fun pinnedCertificateMustMatchOnceDigestsAreRecorded() {
        val pinned = TinderNotificationPlugin(setOf("aa".repeat(32)))
        assertTrue(pinned.isAuthentic(NotificationSource("com.tinder", setOf("bb".repeat(32))), snap()) is AuthenticityVerdict.Rejected)
        assertTrue(pinned.isAuthentic(NotificationSource("com.tinder", null), snap()) is AuthenticityVerdict.Rejected)
        assertEquals(AuthenticityVerdict.Authentic(true), pinned.isAuthentic(NotificationSource("com.tinder", setOf("AA".repeat(32))), snap()))
        assertTrue(TinderNotificationPlugin.PINNED_CERT_SHA256.isEmpty()) // TODO(device N10)
    }

    @Test fun throughTheCoreOnlyALinkedChatRegistersAReplyAction() {
        val core = NotificationListenerCore(NotificationPluginRegistry(listOf(plugin)), InMemoryNotificationStateStore(), LiveReplyActions())
        core.onAccessGranted()
        core.settings.setReadEnabled("tinder", true)
        val handle = ReplyActionHandle { FireResult.Fired }
        val match = core.onPosted(NotificationSource("com.tinder"), snap(title = "It's a Match!", text = "Say hi", actions = listOf(reply), key = "k1")) { handle }
        assertFalse((match as PostedOutcome.Processed).replyActionRegistered)
        assertEquals(NotificationEventKind.MATCH, match.newEvents.single().kind)
        assertFalse(match.newEvents.single().canReply)
        val unlinked = core.onPosted(NotificationSource("com.tinder"), snap(title = "Sam", text = "hi", category = "msg", actions = listOf(reply), key = "k2")) { handle }
        assertFalse((unlinked as PostedOutcome.Processed).replyActionRegistered)
        val linked = core.onPosted(NotificationSource("com.tinder"), snap(messages = listOf(msg("hi")), shortcut = "m1", actions = listOf(reply), key = "k3")) { handle }
        assertTrue((linked as PostedOutcome.Processed).replyActionRegistered)
        assertTrue(linked.newEvents.single().canReply)
        // A lookalike app is never parsed.
        assertEquals(PostedOutcome.NotAllowlisted, core.onPosted(NotificationSource("com.tinder.fake"), snap(pkg = "com.tinder.fake", messages = listOf(msg("x")))) { handle })
    }
}
