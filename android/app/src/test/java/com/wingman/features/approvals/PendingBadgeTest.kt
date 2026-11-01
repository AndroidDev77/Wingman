package com.wingman.features.approvals

import com.wingman.core.api.IdempotencyKey
import com.wingman.core.api.mock.MockFixtures
import com.wingman.core.model.ApproveRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.wingman.features.conversation.settle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PendingBadgeTest {

    @Test fun labels() {
        assertNull(BadgeCount(0, more = false).label)
        assertEquals("No drafts waiting for approval", BadgeCount(0, more = false).description)
        assertEquals("1", BadgeCount(1, more = false).label)
        assertEquals("1 draft waiting for approval", BadgeCount(1, more = false).description)
        assertEquals("7", BadgeCount(7, more = false).label)
        assertEquals("50+", BadgeCount(50, more = true).label)
        assertEquals("More than 50 drafts waiting for approval", BadgeCount(50, more = true).description)
        assertEquals("99+", BadgeCount(150, more = false).label)
    }

    @Test fun countsPendingFromTheServer() = runTest {
        val api = RecordingApi()
        val badge = PendingBadgeState(backgroundScope, api)
        assertNull(badge.badge.value) // unknown until the server answers
        badge.refresh(); settle()
        assertEquals(BadgeCount(2, more = false), badge.badge.value)

        api.mock.approveApproval(MockFixtures.APPROVAL_SAM, ApproveRequest(), IdempotencyKey.random())
        badge.refresh(); settle()
        assertEquals("1", badge.badge.value!!.label)
    }

    @Test fun moreThanOnePageShowsPlus() = runTest {
        val badge = PendingBadgeState(backgroundScope, RecordingApi(), pageSize = 1)
        badge.refresh(); settle()
        assertEquals("1+", badge.badge.value!!.label)
    }

    @Test fun failedRefreshKeepsTheLastKnownCount() = runTest {
        val api = RecordingApi()
        val badge = PendingBadgeState(backgroundScope, api)
        badge.refresh(); settle()
        api.mock.failNextWith()
        badge.refresh(); settle()
        assertEquals(BadgeCount(2, more = false), badge.badge.value)
    }

    @Test fun reportFromTheScreenWins() = runTest {
        val badge = PendingBadgeState(backgroundScope, RecordingApi())
        badge.refresh(); settle()
        badge.report(BadgeCount(0, more = false))
        assertNull(badge.badge.value!!.label)
        badge.report(null) // unknown does not erase a known count
        assertEquals(BadgeCount(0, more = false), badge.badge.value)
    }
}
