package com.wingman.features.assisted

import com.wingman.core.api.ApiError
import com.wingman.core.api.ApiResult
import com.wingman.core.api.AssistedDeliveryFilter
import com.wingman.core.api.IdempotencyKey
import com.wingman.core.api.PageRequest
import com.wingman.core.api.WingmanApi
import com.wingman.core.api.mock.MockAssistedFixtures
import com.wingman.core.api.mock.MockFixtures
import com.wingman.core.api.mock.MockWingmanApi
import com.wingman.core.model.AssistedDelivery
import com.wingman.core.model.AssistedDeliveryConfirmRequest
import com.wingman.core.model.AssistedDeliveryResult
import com.wingman.core.model.AssistedDeliveryStatus
import com.wingman.core.model.ConnectionTransport
import com.wingman.core.model.DeliveryState
import com.wingman.core.model.InboundEventKind
import com.wingman.core.model.InboundNotice
import com.wingman.core.model.MessageOrigin
import com.wingman.core.model.RejectRequest
import com.wingman.core.model.SenderIdentity
import com.wingman.features.conversation.settle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant

/** WING-027/028 assisted flow over MockWingmanApi (contract 1.3.0 semantics). Synthetic text only. */
@OptIn(ExperimentalCoroutinesApi::class)
class AssistedStateTest {
    private val pending = MockAssistedFixtures.ASSISTED_PENDING

    class Api(val mock: MockWingmanApi = MockWingmanApi()) : WingmanApi by mock {
        val confirms = mutableListOf<Pair<String, String>>()
        val dismisses = mutableListOf<Pair<String, String>>()
        var gate: CompletableDeferred<Unit>? = null
        var failNextConfirm: ApiResult.Failure? = null

        override suspend fun confirmAssistedDelivery(
            assistedDeliveryId: String,
            body: AssistedDeliveryConfirmRequest,
            key: IdempotencyKey,
        ): ApiResult<AssistedDeliveryResult> {
            confirms += assistedDeliveryId to key.value
            gate?.await()
            failNextConfirm?.let { failNextConfirm = null; return it }
            return mock.confirmAssistedDelivery(assistedDeliveryId, body, key)
        }

        override suspend fun dismissAssistedDelivery(assistedDeliveryId: String, body: RejectRequest, key: IdempotencyKey): ApiResult<AssistedDeliveryResult> {
            dismisses += assistedDeliveryId to key.value
            return mock.dismissAssistedDelivery(assistedDeliveryId, body, key)
        }
    }

    private val copied = mutableListOf<String>()
    private val opened = mutableListOf<Pair<String, String?>>()
    private val counts = mutableListOf<Int>()

    private fun TestScope.holder(api: Api = Api(), open: OpenAppResult = OpenAppResult.Opened) =
        AssistedState(backgroundScope, api, { copied += it }, { p, t -> opened += p to t; open }, onPendingCount = { c, _ -> counts += c })
            .also { it.load(); settle() }

    private fun AssistedState.row(id: String = pending) = state.value.rows.first { it.id == id }

    @Test fun listsPendingDraftsWithPlatformTextAndConversation() = runTest {
        val s = holder()
        val r = s.row()
        assertEquals("tinder", r.platform)
        assertEquals("Tinder", r.platformLabel)
        assertEquals(MockFixtures.CONVERSATION_SAM, r.conversationId)
        assertTrue(r.text.startsWith("Synthetic draft"))
        assertTrue(r.actionable)
        assertEquals("Waiting for you to send it", r.statusLabel)
        assertEquals(1, s.state.value.pendingCount)
        assertEquals(listOf(1), counts)
        assertFalse(r.toString().contains("Synthetic draft")) // redacted
    }

    @Test fun copyAndOpenNeverConfirm() = runTest {
        val api = Api()
        val s = holder(api)
        s.copy(pending)
        s.open(pending)
        settle()
        assertEquals(listOf(s.row().text), copied)
        assertEquals(listOf("tinder" to "shortcut:synthetic-thread-1"), opened)
        assertTrue(api.confirms.isEmpty())
        assertTrue(api.dismisses.isEmpty())
        assertEquals(AssistedDeliveryStatus.PENDING, s.row().status)
        assertEquals(AssistedCopy.OPENED, s.state.value.message)
        assertTrue(api.mock.calls.none { it == "confirmAssistedDelivery" })
    }

