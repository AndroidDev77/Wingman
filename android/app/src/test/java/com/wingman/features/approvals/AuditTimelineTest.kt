package com.wingman.features.approvals

import com.wingman.core.api.AgentActionFilter
import com.wingman.core.api.IdempotencyKey
import com.wingman.core.api.mock.MockFixtures
import com.wingman.core.model.Actor
import com.wingman.core.model.AgentAction
import com.wingman.core.model.AgentActionType
import com.wingman.core.model.ApproveRequest
import com.wingman.core.model.AutomationActionRequest
import com.wingman.core.model.DeliveryState
import com.wingman.core.model.Page
import com.wingman.core.api.WingmanJson
import com.wingman.core.model.ContractFiles
import com.wingman.core.ui.UiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.wingman.features.conversation.settle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneOffset
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class AuditTimelineTest {

    private fun action(type: AgentActionType, details: JsonObject = JsonObject(emptyMap()), reason: String? = null) = AgentAction(
        id = "0192f3a0-0000-7000-8000-000000000999", type = type, actor = Actor.DISPATCHER,
        conversationId = MockFixtures.CONVERSATION_SAM, contactId = MockFixtures.CONTACT_SAM, reasonCode = reason,
        occurredAt = MockFixtures.NOW, details = details,
    )

    @Test fun labelsNeverUpgradeStates() {
        val stage = AgentActionType.entries.associateWith { AuditLabels.of(it).stage }
        assertEquals(SendStage.QUEUED, stage[AgentActionType.SEND_QUEUED])
        assertEquals(SendStage.ATTEMPTED, stage[AgentActionType.SEND_ATTEMPTED])
        assertEquals(SendStage.SENT, stage[AgentActionType.SEND_ACKNOWLEDGED])
        assertEquals(SendStage.DELIVERED, stage[AgentActionType.DELIVERY_CONFIRMED])
        // Every other type claims nothing on the send ladder.
        assertTrue(stage.filterKeys { it !in LADDER }.values.all { it == SendStage.NONE })

        for (type in AgentActionType.entries) {
            val title = AuditLabels.of(type).title.lowercase()
            val claimsSent = Regex("""\bsent\b""").containsMatchIn(title.replace("not sent", "").replace("nothing sent", ""))
            val claimsDelivered = title.contains("delivered") && !title.contains("not confirmed") && !title.contains("delivery not")
            if (claimsSent) assertTrue("$type claims sent: $title", type == AgentActionType.SEND_ACKNOWLEDGED)
            if (claimsDelivered) assertEquals("$type claims delivered: $title", AgentActionType.DELIVERY_CONFIRMED, type)
        }
        assertEquals("Queued, not sent yet", AuditLabels.of(AgentActionType.SEND_QUEUED).title)
        assertEquals("Sent (delivery not confirmed)", AuditLabels.of(AgentActionType.SEND_ACKNOWLEDGED).title)
        assertTrue(AuditLabels.of(AgentActionType.APPROVAL_APPROVED).title.contains("not sent"))
    }

    @Test fun labelsDependOnlyOnTheirOwnEntry() {
        // A queued entry stays "queued" no matter what comes after it in the list.
        val idx = ConversationIndex.EMPTY
        val queued = action(AgentActionType.SEND_QUEUED)
        val later = action(AgentActionType.DELIVERY_CONFIRMED).copy(id = "0192f3a0-0000-7000-8000-000000000998", occurredAt = MockFixtures.NOW.plusSeconds(5))
        val rows = AuditRows.build(listOf(queued, later), idx, MockFixtures.NOW, ZoneOffset.UTC, Locale.US)
        assertEquals(listOf("Delivered", "Queued, not sent yet"), rows.map { it.title })
    }

    @Test fun detailsAndReasonsAreReadable() {
        fun d(vararg p: Pair<String, Any>) = JsonObject(p.associate { (k, v) -> k to (if (v is Boolean) JsonPrimitive(v) else if (v is Int) JsonPrimitive(v) else JsonPrimitive(v.toString())) })
        assertEquals("You edited the text", AuditLabels.detail(action(AgentActionType.APPROVAL_APPROVED, d("edited" to true))))
        assertEquals("Attempt 2, will retry", AuditLabels.detail(action(AgentActionType.SEND_FAILED, d("attempt" to 2, "retryable" to true))))
        assertEquals(
            "canceled 1 pending draft(s), canceled 2 queued message(s)",
            AuditLabels.detail(action(AgentActionType.AUTOMATION_TAKEOVER, d("canceled_approvals" to 1, "canceled_messages" to 2, "scope" to "contact"))),
        )
        assertEquals("for everyone", AuditLabels.detail(action(AgentActionType.AUTOMATION_PAUSED, d("scope" to "global", "canceled_messages" to 0))))
        assertEquals("Because you took over.", AuditLabels.reason("automation_takeover"))
        assertEquals(ReasonText.of(com.wingman.core.model.ApprovalReason.ASSISTED_MODE), AuditLabels.reason("assisted_mode"))
        assertEquals("Reason: transport error", AuditLabels.reason("transport_error"))
    }

    @Test fun contractExampleTimelineRendersHonestly() {
        val page = WingmanJson.decodeFromString(Page.serializer(AgentAction.serializer()), File(ContractFiles.examplesDir(), "agent_actions.page.json").readText())
        val rows = AuditRows.build(page.items, ConversationIndex.EMPTY, MockFixtures.NOW, ZoneOffset.UTC, Locale.US)
        assertEquals(
            listOf(
                "Sent (delivery not confirmed)",
                "Handed to the messaging app, not confirmed yet",
                "Queued, not sent yet",
                "Draft approved (not sent yet)",
                "Draft proposed for your approval",
                "Message received",
            ),
            rows.map { it.title },
        )
        assertTrue(rows.none { it.title == "Delivered" })
    }

    @Test fun timelinePerConversationAndGlobalFromTheServer() = runTest {
        val api = RecordingApi()
        val r = api.mock.approveApproval(MockFixtures.APPROVAL_SAM, ApproveRequest("hi"), IdempotencyKey.random()).getOrNull()!!
        api.mock.simulateDelivery(r.message!!.id, DeliveryState.SENT)
        api.mock.takeoverContact(MockFixtures.CONTACT_JORDAN, AutomationActionRequest(), IdempotencyKey.random())

        val conv = AuditTimelineState(backgroundScope, api, AgentActionFilter(conversationId = MockFixtures.CONVERSATION_SAM), clock = { MockFixtures.NOW }, zone = { ZoneOffset.UTC }, locale = Locale.US)
        conv.load(); settle()
        val titles = (conv.state.value as UiState.Content).data.rows.map { it.title }
        assertTrue(titles.containsAll(listOf("Draft proposed for your approval", "Draft approved (not sent yet)", "Queued, not sent yet", "Handed to the messaging app, not confirmed yet", "Sent (delivery not confirmed)")))
        assertFalse(titles.contains("Delivered"))
        assertFalse(titles.contains("You took over")) // Jordan's, not this conversation's
        assertTrue((conv.state.value as UiState.Content).data.rows.all { it.contactName == "Sam" })

        val global = AuditTimelineState(backgroundScope, api, clock = { MockFixtures.NOW }, zone = { ZoneOffset.UTC }, locale = Locale.US)
        global.load(); settle()
        val all = (global.state.value as UiState.Content).data.rows
        assertTrue(all.any { it.title == "You took over" && it.contactName == "Jordan" })
        assertTrue(all.any { it.title == "Draft canceled (nothing sent)" && it.reason == "Because you took over." })
    }

    @Test fun timelineErrorIsShown() = runTest {
        val api = RecordingApi()
        api.mock.failAllWith(RecordingApi.http(500))
        val t = AuditTimelineState(backgroundScope, api)
        t.load(); settle()
        assertTrue(t.state.value is UiState.Error)
    }

    private companion object {
        val LADDER = setOf(AgentActionType.SEND_QUEUED, AgentActionType.SEND_ATTEMPTED, AgentActionType.SEND_ACKNOWLEDGED, AgentActionType.DELIVERY_CONFIRMED)
    }
}
