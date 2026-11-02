package com.wingman.integrations.bumble

import com.wingman.integrations.notifications.AuthenticityVerdict
import com.wingman.integrations.notifications.FireResult
import com.wingman.integrations.notifications.InMemoryNotificationStateStore
import com.wingman.integrations.notifications.LiveReplyActions
import com.wingman.integrations.notifications.NotificationContractMapping
import com.wingman.integrations.notifications.NotificationEventKind
import com.wingman.integrations.notifications.NotificationIdentity
import com.wingman.integrations.notifications.NotificationListenerCore
import com.wingman.integrations.notifications.NotificationPluginRegistry
import com.wingman.integrations.notifications.NotificationSnapshot
import com.wingman.integrations.notifications.NotificationSource
import com.wingman.integrations.notifications.PostedOutcome
import com.wingman.integrations.notifications.ReplyActionHandle
import com.wingman.integrations.notifications.SnapshotAction
import com.wingman.integrations.notifications.SnapshotMessage
import com.wingman.integrations.notifications.SnapshotRemoteInput
import com.wingman.integrations.tinder.TinderNotificationPlugin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SYNTHETIC notification shapes only (no real Bumble notification has been inspected yet). */
class BumbleNotificationPluginTest {
    private val plugin = BumbleNotificationPlugin()
    private val t0 = 1_793_473_200_000L
    private val reply = SnapshotAction(0, "Reply", listOf(SnapshotRemoteInput("reply_text", true)), SnapshotAction.SEMANTIC_ACTION_REPLY)

    private fun snap(
        title: String? = null,
        text: String? = null,
        messages: List<SnapshotMessage> = emptyList(),
        shortcut: String? = null,
        category: String? = null,
        actions: List<SnapshotAction> = emptyList(),
        pkg: String = BumbleNotificationPlugin.PACKAGE,
        key: String = "0|com.bumble.app|1|null|10",
        summary: Boolean = false,
    ) = NotificationSnapshot(
        key = key, packageName = pkg, postTimeMillis = t0, whenMillis = t0 - 500, title = title, text = text,
        messages = messages, shortcutId = shortcut, isGroupSummary = summary, category = category, actions = actions,
        template = if (messages.isNotEmpty()) "android.app.Notification\$MessagingStyle" else null,
    )

    private fun msg(text: String?, sender: String? = "Synthetic Robin", key: String? = null) = SnapshotMessage(text, t0 - 1_000, sender, key)

    @Test fun chatWithShortcutIsLinked() {
        val e = plugin.parse(snap(title = "Synthetic Robin", messages = listOf(msg("synthetic hi")), shortcut = "conv-7", category = "msg")).single()
        assertEquals(NotificationEventKind.MESSAGE, e.kind)
        assertEquals(NotificationIdentity.LINKED, e.identity)
        assertEquals("shortcut:conv-7", e.externalConversationId)
        assertEquals("synthetic hi", e.body)
    }

    @Test fun expiryIsCheckedBeforeMatchAndIsNeverAChat() {
        for (title in listOf("Your match expires in 3 hours", "Time is running out", "Last chance to chat with Synthetic Robin", "Your match is expiring")) {
            val s = snap(title = title, text = "Send a message before it's gone")
            assertEquals(title, BumbleNotificationKind.EXPIRY, plugin.classify(s))
            val e = plugin.parse(s).single()
            assertEquals(NotificationEventKind.EXPIRY, e.kind)
            assertEquals(NotificationIdentity.UNLINKED, e.identity)
            assertEquals("expiry", NotificationContractMapping.wireKind(e.kind))
        }
    }

    @Test fun matchReactionLikeSystemUnknown() {
        val cases = listOf(
            snap(title = "You have a new match!", text = "Synthetic Robin") to BumbleNotificationKind.MATCH,
            snap(title = "Make the first move", text = "You matched with Synthetic Robin") to BumbleNotificationKind.MATCH,
            snap(title = "Synthetic Robin reacted to your message") to BumbleNotificationKind.REACTION,
            snap(title = "Bumble", text = "Someone liked your photo") to BumbleNotificationKind.REACTION,
            snap(title = "Your Beeline is buzzing", text = "3 people like you") to BumbleNotificationKind.LIKE,
            snap(title = "Try Premium", category = "promo") to BumbleNotificationKind.SYSTEM,
            snap(title = "Hi", text = "synthetic") to BumbleNotificationKind.UNKNOWN,
        )
        for ((s, expected) in cases) {
            assertEquals(s.title, expected, plugin.classify(s))
            val e = plugin.parse(s).single()
            assertEquals(expected.eventKind, e.kind)
            assertNotEquals(NotificationEventKind.MESSAGE, e.kind)
            assertEquals(NotificationIdentity.UNLINKED, e.identity)
        }
        // A reaction is reported as like_comment: never a chat.
        assertEquals("like_comment", NotificationContractMapping.wireKind(BumbleNotificationKind.REACTION.eventKind))
    }

