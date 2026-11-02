package com.wingman.integrations.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationListenerCoreTest {

    // ---------------------------------------------------------------- allowlist and authenticity

    @Test
    fun `default registry is empty and ignores every notification`() {
        val h = Harness(plugins = emptyList())
        assertTrue(h.core.registry.allowlist.isEmpty())
        assertEquals(PostedOutcome.NotAllowlisted, h.post(chatSnapshot(pkg = "com.tinder")))
        assertTrue(h.core.inbox.events().isEmpty())
        assertTrue(h.core.capabilities().isEmpty())
    }

    @Test
    fun `packages outside the allowlist are dropped without parsing`() {
        val h = Harness()
        assertEquals(PostedOutcome.NotAllowlisted, h.post(chatSnapshot(pkg = "com.whatsapp")))
        assertEquals(0, h.core.liveActions.size())
        assertTrue(h.log.lines.isEmpty() || h.log.lines.none { it.startsWith("notif.processed") })
    }

    @Test
    fun `lookalike packages are not on the allowlist`() {
        val h = Harness()
        for (pkg in listOf("$FAKE_PKG.fake", "com.example.fakedatingg", "Com.Example.FakeDating", " $FAKE_PKG", "com.example")) {
            assertEquals(pkg, PostedOutcome.NotAllowlisted, h.post(chatSnapshot(pkg = pkg)))
        }
    }

    @Test
    fun `snapshot claiming another package than its source is rejected`() {
        val h = Harness()
        val out = h.post(chatSnapshot(pkg = "com.evil"), src = source(FAKE_PKG))
        assertEquals(PostedOutcome.Rejected(FAKE_PLATFORM, NotificationAuthenticity.REASON_SNAPSHOT_SOURCE_MISMATCH), out)
    }

    @Test
    fun `pinned certificate must match and missing evidence is rejected`() {
        val h = Harness(plugins = listOf(FakeDatingPlugin(trustedSigningCertSha256 = setOf(GOOD_CERT))))
        assertEquals(
            PostedOutcome.Rejected(FAKE_PLATFORM, NotificationAuthenticity.REASON_CERT_MISMATCH),
            h.post(chatSnapshot(), source(certs = setOf("00".repeat(32)))),
        )
        assertEquals(
            PostedOutcome.Rejected(FAKE_PLATFORM, NotificationAuthenticity.REASON_NO_CERT_EVIDENCE),
            h.post(chatSnapshot(), source(certs = null)),
        )
        val ok = h.post(chatSnapshot(), source(certs = setOf(GOOD_CERT.uppercase())))
        assertTrue(ok is PostedOutcome.Processed)
        assertTrue((ok as PostedOutcome.Processed).newEvents.single().certificatePinned)
    }

    @Test
    fun `registry refuses two plugins for one package`() {
        val r = runCatching { NotificationPluginRegistry(listOf(FakeDatingPlugin(), FakeDatingPlugin())) }
        assertTrue(r.isFailure)
    }

    @Test
    fun `platform not enabled by the user is not read`() {
        val h = Harness(enableRead = false)
        assertEquals(PostedOutcome.PlatformDisabled(FAKE_PLATFORM), h.post())
        assertTrue(h.core.inbox.events().isEmpty())
    }

    // ---------------------------------------------------------------- parsing

    @Test
    fun `messaging style chat becomes a linked message with can_read and can_reply`() {
        val h = Harness()
        val out = h.post() as PostedOutcome.Processed
        assertEquals(NotificationFlags(canRead = true, canReply = true), out.flags)
        val e = out.newEvents.single()
        assertEquals(NotificationEventKind.MESSAGE, e.kind)
        assertEquals(NotificationIdentity.LINKED, e.identity)
        assertEquals("conv-abc", e.externalConversationId)
        assertEquals("Hey! Free tonight?", e.body)
        assertEquals("Sam", e.senderLabel)
        assertEquals(1_793_473_199_000L, e.occurredAtMillis)
        assertTrue(e.canReply)
        assertTrue(e.externalMessageId.startsWith("notif:"))
        assertEquals(1, h.core.liveActions.size())
    }

    @Test
    fun `notification without remote input is readable but not repliable`() {
        val h = Harness()
        val out = h.post(chatSnapshot(actions = listOf(MARK_READ_ACTION))) as PostedOutcome.Processed
        assertEquals(NotificationFlags(canRead = true, canReply = false), out.flags)
        assertFalse(out.newEvents.single().canReply)
        assertEquals(0, h.core.liveActions.size())
        assertFalse(h.core.settings.remoteInputObserved(FAKE_PLATFORM))
    }

    @Test
    fun `remote input detection ignores contextual and choice-only inputs`() {
        val contextual = REPLY_ACTION.copy(isContextual = true)
        val choiceOnly = SnapshotAction(0, "Quick", listOf(SnapshotRemoteInput("k", allowFreeFormInput = false, choiceCount = 3)))
        val plainReply = SnapshotAction(2, "Reply", listOf(SnapshotRemoteInput("k2", true)))
        assertNull(chatSnapshot(actions = listOf(contextual, choiceOnly)).defaultReplyAction())
        assertEquals(2, chatSnapshot(actions = listOf(choiceOnly, plainReply)).defaultReplyAction()?.index)
        assertEquals(0, chatSnapshot(actions = listOf(plainReply, REPLY_ACTION)).defaultReplyAction()?.index)
    }

    @Test
    fun `unlinked message is recorded but never gets a reply action`() {
        val h = Harness()
        val out = h.post(chatSnapshot(shortcut = null)) as PostedOutcome.Processed
        assertEquals(NotificationIdentity.UNLINKED, out.newEvents.single().identity)
        assertFalse(out.flags.canReply)
        assertEquals(0, h.core.liveActions.size())
        // ... but the RemoteInput WAS observed, which is what the send capability reports.
        assertTrue(h.core.settings.remoteInputObserved(FAKE_PLATFORM))
    }

    @Test
    fun `match and like are kept apart from messages and never repliable`() {
        val h = Harness()
        val match = h.post(chatSnapshot(key = "k-match", title = "New match", text = "You matched", messages = emptyList(), shortcut = "conv-x")) as PostedOutcome.Processed
        assertEquals(NotificationEventKind.MATCH, match.newEvents.single().kind)
        assertFalse(match.flags.canReply)
        val like = h.post(chatSnapshot(key = "k-like", title = "Someone", text = "Someone liked you", messages = emptyList(), shortcut = "conv-y")) as PostedOutcome.Processed
        assertEquals(NotificationEventKind.LIKE, like.newEvents.single().kind)
        assertEquals(0, h.core.liveActions.size())
    }

    @Test
    fun `hidden and truncated content are flagged`() {
        val h = Harness()
        val hidden = h.post(chatSnapshot(key = "k-h", title = "Sam", text = "You have a new message", messages = emptyList())) as PostedOutcome.Processed
        assertTrue(hidden.newEvents.single().contentHidden)
        assertNull(hidden.newEvents.single().body)
        val trunc = h.post(chatSnapshot(key = "k-t", messages = listOf(SnapshotMessage("So I was thinking we could…", 5L, "Sam")))) as PostedOutcome.Processed
        assertTrue(trunc.newEvents.single().truncated)
        val empty = h.post(chatSnapshot(key = "k-e", title = null, text = null, messages = emptyList()))
        assertEquals(NotificationFlags(canRead = false, canReply = false), (empty as PostedOutcome.Processed).flags)
    }

    @Test
    fun `group summaries are skipped`() {
        val h = Harness()
        assertEquals(PostedOutcome.GroupSummarySkipped(FAKE_PLATFORM), h.post(chatSnapshot(key = "summary", summary = true)))
        assertTrue(h.core.inbox.events().isEmpty())
        assertEquals(0, h.core.liveActions.size())
    }

    @Test
    fun `a plugin that throws is contained`() {
        val h = Harness(plugins = listOf(FakeDatingPlugin(throwOnParse = true)))
        val out = h.post() as PostedOutcome.Processed
        assertTrue(out.newEvents.isEmpty())
        assertFalse(out.flags.canReply)
    }

    // ---------------------------------------------------------------- dedup

    @Test
    fun `exact repost is a duplicate`() {
        val h = Harness()
        h.post()
        assertEquals(PostedOutcome.Duplicate(FAKE_PLATFORM), h.post())
        assertEquals(1, h.core.inbox.events().size)
    }

    @Test
    fun `update re-listing old messages records only the new one`() {
        val h = Harness()
        val m1 = SnapshotMessage("Hey! Free tonight?", 1_000L, "Sam")
        val m2 = SnapshotMessage("Or tomorrow?", 2_000L, "Sam")
        h.post(chatSnapshot(messages = listOf(m1), post = 10L))
        val upd = h.post(chatSnapshot(messages = listOf(m1, m2), post = 20L)) as PostedOutcome.Processed
        assertEquals(1, upd.newEvents.size)
        assertEquals(1, upd.repeatedEvents)
        assertEquals("Or tomorrow?", upd.newEvents.single().body)
        assertEquals(2, h.core.inbox.events().size)
        assertEquals(1, h.core.liveActions.size()) // same key: replaced, not added
    }

    @Test
    fun `same content reposted with a new post time is not recorded twice`() {
        val h = Harness()
        h.post(chatSnapshot(post = 10L))
        val again = h.post(chatSnapshot(post = 99L)) as PostedOutcome.Processed
        assertTrue(again.newEvents.isEmpty())
        assertEquals(1, again.repeatedEvents)
    }

    @Test
    fun `dedup survives a restart and the reply action is re-registered`() {
        val store = InMemoryNotificationStateStore()
        val first = Harness(store = store)
        first.post()
        val restarted = Harness(store = store, enableRead = false, enableReply = false, grantAccess = false)
        restarted.core.onAccessGranted() // listener reconnects in the new process
        assertEquals(0, restarted.core.liveActions.size())
        assertEquals(PostedOutcome.Duplicate(FAKE_PLATFORM), restarted.post()) // replay of getActiveNotifications()
        assertEquals(1, restarted.core.inbox.events().size)
        assertEquals(1, restarted.core.liveActions.size())
    }

    @Test
    fun `removed notification drops its reply action`() {
        val h = Harness()
        h.post()
        h.core.onRemoved(chatSnapshot().key)
        assertEquals(0, h.core.liveActions.size())
    }

    @Test
    fun `live action map is bounded`() {
        val live = LiveReplyActions(capacity = 3)
        repeat(5) { i -> live.put(LiveReplyAction("k$i", i.toLong(), FAKE_PLATFORM, "c$i", 0, listOf("r"), FakeHandle(), 0L)) }
        assertEquals(3, live.size())
        assertNull(live.latestFor(FAKE_PLATFORM, "c0"))
        assertEquals("k4", live.latestFor(FAKE_PLATFORM, "c4")?.notificationKey)
    }

    // ---------------------------------------------------------------- access

    @Test
    fun `revoked access stops processing, drops actions and reports a downgrade`() {
        val h = Harness()
        h.post()
        assertEquals(1, h.core.liveActions.size())
        h.core.onAccessRevoked()
        assertFalse(h.core.isActive)
        assertEquals(0, h.core.liveActions.size())
        assertEquals(PostedOutcome.Inactive, h.post(chatSnapshot(key = "new")))
        val change = h.core.inbox.capabilityChanges().last()
        assertEquals(FAKE_PLATFORM, change.platform)
        assertEquals("access_revoked", change.reason)
        val caps = h.core.capabilities(FAKE_PLATFORM)
        assertEquals(CapabilityStatus.UNSUPPORTED, caps.read.status)
        assertEquals(CapabilityStatus.UNSUPPORTED, caps.send.status)
    }

    @Test
    fun `never granted means inactive`() {
        val h = Harness(grantAccess = false)
        assertEquals(PostedOutcome.Inactive, h.post())
    }
}
