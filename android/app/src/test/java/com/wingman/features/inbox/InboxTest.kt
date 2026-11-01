package com.wingman.features.inbox

import com.wingman.core.api.ApiError
import com.wingman.core.api.ApiResult
import com.wingman.core.api.ConversationFilter
import com.wingman.core.api.PageRequest
import com.wingman.core.api.WingmanApi
import com.wingman.core.api.mock.MockFixtures
import com.wingman.core.api.mock.MockWingmanApi
import com.wingman.core.model.AutomationMode
import com.wingman.core.model.Connection
import com.wingman.core.model.Conversation
import com.wingman.core.model.IntentMode
import com.wingman.core.model.Page
import com.wingman.core.ui.PagedList
import com.wingman.core.ui.UiError
import com.wingman.core.ui.UiState
import com.wingman.features.conversation.LabelTone
import com.wingman.features.conversation.settle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.ZoneOffset
import java.util.Locale

/** Records the filters sent and can fail `listConnections`. Can also serve a large inbox. */
private class InboxApi(
    val inner: MockWingmanApi = MockWingmanApi(),
    private val extra: List<Conversation> = emptyList(),
) : WingmanApi by inner {
    val filters = mutableListOf<ConversationFilter>()
    val pages = mutableListOf<PageRequest>()
    var failConnections = false

    override suspend fun listConversations(page: PageRequest, filter: ConversationFilter): ApiResult<Page<Conversation>> {
        filters += filter
        pages += page
        if (extra.isEmpty()) return inner.listConversations(page, filter)
        val offset = page.cursor?.removePrefix("x")?.toInt() ?: 0
        val end = (offset + (page.limit ?: 50)).coerceAtMost(extra.size)
        return ApiResult.Success(Page(extra.subList(offset, end), if (end < extra.size) "x$end" else null))
    }

    override suspend fun listConnections(page: PageRequest): ApiResult<Page<Connection>> =
        if (failConnections) ApiResult.Failure(ApiError.Network(IOException())) else inner.listConnections(page)
}

@OptIn(ExperimentalCoroutinesApi::class)
class InboxTest {
    private val now = MockFixtures.NOW
    private val utc = ZoneOffset.UTC

    private fun TestScope.inbox(api: WingmanApi, pageSize: Int = InboxState.PAGE_SIZE) = InboxState(
        scope = backgroundScope,
        api = api,
        clock = { now },
        zone = { utc },
        locale = Locale.US,
        computeDispatcher = StandardTestDispatcher(testScheduler),
        pageSize = pageSize,
    )

    private fun UiState<InboxList>.rows(): List<InboxRow> = (this as UiState.Content).data.rows

    @Test fun rowShowsBadgeActivityStageUnreadAndAvailability() {
        val byId = MockFixtures.connections.associateBy { it.id }
        val rows = MockFixtures.conversations.associate { c ->
            c.contact.displayName to InboxRows.row(c, byId[c.connectionId], now, utc, Locale.US)
        }
        val sam = rows.getValue("Sam")
        assertEquals("Simulator", sam.platformBadge)
        assertEquals("Connected", sam.availabilityLabel)
        assertTrue(sam.canSend)
        assertEquals("4m ago", sam.lastActivity)
        assertEquals("Rapport", sam.stage)
        assertEquals("Custom goal", sam.intent)
        assertEquals("Assisted", sam.automation)
        assertEquals("1", sam.unreadBadge)
        assertEquals("1 draft to review", sam.pendingApprovals)
        assertEquals("Just got back from Lisbon!", sam.preview)

        val jordan = rows.getValue("Jordan")
        assertEquals("Android SMS", jordan.platformBadge)
        assertEquals("Send UNVERIFIED", jordan.availabilityLabel)
        assertFalse(jordan.canSend)
        assertNull(jordan.unreadBadge)
        assertEquals("1h ago", jordan.lastActivity)

        val riley = rows.getValue("Riley")
        assertEquals("Manual · paused", riley.automation)
        assertEquals("1d ago", riley.lastActivity)
        assertNull(riley.pendingApprovals)

        // Unsupported platform: labeled unavailable, never "Connected".
        val casey = rows.getValue("Casey")
        assertEquals("Notifications", casey.platformBadge)
        assertEquals("Can't send from Wingman", casey.availabilityLabel)
        assertEquals(LabelTone.WARNING, casey.availabilityTone)
        assertFalse(casey.canSend)
        assertEquals("2", casey.unreadBadge)
        assertEquals("2d ago", casey.lastActivity)
    }

