@file:UseSerializers(UtcInstantSerializer::class)

package com.wingman.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/*
 * Response/request bodies from contracts/openapi.yaml v1.0.0 `components.schemas`.
 * See Primitives.kt for the nullability mapping rules. Section order follows the contract.
 */

/* ------------------------------------------------------------------ errors */

@Serializable
data class FieldError(
    val pointer: String,
    val message: String,
)

/**
 * RFC 9457 problem details (ADR 0002). [code] is kept as the raw wire string so that an unknown
 * code still decodes; use [errorCode] to branch.
 */
@Serializable
data class Problem(
    val type: String,
    val title: String,
    val status: Int,
    val code: String,
    val detail: String? = null,
    val instance: String? = null,
    @SerialName("request_id") val requestId: String? = null,
    val errors: List<FieldError>? = null,
) {
    val errorCode: ErrorCode? get() = ErrorCode.fromWire(code)
}

/* ------------------------------------------------------------------ health / users */

@Serializable
enum class Environment {
    @SerialName("dev") DEV,
    @SerialName("test") TEST,
    @SerialName("prod") PROD,
}

@Serializable
data class HealthResponse(
    /** Contract `const: ok`. */
    val status: String,
    val version: String,
    val env: Environment,
    val time: Instant,
)

@Serializable
data class User(
    val id: String,
    val email: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("created_at") val createdAt: Instant,
)

/* ------------------------------------------------------------------ preferences */

@Serializable
data class Intent(
    val mode: IntentMode,
    /** Non-empty when [mode] is CUSTOM; null otherwise. */
    @SerialName("custom_description") val customDescription: String? = null,
)

@Serializable
enum class Punctuation {
    @SerialName("minimal") MINIMAL,
    @SerialName("standard") STANDARD,
    @SerialName("expressive") EXPRESSIVE,
}

@Serializable
enum class EmojiFrequency {
    @SerialName("none") NONE,
    @SerialName("rare") RARE,
    @SerialName("occasional") OCCASIONAL,
    @SerialName("frequent") FREQUENT,
}

@Serializable
enum class Brevity {
    @SerialName("very_short") VERY_SHORT,
    @SerialName("short") SHORT,
    @SerialName("medium") MEDIUM,
    @SerialName("long") LONG,
}

@Serializable
enum class Humor {
    @SerialName("none") NONE,
    @SerialName("light") LIGHT,
    @SerialName("playful") PLAYFUL,
    @SerialName("witty") WITTY,
}

@Serializable
enum class Tone {
    @SerialName("warm") WARM,
    @SerialName("direct") DIRECT,
    @SerialName("playful") PLAYFUL,
    @SerialName("flirty") FLIRTY,
    @SerialName("reserved") RESERVED,
    @SerialName("formal") FORMAL,
}

@Serializable
enum class ResponsePace {
    @SerialName("immediate") IMMEDIATE,
    @SerialName("prompt") PROMPT,
    @SerialName("relaxed") RELAXED,
    @SerialName("slow") SLOW,
}

/** Every attribute nullable: null means "not configured" (never inferred). */
@Serializable
data class StyleProfile(
    val punctuation: Punctuation? = null,
    @SerialName("emoji_frequency") val emojiFrequency: EmojiFrequency? = null,
    val brevity: Brevity? = null,
    val humor: Humor? = null,
    val tone: Tone? = null,
    @SerialName("response_pace") val responsePace: ResponsePace? = null,
    @SerialName("style_samples") val styleSamples: List<String>? = null,
)

@Serializable
enum class Weekday {
    @SerialName("mon") MON,
    @SerialName("tue") TUE,
    @SerialName("wed") WED,
    @SerialName("thu") THU,
    @SerialName("fri") FRI,
    @SerialName("sat") SAT,
    @SerialName("sun") SUN,
}

