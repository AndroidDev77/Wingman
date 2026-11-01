package com.wingman.features.conversation

import com.wingman.core.api.ApiError
import com.wingman.core.api.ApiResult
import com.wingman.core.api.IdempotencyKey
import com.wingman.core.api.WingmanApi
import com.wingman.core.api.PageRequest
import com.wingman.core.api.mock.MockFixtures
import com.wingman.core.api.mock.MockWingmanApi
import com.wingman.core.model.ApprovalReason
import com.wingman.core.model.Connection
import com.wingman.core.model.DeliveryState
import com.wingman.core.model.Draft
import com.wingman.core.model.DraftCreateRequest
import com.wingman.core.model.DraftDecision
import com.wingman.core.model.ErrorCode
import com.wingman.core.model.Message
import com.wingman.core.model.MessageDirection
import com.wingman.core.model.MessageFailure
import com.wingman.core.model.MessageOrigin
import com.wingman.core.model.Page
import com.wingman.core.ui.UiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/** Records draft idempotency keys and can fail `listConnections`. */
private class RecordingApi(
    val inner: MockWingmanApi = MockWingmanApi(),
) : WingmanApi by inner {
    val draftKeys = mutableListOf<IdempotencyKey>()
    val draftBodies = mutableListOf<DraftCreateRequest>()
    var sendCalls = 0
    var failConnections = false

    override suspend fun createDraft(conversationId: String, body: DraftCreateRequest, key: IdempotencyKey): ApiResult<Draft> {
        draftKeys += key
        draftBodies += body
        return inner.createDraft(conversationId, body, key)
    }

    override suspend fun sendMessage(
        conversationId: String,
        body: com.wingman.core.model.SendMessageRequest,
        key: IdempotencyKey,
    ): ApiResult<Message> {
        sendCalls++
        return inner.sendMessage(conversationId, body, key)
    }

    override suspend fun listConnections(page: PageRequest): ApiResult<Page<Connection>> =
        if (failConnections) ApiResult.Failure(ApiError.Network(IOException())) else inner.listConnections(page)
}

