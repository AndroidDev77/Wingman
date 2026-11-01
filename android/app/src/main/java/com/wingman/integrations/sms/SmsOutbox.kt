package com.wingman.integrations.sms

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Wall clock, injectable for deterministic tests. */
fun interface SmsClock {
    fun nowMillis(): Long

    companion object {
        val SYSTEM = SmsClock { System.currentTimeMillis() }
    }
}

/** Result of handing one envelope to the backend. */
sealed interface SmsUploadResult {
    /** Stored by the backend, or recognised as a duplicate (both mean: remove from the outbox). */
    data object Accepted : SmsUploadResult

    /** Network error, 5xx, 409 in-progress, or no route yet: keep it and try again later. */
    data class RetryLater(val reason: String) : SmsUploadResult

    /** Permanent rejection (4xx validation/ownership). Moved to the dead-letter area; never retried. */
    data class Rejected(val reason: String) : SmsUploadResult
}

/**
 * Transport-to-backend uploader for `connector_event.v1` envelopes. Implementations must be
 * safe to call again with the same envelope: the backend dedups by `event_id` and by
 * `(user_id, platform, external_message_id)`, and every envelope here has a deterministic
 * `event_id`.
 *
 * Blocking call; run it off the main thread (WorkManager on device).
 */
fun interface SmsEnvelopeUploader {
    fun upload(envelope: JsonObject): SmsUploadResult
}

/**
 * The production placeholder until the contract has a device-to-backend ingest route.
 *
 * TODO(I-010): there is no contract route for device-side connectors to push
 * `connector_event` envelopes (docs/DECISIONS.md I-010). Once an additive contract PR adds
 * one (proposal in docs/integrations/android_sms.md, "Proposed ingest route"), implement an
 * HTTP uploader against it in core/api and inject it here. Until then, envelopes stay in
 * the durable outbox and nothing is invented.
 */
object NoIngestRouteUploader : SmsEnvelopeUploader {
    override fun upload(envelope: JsonObject): SmsUploadResult =
        SmsUploadResult.RetryLater("no connector-event ingest route in contract v1 (I-010)")
}

/**
 * Durable FIFO of envelopes waiting for upload, keyed by `event_id` so enqueueing the same
 * event twice (e.g. after a crash between enqueue and dedup claim) stores it once.
 */
class SmsOutbox(private val store: SmsStateStore) {
    private val json = Json

    fun enqueue(envelope: SmsEnvelope) {
        val key = PREFIX + envelope.eventId
        if (store.get(key) == null) store.put(key, envelope.json.toString())
    }

    fun pending(): List<JsonObject> =
        store.keys(PREFIX).mapNotNull { store.get(it) }.map { json.parseToJsonElement(it).jsonObject }

    fun size(): Int = store.keys(PREFIX).size

    fun deadLetterCount(): Int = store.keys(DEAD_PREFIX).size

    /**
     * Uploads in FIFO order, up to [max] envelopes. Stops at the first RetryLater so order is
     * kept and a broken network is not hammered. Returns the number accepted.
     */
    fun drain(uploader: SmsEnvelopeUploader, max: Int = 50): DrainReport {
        var accepted = 0
        var rejected = 0
        for (key in store.keys(PREFIX).take(max)) {
            val raw = store.get(key) ?: continue
            when (val result = uploader.upload(json.parseToJsonElement(raw).jsonObject)) {
                SmsUploadResult.Accepted -> {
                    store.remove(key)
                    accepted++
                }
                is SmsUploadResult.Rejected -> {
                    store.put(DEAD_PREFIX + key.removePrefix(PREFIX), raw)
                    store.remove(key)
                    rejected++
                }
                is SmsUploadResult.RetryLater -> return DrainReport(accepted, rejected, retryReason = result.reason)
            }
        }
        return DrainReport(accepted, rejected, retryReason = null)
    }

    data class DrainReport(val accepted: Int, val rejected: Int, val retryReason: String?)

    companion object {
        const val PREFIX = "outbox:"
        const val DEAD_PREFIX = "outbox-dead:"
    }
}