    @Test fun reducerPassesThroughLoadingEmptyErrorAndDedupes() {
        assertEquals(UiState.Loading, InboxRows.reduce(UiState.Loading, null, now, utc))
        assertEquals(UiState.Empty, InboxRows.reduce(UiState.Empty, null, now, utc))
        val err = UiState.Error(UiError("boom"))
        assertEquals(err, InboxRows.reduce(err, emptyList(), now, utc))

        val c = MockFixtures.conversations
        val pager = UiState.Content(
            PagedList(c + c.first(), nextCursor = "n", loadingMore = true, loadMoreError = UiError("page")),
            refreshing = true,
        )
        val out = InboxRows.reduce(pager, null, now, utc, Locale.US) as UiState.Content
        assertEquals(c.size, out.data.rows.size)
        assertTrue(out.data.loadingMore)
        assertEquals("page", out.data.loadMoreError?.message)
        assertTrue(out.refreshing)
        assertFalse(out.data.capabilitiesKnown)
        assertTrue("no capabilities -> never claims connected", out.data.rows.none { it.canSend || it.availabilityLabel == "Connected" })
    }

    @Test fun loadsMockInboxNewestFirst() = runTest {
        val api = InboxApi()
        val s = inbox(api)
        assertEquals(UiState.Loading, s.state.value)
        s.load(); settle()
        val content = s.state.value as UiState.Content
        assertEquals(listOf("Sam", "Jordan", "Riley", "Casey"), content.data.rows.map { it.contactName })
        assertTrue(content.data.capabilitiesKnown)
        assertTrue(content.data.endReached)
        assertEquals(InboxState.PAGE_SIZE, api.pages.first().limit)
    }

    @Test fun filtersAreSentAsContractQueryAndResetPaging() = runTest {
        val api = InboxApi()
        val s = inbox(api)
        s.load(); settle()

        s.setIntent(IntentMode.SERIOUS); settle()
        assertEquals(ConversationFilter(intent = IntentMode.SERIOUS), api.filters.last())
        assertNull("filter change restarts at the first page", api.pages.last().cursor)
        assertEquals(listOf("Jordan"), s.state.value.rows().map { it.contactName })

        s.setAutomationMode(AutomationMode.ASSISTED); settle()
        assertEquals(ConversationFilter(intent = IntentMode.SERIOUS, automationMode = AutomationMode.ASSISTED), api.filters.last())
        assertEquals(UiState.Empty, s.state.value)
        assertEquals("No conversations match these filters.", s.filter.value.emptyMessage)

        s.setIntent(null); settle()
        assertEquals(listOf("Sam"), s.state.value.rows().map { it.contactName })

        val calls = api.filters.size
        s.setAutomationMode(AutomationMode.ASSISTED); settle()
        assertEquals("unchanged filter does not reload", calls, api.filters.size)

        s.clearFilters(); settle()
        assertFalse(s.filter.value.isActive)
        assertEquals(4, s.state.value.rows().size)
        assertEquals("No conversations yet. Connect a platform to get started.", s.filter.value.emptyMessage)
    }

    @Test fun filterOptionsAndLabels() {
        assertNull(InboxFilter.intentOptions.first())
        assertEquals(IntentMode.entries.size + 1, InboxFilter.intentOptions.size)
        assertEquals(AutomationMode.entries.size + 1, InboxFilter.automationOptions.size)
        assertEquals("Any intent", InboxFilter.intentLabel(null))
        assertEquals("Autopilot", InboxFilter.automationLabel(AutomationMode.AUTOPILOT))
        assertEquals(ConversationFilter(), InboxFilter().toApi())
    }