    @Test fun openReportsWhenTheAppIsMissing() = runTest {
        val s = holder(open = OpenAppResult.Unavailable("Tinder isn't installed on this phone."))
        s.open(pending)
        assertEquals("Tinder isn't installed on this phone.", s.state.value.message)
    }

    @Test fun confirmShowsYouSentThisNeverDelivered() = runTest {
        val api = Api()
        val s = holder(api)
        s.confirmSent(pending)
        settle()
        val r = s.row()
        assertEquals(AssistedDeliveryStatus.USER_REPORTED_SENT, r.status)
        assertEquals("You sent this", r.statusLabel)
        assertFalse(r.actionable)
        for (st in AssistedDeliveryStatus.entries) {
            val label = AssistedCopy.statusLabel(st)
            assertFalse(label, label.contains("Delivered", ignoreCase = true))
            assertFalse(label, label.equals("Sent", ignoreCase = true))
        }
        // The server recorded the user's report: assisted_draft / user_reported_sent, no sent_at.
        val msg = api.mock.listMessages(MockFixtures.CONVERSATION_SAM).getOrNull()!!.items.single { it.origin == MessageOrigin.ASSISTED_DRAFT }
        assertEquals(MessageOrigin.ASSISTED_DRAFT, msg.origin)
        assertEquals(DeliveryState.USER_REPORTED_SENT, msg.deliveryState)
        assertNull(msg.sentAt)
        assertNull(msg.deliveredAt)
        assertEquals(listOf(1, 0), counts)
    }

    @Test fun confirmWithAnEditReportsTheEditedText() = runTest {
        val api = Api()
        val s = holder(api)
        s.confirmSent(pending, editedText = "Synthetic edited text")
        settle()
        assertEquals("Synthetic edited text", s.row().text)
        val msg = api.mock.listMessages(MockFixtures.CONVERSATION_SAM).getOrNull()!!.items.single { it.origin == MessageOrigin.ASSISTED_DRAFT }
        assertEquals("Synthetic edited text", msg.text)
    }

    @Test fun doubleTapSendsOneRequestWithOneKey() = runTest {
        val api = Api()
        val s = holder(api)
        api.gate = CompletableDeferred()
        s.confirmSent(pending)
        runCurrent()
        assertTrue(s.row().busy)
        repeat(4) { s.confirmSent(pending) }
        s.dismiss(pending) // no conflicting decision while one is in flight
        runCurrent()
        api.gate!!.complete(Unit)
        settle()
        assertEquals(1, api.confirms.size)
        assertTrue(api.dismisses.isEmpty())
        // Already decided: further taps send nothing.
        s.confirmSent(pending)
        settle()
        assertEquals(1, api.confirms.size)
    }

    @Test fun retryAfterANetworkErrorReusesTheKey() = runTest {
        val api = Api()
        val s = holder(api)
        api.failNextConfirm = ApiResult.Failure(ApiError.Network(IOException("offline")))
        s.confirmSent(pending)
        settle()
        assertEquals(AssistedDeliveryStatus.PENDING, s.row().status)
        assertTrue(s.state.value.message!!.startsWith("Couldn't save that"))
        s.confirmSent(pending)
        settle()
        assertEquals(2, api.confirms.size)
        assertEquals(api.confirms[0].second, api.confirms[1].second)
        assertEquals("You sent this", s.row().statusLabel)
    }

    @Test fun conflict409IsHandledByReloading() = runTest {
        val api = Api()
        val s = holder(api)
        // Handled elsewhere (e.g. another device dismissed it).
        api.mock.dismissAssistedDelivery(pending, RejectRequest(), IdempotencyKey("other-device-1"))
        s.confirmSent(pending)
        settle()
        assertEquals(AssistedCopy.ALREADY_HANDLED, s.state.value.message)
        assertTrue(s.state.value.rows.none { it.id == pending })
        assertEquals(1, api.confirms.size)
        // The mock answered 409 assisted_delivery_not_pending.
        val again = api.mock.confirmAssistedDelivery(pending, AssistedDeliveryConfirmRequest(), IdempotencyKey("other-key-2"))
        assertEquals(409, (again.errorOrNull() as ApiError.Http).status)
    }

