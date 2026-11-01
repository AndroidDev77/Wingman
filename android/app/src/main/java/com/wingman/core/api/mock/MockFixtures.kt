package com.wingman.core.api.mock

import com.wingman.core.model.Approval
import com.wingman.core.model.ApprovalReason
import com.wingman.core.model.ApprovalStatus
import com.wingman.core.model.AutomationMode
import com.wingman.core.model.AutomationSettings
import com.wingman.core.model.Capability
import com.wingman.core.model.CapabilitySet
import com.wingman.core.model.Connection
import com.wingman.core.model.ConnectionStatus
import com.wingman.core.model.ConnectorCapabilities
import com.wingman.core.model.ContactAutomationStatus
import com.wingman.core.model.ContactSummary
import com.wingman.core.model.Conversation
import com.wingman.core.model.ConversationAnalysis
import com.wingman.core.model.ConversationAutomation
import com.wingman.core.model.ConversationStage
import com.wingman.core.model.DeliveryState
import com.wingman.core.model.Draft
import com.wingman.core.model.DraftDecision
import com.wingman.core.model.DraftGrounding
import com.wingman.core.model.Environment
import com.wingman.core.model.GlobalAutomationStatus
import com.wingman.core.model.HealthResponse
import com.wingman.core.model.Intent
import com.wingman.core.model.IntentMode
import com.wingman.core.model.InterestSignal
import com.wingman.core.model.Message
import com.wingman.core.model.MessageDirection
import com.wingman.core.model.MessageOrigin
import com.wingman.core.model.NextAction
import com.wingman.core.model.Preferences
import com.wingman.core.model.SignalPolarity
import com.wingman.core.model.StyleProfile
import com.wingman.core.model.User
import com.wingman.core.model.Verification
import java.time.Instant

/**
 * Deterministic offline fixtures, modeled on `contracts/examples/`. IDs are fixed lowercase UUIDs
 * so tests and previews are stable. Nothing here pretends a real platform works: the SMS
 * connection is UNVERIFIED, the notification connection cannot send, and the mock connection's
 * VERIFIED labels say they are simulated (ADR 0005).
 */
object MockFixtures {
    private fun id(suffix: String) = "0192f3a0-0000-7000-8000-" + suffix.padStart(12, '0')
    private fun t(raw: String): Instant = Instant.parse(raw)

    val NOW: Instant = t("2026-10-31T18:05:00Z")

    const val USER_ID = "0192f3a0-0000-7000-8000-000000000001"
    val CONNECTION_SMS = id("a1")

    /** The app install the fixture SMS connection is paired with (ADR 0007). Only it may claim (I-018, D-017). */
    const val SMS_DEVICE_INSTALL_ID = "0192f3a0-0000-4000-8000-0000000005f1"
    val CONNECTION_MOCK = id("a2")
    val CONNECTION_NOTIFICATION = id("a3")

    val CONTACT_SAM = id("c1")
    val CONTACT_JORDAN = id("c2")
    val CONTACT_RILEY = id("c3")

    val CONVERSATION_SAM = id("b1")
    val CONVERSATION_JORDAN = id("b2")
    val CONVERSATION_RILEY = id("b3")
    val CONTACT_CASEY = id("c4")

    /** On the notification connection, whose `send` is UNSUPPORTED: sends must fail with a typed error. */
    val CONVERSATION_CASEY = id("b4")

    val APPROVAL_SAM = id("f1")
    val APPROVAL_JORDAN = id("f2")

    /** Riley's thread has [LONG_THREAD_SIZE] messages so list UIs can be tested against 100+ items. */
    const val LONG_THREAD_SIZE = 120

    val health = HealthResponse(status = "ok", version = "0.1.0-mock", env = Environment.DEV, time = NOW)

    val me = User(id = USER_ID, email = "tester@example.com", displayName = "Alex", createdAt = t("2026-10-01T12:00:00Z"))