@Serializable
data class ActiveHoursWindow(
    val days: List<Weekday>,
    /** Local `HH:MM`, inclusive. */
    val start: String,
    /** Local `HH:MM`, exclusive; `end < start` wraps past midnight. */
    val end: String,
)

@Serializable
data class ActiveHours(
    val timezone: String,
    val windows: List<ActiveHoursWindow>,
)

@Serializable
data class DelayRange(
    val min: Int,
    val max: Int,
)

@Serializable
data class AutomationSettings(
    val mode: AutomationMode,
    @SerialName("active_hours") val activeHours: ActiveHours? = null,
    @SerialName("reply_delay_seconds") val replyDelaySeconds: DelayRange? = null,
    @SerialName("daily_send_limit") val dailySendLimit: Int? = null,
    @SerialName("approval_required_for") val approvalRequiredFor: List<ApprovalReason>? = null,
)

@Serializable
data class PreferencesUpdate(
    val intent: Intent,
    val style: StyleProfile,
    val automation: AutomationSettings,
    val dealbreakers: List<String>? = null,
)

@Serializable
data class Preferences(
    /** 0 = never saved (safe defaults). */
    val version: Int,
    val intent: Intent,
    val dealbreakers: List<String>,
    val style: StyleProfile,
    val automation: AutomationSettings,
    /** Required; null only when [version] is 0. */
    @SerialName("updated_at") val updatedAt: Instant?,
)

/** Every field null = inherit from user-wide preferences. */
@Serializable
data class ContactPreferenceOverrideUpdate(
    val intent: Intent? = null,
    val style: StyleProfile? = null,
    @SerialName("automation_mode") val automationMode: AutomationMode? = null,
)

@Serializable
data class ContactPreferenceOverride(
    val version: Int,
    val intent: Intent? = null,
    val style: StyleProfile? = null,
    @SerialName("automation_mode") val automationMode: AutomationMode? = null,
    @SerialName("updated_at") val updatedAt: Instant? = null,
)

@Serializable
data class ContactPreferences(
    @SerialName("contact_id") val contactId: String,
    val override: ContactPreferenceOverride,
    val effective: Preferences,
)

/* ------------------------------------------------------------------ connections (capabilities v1) */

@Serializable
data class Capability(
    val status: Verification,
    val evidence: String? = null,
    @SerialName("verified_at") val verifiedAt: Instant? = null,
    val notes: String? = null,
    /** Only meaningful for `send`. Absent/false means messages stop at `sent`. */
    @SerialName("reports_delivery_receipts") val reportsDeliveryReceipts: Boolean? = null,
) {
    /** UNSUPPORTED means unavailable; UNVERIFIED must be shown as unverified, not working (ADR 0005). */
    val isAvailable: Boolean get() = status != Verification.UNSUPPORTED
}

/** All five are required and independent: none implies another. */
@Serializable
data class CapabilitySet(
    val read: Capability,
    val draft: Capability,
    val send: Capability,
    val swipe: Capability,
    @SerialName("calendar_write") val calendarWrite: Capability,
)

@Serializable
data class ConnectorCapabilities(
    /** Contract `const: connector_capabilities.v1`. */
    @SerialName("schema_version") val schemaVersion: String,
    val platform: String,
    @SerialName("connector_version") val connectorVersion: String,
    val capabilities: CapabilitySet,
    @SerialName("assessed_at") val assessedAt: Instant,
)

@Serializable
data class Connection(
    val id: String,
    val platform: String,
    @SerialName("display_name") val displayName: String,
    val status: ConnectionStatus,
    val capabilities: ConnectorCapabilities,
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("updated_at") val updatedAt: Instant,
)

/* ------------------------------------------------------------------ conversations */

@Serializable
data class ContactSummary(
    val id: String,
    @SerialName("display_name") val displayName: String,
)

@Serializable
data class ConversationAutomation(
    @SerialName("effective_mode") val effectiveMode: AutomationMode,
    @SerialName("contact_status") val contactStatus: ContactAutomationStatus,
    @SerialName("global_status") val globalStatus: GlobalAutomationStatus,
)

