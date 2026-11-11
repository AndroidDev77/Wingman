package com.wingman.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.serializer
import java.time.Instant
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/*
 * Shared primitives from contracts/openapi.yaml (v1.0.0). Pure Kotlin: no android.* imports.
 *
 * Mapping rules used in every model file:
 *  - Wire names are the contract's snake_case names, set with @SerialName.
 *  - Contract `required` + non-null type  -> Kotlin non-null, no default.
 *  - Contract `required` + nullable type  -> Kotlin nullable, NO default (must be present).
 *  - Contract optional (not in `required`) -> Kotlin nullable with `= null` (or empty list).
 *  - Wingman IDs and `Platform` are opaque Strings (ADR 0001). Timestamps are [Instant].
 */

/** Matches `components.schemas.Timestamp`: UTC RFC 3339 with a literal `Z`, up to 6 fractional digits. */
private val UTC_Z_PATTERN = Regex("""^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,6})?Z$""")

object UtcTimestamps {
    fun isValid(raw: String): Boolean = UTC_Z_PATTERN.matches(raw)

    /** Parses a contract timestamp. Offsets such as `+00:00` are rejected (ADR 0001). */
    fun parse(raw: String): Instant {
        if (!isValid(raw)) throw SerializationException("Timestamp must be UTC RFC 3339 with a literal Z: '$raw'")
        return try {
            Instant.parse(raw)
        } catch (e: DateTimeParseException) {
            throw SerializationException("Invalid timestamp '$raw'", e)
        }
    }

    /** Formats with a literal `Z` and at most microsecond precision. */
    fun format(instant: Instant): String = instant.truncatedTo(ChronoUnit.MICROS).toString()
}

object UtcInstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("wingman.UtcInstant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(UtcTimestamps.format(value))
    override fun deserialize(decoder: Decoder): Instant = UtcTimestamps.parse(decoder.decodeString())
}

/** Generic `{items, next_cursor}` page (ADR 0003). `nextCursor == null` marks the end. */
@Serializable
data class Page<T>(
    val items: List<T>,
    @SerialName("next_cursor") val nextCursor: String?,
) {
    val isLastPage: Boolean get() = nextCursor == null
}

/** The contract's wire value of an enum constant (its @SerialName), e.g. for query parameters. */
@OptIn(ExperimentalSerializationApi::class)
inline fun <reified E : Enum<E>> E.wireName(): String = serializer<E>().descriptor.getElementName(ordinal)

/* ------------------------------------------------------------------ enums */

@Serializable
enum class IntentMode {
    @SerialName("serious") SERIOUS,
    @SerialName("casual_dating") CASUAL_DATING,
    @SerialName("casual_physical") CASUAL_PHYSICAL,
    @SerialName("open") OPEN,
    @SerialName("custom") CUSTOM,
}

@Serializable
enum class AutomationMode {
    @SerialName("manual") MANUAL,
    @SerialName("assisted") ASSISTED,
    @SerialName("autopilot") AUTOPILOT,
}

@Serializable
enum class ConversationStage {
    @SerialName("new") NEW,
    @SerialName("introduction") INTRODUCTION,
    @SerialName("rapport") RAPPORT,
    @SerialName("interest") INTEREST,
    @SerialName("date_proposal") DATE_PROPOSAL,
    @SerialName("scheduling") SCHEDULING,
    @SerialName("confirmed") CONFIRMED,
    @SerialName("follow_up") FOLLOW_UP,
    @SerialName("paused") PAUSED,
}

@Serializable
enum class DeliveryState {
    @SerialName("received") RECEIVED,
    @SerialName("queued") QUEUED,
    @SerialName("sending") SENDING,
    @SerialName("sent") SENT,
    @SerialName("delivered") DELIVERED,
    @SerialName("failed") FAILED,
    @SerialName("dead_lettered") DEAD_LETTERED,
    @SerialName("canceled") CANCELED,
    /** (1.3.0) The user SAID they sent an assisted draft by hand. Never sent, never delivered. */
    @SerialName("user_reported_sent") USER_REPORTED_SENT,
}

@Serializable
enum class MessageDirection {
    @SerialName("inbound") INBOUND,
    @SerialName("outbound") OUTBOUND,
}

@Serializable
enum class MessageOrigin {
    @SerialName("contact") CONTACT,
    @SerialName("user_manual") USER_MANUAL,
    @SerialName("approved_draft") APPROVED_DRAFT,
    @SerialName("autopilot") AUTOPILOT,
    /** (1.3.0) An agent draft the user sent by hand in the platform app. */
    @SerialName("assisted_draft") ASSISTED_DRAFT,
}

