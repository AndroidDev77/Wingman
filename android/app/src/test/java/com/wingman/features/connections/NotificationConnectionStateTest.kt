package com.wingman.features.connections

import com.wingman.core.model.Verification
import com.wingman.features.conversation.settle
import com.wingman.integrations.bumble.BumbleNotificationPlugin
import com.wingman.integrations.notifications.InMemoryNotificationStateStore
import com.wingman.integrations.notifications.LiveReplyActions
import com.wingman.integrations.notifications.NFakeClock
import com.wingman.integrations.notifications.NotificationListenerCore
import com.wingman.integrations.notifications.NotificationPluginRegistry
import com.wingman.integrations.notifications.NotificationReplyAdapter
import com.wingman.integrations.notifications.NotificationSyncHost
import com.wingman.integrations.notifications.NotificationSyncHostTest
import com.wingman.integrations.tinder.TinderNotificationPlugin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings toggles for Tinder/Bumble notifications (I-033): they gate pairing and reading. */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationConnectionStateTest {
    private var access = true

    private fun TestScope.holder(
        backend: NotificationSyncHostTest.Backend? = NotificationSyncHostTest.Backend(),
        changes: MutableList<Unit> = mutableListOf(),
    ): Pair<NotificationConnectionsState, NotificationSyncHost> {
        val store = InMemoryNotificationStateStore()
        val clock = NFakeClock()
        val core = NotificationListenerCore(NotificationPluginRegistry(listOf(TinderNotificationPlugin(), BumbleNotificationPlugin())), store, LiveReplyActions(), clock)
        if (access) core.onAccessGranted()
        val host = NotificationSyncHost(core, NotificationReplyAdapter(core, store, clock), store, backend, clock)
        return NotificationConnectionsState(backgroundScope, host, { access }, afterChange = { changes += Unit }) to host
    }

    private fun NotificationConnectionsState.p(platform: String) = state.value.platforms.first { it.platform == platform }

    @Test fun everyPlatformShowsTheAssistedOnlyLabelAndStartsOff() = runTest {
        val (s, _) = holder()
        assertEquals(listOf("tinder", "bumble"), s.state.value.platforms.map { it.platform })
        for (v in s.state.value.platforms) {
            assertEquals("Assisted only: Wingman drafts, you send", v.assistedLabel)
            assertFalse(v.readingOn)
            assertTrue(v.readingToggleEnabled)
            assertFalse(v.directReplyOn)
            assertFalse(v.directReplyToggleEnabled)
            assertEquals(NotificationConnectionCopy.DIRECT_REPLY_DISABLED, v.directReplyExplanation)
        }
        assertEquals(NotificationConnectionCopy.ACCESS_ON, s.state.value.accessLabel)
    }

    @Test fun readingToggleOnPairsAndOffUnpairs() = runTest {
        val backend = NotificationSyncHostTest.Backend()
        val changes = mutableListOf<Unit>()
        val (s, host) = holder(backend, changes)
        s.setReading("tinder", true)
        settle()
        assertEquals(1, backend.creates.size)
        assertEquals("tinder", backend.creates.single().first.platform)
        assertTrue(s.p("tinder").readingOn)
        assertFalse(s.p("bumble").readingOn)
        assertEquals("Connected", s.p("tinder").statusLabel)
        val rows = s.p("tinder").capabilities.associateBy { it.name }
        assertEquals(Verification.UNVERIFIED, rows.getValue("Read notifications").status)
        assertEquals(Verification.UNSUPPORTED, rows.getValue("Send from Wingman").status)
        assertEquals(Verification.UNSUPPORTED, rows.getValue("Swipe").status)
        assertEquals(1, changes.size)
        // Direct reply stays disabled and explained: send isn't VERIFIED.
        assertFalse(s.p("tinder").directReplyToggleEnabled)
        s.setDirectReply("tinder", true)
        settle()
        assertFalse(s.p("tinder").directReplyOn)
        assertFalse(host.core.settings.directReplyEnabled("tinder"))

        s.setReading("tinder", false)
        settle()
        assertEquals(1, backend.unpairs.size)
        assertFalse(s.p("tinder").readingOn)
        assertFalse(host.core.settings.readEnabled("tinder"))
    }

    @Test fun withoutNotificationAccessTheToggleIsDisabledAndNothingPairs() = runTest {
        access = false
        val backend = NotificationSyncHostTest.Backend()
        val (s, _) = holder(backend)
        assertEquals(NotificationConnectionCopy.ACCESS_OFF, s.state.value.accessLabel)
        assertFalse(s.p("tinder").readingToggleEnabled)
        assertEquals(NotificationConnectionCopy.NEEDS_ACCESS_HINT, s.p("tinder").readingHint)
        s.setReading("tinder", true) // a stale tap still cannot pair
        settle()
        assertTrue(backend.creates.isEmpty())
        assertEquals(NotificationSyncHost.NEEDS_ACCESS, s.state.value.message)
    }

    @Test fun withoutABackendTheToggleIsDisabled() = runTest {
        val (s, _) = holder(backend = null)
        assertFalse(s.state.value.backendAvailable)
        assertFalse(s.p("bumble").readingToggleEnabled)
        assertEquals(NotificationConnectionCopy.NO_BACKEND_HINT, s.p("bumble").readingHint)
    }

    @Test fun revocationSeenOnRefreshStopsReading() = runTest {
        val (s, host) = holder()
        s.setReading("tinder", true)
        settle()
        access = false
        s.refresh()
        assertFalse(host.core.isActive)
        assertEquals(NotificationConnectionCopy.ACCESS_OFF, s.state.value.accessLabel)
        // Reading is still on (paired) but can be turned off; turning on elsewhere is blocked.
        assertTrue(s.p("tinder").readingToggleEnabled)
        assertFalse(s.p("bumble").readingToggleEnabled)
        assertNull(s.p("tinder").readingHint)
    }
}