    val defaultPreferences = Preferences(
        version = 0,
        intent = Intent(IntentMode.SERIOUS, customDescription = null),
        dealbreakers = emptyList(),
        style = StyleProfile(),
        // Automation is off by default until the user enables it.
        automation = AutomationSettings(mode = AutomationMode.MANUAL, approvalRequiredFor = emptyList()),
        updatedAt = null,
    )

    private val unsupported = Capability(Verification.UNSUPPORTED)
    private fun simulated(receipts: Boolean? = null) = Capability(
        status = Verification.VERIFIED,
        evidence = "backend/tests/connectors (deterministic simulator)",
        verifiedAt = t("2026-10-31T18:00:00Z"),
        notes = "Simulated transport. VERIFIED means the simulator contract is tested, not a real platform.",
        reportsDeliveryReceipts = receipts,
    )

    private fun caps(platform: String, set: CapabilitySet) = ConnectorCapabilities(
        schemaVersion = "connector_capabilities.v1",
        platform = platform,
        connectorVersion = "0.1.0",
        capabilities = set,
        assessedAt = t("2026-10-31T18:00:00Z"),
    )

    val connections: List<Connection> = listOf(
        Connection(
            id = CONNECTION_SMS, platform = "sms", displayName = "Android SMS", status = ConnectionStatus.CONNECTED,
            capabilities = caps(
                "sms",
                CapabilitySet(
                    read = Capability(Verification.UNVERIFIED, notes = "Pending physical-device test (WING-016)."),
                    draft = unsupported,
                    send = Capability(Verification.UNVERIFIED, reportsDeliveryReceipts = true, notes = "Not yet device-tested."),
                    swipe = unsupported,
                    calendarWrite = unsupported,
                ),
            ),
            createdAt = t("2026-10-30T09:00:00Z"), updatedAt = t("2026-10-31T18:00:00Z"),
            deviceInstallId = SMS_DEVICE_INSTALL_ID, pairedAt = t("2026-10-30T09:00:00Z"),
        ),
        Connection(
            id = CONNECTION_MOCK, platform = "mock", displayName = "Simulator", status = ConnectionStatus.CONNECTED,
            capabilities = caps(
                "mock",
                CapabilitySet(simulated(), unsupported, simulated(receipts = true), unsupported, unsupported),
            ),
            createdAt = t("2026-10-30T09:00:00Z"), updatedAt = t("2026-10-31T18:00:00Z"),
        ),
        Connection(
            id = CONNECTION_NOTIFICATION, platform = "android_notification", displayName = "Notifications",
            status = ConnectionStatus.CONNECTED,
            capabilities = caps(
                "android_notification",
                CapabilitySet(
                    read = Capability(Verification.UNVERIFIED, notes = "Notification access gives read only (WING-017)."),
                    draft = unsupported, send = unsupported, swipe = unsupported, calendarWrite = unsupported,
                ),
            ),
            createdAt = t("2026-10-30T09:00:00Z"), updatedAt = t("2026-10-31T18:00:00Z"),
        ),
    )