@Serializable
enum class ApprovalStatus {
    @SerialName("pending") PENDING,
    @SerialName("approved") APPROVED,
    @SerialName("rejected") REJECTED,
    @SerialName("expired") EXPIRED,
    @SerialName("canceled") CANCELED,
}

@Serializable
enum class ApprovalReason {
    @SerialName("assisted_mode") ASSISTED_MODE,
    @SerialName("sensitive_content") SENSITIVE_CONTENT,
    @SerialName("date_or_commitment") DATE_OR_COMMITMENT,
    @SerialName("low_confidence") LOW_CONFIDENCE,
    @SerialName("first_message") FIRST_MESSAGE,
    @SerialName("outside_active_hours") OUTSIDE_ACTIVE_HOURS,
    @SerialName("capability_unverified") CAPABILITY_UNVERIFIED,
    @SerialName("send_limit_reached") SEND_LIMIT_REACHED,
    @SerialName("user_rule") USER_RULE,
    @SerialName("paused") PAUSED,
}

@Serializable
enum class DraftDecision {
    @SerialName("send_candidate") SEND_CANDIDATE,
    @SerialName("no_send") NO_SEND,
    @SerialName("ask_user") ASK_USER,
}

@Serializable
enum class ContactAutomationStatus {
    @SerialName("active") ACTIVE,
    @SerialName("paused") PAUSED,
    @SerialName("taken_over") TAKEN_OVER,
}

@Serializable
enum class GlobalAutomationStatus {
    @SerialName("active") ACTIVE,
    @SerialName("paused") PAUSED,
}

@Serializable
enum class Actor {
    @SerialName("user") USER,
    @SerialName("agent") AGENT,
    @SerialName("dispatcher") DISPATCHER,
    @SerialName("connector") CONNECTOR,
    @SerialName("system") SYSTEM,
}

@Serializable
enum class AgentActionType {
    @SerialName("message_received") MESSAGE_RECEIVED,
    @SerialName("analysis_updated") ANALYSIS_UPDATED,
    @SerialName("draft_generated") DRAFT_GENERATED,
    @SerialName("approval_requested") APPROVAL_REQUESTED,
    @SerialName("approval_approved") APPROVAL_APPROVED,
    @SerialName("approval_rejected") APPROVAL_REJECTED,
    @SerialName("approval_expired") APPROVAL_EXPIRED,
    @SerialName("approval_canceled") APPROVAL_CANCELED,
    @SerialName("policy_blocked") POLICY_BLOCKED,
    @SerialName("send_queued") SEND_QUEUED,
    @SerialName("send_attempted") SEND_ATTEMPTED,
    @SerialName("send_acknowledged") SEND_ACKNOWLEDGED,
    @SerialName("delivery_confirmed") DELIVERY_CONFIRMED,
    @SerialName("send_failed") SEND_FAILED,
    @SerialName("send_dead_lettered") SEND_DEAD_LETTERED,
    @SerialName("send_canceled") SEND_CANCELED,
    @SerialName("automation_paused") AUTOMATION_PAUSED,
    @SerialName("automation_resumed") AUTOMATION_RESUMED,
    @SerialName("automation_takeover") AUTOMATION_TAKEOVER,
    @SerialName("preferences_updated") PREFERENCES_UPDATED,
    @SerialName("lease_expired") LEASE_EXPIRED,
    @SerialName("connection_paired") CONNECTION_PAIRED,
    @SerialName("connection_unpaired") CONNECTION_UNPAIRED,
    // contract 1.3.0 (ADR 0008)
    @SerialName("notice_received") NOTICE_RECEIVED,
    @SerialName("assisted_delivery_queued") ASSISTED_DELIVERY_QUEUED,
    @SerialName("assisted_delivery_confirmed") ASSISTED_DELIVERY_CONFIRMED,
    @SerialName("assisted_delivery_dismissed") ASSISTED_DELIVERY_DISMISSED,
    @SerialName("assisted_delivery_canceled") ASSISTED_DELIVERY_CANCELED,
    @SerialName("experimental_consent_recorded") EXPERIMENTAL_CONSENT_RECORDED,
    @SerialName("experimental_consent_revoked") EXPERIMENTAL_CONSENT_REVOKED,
    @SerialName("identity_link_created") IDENTITY_LINK_CREATED,
    @SerialName("identity_link_deleted") IDENTITY_LINK_DELETED,
    @SerialName("profile_imported") PROFILE_IMPORTED,
    @SerialName("profile_decision_recorded") PROFILE_DECISION_RECORDED,
    @SerialName("profile_action_queued") PROFILE_ACTION_QUEUED,
    // contract 1.3.2 (D-032)
    @SerialName("profile_linked") PROFILE_LINKED,
    @SerialName("profile_unlinked") PROFILE_UNLINKED,
    @SerialName("profile_deleted") PROFILE_DELETED,
}

