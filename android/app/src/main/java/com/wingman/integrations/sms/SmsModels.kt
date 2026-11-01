package com.wingman.integrations.sms

/*
 * WING-016 SMS connector: pure-Kotlin domain types. NO android.* imports in this package's
 * top level; the thin platform adapters live in `integrations/sms/android/`.
 *
 * Privacy: message bodies and phone numbers are private. Nothing in this package logs, and
 * `toString()` of every type that carries a body or address redacts it.
 */

/** Platform key used in every envelope (`contracts/schemas/connector_event.v1.json`). */
const val SMS_PLATFORM: String = "sms"

/** Version of this connector implementation, published in `connector_capabilities.v1`. */
const val SMS_CONNECTOR_VERSION: String = "0.1.0"

private val UUID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
private val IDEMPOTENCY_KEY_PATTERN = Regex("^[A-Za-z0-9._:-]{8,128}$")

internal fun requireWingmanId(value: String, name: String) {
    require(UUID_PATTERN.matches(value)) { "$name must be a lowercase canonical UUID" }
}

/**
 * The Wingman user and connection that this device-side connector reports for. Every envelope
 * carries both; the backend rejects events whose connection is not owned by the user (B1).
 *
 * Set by pairing (I-020, [SmsPairingManager]): `userId` from `getMe`, `connectionId` from
 * `createConnection`. The debug harness can also set a fixed demo scope for offline tests.
 */
data class SmsConnectionScope(val userId: String, val connectionId: String) {
    init {
        requireWingmanId(userId, "userId")
        requireWingmanId(connectionId, "connectionId")
    }
}

/**
 * Phone-number / sender-ID normalisation, used for `external_contact_id` and the thread ID.
 *
 * Numeric addresses keep a leading `+` and drop formatting characters (`+1 (555) 010-1234`
 * and `+15550101234` are the same contact). Alphanumeric sender IDs (e.g. `ACME`) are kept
 * as-is apart from trimming. No country-code inference is attempted: `5550101234` and
 * `+15550101234` stay different, because guessing the region could merge two real contacts.
 */
object SmsAddress {
    const val THREAD_PREFIX = "sms:"
    private val FORMATTING = Regex("[\\s().\\-/]")
    private val NUMERIC = Regex("^\\+?[0-9*#]+$")

    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) { "SMS address is empty" }
        val stripped = trimmed.replace(FORMATTING, "")
        val normalized = if (NUMERIC.matches(stripped)) stripped else trimmed
        require(normalized.length <= 200) { "SMS address too long" }
        return normalized
    }

    /** `external_thread_id` for a 1:1 SMS conversation with [address]. */
    fun threadIdFor(address: String): String = THREAD_PREFIX + normalize(address)

    /** Inverse of [threadIdFor]; null when the thread ID is not an SMS thread we created. */
    fun destinationFromThreadId(threadId: String): String? {
        if (!threadId.startsWith(THREAD_PREFIX)) return null
        val address = threadId.removePrefix(THREAD_PREFIX)
        return runCatching { normalize(address) }.getOrNull()?.takeIf { it == address }
    }
}

/**
 * One incoming SMS, already extracted from the PDUs by the platform adapter. Multipart
 * messages arrive as one broadcast with several PDUs; the adapter concatenates the parts
 * in order into [body] and passes the part count.
 *
 * @property serviceCenterTimestampMillis `SmsMessage.getTimestampMillis()` of the first part:
 *   the SMSC timestamp from the PDU. Identical across duplicate broadcasts and redeliveries,
 *   which is why it (not the local receive time) is part of the message identity.
 * @property receivedAtMillis device wall clock when the broadcast was handled.
 * @property subscriptionId SIM subscription the message arrived on, or null if unknown.
 */
class IncomingSms(
    val originatingAddress: String,
    val body: String,
    val serviceCenterTimestampMillis: Long,
    val receivedAtMillis: Long,
    val subscriptionId: Int?,
    val partCount: Int = 1,
) {
    init {
        require(originatingAddress.isNotBlank()) { "originatingAddress is blank" }
        require(partCount >= 1) { "partCount must be >= 1" }
        require(body.length <= 20_000) { "body exceeds the contract's 20000 chars" }
    }

    override fun toString(): String =
        "IncomingSms(address=<redacted>, body=<redacted ${body.length} chars>, " +
            "smscTs=$serviceCenterTimestampMillis, sub=$subscriptionId, parts=$partCount)"
}

/**
 * An authorized outbound send handed to the device by the dispatch path (WING-011
 * automation controller). The connector never builds one of these itself; in particular
 * the inbound receiver has no reference to the sender at all.
 *
 * @property idempotencyKey the dispatcher's per-attempt send key (D-006: one transport
 *   attempt per key; a retry needs a fresh key such as `send:<message_id>:2`).
 * @property authorizedBy opaque reference to what authorized this send (dispatch or
 *   approval ID), kept in the local send record for audit. Required and non-blank.
 */
class SmsSendCommand(
    val outboundMessageId: String,
    val idempotencyKey: String,
    val externalThreadId: String,
    val text: String,
    val authorizedBy: String,
) {
    init {
        requireWingmanId(outboundMessageId, "outboundMessageId")
        require(IDEMPOTENCY_KEY_PATTERN.matches(idempotencyKey)) { "idempotencyKey must match ${IDEMPOTENCY_KEY_PATTERN.pattern}" }
        require(externalThreadId.isNotEmpty() && externalThreadId.length <= 256) { "externalThreadId length" }
        require(text.isNotEmpty() && text.length <= 20_000) { "text must be 1..20000 chars" }
        require(authorizedBy.isNotBlank() && authorizedBy.length <= 200) { "authorizedBy is required" }
    }

    override fun toString(): String =
        "SmsSendCommand(outbound=$outboundMessageId, key=$idempotencyKey, thread=<redacted>, " +
            "text=<redacted ${text.length} chars>, authorizedBy=$authorizedBy)"
}

/** `connector_event.v1` `send_failed_payload.failure.code`, exactly the contract's enum. */
enum class SmsFailureCode(val wire: String) {
    CAPABILITY_UNSUPPORTED("capability_unsupported"),
    TRANSPORT_ERROR("transport_error"),
    TIMEOUT("timeout"),
    REJECTED_BY_PLATFORM("rejected_by_platform"),
    NOT_CONNECTED("not_connected"),
    PERMISSION_DENIED("permission_denied"),
    ;

    companion object {
        fun fromWire(wire: String): SmsFailureCode = entries.first { it.wire == wire }
    }
}

/** A typed send failure. [detail] never contains message text or phone numbers. */
data class SmsSendFailure(val code: SmsFailureCode, val retryable: Boolean, val detail: String) {
    init {
        require(detail.length <= 500) { "detail too long" }
    }
}