    val conversations: List<Conversation> = listOf(
        Conversation(
            id = CONVERSATION_SAM,
            contact = ContactSummary(CONTACT_SAM, "Sam"),
            connectionId = CONNECTION_MOCK,
            platform = "mock",
            externalThreadId = "mock-thread-1",
            stage = ConversationStage.RAPPORT,
            effectiveIntent = IntentMode.CUSTOM,
            automation = ConversationAutomation(AutomationMode.ASSISTED, ContactAutomationStatus.ACTIVE, GlobalAutomationStatus.ACTIVE),
            unreadCount = 1,
            pendingApprovalCount = 1,
            lastMessagePreview = "Just got back from Lisbon!",
            lastActivityAt = t("2026-10-31T18:01:00Z"),
            createdAt = t("2026-10-29T20:00:00Z"),
        ),
        Conversation(
            id = CONVERSATION_JORDAN,
            contact = ContactSummary(CONTACT_JORDAN, "Jordan"),
            connectionId = CONNECTION_SMS,
            platform = "sms",
            externalThreadId = null,
            stage = ConversationStage.INTRODUCTION,
            effectiveIntent = IntentMode.SERIOUS,
            automation = ConversationAutomation(AutomationMode.MANUAL, ContactAutomationStatus.ACTIVE, GlobalAutomationStatus.ACTIVE),
            unreadCount = 0,
            pendingApprovalCount = 1,
            lastMessagePreview = "Nice to meet you too",
            lastActivityAt = t("2026-10-31T16:30:00Z"),
            createdAt = t("2026-10-31T15:00:00Z"),
        ),
        Conversation(
            id = CONVERSATION_RILEY,
            contact = ContactSummary(CONTACT_RILEY, "Riley"),
            connectionId = CONNECTION_MOCK,
            platform = "mock",
            externalThreadId = "mock-thread-3",
            stage = ConversationStage.FOLLOW_UP,
            effectiveIntent = IntentMode.OPEN,
            automation = ConversationAutomation(AutomationMode.MANUAL, ContactAutomationStatus.PAUSED, GlobalAutomationStatus.ACTIVE),
            unreadCount = 0,
            pendingApprovalCount = 0,
            lastMessagePreview = "Message ${LONG_THREAD_SIZE}",
            lastActivityAt = t("2026-10-30T12:00:00Z"),
            createdAt = t("2026-10-20T12:00:00Z"),
        ),
        Conversation(
            id = CONVERSATION_CASEY,
            contact = ContactSummary(CONTACT_CASEY, "Casey"),
            connectionId = CONNECTION_NOTIFICATION,
            platform = "android_notification",
            externalThreadId = null,
            stage = ConversationStage.NEW,
            effectiveIntent = IntentMode.CASUAL_DATING,
            automation = ConversationAutomation(AutomationMode.MANUAL, ContactAutomationStatus.ACTIVE, GlobalAutomationStatus.ACTIVE),
            unreadCount = 2,
            pendingApprovalCount = 0,
            lastMessagePreview = "hey :)",
            lastActivityAt = t("2026-10-29T09:00:00Z"),
            createdAt = t("2026-10-29T08:59:00Z"),
        ),
    )

    val analyses: Map<String, ConversationAnalysis> = mapOf(
        CONVERSATION_SAM to ConversationAnalysis(
            stage = ConversationStage.RAPPORT,
            confidence = 0.72,
            uncertain = false,
            interestSignals = listOf(InterestSignal("asks follow-up questions", SignalPolarity.POSITIVE, id("d1"))),
            nextAction = NextAction.REPLY,
            sourceMessageIds = listOf(id("d1")),
            analyzedAt = t("2026-10-31T18:01:05Z"),
        ),
    )

    private fun inbound(idSuffix: String, conv: String, text: String, at: String, ext: String) = Message(
        id = id(idSuffix), conversationId = conv, direction = MessageDirection.INBOUND, origin = MessageOrigin.CONTACT,
        text = text, deliveryState = DeliveryState.RECEIVED, externalMessageId = ext, approvalId = null,
        attemptCount = 0, failure = null, createdAt = t(at), sentAt = null, deliveredAt = null,
    )

