package com.wingman.integrations.sms

import com.wingman.core.model.UtcTimestamps
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Deterministic identities. A connector re-sending the same event must reuse the same
 * `event_id` (contract), and a duplicate SMS broadcast must map to the same
 * `external_message_id` (dedup key), so everything here is derived, never random.
 */
object SmsIds {
    const val INBOUND_PREFIX = "sms:in:"
    const val OUTBOUND_PREFIX = "sms:out:"

    internal fun sha256Hex(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach {
            val bytes = it.toByteArray(Charsets.UTF_8)
            // Length-prefix every part so ("ab","c") and ("a","bc") never collide.
            digest.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
            digest.update(':'.code.toByte())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Stable ID of an incoming SMS. Inputs are what is identical across a duplicate
     * broadcast (SMS_DELIVER and SMS_RECEIVED for the same message, or the OS redelivering
     * after a restart): normalized sender, the SMSC timestamp from the PDU, the
     * subscription and the full body. The local receive time is deliberately NOT an input.
     *
     * Known limit (UNVERIFIED on carriers): two genuinely different messages with the same
     * sender, same body and the same SMSC timestamp (second resolution) on the same SIM
     * collapse into one. That is preferred over a double reply.
     *
     * The body is hashed, never embedded, so the ID is safe to log and to use as a key.
     */
    fun inboundExternalMessageId(sms: IncomingSms): String =
        INBOUND_PREFIX + sha256Hex(
            SmsAddress.normalize(sms.originatingAddress),
            sms.serviceCenterTimestampMillis.toString(),
            (sms.subscriptionId ?: -1).toString(),
            sms.body,
        ).take(40)

    /** SMS has no platform message ID for our own sends; the send key is the stable identity. */
    fun outboundExternalMessageId(idempotencyKey: String): String = OUTBOUND_PREFIX + idempotencyKey

    /** Name-based UUID (lowercase canonical), stable for the same inputs. */
    fun eventId(vararg parts: String): String =
        UUID.nameUUIDFromBytes(("wingman.sms.event|" + parts.joinToString("|")).toByteArray(Charsets.UTF_8))
            .toString()

    /** SHA-256 of the body for the send fingerprint, so the record never stores the text. */
    fun textFingerprint(text: String): String = sha256Hex(text)
}

/** Event types this connector emits (live `message.*` / `connection.*` only, never `mock.*`). */
enum class SmsEventType(val wire: String) {
    MESSAGE_RECEIVED("message.received"),
    MESSAGE_SEND_ACKNOWLEDGED("message.send_acknowledged"),
    MESSAGE_SEND_FAILED("message.send_failed"),
    MESSAGE_DELIVERY_RECEIPT("message.delivery_receipt"),
    CONNECTION_CAPABILITIES_CHANGED("connection.capabilities_changed"),
}

/**
 * One `connector_event.v1` envelope, ready for the uploader. [json] is the exact wire body;
 * it contains private text for `message.received`, so never log [json] (use [toString]).
 */
class SmsEnvelope(val eventId: String, val type: SmsEventType, val externalMessageId: String?, val json: JsonObject) {
    override fun toString(): String = "SmsEnvelope(eventId=$eventId, type=${type.wire}, externalMessageId=$externalMessageId)"
}

/** Builds `connector_event.v1` envelopes for platform `sms`. Pure, deterministic. */
class SmsEnvelopeFactory(private val scope: SmsConnectionScope) {

    private fun ts(millis: Long): String = UtcTimestamps.format(Instant.ofEpochMilli(millis))

    private fun envelope(
        type: SmsEventType,
        eventId: String,
        externalMessageId: String?,
        externalThreadId: String?,
        occurredAtMillis: Long,
        observedAtMillis: Long,
        payload: JsonObject,
    ): SmsEnvelope {
        val json = buildJsonObject {
            put("schema_version", "connector_event.v1")
            put("event_id", eventId)
            put("type", type.wire)
            put("platform", SMS_PLATFORM)
            put("connection_id", scope.connectionId)
            put("user_id", scope.userId)
            put("external_message_id", externalMessageId?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
            put("external_thread_id", externalThreadId?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
            put("occurred_at", ts(occurredAtMillis))
            put("observed_at", ts(observedAtMillis))
            put("payload", payload)
        }
        return SmsEnvelope(eventId, type, externalMessageId, json)
    }

    /**
     * `message.received`. `occurred_at` is the SMSC timestamp from the PDU (when the network
     * says the message was sent); `observed_at` is when this device handled the broadcast.
     * Both are derived from the message, so a duplicate broadcast yields an identical envelope.
     * Note: observed_at uses the FIRST observation only because the processor drops later
     * duplicates before building a second envelope.
     */
    fun received(sms: IncomingSms): SmsEnvelope {
        val externalId = SmsIds.inboundExternalMessageId(sms)
        val address = SmsAddress.normalize(sms.originatingAddress)
        val payload = buildJsonObject {
            putJsonObject("sender") {
                put("external_contact_id", address)
                put("display_name", JsonNull)
            }
            put("text", sms.body)
        }
        return envelope(
            type = SmsEventType.MESSAGE_RECEIVED,
            eventId = SmsIds.eventId(scope.userId, SmsEventType.MESSAGE_RECEIVED.wire, externalId),
            externalMessageId = externalId,
            externalThreadId = SmsAddress.threadIdFor(address),
            occurredAtMillis = sms.serviceCenterTimestampMillis,
            observedAtMillis = sms.receivedAtMillis,
            payload = payload,
        )
    }

    /** `message.send_acknowledged`: the radio accepted every part. This is `sent`, NOT delivered. */
    fun sendAcknowledged(record: SmsSendRecord, atMillis: Long): SmsEnvelope = envelope(
        type = SmsEventType.MESSAGE_SEND_ACKNOWLEDGED,
        eventId = SmsIds.eventId(scope.userId, SmsEventType.MESSAGE_SEND_ACKNOWLEDGED.wire, record.idempotencyKey),
        externalMessageId = SmsIds.outboundExternalMessageId(record.idempotencyKey),
        externalThreadId = record.externalThreadId,
        occurredAtMillis = atMillis,
        observedAtMillis = atMillis,
        payload = buildJsonObject {
            put("outbound_message_id", record.outboundMessageId)
            put("idempotency_key", record.idempotencyKey)
        },
    )

    /** `message.send_failed`. `external_message_id` is null: nothing reached the network as far as we know. */
    fun sendFailed(record: SmsSendRecord, failure: SmsSendFailure, atMillis: Long): SmsEnvelope = envelope(
        type = SmsEventType.MESSAGE_SEND_FAILED,
        eventId = SmsIds.eventId(scope.userId, SmsEventType.MESSAGE_SEND_FAILED.wire, record.idempotencyKey),
        externalMessageId = null,
        externalThreadId = record.externalThreadId,
        occurredAtMillis = atMillis,
        observedAtMillis = atMillis,
        payload = buildJsonObject {
            put("outbound_message_id", record.outboundMessageId)
            put("idempotency_key", record.idempotencyKey)
            putJsonObject("failure") {
                put("code", failure.code.wire)
                put("detail", failure.detail)
                put("retryable", failure.retryable)
            }
        },
    )

    /** `message.delivery_receipt`: every part's status report said "delivered". */
    fun deliveryReceipt(record: SmsSendRecord, atMillis: Long): SmsEnvelope = envelope(
        type = SmsEventType.MESSAGE_DELIVERY_RECEIPT,
        eventId = SmsIds.eventId(scope.userId, SmsEventType.MESSAGE_DELIVERY_RECEIPT.wire, record.idempotencyKey),
        externalMessageId = SmsIds.outboundExternalMessageId(record.idempotencyKey),
        externalThreadId = record.externalThreadId,
        occurredAtMillis = atMillis,
        observedAtMillis = atMillis,
        payload = buildJsonObject { put("outbound_message_id", record.outboundMessageId) },
    )

    /** `connection.capabilities_changed` carrying a full `connector_capabilities.v1` document. */
    fun capabilitiesChanged(capabilities: JsonObject, atMillis: Long): SmsEnvelope {
        val digest = SmsIds.sha256Hex(capabilities.toString()).take(16)
        return envelope(
            type = SmsEventType.CONNECTION_CAPABILITIES_CHANGED,
            eventId = SmsIds.eventId(scope.userId, SmsEventType.CONNECTION_CAPABILITIES_CHANGED.wire, digest, atMillis.toString()),
            externalMessageId = null,
            externalThreadId = null,
            occurredAtMillis = atMillis,
            observedAtMillis = atMillis,
            payload = buildJsonObject { put("capabilities", capabilities) },
        )
    }
}