    @Test fun dismissRecordsNothingAsSent() = runTest {
        val api = Api()
        val s = holder(api)
        s.dismiss(pending)
        settle()
        assertEquals("Dismissed", s.row().statusLabel)
        assertTrue(api.mock.listMessages(MockFixtures.CONVERSATION_SAM).getOrNull()!!.items.none { it.origin == MessageOrigin.ASSISTED_DRAFT })
    }

    @Test fun noticesAreReadOnlyWithKindLabels() = runTest {
        val s = holder()
        val notices = s.state.value.notices
        assertEquals(listOf("New match", "Expiring"), notices.map { it.kindLabel })
        // NoticeRow carries no action; the screen offers none.
        fun n(kind: InboundEventKind, identity: SenderIdentity, hidden: Boolean = false, truncated: Boolean = false) = InboundNotice(
            "0192f3a0-0000-7000-8000-000000000f99", "c", "tinder", kind, identity, "Synthetic", "t", truncated, hidden, "x", null,
            ConnectionTransport.NOTIFICATION, Instant.EPOCH, Instant.EPOCH,
        )
        assertEquals("Couldn't link this to a conversation, so Wingman won't draft a reply.", AssistedCopy.noticeNote(n(InboundEventKind.CHAT_MESSAGE, SenderIdentity.UNLINKED)))
        assertEquals("The app hid the text. Open the app to read it.", AssistedCopy.noticeNote(n(InboundEventKind.CHAT_MESSAGE, SenderIdentity.LINKED, hidden = true)))
        assertEquals("Only part of the message was shown.", AssistedCopy.noticeNote(n(InboundEventKind.CHAT_MESSAGE, SenderIdentity.LINKED, truncated = true)))
        assertNull(AssistedCopy.noticeNote(n(InboundEventKind.MATCH, SenderIdentity.UNLINKED)))
        assertEquals("Like or reaction", AssistedCopy.noticeKindLabel(n(InboundEventKind.LIKE_COMMENT, SenderIdentity.UNLINKED)))
    }

    @Test fun badgeCountsPendingAndKeepsTheLastValueOnFailure() = runTest {
        val api = MockWingmanApi()
        val b = AssistedBadgeState(backgroundScope, api)
        b.refresh()
        settle()
        assertEquals(AssistedBadge(1, false), b.badge.value)
        assertEquals("1", b.badge.value!!.label)
        api.failNextWith()
        b.refresh()
        settle()
        assertEquals(AssistedBadge(1, false), b.badge.value)
        b.report(0, false)
        assertNull(b.badge.value!!.label)
        assertEquals("99+", AssistedBadge(150, false).label)
        assertEquals("3+", AssistedBadge(3, true).label)
    }

    @Test fun cancelReasonsHavePlainLabels() {
        assertEquals("A newer message arrived, so this draft was replaced.", AssistedCopy.cancelLabel("superseded"))
        assertEquals("This app was disconnected.", AssistedCopy.cancelLabel("connection_unavailable"))
        assertNull(AssistedCopy.cancelLabel(null))
    }

    @Test fun mockListsOnlyPendingByDefaultNewestFirst() = runTest {
        val api = MockWingmanApi()
        val later = MockAssistedFixtures.assistedDeliveries.single().copy(
            id = "0192f3a0-0000-7000-8000-000000a27002", createdAt = Instant.parse("2026-10-31T18:06:00Z"),
        )
        api.queueAssistedDelivery(later)
        val page = api.listAssistedDeliveries(PageRequest.FIRST, AssistedDeliveryFilter()).getOrNull()!!
        assertEquals(listOf(later.id, pending), page.items.map(AssistedDelivery::id))
        api.dismissAssistedDelivery(later.id, RejectRequest(), IdempotencyKey("dismiss-1"))
        assertEquals(listOf(pending), api.listAssistedDeliveries().getOrNull()!!.items.map { it.id })
        assertEquals(
            listOf(later.id),
            api.listAssistedDeliveries(filter = AssistedDeliveryFilter(status = AssistedDeliveryStatus.DISMISSED)).getOrNull()!!.items.map { it.id },
        )
    }
}