    @Test fun cursorPaginationOverLargeInbox() = runTest {
        val template = MockFixtures.conversations.first()
        val many = (1..130).map { n ->
            template.copy(
                id = "0192f3a0-0000-7000-8002-" + n.toString().padStart(12, '0'),
                contact = template.contact.copy(displayName = "Contact $n"),
                lastActivityAt = now.minusSeconds(n * 60L),
            )
        }
        val api = InboxApi(extra = many)
        val s = inbox(api, pageSize = 50)
        s.load(); settle()
        assertEquals(50, s.state.value.rows().size)
        s.loadMore(); settle()
        assertEquals("x50", api.pages.last().cursor)
        s.loadMore(); settle()
        val content = s.state.value as UiState.Content
        assertEquals(130, content.data.rows.size)
        assertTrue(content.data.endReached)
        assertEquals(130, content.data.rows.map { it.key }.toSet().size)
        val callsAtEnd = api.pages.size
        s.loadMore(); settle()
        assertEquals("no fetch past the end", callsAtEnd, api.pages.size)
    }

    @Test fun loadMoreFailureKeepsRowsAndCanRetry() = runTest {
        val template = MockFixtures.conversations.first()
        val many = (1..60).map { n -> template.copy(id = "0192f3a0-0000-7000-8002-" + n.toString().padStart(12, '0')) }
        val delegate = InboxApi(extra = many)
        val api = object : WingmanApi by delegate {
            var failNext = false
            override suspend fun listConversations(page: PageRequest, filter: ConversationFilter): ApiResult<Page<Conversation>> {
                if (failNext) { failNext = false; return ApiResult.Failure(ApiError.Http(503, null)) }
                return delegate.listConversations(page, filter)
            }
        }
        val s = inbox(api, pageSize = 50)
        s.load(); settle()
        api.failNext = true
        s.loadMore(); settle()
        val failed = s.state.value as UiState.Content
        assertEquals(50, failed.data.rows.size)
        assertTrue(failed.data.loadMoreError != null && failed.data.loadMoreError!!.retryable)
        s.loadMore(); settle()
        assertEquals(60, s.state.value.rows().size)
    }

    @Test fun errorStateThenRetry() = runTest {
        val api = InboxApi()
        val s = inbox(api)
        api.inner.failAllWith(ApiError.Network(IOException()))
        s.load(); settle()
        val err = s.state.value
        assertTrue("got $err", err is UiState.Error && err.error.isNetwork)
        api.inner.failAllWith(null)
        s.refresh(); settle()
        val content = s.state.value as UiState.Content
        assertEquals(4, content.data.rows.size)
        assertTrue("connections reloaded after failing", content.data.capabilitiesKnown)
    }

    @Test fun emptyInbox() = runTest {
        val api = object : WingmanApi by MockWingmanApi() {
            override suspend fun listConversations(page: PageRequest, filter: ConversationFilter): ApiResult<Page<Conversation>> =
                ApiResult.Success(Page(emptyList(), null))
        }
        val s = inbox(api)
        s.load(); settle()
        assertEquals(UiState.Empty, s.state.value)
    }

    @Test fun connectionsFailureShowsStatusUnknownNotConnected() = runTest {
        val api = InboxApi().apply { failConnections = true }
        val s = inbox(api)
        s.load(); settle()
        val content = s.state.value as UiState.Content
        assertFalse(content.data.capabilitiesKnown)
        assertTrue(content.data.rows.all { it.availabilityLabel == "Status unknown" && !it.canSend })
        // Badges fall back to the opaque platform string.
        assertEquals(listOf("mock", "sms", "mock", "android_notification"), content.data.rows.map { it.platformBadge })
    }
}