@Serializable
data class Conversation(
    val id: String,
    val contact: ContactSummary,
    @SerialName("connection_id") val connectionId: String,
    val platform: String,
    @SerialName("external_thread_id") val externalThreadId: String? = null,
    val stage: ConversationStage,
    @SerialName("effective_intent") val effectiveIntent: IntentMode,
    val automation: ConversationAutomation,
    @SerialName("unread_count") val unreadCount: Int,
    @SerialName("pending_approval_count") val pendingApprovalCount: Int,
    @SerialName("last_message_preview") val lastMessagePreview: String? = null,
    @SerialName("last_activity_at") val lastActivityAt: Instant,
    @SerialName("created_at") val createdAt: Instant,
)

@Serializable
enum class SignalPolarity {
    @SerialName("positive") POSITIVE,
    @SerialName("negative") NEGATIVE,
    @SerialName("neutral") NEUTRAL,
    @SerialName("uncertain") UNCERTAIN,
}

@Serializable
data class InterestSignal(
    val signal: String,
    val polarity: SignalPolarity,
    @SerialName("source_message_id") val sourceMessageId: String? = null,
)

@Serializable
enum class NextAction {
    @SerialName("reply") REPLY,
    @SerialName("wait") WAIT,
    @SerialName("ask_question") ASK_QUESTION,
    @SerialName("suggest_date") SUGGEST_DATE,
    @SerialName("confirm_details") CONFIRM_DETAILS,
    @SerialName("give_space") GIVE_SPACE,
    @SerialName("stop") STOP,
    @SerialName("ask_user") ASK_USER,
}

/** Advisory (WING-009). Never authorizes or triggers a send. */
@Serializable
data class ConversationAnalysis(
    val stage: ConversationStage,
    val confidence: Double,
    val uncertain: Boolean,
    @SerialName("interest_signals") val interestSignals: List<InterestSignal>,
    @SerialName("next_action") val nextAction: NextAction,
    @SerialName("source_message_ids") val sourceMessageIds: List<String>,
    @SerialName("analyzed_at") val analyzedAt: Instant,
)

/** `ConversationDetail` = `Conversation` allOf `{analysis (required, nullable)}`. Flattened on the wire. */
@Serializable
data class ConversationDetail(
    val id: String,
    val contact: ContactSummary,
    @SerialName("connection_id") val connectionId: String,
    val platform: String,
    @SerialName("external_thread_id") val externalThreadId: String? = null,
    val stage: ConversationStage,
    @SerialName("effective_intent") val effectiveIntent: IntentMode,
    val automation: ConversationAutomation,
    @SerialName("unread_count") val unreadCount: Int,
    @SerialName("pending_approval_count") val pendingApprovalCount: Int,
    @SerialName("last_message_preview") val lastMessagePreview: String? = null,
    @SerialName("last_activity_at") val lastActivityAt: Instant,
    @SerialName("created_at") val createdAt: Instant,
    val analysis: ConversationAnalysis?,
) {
    val conversation: Conversation
        get() = Conversation(
            id, contact, connectionId, platform, externalThreadId, stage, effectiveIntent, automation,
            unreadCount, pendingApprovalCount, lastMessagePreview, lastActivityAt, createdAt,
        )

    companion object {
        fun of(c: Conversation, analysis: ConversationAnalysis?) = ConversationDetail(
            c.id, c.contact, c.connectionId, c.platform, c.externalThreadId, c.stage, c.effectiveIntent,
            c.automation, c.unreadCount, c.pendingApprovalCount, c.lastMessagePreview, c.lastActivityAt,
            c.createdAt, analysis,
        )
    }
}

/* ------------------------------------------------------------------ messages */

@Serializable
data class MessageFailure(
    val code: String,
    val detail: String? = null,
    val retryable: Boolean,
)