    val messages: Map<String, List<Message>> = mapOf(
        CONVERSATION_SAM to listOf(
            inbound("d0", CONVERSATION_SAM, "Hey! How was your week?", "2026-10-31T17:50:00Z", "mock-msg-0000"),
            Message(
                id = id("d9"), conversationId = CONVERSATION_SAM, direction = MessageDirection.OUTBOUND,
                origin = MessageOrigin.USER_MANUAL, text = "Busy but good! Yours?", deliveryState = DeliveryState.DELIVERED,
                externalMessageId = "mock-msg-0009", approvalId = null, attemptCount = 1, failure = null,
                createdAt = t("2026-10-31T17:55:00Z"), sentAt = t("2026-10-31T17:55:01Z"), deliveredAt = t("2026-10-31T17:55:02Z"),
            ),
            inbound("d1", CONVERSATION_SAM, "Just got back from Lisbon!", "2026-10-31T18:01:00Z", "mock-msg-0001"),
        ),
        CONVERSATION_JORDAN to listOf(
            Message(
                id = id("d5"), conversationId = CONVERSATION_JORDAN, direction = MessageDirection.OUTBOUND,
                origin = MessageOrigin.USER_MANUAL, text = "Hi Jordan, great to match!", deliveryState = DeliveryState.SENT,
                externalMessageId = "sms-77", approvalId = null, attemptCount = 1, failure = null,
                createdAt = t("2026-10-31T15:00:00Z"), sentAt = t("2026-10-31T15:00:02Z"), deliveredAt = null,
            ),
            inbound("d6", CONVERSATION_JORDAN, "Nice to meet you too", "2026-10-31T16:30:00Z", "sms-78"),
        ),
        CONVERSATION_CASEY to listOf(
            inbound("d7", CONVERSATION_CASEY, "hey :)", "2026-10-29T09:00:00Z", "notif-1"),
        ),
        CONVERSATION_RILEY to (1..LONG_THREAD_SIZE).map { n ->
            val at = t("2026-10-25T12:00:00Z").plusSeconds(n * 60L * 37)
            val inboundMsg = n % 2 == 1
            Message(
                id = "0192f3a0-0000-7000-8001-" + n.toString().padStart(12, '0'),
                conversationId = CONVERSATION_RILEY,
                direction = if (inboundMsg) MessageDirection.INBOUND else MessageDirection.OUTBOUND,
                origin = if (inboundMsg) MessageOrigin.CONTACT else MessageOrigin.USER_MANUAL,
                text = "Message $n",
                deliveryState = if (inboundMsg) DeliveryState.RECEIVED else DeliveryState.SENT,
                externalMessageId = "mock-r-$n",
                approvalId = null,
                attemptCount = if (inboundMsg) 0 else 1,
                failure = null,
                createdAt = at,
                sentAt = if (inboundMsg) null else at.plusSeconds(1),
                deliveredAt = null,
            )
        },
    )

    val approvals: List<Approval> = listOf(
        Approval(
            id = APPROVAL_SAM,
            conversationId = CONVERSATION_SAM,
            contactId = CONTACT_SAM,
            status = ApprovalStatus.PENDING,
            draft = Draft(
                id = id("e1"), conversationId = CONVERSATION_SAM, decision = DraftDecision.SEND_CANDIDATE,
                text = "Welcome back! What was the highlight, the food or the views?", questionForUser = null,
                rationale = "Contact shared a trip; a specific follow-up question builds rapport.",
                confidence = 0.81, requiresApproval = true, approvalReasons = listOf(ApprovalReason.ASSISTED_MODE),
                intentMode = IntentMode.CUSTOM, grounding = DraftGrounding(listOf(id("101")), listOf(id("d1"))),
                createdAt = t("2026-10-31T18:01:10Z"),
            ),
            reasons = listOf(ApprovalReason.ASSISTED_MODE),
            createdAt = t("2026-10-31T18:01:10Z"),
            expiresAt = t("2026-11-01T18:01:10Z"),
        ),
        Approval(
            id = APPROVAL_JORDAN,
            conversationId = CONVERSATION_JORDAN,
            contactId = CONTACT_JORDAN,
            status = ApprovalStatus.PENDING,
            draft = Draft(
                id = id("e2"), conversationId = CONVERSATION_JORDAN, decision = DraftDecision.SEND_CANDIDATE,
                text = "What are you up to this weekend?", questionForUser = null,
                rationale = "Keeps the conversation going with an open question.",
                confidence = 0.64, requiresApproval = true,
                approvalReasons = listOf(ApprovalReason.CAPABILITY_UNVERIFIED),
                intentMode = IntentMode.SERIOUS, grounding = DraftGrounding(emptyList(), listOf(id("d6"))),
                createdAt = t("2026-10-31T16:31:00Z"),
            ),
            reasons = listOf(ApprovalReason.CAPABILITY_UNVERIFIED),
            createdAt = t("2026-10-31T16:31:00Z"),
            expiresAt = null,
        ),
    )
}
