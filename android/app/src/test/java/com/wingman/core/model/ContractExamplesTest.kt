package com.wingman.core.model

import com.wingman.core.api.WingmanJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * Decodes every relevant file in `contracts/examples/` (the frozen v1.0.0 examples) into the
 * Kotlin models and re-encodes it, checking that no field is lost or renamed.
 */
class ContractExamplesTest {

    private fun example(name: String): String = File(ContractFiles.examplesDir(), name).readText()

    /** Decode, re-encode, and compare as JSON trees (absent optional keys == explicit null). */
    private fun <T> roundTrip(name: String, serializer: KSerializer<T>): T {
        val raw = example(name)
        val decoded = WingmanJson.decodeFromString(serializer, raw)
        val reEncoded = WingmanJson.encodeToJsonElement(serializer, decoded)
        assertEquals("round trip of $name", normalize(Json.parseToJsonElement(raw)), normalize(reEncoded))
        return decoded
    }

    private fun normalize(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.filterValues { it !is JsonNull }.mapValues { normalize(it.value) })
        is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(e.map(::normalize))
        else -> e
    }

    @Test fun health() {
        val h = roundTrip("health.response.json", HealthResponse.serializer())
        assertEquals("ok", h.status)
        assertEquals(Environment.DEV, h.env)
        assertEquals(Instant.parse("2026-11-01T03:46:14.241376Z"), h.time)
    }

    @Test fun me() {
        val u = roundTrip("me.response.json", User.serializer())
        assertEquals("Alex", u.displayName)
    }

    @Test fun preferences() {
        val p = roundTrip("preferences.response.json", Preferences.serializer())
        assertEquals(3, p.version)
        assertEquals(AutomationMode.ASSISTED, p.automation.mode)
        assertEquals(listOf(Weekday.SAT, Weekday.SUN), p.automation.activeHours!!.windows[1].days)
        assertEquals(Tone.WARM, p.style.tone)
        val d = roundTrip("preferences.defaults.response.json", Preferences.serializer())
        assertEquals(0, d.version)
        assertNull(d.updatedAt)
        assertEquals(AutomationMode.MANUAL, d.automation.mode)
        roundTrip("preferences.put.request.json", PreferencesUpdate.serializer())
    }

    @Test fun contactPreferences() {
        val c = roundTrip("contact_preferences.response.json", ContactPreferences.serializer())
        assertEquals(IntentMode.CUSTOM, c.override.intent!!.mode)
        assertNull(c.override.style)
        roundTrip("contact_preferences.put.request.json", ContactPreferenceOverrideUpdate.serializer())
    }

    @Test fun connectionsAndCapabilities() {
        val page = roundTrip("connections.page.json", Page.serializer(Connection.serializer()))
        val sms = page.items.first { it.platform == "sms" }.capabilities.capabilities
        assertEquals(Verification.UNVERIFIED, sms.send.status)
        assertEquals(true, sms.send.reportsDeliveryReceipts)
        assertFalse(sms.swipe.isAvailable)
        for (name in listOf("capabilities.sms.json", "capabilities.mock.json", "capabilities.android_notification.json")) {
            val caps = roundTrip(name, ConnectorCapabilities.serializer())
            assertEquals("connector_capabilities.v1", caps.schemaVersion)
        }
        val notif = roundTrip("capabilities.android_notification.json", ConnectorCapabilities.serializer())
        // Notification access never implies send (ADR 0005).
        assertEquals(Verification.UNSUPPORTED, notif.capabilities.swipe.status)
    }

    @Test fun conversations() {
        val page = roundTrip("conversations.page.json", Page.serializer(Conversation.serializer()))
        assertEquals("eyJrIjoiMjAyNi0xMC0zMVQxODowMTowMFoifQ", page.nextCursor)
        assertFalse(page.isLastPage)
        val c = page.items.single()
        assertEquals(ConversationStage.RAPPORT, c.stage)
        assertEquals(ContactAutomationStatus.ACTIVE, c.automation.contactStatus)

        val d = roundTrip("conversation.detail.json", ConversationDetail.serializer())
        assertEquals(NextAction.REPLY, d.analysis!!.nextAction)
        assertEquals(c, d.conversation)
    }

    @Test fun conversationDetailRequiresAnalysisKey() {
        // `analysis` is required (nullable): an explicit null decodes, a missing key does not.
        val withNull = example("conversation.detail.json").replace(Regex("\"analysis\":\\s*\\{[\\s\\S]*\\}\\s*\\}\\s*$"), "\"analysis\": null }")
        assertNull(WingmanJson.decodeFromString(ConversationDetail.serializer(), withNull).analysis)
        val obj = Json.parseToJsonElement(example("conversation.detail.json")).jsonObject
        val missing = JsonObject(obj - "analysis").toString()
        assertThrows(SerializationException::class.java) {
            WingmanJson.decodeFromString(ConversationDetail.serializer(), missing)
        }
    }

    @Test fun messages() {
        val page = roundTrip("messages.page.json", Page.serializer(Message.serializer()))
        assertTrue(page.isLastPage)
        assertEquals(DeliveryState.RECEIVED, page.items[0].deliveryState)
        assertEquals(DeliveryState.DELIVERED, page.items[1].deliveryState)
        roundTrip("send_message.request.json", SendMessageRequest.serializer())
        val sent = roundTrip("send_message.response.json", Message.serializer())
        assertEquals(DeliveryState.QUEUED, sent.deliveryState)
        assertNull(sent.sentAt)
    }

    @Test fun drafts() {
        roundTrip("draft.create.request.json", DraftCreateRequest.serializer())
        val d = roundTrip("draft.response.json", Draft.serializer())
        assertEquals(DraftDecision.SEND_CANDIDATE, d.decision)
        assertNotNull(d.text)
        val ask = roundTrip("draft.ask_user.response.json", Draft.serializer())
        assertEquals(DraftDecision.ASK_USER, ask.decision)
        assertNull(ask.text)
        assertEquals(listOf(ApprovalReason.DATE_OR_COMMITMENT), ask.approvalReasons)
    }

    @Test fun draftTextKeyIsRequiredEvenWhenNull() {
        val obj = Json.parseToJsonElement(example("draft.ask_user.response.json")).jsonObject
        assertThrows(SerializationException::class.java) {
            WingmanJson.decodeFromString(Draft.serializer(), JsonObject(obj - "text").toString())
        }
    }

    @Test fun approvals() {
        val page = roundTrip("approvals.page.json", Page.serializer(Approval.serializer()))
        assertEquals(ApprovalStatus.PENDING, page.items.single().status)
        roundTrip("approval.approve.request.json", ApproveRequest.serializer())
        roundTrip("approval.reject.request.json", RejectRequest.serializer())
        val approved = roundTrip("approval.approve.response.json", ApprovalDecisionResult.serializer())
        assertEquals(DeliveryState.QUEUED, approved.message!!.deliveryState)
        val rejected = roundTrip("approval.reject.response.json", ApprovalDecisionResult.serializer())
        assertNull(rejected.message)
        assertEquals(ApprovalStatus.REJECTED, rejected.approval.status)
    }

    @Test fun automation() {
        roundTrip("automation.action.request.json", AutomationActionRequest.serializer())
        val t = roundTrip("automation.takeover.response.json", ContactAutomationState.serializer())
        assertEquals(ContactAutomationStatus.TAKEN_OVER, t.status)
        assertEquals(1, t.canceledApprovalCount)
        val g = roundTrip("automation.global_pause.response.json", GlobalAutomationState.serializer())
        assertEquals(GlobalAutomationStatus.PAUSED, g.status)
    }

    @Test fun agentActions() {
        val page = roundTrip("agent_actions.page.json", Page.serializer(AgentAction.serializer()))
        assertEquals(AgentActionType.SEND_ACKNOWLEDGED, page.items.first().type)
        assertEquals(Actor.CONNECTOR, page.items.first().actor)
        assertEquals("assisted_mode", page.items.first { it.type == AgentActionType.APPROVAL_REQUESTED }.reasonCode)
    }

    @Test fun problems() {
        for (name in listOf("problem.idempotency_mismatch.json", "problem.validation_error.json", "problem.capability_unsupported.json")) {
            val p = roundTrip(name, Problem.serializer())
            assertNotNull("known code in $name", p.errorCode)
        }
        val v = roundTrip("problem.validation_error.json", Problem.serializer())
        assertEquals(ErrorCode.VALIDATION_ERROR, v.errorCode)
        assertTrue(v.errors!!.isNotEmpty())
        assertEquals(ErrorCode.CAPABILITY_UNSUPPORTED, roundTrip("problem.capability_unsupported.json", Problem.serializer()).errorCode)
    }

    @Test fun unknownProblemCodeStillDecodesAndFallsBack() {
        // contracts/examples/invalid/problem.unknown_code.json fails the SCHEMA, but a client must
        // still survive it and fall back to the HTTP status (ADR 0002).
        val p = WingmanJson.decodeFromString(Problem.serializer(), File(ContractFiles.examplesDir(), "invalid/problem.unknown_code.json").readText())
        assertNull(p.errorCode)
    }

    @Test fun unknownResponseFieldsAreIgnored() {
        val obj = Json.parseToJsonElement(example("me.response.json")).jsonObject
        val extended = JsonObject(obj + ("added_in_v1_1" to kotlinx.serialization.json.JsonPrimitive("x"))).toString()
        assertEquals("Alex", WingmanJson.decodeFromString(User.serializer(), extended).displayName)
    }

    @Test fun unknownEnumValueIsADecodingError() {
        val bad = example("me.response.json") // sanity: fine
        WingmanJson.decodeFromString(User.serializer(), bad)
        assertThrows(SerializationException::class.java) {
            WingmanJson.decodeFromString(DeliveryState.serializer(), "\"teleported\"")
        }
    }

    @Test fun timestampsMustBeUtcZ() {
        assertEquals("\"2026-10-31T18:04:05.123456Z\"", WingmanJson.encodeToString(UtcInstantSerializer, Instant.parse("2026-10-31T18:04:05.123456789Z")))
        assertEquals("\"2026-10-31T18:04:05Z\"", WingmanJson.encodeToString(UtcInstantSerializer, Instant.parse("2026-10-31T18:04:05Z")))
        for (bad in listOf("2026-10-31T18:04:05+00:00", "2026-10-31T18:04:05.1234567Z", "2026-10-31 18:04:05Z", "2026-13-31T18:04:05Z")) {
            assertThrows(bad, SerializationException::class.java) { WingmanJson.decodeFromString(UtcInstantSerializer, "\"$bad\"") }
        }
    }

    @Test fun enumWireNamesMatchContract() {
        assertEquals("casual_physical", IntentMode.CASUAL_PHYSICAL.wireName())
        assertEquals("taken_over", ContactAutomationStatus.TAKEN_OVER.wireName())
        assertEquals("send_dead_lettered", AgentActionType.SEND_DEAD_LETTERED.wireName())
        assertEquals("pending", ApprovalStatus.PENDING.wireName())
        assertEquals("\"dead_lettered\"", WingmanJson.encodeToString(DeliveryState.serializer(), DeliveryState.DEAD_LETTERED))
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @Test fun enumValuesMatchOpenApi() {
        val spec = ContractFiles.openApiText()
        fun contractEnum(schema: String): List<String> {
            val marker = "\n    $schema:\n"
            check(marker in spec) { "schema $schema not in openapi.yaml" }
            val block = spec.substringAfter(marker).lineSequence()
                .takeWhile { l -> l.isBlank() || l.startsWith("      ") }.joinToString("\n")
            val inline = Regex("enum: \\[([^\\]]*)]").find(block)
            if (inline != null) return inline.groupValues[1].split(',').map { it.trim() }
            return block.substringAfter("enum:").lineSequence().map { it.trim() }
                .filter { it.startsWith("- ") }.map { it.removePrefix("- ").substringBefore('#').trim() }.toList()
        }
        fun <E : Enum<E>> wire(values: Array<E>, ser: KSerializer<E>) = values.map { ser.descriptor.getElementName(it.ordinal) }

        assertEquals(contractEnum("IntentMode"), wire(IntentMode.entries.toTypedArray(), IntentMode.serializer()))
        assertEquals(contractEnum("AutomationMode"), wire(AutomationMode.entries.toTypedArray(), AutomationMode.serializer()))
        assertEquals(contractEnum("ConversationStage"), wire(ConversationStage.entries.toTypedArray(), ConversationStage.serializer()))
        assertEquals(contractEnum("DeliveryState"), wire(DeliveryState.entries.toTypedArray(), DeliveryState.serializer()))
        assertEquals(contractEnum("MessageDirection"), wire(MessageDirection.entries.toTypedArray(), MessageDirection.serializer()))
        assertEquals(contractEnum("MessageOrigin"), wire(MessageOrigin.entries.toTypedArray(), MessageOrigin.serializer()))
        assertEquals(contractEnum("ApprovalStatus"), wire(ApprovalStatus.entries.toTypedArray(), ApprovalStatus.serializer()))
        assertEquals(contractEnum("ApprovalReason"), wire(ApprovalReason.entries.toTypedArray(), ApprovalReason.serializer()))
        assertEquals(contractEnum("DraftDecision"), wire(DraftDecision.entries.toTypedArray(), DraftDecision.serializer()))
        assertEquals(contractEnum("ContactAutomationStatus"), wire(ContactAutomationStatus.entries.toTypedArray(), ContactAutomationStatus.serializer()))
        assertEquals(contractEnum("GlobalAutomationStatus"), wire(GlobalAutomationStatus.entries.toTypedArray(), GlobalAutomationStatus.serializer()))
        assertEquals(contractEnum("Actor"), wire(Actor.entries.toTypedArray(), Actor.serializer()))
        assertEquals(contractEnum("AgentActionType"), wire(AgentActionType.entries.toTypedArray(), AgentActionType.serializer()))
        assertEquals(contractEnum("ConnectionStatus"), wire(ConnectionStatus.entries.toTypedArray(), ConnectionStatus.serializer()))
        assertEquals(contractEnum("ErrorCode"), ErrorCode.entries.map { it.wire })
        // Sanity: the parser found real lists.
        assertEquals(18, contractEnum("ErrorCode").size)
        assertEquals(38, contractEnum("AgentActionType").size) // 35 + 3 in 1.3.2 (D-032)
        // Contract 1.3.0 (ADR 0008) enums.
        assertEquals(contractEnum("ConnectionTransport"), wire(ConnectionTransport.entries.toTypedArray(), ConnectionTransport.serializer()))
        assertEquals(contractEnum("InboundEventKind"), wire(InboundEventKind.entries.toTypedArray(), InboundEventKind.serializer()))
        assertEquals(contractEnum("SenderIdentity"), wire(SenderIdentity.entries.toTypedArray(), SenderIdentity.serializer()))
        assertEquals(contractEnum("AssistedDeliveryStatus"), wire(AssistedDeliveryStatus.entries.toTypedArray(), AssistedDeliveryStatus.serializer()))
        assertEquals(contractEnum("ProfileDecision"), wire(ProfileDecision.entries.toTypedArray(), ProfileDecision.serializer()))
        assertEquals(contractEnum("ProfileActionExecution"), wire(ProfileActionExecution.entries.toTypedArray(), ProfileActionExecution.serializer()))
        assertEquals(9, contractEnum("DeliveryState").size)
        assertEquals(5, contractEnum("MessageOrigin").size)
    }

    @Test fun contractV13Examples() {
        val conn = roundTrip("connection.hinge_experimental.response.json", Connection.serializer())
        assertEquals(ConnectionTransport.UNOFFICIAL_API, conn.transport)
        assertTrue(conn.isExperimentalUnverified)
        assertNull(conn.experimentalConsent!!.revokedAt)
        assertEquals(Verification.UNVERIFIED, conn.capabilities.capabilities.profileRead!!.status)
        val revoked = roundTrip("connection.revoke_consent.response.json", ConnectionUnpairResult.serializer())
        assertNotNull(revoked.connection.experimentalConsent!!.revokedAt)
        assertFalse(revoked.connection.isPaired)
        val reported = roundTrip("message.user_reported_sent.json", Message.serializer())
        assertEquals(DeliveryState.USER_REPORTED_SENT, reported.deliveryState)
        assertEquals(MessageOrigin.ASSISTED_DRAFT, reported.origin)
        assertNull(reported.sentAt)
        val p = roundTrip("problem.experimental_consent_required.json", Problem.serializer())
        assertEquals(ErrorCode.EXPERIMENTAL_CONSENT_REQUIRED, p.errorCode)
        assertEquals(ErrorCode.SWIPE_EXECUTION_DISABLED, roundTrip("problem.swipe_execution_disabled.json", Problem.serializer()).errorCode)
        // Older capability documents (no profile_read) still decode.
        val sms = roundTrip("capabilities.sms.json", ConnectorCapabilities.serializer())
        assertNull(sms.capabilities.profileRead)
    }

    @Test fun stringSerializerSanity() {
        assertEquals("\"x\"", WingmanJson.encodeToString(String.serializer(), "x"))
    }
}
