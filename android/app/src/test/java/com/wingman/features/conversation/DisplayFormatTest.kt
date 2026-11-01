package com.wingman.features.conversation

import com.wingman.core.api.ApiError
import com.wingman.core.api.ApiResult
import com.wingman.core.api.mock.MockFixtures
import com.wingman.core.api.mock.MockWingmanApi
import com.wingman.core.model.AutomationMode
import com.wingman.core.model.Capability
import com.wingman.core.model.ConnectionStatus
import com.wingman.core.model.ContactAutomationStatus
import com.wingman.core.model.ConversationAutomation
import com.wingman.core.model.GlobalAutomationStatus
import com.wingman.core.model.Verification
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

class DisplayFormatTest {
    private val now = Instant.parse("2026-10-31T18:05:00Z")
    private val utc: ZoneId = ZoneOffset.UTC

    private fun rel(raw: String, zone: ZoneId = utc) = TimeFormat.relative(Instant.parse(raw), now, zone, Locale.US)

    @Test fun relativeTimeBuckets() {
        assertEquals("now", rel("2026-10-31T18:04:30Z"))
        assertEquals("now", rel("2026-10-31T18:10:00Z")) // future (clock skew) is never negative
        assertEquals("1m ago", rel("2026-10-31T18:04:00Z"))
        assertEquals("59m ago", rel("2026-10-31T17:06:00Z"))
        assertEquals("1h ago", rel("2026-10-31T17:05:00Z"))
        assertEquals("23h ago", rel("2026-10-30T19:00:00Z"))
        assertEquals("1d ago", rel("2026-10-30T18:05:00Z"))
        assertEquals("6d ago", rel("2026-10-25T18:05:00Z"))
        assertEquals("Oct 24", rel("2026-10-24T18:05:00Z"))
        assertEquals("Dec 31, 2025", rel("2025-12-31T12:00:00Z"))
    }

    @Test fun absoluteDatesUseViewerZoneButRelativeIsZoneIndependent() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        // 2026-10-20T20:00Z is Oct 21 in Tokyo.
        assertEquals("Oct 20", rel("2026-10-20T20:00:00Z"))
        assertEquals("Oct 21", rel("2026-10-20T20:00:00Z", tokyo))
        assertEquals(rel("2026-10-31T15:00:00Z"), rel("2026-10-31T15:00:00Z", tokyo))
        assertEquals("Oct 31, 17:50", TimeFormat.messageTime(Instant.parse("2026-10-31T17:50:00Z"), utc, Locale.US))
        assertEquals("Nov 1, 02:50", TimeFormat.messageTime(Instant.parse("2026-10-31T17:50:00Z"), tokyo, Locale.US))
    }

    @Test fun labels() {
        assertNull(Labels.unreadBadge(0))
        assertNull(Labels.unreadBadge(-1))
        assertEquals("7", Labels.unreadBadge(7))
        assertEquals("99", Labels.unreadBadge(99))
        assertEquals("99+", Labels.unreadBadge(100))
        val active = ConversationAutomation(AutomationMode.ASSISTED, ContactAutomationStatus.ACTIVE, GlobalAutomationStatus.ACTIVE)
        assertEquals("Assisted", Labels.automationStatus(active))
        assertEquals("Assisted · paused", Labels.automationStatus(active.copy(contactStatus = ContactAutomationStatus.PAUSED)))
        assertEquals("Assisted · you took over", Labels.automationStatus(active.copy(contactStatus = ContactAutomationStatus.TAKEN_OVER)))
        // Global pause wins over everything.
        assertEquals(
            "Assisted · all automation paused",
            Labels.automationStatus(active.copy(contactStatus = ContactAutomationStatus.TAKEN_OVER, globalStatus = GlobalAutomationStatus.PAUSED)),
        )
    }

    private val sms = MockFixtures.connections.first { it.id == MockFixtures.CONNECTION_SMS }
    private val sim = MockFixtures.connections.first { it.id == MockFixtures.CONNECTION_MOCK }
    private val notif = MockFixtures.connections.first { it.id == MockFixtures.CONNECTION_NOTIFICATION }

    @Test fun unsupportedSendIsLabeledUnavailableNotConnected() {
        val a = Availability.of(notif.platform, notif)
        assertFalse(a.canSend)
        assertEquals("Can't send from Wingman", a.label)
        assertEquals(LabelTone.WARNING, a.tone)
        assertTrue(a.detail.contains("unsupported", ignoreCase = true))
        assertFalse(a.label.contains("Connected"))
        assertTrue("read is UNVERIFIED on this connection", a.unverified)
    }

    @Test fun unverifiedSendIsFlaggedAndNotSendable() {
        val a = Availability.of(sms.platform, sms)
        assertFalse(a.canSend)
        assertTrue(a.unverified)
        assertEquals("Send UNVERIFIED", a.label)
    }

    @Test fun onlyVerifiedSendOnConnectedConnectionIsConnected() {
        val a = Availability.of(sim.platform, sim)
        assertTrue(a.canSend)
        assertEquals("Connected", a.label)
        assertEquals(LabelTone.SUCCESS, a.tone)
        // The simulator note is surfaced so "VERIFIED" is never mistaken for a real platform.
        assertTrue(a.detail.contains("Simulated"))

        for (status in listOf(ConnectionStatus.DISCONNECTED, ConnectionStatus.ERROR)) {
            val off = Availability.of(sim.platform, sim.copy(status = status))
            assertFalse("$status must not be sendable", off.canSend)
            assertEquals(LabelTone.ERROR, off.tone)
        }
    }

    @Test fun fullyUnsupportedAndUnknownConnections() {
        val none = Capability(Verification.UNSUPPORTED)
        val caps = sim.capabilities.copy(capabilities = sim.capabilities.capabilities.copy(read = none, send = none))
        val dead = Availability.of("someplatform", sim.copy(capabilities = caps))
        assertEquals("Unsupported", dead.label)
        assertFalse(dead.canSend)

        val unknown = Availability.of("tinder", null)
        assertEquals("tinder", unknown.badge)
        assertEquals("Status unknown", unknown.label)
        assertFalse(unknown.canSend)
        assertTrue(unknown.unverified)
    }

    @Test fun availabilityNeverBranchesOnPlatformName() {
        // Same capabilities under any platform string give the same verdict (ticket constraint).
        for (p in listOf("sms", "mock", "hinge", "x")) {
            val a = Availability.of(p, sms.copy(platform = p))
            assertEquals("Send UNVERIFIED", a.label)
            assertFalse(a.canSend)
        }
    }

    @Test fun loadAllConnectionsPagesAndFails() = runTest {
        val api = MockWingmanApi()
        val ok = loadAllConnections(api)
        assertTrue(ok is ApiResult.Success && ok.value.first.size == 3 && ok.value.second)
        api.failNextWith(ApiError.Network(IOException()))
        assertTrue(loadAllConnections(api) is ApiResult.Failure)
    }
}