@Serializable
enum class ConnectionStatus {
    @SerialName("connected") CONNECTED,
    @SerialName("disconnected") DISCONNECTED,
    @SerialName("error") ERROR,
}

/** (1.3.0) `ConnectionTransport`: how the paired device reaches the platform. */
@Serializable
enum class ConnectionTransport {
    @SerialName("simulator") SIMULATOR,
    @SerialName("sms_provider") SMS_PROVIDER,
    @SerialName("notification") NOTIFICATION,
    @SerialName("companion") COMPANION,
    /** D-021: always experimental (approval-only, never swipes until operator-verified). */
    @SerialName("unofficial_api") UNOFFICIAL_API,
}

/** (1.3.0) `InboundEventKind`. Only CHAT_MESSAGE can ever be replied to. */
@Serializable
enum class InboundEventKind {
    @SerialName("chat_message") CHAT_MESSAGE,
    @SerialName("match") MATCH,
    @SerialName("like_comment") LIKE_COMMENT,
    @SerialName("expiry") EXPIRY,
    @SerialName("system") SYSTEM,
    @SerialName("unknown") UNKNOWN,
}

/** (1.3.0) `SenderIdentity`. Only LINKED events become messages; nothing is merged by name. */
@Serializable
enum class SenderIdentity {
    @SerialName("linked") LINKED,
    @SerialName("unlinked") UNLINKED,
    @SerialName("ambiguous") AMBIGUOUS,
}

/** (1.3.0) `AssistedDeliveryStatus`. USER_REPORTED_SENT is the user's word, not an observed send. */
@Serializable
enum class AssistedDeliveryStatus {
    @SerialName("pending") PENDING,
    @SerialName("user_reported_sent") USER_REPORTED_SENT,
    @SerialName("dismissed") DISMISSED,
    @SerialName("canceled") CANCELED,
}

/** (1.3.0) `ProfileDecision`: the user's choice, separate from execution. */
@Serializable
enum class ProfileDecision {
    @SerialName("like") LIKE,
    @SerialName("pass") PASS,
    @SerialName("skip") SKIP,
    @SerialName("superlike") SUPERLIKE,
}

/** (1.3.0) `ProfileActionExecution`. QUEUED is accepted, NOT executed. */
@Serializable
enum class ProfileActionExecution {
    @SerialName("not_executable") NOT_EXECUTABLE,
    @SerialName("queued") QUEUED,
    @SerialName("executed") EXECUTED,
    @SerialName("failed") FAILED,
    @SerialName("unknown") UNKNOWN,
}

/** `connector_capabilities.v1.json#/$defs/verification`. */
@Serializable
enum class Verification { VERIFIED, UNVERIFIED, UNSUPPORTED }

/**
 * `components.schemas.ErrorCode`. Not decoded strictly: the contract says clients that see an
 * unknown code fall back to the HTTP status, so [Problem.code] stays a String and is mapped here.
 */
enum class ErrorCode(val wire: String) {
    VALIDATION_ERROR("validation_error"),
    IDEMPOTENCY_KEY_REQUIRED("idempotency_key_required"),
    IDEMPOTENCY_KEY_MISMATCH("idempotency_key_mismatch"),
    IDEMPOTENCY_KEY_IN_PROGRESS("idempotency_key_in_progress"),
    UNAUTHENTICATED("unauthenticated"),
    NOT_FOUND("not_found"),
    PRECONDITION_FAILED("precondition_failed"),
    APPROVAL_NOT_PENDING("approval_not_pending"),
    AUTOMATION_PAUSED("automation_paused"),
    CAPABILITY_UNSUPPORTED("capability_unsupported"),
    CAPABILITY_UNVERIFIED("capability_unverified"),
    CONNECTION_UNAVAILABLE("connection_unavailable"),
    RATE_LIMITED("rate_limited"),
    UPSTREAM_UNAVAILABLE("upstream_unavailable"),
    INTERNAL_ERROR("internal_error"),
    // contract 1.3.0 (ADR 0008)
    EXPERIMENTAL_CONSENT_REQUIRED("experimental_consent_required"),
    ASSISTED_DELIVERY_NOT_PENDING("assisted_delivery_not_pending"),
    SWIPE_EXECUTION_DISABLED("swipe_execution_disabled"),
    ;

    companion object {
        private val byWire = entries.associateBy { it.wire }
        fun fromWire(wire: String?): ErrorCode? = wire?.let { byWire[it] }
    }
}
