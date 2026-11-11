package com.wingman.integrations.screenreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The allowlist and the toggles: off by default, other packages dropped before anything is read. */
class ScreenReaderGateTest {
    @Test fun `everything is off by default and capture needs the explainer first`() {
        val w = ReaderWorld(capture = false)
        for (pkg in ScreenReaderApps.ALL + "com.example.bank") assertEquals(ReadMode.DROP, w.core.mode(pkg))
        assertEquals(ReadMode.DROP, w.core.mode(null))
        assertFalse(w.settings.anythingEnabled())
        assertFalse("capture cannot be switched on before the explainer", w.settings.setCaptureEnabled(ScreenReaderApps.HINGE, true))
        assertEquals(ReadMode.DROP, w.core.mode(ScreenReaderApps.HINGE))
        w.settings.explainerAccepted = true
        assertTrue(w.settings.setCaptureEnabled(ScreenReaderApps.HINGE, true))
        assertEquals(ReadMode.CAPTURE, w.core.mode(ScreenReaderApps.HINGE))
        assertTrue(w.settings.setInspectorEnabled(ScreenReaderApps.HINGE, true))
        assertEquals(ReadMode.INSPECT_AND_CAPTURE, w.core.mode(ScreenReaderApps.HINGE))
        // Withdrawing the explainer acceptance stops capture at once.
        w.settings.explainerAccepted = false
        assertEquals(ReadMode.INSPECT, w.core.mode(ScreenReaderApps.HINGE))
    }

    @Test fun `only hinge can be captured - tinder and bumble are inspector only - nothing else at all`() {
        val w = ReaderWorld()
        assertEquals(setOf("co.hinge.app"), ScreenReaderApps.CAPTURE)
        assertEquals(setOf("co.hinge.app", "com.tinder", "com.bumble.app"), ScreenReaderApps.ALL)
        for (pkg in listOf(ScreenReaderApps.TINDER, ScreenReaderApps.BUMBLE)) {
            assertFalse(w.settings.setCaptureEnabled(pkg, true))
            assertTrue(w.settings.setInspectorEnabled(pkg, true))
            assertEquals(ReadMode.INSPECT, w.core.mode(pkg))
            assertFalse(ReadMode.INSPECT.capture)
        }
        for (pkg in listOf("com.example.bank", "com.android.settings", "co.hinge.app.evil", "")) {
            assertFalse(w.settings.setCaptureEnabled(pkg, true))
            assertFalse(w.settings.setInspectorEnabled(pkg, true))
            assertEquals(ReadMode.DROP, w.core.mode(pkg))
        }
    }

    @Test fun `a snapshot of any other package is dropped with nothing recorded`() {
        val w = ReaderWorld(inspect = true)
        assertEquals(SnapshotOutcome.DROPPED, w.core.onSnapshot(bankScreen()))
        // Even a profile-shaped tree from another package is dropped.
        assertEquals(SnapshotOutcome.DROPPED, w.core.onSnapshot(profileTop(pkg = "com.example.bank")))
        w.core.flush()
        assertTrue(w.inspector.snapshots().isEmpty())
        assertTrue(w.profiles.all().isEmpty())
        assertTrue(w.captured.isEmpty())
        for (s in Sentinels.ALL) assertFalse(w.storedText().contains(s))
        assertTrue(w.log.lines.isEmpty())
    }

    @Test fun `switching capture off stops reading immediately and discards the viewing in progress`() {
        val w = ReaderWorld()
        assertEquals(SnapshotOutcome.PROFILE_PART, w.core.onSnapshot(profileTop()))
        assertTrue(w.settings.setCaptureEnabled(ScreenReaderApps.HINGE, false))
        w.core.stop(ScreenReaderApps.HINGE)
        // Later screens are dropped, and leaving the profile saves nothing.
        assertEquals(SnapshotOutcome.DROPPED, w.core.onSnapshot(profileScrolled()))
        assertEquals(SnapshotOutcome.DROPPED, w.core.onSnapshot(chatScreen()))
        w.core.flush()
        w.core.onIdle(1_000_000)
        assertTrue(w.profiles.all().isEmpty())
        assertNull(w.core.lastChatContext())
        for (s in Sentinels.ALL) assertFalse(w.storedText().contains(s))
    }

    @Test fun `switching off between the last snapshot and leaving saves nothing even without stop`() {
        val w = ReaderWorld()
        w.core.onSnapshot(profileTop())
        w.settings.setCaptureEnabled(ScreenReaderApps.HINGE, false)
        w.core.flush()
        assertTrue(w.profiles.all().isEmpty())
    }

    @Test fun `inspector-only mode never captures a profile`() {
        val w = ReaderWorld(capture = false, inspect = true)
        assertEquals(SnapshotOutcome.INSPECTED, w.core.onSnapshot(profileTop()))
        assertEquals(SnapshotOutcome.INSPECTED, w.core.onSnapshot(chatScreen()))
        w.core.flush()
        assertTrue(w.profiles.all().isEmpty())
        assertNull(w.core.lastChatContext())
        assertEquals(2, w.inspector.snapshots().size)
        for (s in Sentinels.ALL) assertFalse(s, w.storedText().contains(s))
    }
}
