package com.wingman.integrations.hinge

import com.wingman.core.model.UtcTimestamps
import com.wingman.integrations.sms.SmsBackoff
import com.wingman.integrations.sms.SmsEnvelopeUploader
import com.wingman.integrations.sms.SmsStateStore
import com.wingman.integrations.sms.SmsUploadResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant

/** Contract 1.3.0 `InboundEventKind` values this connector reports. */
enum class HingeEventKind(val wire: String) {
    CHAT_MESSAGE("chat_message"),
    MATCH("match"),
    LIKE_COMMENT("like_comment"),
    SYSTEM("system"),
    UNKNOWN("unknown"),
}

/** One `connector_event.v1` envelope. [json] may hold private text: never log it (use [toString]). */
class HingeEnvelope(val eventId: String, val type: String, val json: JsonObject) {
    override fun toString(): String = "HingeEnvelope(eventId=$eventId, type=$type)"
}

/** A Sendbird message as read from `group_channels/{c}/messages`, reduced to what is mapped. Private fields redacted. */
class SendbirdMessage(
    val messageId: String,
    val type: String,
    val customType: String,
    val text: String,
    val createdAtMillis: Long,
    val senderId: String,
    val senderNickname: String?,
    val channelUrl: String,
    val removed: Boolean,
    val hasFile: Boolean,
) {
    override fun toString(): String =
        "SendbirdMessage(id=#${HingeHashes.short(messageId)}, type=$type, custom=${customType.isNotEmpty()}, text=${redacted(text)}, at=$createdAtMillis)"

    companion object {
        /** Null when a field the mapping needs is missing (the caller treats a batch of nulls as drift). */
        fun parse(o: JsonObject): SendbirdMessage? {
            fun s(name: String) = (o[name] as? JsonPrimitive)?.contentOrNull
            val id = s("message_id") ?: s("msg_id") ?: return null
            val user = o["user"] as? JsonObject
            return SendbirdMessage(
                messageId = id,
                type = s("type") ?: return null,
                customType = s("custom_type").orEmpty(),
                text = s("message").orEmpty(),
                createdAtMillis = (o["created_at"] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: return null,
                senderId = (user?.get("user_id") as? JsonPrimitive)?.contentOrNull.orEmpty(),
                senderNickname = (user?.get("nickname") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
                channelUrl = s("channel_url") ?: return null,
                removed = (o["is_removed"] as? JsonPrimitive)?.booleanOrNull == true,
                hasFile = o["file"].let { it is JsonObject && it.isNotEmpty() } || s("type") == "FILE",
            )
        }
    }
}

/**
 * Maps Hinge/Sendbird data to `connector_event.v1` (contract 1.3.0, ADR 0008 §4).
 *
 * - `source: unofficial_api` on every inbound payload.
 * - `identity: linked` ONLY when both exact platform ids are present: the Sendbird channel URL
 *   (thread) and the sender's Hinge user id. Otherwise `unlinked`; nothing is ever matched by name.
 * - `kind`: a plain text `MESG` from the match is `chat_message`; a `MESG` with a custom type that
 *   names a like or comment is `like_comment`; any other custom type, files and empty texts are
 *   `unknown`; admin messages are `system`; new connections are `match`. Only a linked, complete
 *   `chat_message` can reach the pipeline (the backend enforces it), so likes, comments and
 *   matches are never auto-replied to.
 * - The user's own messages and removed messages are skipped.
 * - Deterministic `event_id` and `external_message_id`, so a re-sync creates no duplicate.
 */
class HingeEventMapper(private val userId: String, private val connectionId: String) {

    fun classify(m: SendbirdMessage): HingeEventKind = when {
        m.type == "ADMM" -> HingeEventKind.SYSTEM
        m.type != "MESG" -> HingeEventKind.UNKNOWN
        m.customType.isNotEmpty() -> if (LIKE_OR_COMMENT.containsMatchIn(m.customType)) HingeEventKind.LIKE_COMMENT else HingeEventKind.UNKNOWN
        m.hasFile || m.text.isBlank() -> HingeEventKind.UNKNOWN
        else -> HingeEventKind.CHAT_MESSAGE
    }

    /** `message.received` for one message from the match, or null for our own or removed ones. */
    fun received(m: SendbirdMessage, selfId: String, observedAtMillis: Long): HingeEnvelope? {
        if (m.removed || m.senderId == selfId) return null
        if (m.channelUrl.length > MAX_EXTERNAL_ID || m.messageId.length > 200) return null
        val kind = classify(m)
        val linked = m.senderId.isNotEmpty() && m.channelUrl.isNotEmpty()
        val truncated = m.text.length > MAX_TEXT
        val text = if (truncated) m.text.take(MAX_TEXT) else m.text
        val label = m.senderNickname?.take(200)
        val externalId = MESSAGE_PREFIX + m.messageId
        val payload = buildJsonObject {
            putJsonObject("sender") {
                put("external_contact_id", if (linked) m.senderId else "hinge-channel:" + HingeHashes.short(m.channelUrl))
                put("display_name", label?.let(::JsonPrimitive) ?: JsonNull)
            }
            put("text", text)
            if (m.hasFile) put("attachment_count", 1)
            put("kind", kind.wire)
            put("external_conversation_id", m.channelUrl)
            put("sender_label", label?.let(::JsonPrimitive) ?: JsonNull)
            put("identity", if (linked) "linked" else "unlinked")
            put("truncated", truncated)
            put("content_hidden", false)
            put("source", SOURCE)
        }
        return envelope(TYPE_RECEIVED, HingeHashes.eventId(userId, TYPE_RECEIVED, externalId), externalId, m.channelUrl, m.createdAtMillis, observedAtMillis, payload)
    }

    /** A new match (connection) without conversation yet: a `match` notice, never a message. */
    fun match(subjectId: String, occurredAtMillis: Long, observedAtMillis: Long): HingeEnvelope? {
        if (subjectId.isBlank() || subjectId.length > 200) return null
        val externalId = MATCH_PREFIX + subjectId
        val payload = buildJsonObject {
            putJsonObject("sender") {
                put("external_contact_id", subjectId)
                put("display_name", JsonNull)
            }
            put("text", "")
            put("kind", HingeEventKind.MATCH.wire)
            put("identity", "linked")
            put("truncated", false)
            put("content_hidden", false)
            put("source", SOURCE)
        }
        return envelope(TYPE_RECEIVED, HingeHashes.eventId(userId, TYPE_RECEIVED, externalId), externalId, null, occurredAtMillis, observedAtMillis, payload)
    }

    /** `message.send_acknowledged`: Sendbird echoed our `MESG` ([sendbirdMessageId] is its `msg_id`). Sent, not delivered. */
    fun sendAcknowledged(outboundMessageId: String, idempotencyKey: String, threadId: String, sendbirdMessageId: String, atMillis: Long): HingeEnvelope =
        envelope(
            TYPE_ACK, HingeHashes.eventId(userId, TYPE_ACK, idempotencyKey), MESSAGE_PREFIX + sendbirdMessageId, threadId.takeIf { it.length <= MAX_EXTERNAL_ID },
            atMillis, atMillis,
            buildJsonObject {
                put("outbound_message_id", outboundMessageId)
                put("idempotency_key", idempotencyKey)
            },
        )

    /** `message.send_failed`. Only for outcomes KNOWN not to have been stored by Sendbird. */
    fun sendFailed(outboundMessageId: String, idempotencyKey: String, threadId: String?, code: String, detail: String, retryable: Boolean, atMillis: Long): HingeEnvelope =
        envelope(
            TYPE_FAILED, HingeHashes.eventId(userId, TYPE_FAILED, idempotencyKey), null, threadId?.takeIf { it.length <= MAX_EXTERNAL_ID },
            atMillis, atMillis,
            buildJsonObject {
                put("outbound_message_id", outboundMessageId)
                put("idempotency_key", idempotencyKey)
                putJsonObject("failure") {
                    put("code", code)
                    put("detail", detail.take(500))
                    put("retryable", retryable)
                }
            },
        )

    /** `connection.status_changed` with a reason CODE only (no secrets, no text). */
    fun statusChanged(status: String, detail: String, atMillis: Long): HingeEnvelope =
        envelope(
            TYPE_STATUS, HingeHashes.eventId(userId, TYPE_STATUS, status, detail, atMillis.toString()), null, null, atMillis, atMillis,
            buildJsonObject {
                put("status", status)
                put("detail", detail.take(500))
            },
        )

    private fun envelope(type: String, eventId: String, externalMessageId: String?, threadId: String?, occurredAt: Long, observedAt: Long, payload: JsonObject) =
        HingeEnvelope(
            eventId, type,
            buildJsonObject {
                put("schema_version", "connector_event.v1")
                put("event_id", eventId)
                put("type", type)
                put("platform", HINGE_PLATFORM)
                put("connection_id", connectionId)
                put("user_id", userId)
                put("external_message_id", externalMessageId?.let(::JsonPrimitive) ?: JsonNull)
                put("external_thread_id", threadId?.let(::JsonPrimitive) ?: JsonNull)
                put("occurred_at", UtcTimestamps.format(Instant.ofEpochMilli(occurredAt)))
                put("observed_at", UtcTimestamps.format(Instant.ofEpochMilli(observedAt)))
                put("payload", payload)
            },
        )

    companion object {
        const val SOURCE = "unofficial_api"
        const val TYPE_RECEIVED = "message.received"
        const val TYPE_ACK = "message.send_acknowledged"
        const val TYPE_FAILED = "message.send_failed"
        const val TYPE_STATUS = "connection.status_changed"
        const val MESSAGE_PREFIX = "hinge:msg:"
        const val MATCH_PREFIX = "hinge:match:"
        const val MAX_TEXT = 20_000
        const val MAX_EXTERNAL_ID = 256
        private val LIKE_OR_COMMENT = Regex("like|comment|rose", RegexOption.IGNORE_CASE)
    }
}

/**
 * Durable FIFO of envelopes waiting for `ingestConnectorEvent`, keyed by `event_id` (enqueueing
 * the same event twice stores it once). Uploads through the SMS connector's production uploader
 * ([com.wingman.integrations.sms.WingmanApiSmsEnvelopeUploader]: same route, same response
 * mapping) and its persisted [SmsBackoff]. These are WINGMAN calls, never Hinge calls, so they
 * continue after an opt-out or a breaker trip (late send outcomes and the status change must arrive).
 */
class HingeEnvelopeQueue(private val store: SmsStateStore) {
    fun enqueue(e: HingeEnvelope) {
        val key = PREFIX + e.eventId
        if (store.get(key) == null && store.get(DONE_PREFIX + e.eventId) == null) store.put(key, e.json.toString())
    }

    fun size(): Int = store.keys(PREFIX).size

    fun pending(): List<JsonObject> = store.keys(PREFIX).mapNotNull { store.get(it) }.map { Json.parseToJsonElement(it).jsonObject }

    fun deadLetterCount(): Int = store.keys(DEAD_PREFIX).size

    /** FIFO upload; stops at the first retryable failure (order kept, network not hammered). */
    @Synchronized
    fun drain(uploader: SmsEnvelopeUploader, backoff: SmsBackoff, max: Int = 100): Int {
        if (backoff.isWaiting()) return 0
        var done = 0
        for (key in store.keys(PREFIX).take(max)) {
            val raw = store.get(key) ?: continue
            when (val r = uploader.upload(Json.parseToJsonElement(raw).jsonObject)) {
                SmsUploadResult.Accepted -> {
                    store.remove(key)
                    done++
                    backoff.onProgress()
                }
                is SmsUploadResult.Rejected -> {
                    store.put(DEAD_PREFIX + key.removePrefix(PREFIX), raw)
                    store.remove(key)
                    backoff.onProgress()
                }
                is SmsUploadResult.RetryLater -> {
                    backoff.onRetryLater(r.retryAfterMillis)
                    return done
                }
            }
        }
        return done
    }

    /** Drops queued INBOUND envelopes (opt-out); send outcomes and status changes are kept. */
    fun dropInbound() {
        store.keys(PREFIX).filter { k -> store.get(k)?.let { it.contains("\"type\":\"$TYPE_RECEIVED_JSON\"") } == true }.forEach(store::remove)
    }

    companion object {
        const val PREFIX = "hq:"
        const val DEAD_PREFIX = "hq-dead:"
        const val DONE_PREFIX = "hq-done:"
        private const val TYPE_RECEIVED_JSON = HingeEventMapper.TYPE_RECEIVED
    }
}