/**
 * State holders run in `backgroundScope` (their `stateIn` flows never complete), and
 * `advanceUntilIdle` ignores background work, so advance virtual time explicitly. 60s covers the
 * retry backoff (RetryPolicy caps delays at 5s).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.settle() {
    advanceTimeBy(60_000)
    runCurrent()
}

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationTest {
    private val now = MockFixtures.NOW
    private val utc = ZoneOffset.UTC

    private fun msg(
        n: Int,
        at: String,
        direction: MessageDirection = MessageDirection.OUTBOUND,
        state: DeliveryState = DeliveryState.SENT,
        origin: MessageOrigin = if (direction == MessageDirection.INBOUND) MessageOrigin.CONTACT else MessageOrigin.USER_MANUAL,
        failure: MessageFailure? = null,
    ) = Message(
        id = "0192f3a0-0000-7000-9999-" + n.toString().padStart(12, '0'),
        conversationId = MockFixtures.CONVERSATION_SAM,
        direction = direction,
        origin = origin,
        text = "m$n",
        deliveryState = state,
        attemptCount = 1,
        failure = failure,
        createdAt = Instant.parse(at),
    )

    /* ------------------------------------------------------------ delivery honesty */

    @Test fun deliveryLabelsAreHonest() {
        fun label(s: DeliveryState, f: MessageFailure? = null) = DeliveryLabels.of(msg(1, "2026-10-31T10:00:00Z", state = s, failure = f))
        assertEquals("Queued, not sent yet", label(DeliveryState.QUEUED).text)
        assertEquals("Sending…", label(DeliveryState.SENDING).text)
        assertEquals("Sent", label(DeliveryState.SENT).text)
        assertEquals("Delivered", label(DeliveryState.DELIVERED).text)
        // queued/sending must never read as sent; sent must never read as delivered.
        for (s in listOf(DeliveryState.QUEUED, DeliveryState.SENDING)) {
            assertFalse(label(s).text.startsWith("Sent"))
            assertEquals(LabelTone.PENDING, label(s).tone)
        }
        assertFalse(label(DeliveryState.SENT).text.contains("Delivered"))
        assertEquals(LabelTone.SUCCESS, label(DeliveryState.DELIVERED).tone)
        assertTrue(DeliveryState.entries.filter { label(it).tone == LabelTone.SUCCESS } == listOf(DeliveryState.DELIVERED))

        val retrying = label(DeliveryState.FAILED, MessageFailure("timeout", null, retryable = true))
        assertEquals("Failed, will retry", retrying.text)
        assertTrue(retrying.isFailure)
        assertEquals(LabelTone.ERROR, retrying.tone)
        assertEquals("Failed", label(DeliveryState.FAILED, MessageFailure("rejected_by_platform", null, retryable = false)).text)
        assertEquals("Failed, not sent", label(DeliveryState.DEAD_LETTERED).text)
        assertTrue(label(DeliveryState.DEAD_LETTERED).isFailure)
        assertEquals("Canceled, not sent", label(DeliveryState.CANCELED).text)
    }

    @Test fun rowsAreChronologicalDedupedAndAnnotated() {
        val input = listOf(
            msg(3, "2026-10-31T10:02:00Z", state = DeliveryState.FAILED, failure = MessageFailure("transport_error", "x", true)),
            msg(1, "2026-10-30T23:59:00Z", direction = MessageDirection.INBOUND, state = DeliveryState.RECEIVED),
            msg(2, "2026-10-31T10:00:00Z", origin = MessageOrigin.AUTOPILOT),
            msg(2, "2026-10-31T10:00:00Z", origin = MessageOrigin.AUTOPILOT), // duplicate from overlapping pages
            msg(4, "2026-10-31T10:00:00Z", origin = MessageOrigin.APPROVED_DRAFT, state = DeliveryState.QUEUED), // tie -> by id
        )
        val rows = MessageRows.build(input, utc, Locale.US)
        assertEquals(listOf("m1", "m2", "m4", "m3"), rows.map { it.text })
        assertEquals(rows.size, rows.map { it.key }.toSet().size)

        assertFalse(rows[0].outbound)
        assertNull("inbound bubbles carry no delivery label", rows[0].delivery)
        assertTrue(rows[0].startsNewDay)
        assertTrue("new calendar day", rows[1].startsNewDay)
        assertFalse(rows[2].startsNewDay)
        assertEquals("Sat, Oct 31, 2026", rows[1].dayLabel)

        assertEquals("Sent by autopilot", rows[1].originNote)
        assertEquals("Approved draft", rows[2].originNote)
        assertEquals("Queued, not sent yet", rows[2].delivery?.text)
        assertEquals("Reason: transport_error", rows[3].failureNote)
        assertTrue(rows[3].delivery!!.isFailure)
        assertNull(rows[1].failureNote)
        assertEquals("Oct 31, 10:02", rows[3].time)
    }

    @Test fun largeThreadsMapQuickly() {
        val base = Instant.parse("2026-01-01T00:00:00Z")
        val many = (1..5_000).map { n ->
            msg(n, base.plusSeconds(n * 61L).toString(), direction = if (n % 2 == 0) MessageDirection.INBOUND else MessageDirection.OUTBOUND,
                state = if (n % 2 == 0) DeliveryState.RECEIVED else DeliveryState.SENT)
        }.shuffled(java.util.Random(7))
        MessageRows.build(many.take(200), utc, Locale.US) // warm up
        val start = System.nanoTime()
        val rows = MessageRows.build(many, utc, Locale.US)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(5_000, rows.size)
        assertEquals((1..5_000).map { "m$it" }, rows.map { it.text })
        assertTrue("mapping 5000 messages took ${ms}ms", ms < 2_000)
    }

    /* ------------------------------------------------------------ screen loading */

    @Test fun mockConversationOpensWith100PlusChronologicalMessages() = runTest {
        val api = MockWingmanApi()
        val r = loadConversationScreen(api, MockFixtures.CONVERSATION_RILEY, now, utc, Locale.US)
        assertTrue(r is ApiResult.Success)
        val data = (r as ApiResult.Success).value
        assertEquals(MockFixtures.LONG_THREAD_SIZE, data.messageCount)
        assertTrue(data.messageCount >= 100)
        assertFalse(data.truncated)
        assertEquals((1..MockFixtures.LONG_THREAD_SIZE).map { "Message $it" }, data.rows.map { it.text })
        assertEquals(data.rows.size, data.rows.map { it.key }.toSet().size)
        assertEquals("Riley", data.contactName)
        assertTrue(data.subtitle.contains("Follow-up"))
        assertTrue(data.subtitle.contains("paused"))
        // Default page size 100 -> two message pages.
        assertEquals(2, api.calls.count { it == "listMessages" })
    }

    @Test fun messagePageCapMarksTruncated() = runTest {
        val r = loadConversationScreen(MockWingmanApi(), MockFixtures.CONVERSATION_RILEY, now, utc, Locale.US, messagePageSize = 50, maxMessagePages = 2)
        val data = (r as ApiResult.Success).value
        assertEquals(100, data.messageCount)
        assertTrue(data.truncated)
    }

    @Test fun unsupportedPlatformConversationIsLabeledUnavailable() = runTest {
        val r = loadConversationScreen(MockWingmanApi(), MockFixtures.CONVERSATION_CASEY, now, utc, Locale.US)
        val a = (r as ApiResult.Success).value.availability
        assertFalse(a.canSend)
        assertEquals("Can't send from Wingman", a.label)
    }

    @Test fun capabilityFailureNeverShowsConnected() = runTest {
        val api = RecordingApi().apply { failConnections = true }
        val r = loadConversationScreen(api, MockFixtures.CONVERSATION_SAM, now, utc, Locale.US)
        val a = (r as ApiResult.Success).value.availability
        assertEquals("Status unknown", a.label)
        assertFalse(a.canSend)
    }

    @Test fun unknownConversationAndMessageFailuresAreErrors() = runTest {
        val api = MockWingmanApi()
        val missing = loadConversationScreen(api, "0192f3a0-0000-7000-8000-00000000dead", now, utc)
        assertEquals(ErrorCode.NOT_FOUND, missing.errorOrNull()?.code)
        // getConversation succeeds, then listMessages fails.
        val flaky = object : WingmanApi by api {
            override suspend fun listMessages(conversationId: String, page: PageRequest) =
                ApiResult.Failure(ApiError.Http(503, null))
        }
        assertTrue(loadConversationScreen(flaky, MockFixtures.CONVERSATION_SAM, now, utc) is ApiResult.Failure)
    }

    private fun TestScope.state(api: WingmanApi, id: String = MockFixtures.CONVERSATION_SAM) = ConversationState(
        scope = backgroundScope,
        api = api,
        conversationId = id,
        clock = { now },
        zone = { utc },
        locale = Locale.US,
        computeDispatcher = StandardTestDispatcher(testScheduler),
    )

    @Test fun stateLoadingErrorRetryContent() = runTest {
        val api = MockWingmanApi()
        val s = state(api)
        assertEquals(UiState.Loading, s.state.value)
        api.failNextWith()
        s.load(); settle()
        val err = s.state.value
        assertTrue("got $err", err is UiState.Error && err.error.isNetwork && err.error.retryable)
        s.load(); settle()
        val content = s.state.value
        assertTrue(content is UiState.Content)
        assertEquals(listOf("Hey! How was your week?", "Busy but good! Yours?", "Just got back from Lisbon!"),
            (content as UiState.Content).data.rows.map { it.text })
        assertEquals("Delivered", content.data.rows[1].delivery?.text)
    }

    /* ------------------------------------------------------------ drafts */

    @Test fun doubleTapCreatesOneDraftWithOneKeyAndNeverSends() = runTest {
        val api = RecordingApi()
        val s = state(api)
        assertEquals(DraftPanel.Idle, s.draft.value)
        assertTrue(s.requestDraft())
        assertFalse("second tap while in flight is dropped", s.requestDraft())
        settle()
        assertEquals(1, api.draftKeys.size)
        assertEquals(1, api.inner.calls.count { it == "createDraft" })
        assertEquals(0, api.sendCalls)
        assertFalse(api.inner.calls.contains("sendMessage"))
        val panel = s.draft.value
        assertTrue("got $panel", panel is DraftPanel.Ready)
        panel as DraftPanel.Ready
        assertEquals("That sounds great! Tell me more.", panel.suggestion)
        assertEquals(DraftPanel.NOT_SENT, panel.notSentNote)
        assertFalse(panel.replayed)
        // Blank guidance is sent as null.
        assertNull(api.draftBodies.single().guidance)
    }

    @Test fun retryableFailureRetriesWithSameKey() = runTest {
        val api = RecordingApi()
        val s = state(api)
        api.inner.failNextWith(ApiError.Network(IOException()))
        s.setGuidance("ask about her trip")
        assertTrue(s.requestDraft())
        settle()
        assertEquals(2, api.draftKeys.size)
        assertEquals(1, api.draftKeys.toSet().size)
        assertEquals("ask about her trip", api.draftBodies.last().guidance)
        assertTrue(s.draft.value is DraftPanel.Ready)
    }

    @Test fun exhaustedFailureShowsErrorAndEditedGuidanceGetsFreshKey() = runTest {
        val api = RecordingApi()
        val s = state(api)
        api.inner.failAllWith(ApiError.Network(IOException()))
        s.requestDraft(); settle()
        val failed = s.draft.value
        assertTrue("got $failed", failed is DraftPanel.Failed && failed.error.retryable)
        val firstKey = api.draftKeys.last()

        // Manual retry with the same body reuses the kept key (safe replay).
        api.inner.failAllWith(null)
        s.requestDraft(); settle()
        assertEquals(firstKey, api.draftKeys.last())
        assertTrue(s.draft.value is DraftPanel.Ready)

        // A later request after an edit is a new action with a new key, so no 422 mismatch.
        s.setGuidance("something else")
        s.requestDraft(); settle()
        assertNotEquals(firstKey, api.draftKeys.last())
        assertTrue(s.draft.value is DraftPanel.Ready)
    }

    @Test fun editAfterFailureDoesNotReuseKeyForDifferentBody() = runTest {
        val api = RecordingApi()
        val s = state(api)
        api.inner.failAllWith(ApiError.Network(IOException()))
        s.requestDraft(); settle()
        val firstKey = api.draftKeys.last()
        api.inner.failAllWith(null)
        s.setGuidance("new hint")
        s.requestDraft(); settle()
        assertNotEquals(firstKey, api.draftKeys.last())
        assertTrue(s.draft.value is DraftPanel.Ready)
    }

    @Test fun tooLongGuidanceIsRejectedClientSide() = runTest {
        val api = RecordingApi()
        val s = state(api)
        s.setGuidance("x".repeat(DraftInput.GUIDANCE_MAX + 1))
        assertFalse(s.requestDraft())
        settle()
        assertTrue(api.draftKeys.isEmpty())
        assertEquals("Keep guidance under 500 characters.", DraftInput.guidanceError(s.guidance.value))
        assertNull(DraftInput.guidanceError("  ok  "))
        assertEquals("ok", DraftInput.request("  ok  ")?.guidance)
    }

    @Test fun draftForUnknownConversationFails() = runTest {
        val s = state(RecordingApi(), id = "0192f3a0-0000-7000-8000-00000000dead")
        s.requestDraft(); settle()
        val p = s.draft.value
        assertTrue(p is DraftPanel.Failed && p.error.code == ErrorCode.NOT_FOUND && !p.error.retryable)
        s.dismissDraft(); settle()
        assertEquals(DraftPanel.Idle, s.draft.value)
    }

    @Test fun draftPanelDecisions() {
        val base = Draft(
            id = "d", conversationId = "c", decision = DraftDecision.SEND_CANDIDATE, text = "hi",
            rationale = "why", confidence = 0.5, requiresApproval = true,
            approvalReasons = listOf(ApprovalReason.CAPABILITY_UNVERIFIED, ApprovalReason.FIRST_MESSAGE),
            intentMode = com.wingman.core.model.IntentMode.OPEN,
            grounding = com.wingman.core.model.DraftGrounding(emptyList(), emptyList()), createdAt = now,
        )
        val send = DraftPanel.ready(base)
        assertEquals("hi", send.suggestion)
        assertEquals("Would need approval: platform capability unverified, first message", send.approvalNote)

        val noSend = DraftPanel.ready(base.copy(decision = DraftDecision.NO_SEND, text = null, requiresApproval = false))
        assertNull(noSend.suggestion)
        assertNull(noSend.approvalNote)
        assertEquals("Wingman suggests not replying yet", noSend.title)

        val ask = DraftPanel.ready(base.copy(decision = DraftDecision.ASK_USER, text = null, questionForUser = "Did you meet?"))
        assertEquals("Did you meet?", ask.explanation)
        assertEquals("Wingman needs your input", ask.title)
        // Text on a non-send_candidate is never offered as a suggestion.
        assertNull(DraftPanel.ready(base.copy(decision = DraftDecision.NO_SEND, text = "leaked")).suggestion)
    }
}