@Serializable
data class Message(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    val direction: MessageDirection,
    val origin: MessageOrigin,
    val text: String,
    @SerialName("delivery_state") val deliveryState: DeliveryState,
    @SerialName("external_message_id") val externalMessageId: String? = null,
    @SerialName("approval_id") val approvalId: String? = null,
    @SerialName("attempt_count") val attemptCount: Int,
    val failure: MessageFailure? = null,
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("sent_at") val sentAt: Instant? = null,
    @SerialName("delivered_at") val deliveredAt: Instant? = null,
)

@Serializable
data class SendMessageRequest(
    val text: String,
)

/* ------------------------------------------------------------------ drafts */

@Serializable
data class DraftCreateRequest(
    val guidance: String? = null,
)

@Serializable
data class DraftGrounding(
    @SerialName("memory_ids") val memoryIds: List<String>,
    @SerialName("message_ids") val messageIds: List<String>,
)

/** An LLM suggestion. Not an authorization to send. */
@Serializable
data class Draft(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    val decision: DraftDecision,
    /** Required; non-null only when [decision] is SEND_CANDIDATE. */
    val text: String?,
    @SerialName("question_for_user") val questionForUser: String? = null,
    val rationale: String,
    val confidence: Double,
    @SerialName("requires_approval") val requiresApproval: Boolean,
    @SerialName("approval_reasons") val approvalReasons: List<ApprovalReason>,
    @SerialName("intent_mode") val intentMode: IntentMode,
    val grounding: DraftGrounding,
    @SerialName("created_at") val createdAt: Instant,
)

/* ------------------------------------------------------------------ approvals */

@Serializable
data class Approval(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("contact_id") val contactId: String,
    val status: ApprovalStatus,
    val draft: Draft,
    val reasons: List<ApprovalReason>,
    @SerialName("final_text") val finalText: String? = null,
    @SerialName("rejection_reason") val rejectionReason: String? = null,
    @SerialName("message_id") val messageId: String? = null,
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("expires_at") val expiresAt: Instant? = null,
    @SerialName("decided_at") val decidedAt: Instant? = null,
)

@Serializable
data class ApproveRequest(
    /** Replaces the draft text. Null sends the draft as-is. */
    @SerialName("edited_text") val editedText: String? = null,
)

@Serializable
data class RejectRequest(
    val reason: String? = null,
)

@Serializable
data class ApprovalDecisionResult(
    val approval: Approval,
    /** The queued outbound message (approve), or null (reject). Required key. */
    val message: Message?,
)

/* ------------------------------------------------------------------ automation */

@Serializable
data class AutomationActionRequest(
    val reason: String? = null,
)

@Serializable
data class GlobalAutomationState(
    val status: GlobalAutomationStatus,
    @SerialName("changed_at") val changedAt: Instant?,
)

@Serializable
data class ContactAutomationState(
    @SerialName("contact_id") val contactId: String,
    val status: ContactAutomationStatus,
    @SerialName("effective_mode") val effectiveMode: AutomationMode,
    @SerialName("global_status") val globalStatus: GlobalAutomationStatus,
    @SerialName("changed_at") val changedAt: Instant?,
    @SerialName("canceled_approval_count") val canceledApprovalCount: Int,
    @SerialName("canceled_message_count") val canceledMessageCount: Int,
)

/* ------------------------------------------------------------------ audit */

@Serializable
data class AgentAction(
    val id: String,
    val type: AgentActionType,
    val actor: Actor,
    @SerialName("conversation_id") val conversationId: String? = null,
    @SerialName("contact_id") val contactId: String? = null,
    @SerialName("message_id") val messageId: String? = null,
    @SerialName("approval_id") val approvalId: String? = null,
    @SerialName("reason_code") val reasonCode: String? = null,
    @SerialName("occurred_at") val occurredAt: Instant,
    /** Redacted metadata: never message text, tokens or phone numbers. */
    val details: JsonObject,
)