    @Test fun messagingStyleHeadlineDecidesExpiryOrReactionButBodiesAreNeverKeywordMatched() {
        assertEquals(BumbleNotificationKind.EXPIRY, plugin.classify(snap(title = "Your match expires soon", messages = listOf(msg("x")))))
        assertEquals(BumbleNotificationKind.REACTION, plugin.classify(snap(title = "Robin reacted", messages = listOf(msg("x")))))
        assertEquals(
            BumbleNotificationKind.MESSAGE,
            plugin.classify(snap(title = "Synthetic Robin", messages = listOf(msg("does our match expire? I reacted badly lol")), shortcut = "c")),
        )
    }

    @Test fun hiddenAndTruncated() {
        val h = plugin.parse(snap(title = "Bumble", text = "You have a new message")).single()
        assertTrue(h.contentHidden)
        assertNull(h.body)
        assertNull(h.senderLabel)
        val t = plugin.parse(snap(messages = listOf(msg("a long synthetic message…")), shortcut = "c")).single()
        assertTrue(t.truncated)
    }

    @Test fun ambiguousIdentityNeverMerged() {
        val a = plugin.parse(snap(title = "Robin", text = "a", category = "msg")).single()
        val b = plugin.parse(snap(title = "Robin", text = "b", category = "msg")).single()
        assertNull(a.externalConversationId)
        assertNull(b.externalConversationId)
        assertEquals(NotificationIdentity.UNLINKED, a.identity)
        val d = plugin.parse(snap(messages = listOf(msg("x", sender = "Robin"), msg("y", sender = "Robin2")))).first()
        assertEquals(NotificationIdentity.AMBIGUOUS, d.identity)
    }

    @Test fun replyActionOnlyOnChats() {
        assertEquals(reply, plugin.replyAction(snap(messages = listOf(msg("hi")), shortcut = "c", actions = listOf(reply))))
        assertNull(plugin.replyAction(snap(title = "Your match expires in 1 hour", actions = listOf(reply))))
        assertNull(plugin.replyAction(snap(title = "Make the first move", actions = listOf(reply))))
    }

    @Test fun exactPackageOnly() {
        for (bad in listOf("com.bumble.app.fake", "com.bumble", "com.bumble.apps", "COM.BUMBLE.APP", "com.bumble.dating")) {
            assertTrue(bad, plugin.isAuthentic(NotificationSource(bad), snap(pkg = bad)) is AuthenticityVerdict.Rejected)
        }
        assertEquals(AuthenticityVerdict.Authentic(false), plugin.isAuthentic(NotificationSource("com.bumble.app"), snap()))
        assertTrue(BumbleNotificationPlugin.PINNED_CERT_SHA256.isEmpty()) // TODO(device N10)
    }

    @Test fun bothPluginsRegisterTogetherAndKeepTheirPackagesApart() {
        val registry = NotificationPluginRegistry(listOf(TinderNotificationPlugin(), plugin))
        assertEquals(setOf("com.tinder", "com.bumble.app"), registry.allowlist)
        assertEquals("bumble", registry.forPackage("com.bumble.app")!!.platform)
        assertNull(registry.forPackage("com.bumble"))
    }

    @Test fun expiryNotificationWithAReplyActionRegistersNothingThroughTheCore() {
        val core = NotificationListenerCore(NotificationPluginRegistry(listOf(plugin)), InMemoryNotificationStateStore(), LiveReplyActions())
        core.onAccessGranted()
        core.settings.setReadEnabled("bumble", true)
        val o = core.onPosted(NotificationSource("com.bumble.app"), snap(title = "Your match expires in 1 hour", shortcut = "c", actions = listOf(reply))) {
            ReplyActionHandle { FireResult.Fired }
        } as PostedOutcome.Processed
        assertFalse(o.replyActionRegistered)
        assertEquals(NotificationEventKind.EXPIRY, o.newEvents.single().kind)
        assertFalse(o.newEvents.single().canReply)
        assertEquals(0, core.liveActions.size())
    }
}
