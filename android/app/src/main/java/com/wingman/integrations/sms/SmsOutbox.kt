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
    /** Stored by the backend (`202 accepted`) or recognised as a duplicate (`200`): remove from the outbox. */
    data object Accepted : SmsUploadResult

    /**
     * Network error, 401, 409, 429, 5xx or any status the contract does not call permanent: keep
     * it and try again later with the same envelope and key. [retryAfterMillis] is the server's
     * `Retry-After`, when it sent one.
     */
    data class RetryLater(val reason: String, val retryAfterMillis: Long? = null) : SmsUploadResult

    /** Permanent rejection (`400`/`404`/`422`). Moved to the dead-letter area; never retried. */
    data class Rejected(val reason: String) : SmsUploadResult
}

/**
 * Transport-to-backend uploader for `connector_event.v1` envelopes. Implementations must be
 * safe to call again with the same envelope: the backend dedups by `event_id` and by
 * `(user_id, platform, external_message_id)`, and every envelope here has a deterministic
 * `event_id`. The production implementation is [WingmanApiSmsEnvelopeUploader].
 *
 * Blocking call; run it off the main thread.
 */
fun interface SmsEnvelopeUploader {
    fun upload(envelope: JsonObject): SmsUploadResult
}

/**
 * Used while no backend client is connected ([SmsConnector] built without an API). It keeps every
 * envelope queued, so nothing is lost or invented. With a client, [WingmanApiSmsEnvelopeUploader]
 * (contract v1.1.0 `ingestConnectorEvent`) is used instead.
 */
object NoBackendUploader : SmsEnvelopeUploader {
    override fun upload(envelope: JsonObject): SmsUploadResult =
        SmsUploadResult.RetryLater("no backend client connected (SmsConnectorRuntime.connectBackend not called)")
}

/**
 * Persisted retry schedule, one per [name] (the outbox upload and the outbox claim each have
 * one). Upload is head-of-line (FIFO), so one schedule for the whole outbox is enough: after a
 * retryable failure nothing is attempted until [nextAttemptAtMillis]. The delay is the server's
 * `Retry-After` when it sent one (capped at [maxRetryAfterMillis]), otherwise exponential from
 * [initialDelayMillis], capped at [maxDelayMillis]. Progress resets it. It lives in
 * [SmsStateStore], so it survives process death.
 */
class SmsBackoff(
    private val store: SmsStateStore,
    name: String,
    private val clock: SmsClock = SmsClock.SYSTEM,
    private val initialDelayMillis: Long = 5_000,
    private val maxDelayMillis: Long = 15 * 60_000,
    private val maxRetryAfterMillis: Long = 60 * 60_000,
) {
    private val keyFailures = "backoff:$name:failures"
    private val keyNextAt = "backoff:$name:next_at"

    /** Consecutive retryable failures since the last progress. */
    fun failures(): Int = store.get(keyFailures)?.toIntOrNull() ?: 0

    fun nextAttemptAtMillis(): Long? = store.get(keyNextAt)?.toLongOrNull()

    fun isWaiting(): Boolean = nextAttemptAtMillis()?.let { clock.nowMillis() < it } ?: false

    /** Records a retryable failure and returns the next allowed attempt time. */
    fun onRetryLater(retryAfterMillis: Long?): Long {
        val n = failures() + 1
        val exponential = (initialDelayMillis shl (n - 1).coerceIn(0, 30)).coerceIn(initialDelayMillis, maxDelayMillis)
        val delay = retryAfterMillis?.coerceIn(0, maxRetryAfterMillis) ?: exponential
        val next = clock.nowMillis() + delay
        store.put(keyFailures, n.toString())
        store.put(keyNextAt, next.toString())
        return next
    }

    fun onProgress() {
        if (store.get(keyFailures) != null) store.remove(keyFailures)
        if (store.get(keyNextAt) != null) store.remove(keyNextAt)
    }

    companion object {
        const val UPLOAD = "upload"
        const val CLAIM = "claim"
    }
}

/**
 * Durable FIFO of envelopes waiting for upload, keyed by `event_id` so enqueueing the same
 * event twice (e.g. after a crash between enqueue and dedup claim) stores it once.
 */
class SmsOutbox(private val store: SmsStateStore) {
    private val json = Json

    /** Never blocks on an upload in progress: the receiver calls this on its short goAsync budget. */
    fun enqueue(envelope: SmsEnvelope) {
        val key = PREFIX + envelope.eventId
        if (store.get(key) == null) store.put(key, envelope.json.toString())
    }

    fun pending(): List<JsonObject> =
        store.keys(PREFIX).mapNotNull { store.get(it) }.map { json.parseToJsonElement(it).jsonObject }

    fun size(): Int = store.keys(PREFIX).size

    fun deadLetterCount(): Int = store.keys(DEAD_PREFIX).size

    /** Dead-lettered envelopes (rejected `400`/`404`/`422`), oldest first. Kept for diagnosis; never retried. */
    fun deadLetters(): List<JsonObject> =
        store.keys(DEAD_PREFIX).mapNotNull { store.get(it) }.map { json.parseToJsonElement(it).jsonObject }

    /**
     * Uploads in FIFO (enqueue) order, up to [max] envelopes, one request per envelope.
     *
     * - Accepted: removed only AFTER the backend answered, so a crash in between re-uploads the
     *   same envelope (same `event_id` and key), which the backend answers `200 duplicate_event`.
     * - Rejected: moved to the dead-letter area (written before the removal, so it is never lost).
     * - RetryLater: stops at once. Nothing behind the failed envelope is uploaded, so the order of
     *   every thread (in fact, of all envelopes) is preserved, and a broken network is not
     *   hammered. With a [backoff], the next drain before the scheduled time uploads nothing.
     */
    fun drain(uploader: SmsEnvelopeUploader, max: Int = 50, backoff: SmsBackoff? = null): DrainReport = synchronized(drainLock) {
        drainLocked(uploader, max, backoff)
    }

    /** One drain at a time (two would upload the same head envelope twice). Separate from [enqueue]. */
    private val drainLock = Any()

    private fun drainLocked(uploader: SmsEnvelopeUploader, max: Int, backoff: SmsBackoff?): DrainReport {
        if (backoff != null && backoff.isWaiting()) {
            return DrainReport(0, 0, retryReason = "backing off", nextAttemptAtMillis = backoff.nextAttemptAtMillis())
        }
        var accepted = 0
        var rejected = 0
        for (key in store.keys(PREFIX).take(max)) {
            val raw = store.get(key) ?: continue
            when (val result = uploader.upload(json.parseToJsonElement(raw).jsonObject)) {
                SmsUploadResult.Accepted -> {
                    store.remove(key)
                    accepted++
                    backoff?.onProgress()
                }
                is SmsUploadResult.Rejected -> {
                    store.put(DEAD_PREFIX + key.removePrefix(PREFIX), raw)
                    store.remove(key)
                    rejected++
                    backoff?.onProgress()
                }
                is SmsUploadResult.RetryLater -> {
                    val next = backoff?.onRetryLater(result.retryAfterMillis)
                    return DrainReport(accepted, rejected, retryReason = result.reason, nextAttemptAtMillis = next)
                }
            }
        }
        return DrainReport(accepted, rejected, retryReason = null)
    }

    data class DrainReport(val accepted: Int, val rejected: Int, val retryReason: String?, val nextAttemptAtMillis: Long? = null)

    companion object {
        const val PREFIX = "outbox:"
        const val DEAD_PREFIX = "outbox-dead:"
    }
}
