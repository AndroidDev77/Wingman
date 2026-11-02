package com.wingman.integrations.notifications

import com.wingman.core.api.ApiError
import com.wingman.core.api.ApiResult
import com.wingman.core.api.IdempotencyKey
import com.wingman.core.api.WingmanApi
import com.wingman.core.model.ErrorCode
import com.wingman.core.model.OutboxClaimRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.util.UUID

/*
 * I-033 (WING-027/028): the device <-> backend loop of the notification path, modelled on the SMS
 * connector's uploader and poller (WING-016, ADR 0006). Pure Kotlin, no android.* imports.
 *
 *  - [NotificationUploadQueue]: persisted, ordered FIFO of `connector_event.v1` envelopes, drained
 *    head-of-line to `ingestConnectorEvent`, with a dead-letter area and a persisted backoff.
 *  - [NotificationOutboxPoller]: `claimOutboxInstructions` with a persisted claim key, handing each
 *    answer to WING-017's [NotificationClaimExecutor]. On Tinder and Bumble `send` is UNSUPPORTED, so
 *    the dispatcher creates assisted deliveries instead of instructions and a claim normally returns
 *    nothing; the poller exists for a platform whose send is ever operator-VERIFIED.
 *
 * All calls are blocking (runBlocking around the suspend client): run them off the main thread.
 * Nothing here logs envelope content.
 */

/** Per-install UUID for `createConnection.device_install_id` and every claim (same scheme as SMS). */
object NotificationInstallId {
    const val KEY = "config:device_install_id"
    private val UUID_RE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    fun getOrCreate(store: NotificationStateStore, newId: () -> String = { UUID.randomUUID().toString() }): String = synchronized(store) {
        store.get(KEY)?.let { return it }
        val id = newId()
        require(UUID_RE.matches(id)) { "device_install_id must be a lowercase canonical UUID" }
        store.put(KEY, id)
        id
    }
}

/** Result of uploading one envelope. Same classes as the SMS uploader (ADR 0006). */
sealed interface NotificationUploadResult {
    /** `202 accepted` or `200 duplicate_*`: removed from the queue. */
    data object Accepted : NotificationUploadResult

    /** Network, 401, 409, 429, 5xx, unexpected body: kept, same envelope and key later. */
    data class RetryLater(val reason: String, val retryAfterMillis: Long? = null) : NotificationUploadResult

    /** `400`/`404`/`422`: moved to the dead-letter area, never retried. */
    data class Rejected(val reason: String) : NotificationUploadResult
}

fun interface NotificationEnvelopeUploader {
    fun upload(envelope: JsonObject): NotificationUploadResult
}

/** Production uploader: `Idempotency-Key` = `event_id`, path = the envelope's own `connection_id`. */
class WingmanApiNotificationUploader(private val api: WingmanApi) : NotificationEnvelopeUploader {
    override fun upload(envelope: JsonObject): NotificationUploadResult {
        val eventId = envelope.string("event_id") ?: return NotificationUploadResult.Rejected("envelope has no event_id")
        val connectionId = envelope.string("connection_id") ?: return NotificationUploadResult.Rejected("envelope has no connection_id")
        val key = runCatching { IdempotencyKey(eventId) }.getOrNull()
            ?: return NotificationUploadResult.Rejected("event_id is not a valid Idempotency-Key")
        return map(runBlocking { api.ingestConnectorEvent(connectionId, envelope, key) })
    }

    companion object {
        val REJECTED_STATUSES = setOf(400, 404, 422)

        fun map(result: ApiResult<*>): NotificationUploadResult = when (result) {
            is ApiResult.Success -> NotificationUploadResult.Accepted
            is ApiResult.Failure -> when (val e = result.error) {
                is ApiError.Http -> if (e.status in REJECTED_STATUSES) {
                    NotificationUploadResult.Rejected("HTTP ${e.status} ${e.problem?.code ?: ""}".trim())
                } else {
                    NotificationUploadResult.RetryLater("HTTP ${e.status} ${e.problem?.code ?: ""}".trim(), e.retryAfterSeconds?.times(1000))
                }
                is ApiError.Network -> NotificationUploadResult.RetryLater("network: ${e.cause.javaClass.simpleName}")
                is ApiError.Decoding -> NotificationUploadResult.RetryLater("unexpected 2xx body")
                is ApiError.Client -> NotificationUploadResult.Rejected("client error")
            }
        }
    }
}

/**
 * Persisted retry schedule (same rules as `SmsBackoff`): the server's `Retry-After` when sent
 * (capped), otherwise exponential from [initialDelayMillis] up to [maxDelayMillis]; progress resets it.
 */
class NotificationBackoff(
    private val store: NotificationStateStore,
    name: String,
    private val clock: NotificationClock = NotificationClock.SYSTEM,
    private val initialDelayMillis: Long = 5_000,
    private val maxDelayMillis: Long = 15 * 60_000,
    private val maxRetryAfterMillis: Long = 60 * 60_000,
) {
    private val keyFailures = "backoff:$name:failures"
    private val keyNextAt = "backoff:$name:next_at"

    fun failures(): Int = store.get(keyFailures)?.toIntOrNull() ?: 0

    fun nextAttemptAtMillis(): Long? = store.get(keyNextAt)?.toLongOrNull()

    fun isWaiting(): Boolean = nextAttemptAtMillis()?.let { clock.nowMillis() < it } ?: false

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
        fun claim(platform: String) = "claim:$platform"
    }
}

/**
 * Durable FIFO of envelopes waiting for upload, keyed by `event_id` (enqueueing the same event twice
 * stores it once). One queue for every platform, so the order of all events is kept.
 */
class NotificationUploadQueue(private val store: NotificationStateStore) {
    private val json = Json
    private val drainLock = Any()

    fun enqueue(envelope: JsonObject) {
        val id = envelope.string("event_id") ?: return
        val key = PREFIX + id
        if (store.get(key) == null) store.put(key, envelope.toString())
    }

    fun pending(): List<JsonObject> = store.keys(PREFIX).mapNotNull { store.get(it) }.map { json.parseToJsonElement(it).jsonObject }

    fun size(): Int = store.keys(PREFIX).size

    fun deadLetterCount(): Int = store.keys(DEAD_PREFIX).size

    fun deadLetters(): List<JsonObject> = store.keys(DEAD_PREFIX).mapNotNull { store.get(it) }.map { json.parseToJsonElement(it).jsonObject }

    /**
     * Drops queued `message.received` envelopes of [platform] (the user turned reading off; they would
     * be dead-lettered by the unpaired connection anyway). Send outcomes stay. Returns the count.
     */
    fun purgeInbound(platform: String): Int = synchronized(drainLock) {
        var n = 0
        for (k in store.keys(PREFIX)) {
            val e = store.get(k)?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: continue
            if (e.string("platform") == platform && e.string("type") == "message.received") {
                store.remove(k)
                n++
            }
        }
        n
    }

    /**
     * Uploads in FIFO order, one request per envelope, up to [max]. Accepted: removed after the
     * answer. Rejected: dead-lettered (written before the removal). RetryLater: stops at once (head
     * of line, so order is kept) and schedules [backoff]; a drain during the wait uploads nothing.
     */
    fun drain(uploader: NotificationEnvelopeUploader, max: Int = 50, backoff: NotificationBackoff? = null): DrainReport = synchronized(drainLock) {
        if (backoff != null && backoff.isWaiting()) {
            return DrainReport(0, 0, retryReason = "backing off", nextAttemptAtMillis = backoff.nextAttemptAtMillis())
        }
        var accepted = 0
        var rejected = 0
        for (key in store.keys(PREFIX).take(max)) {
            val raw = store.get(key) ?: continue
            val envelope = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            val result = if (envelope == null) NotificationUploadResult.Rejected("unreadable envelope") else uploader.upload(envelope)
            when (result) {
                NotificationUploadResult.Accepted -> {
                    store.remove(key)
                    accepted++
                    backoff?.onProgress()
                }
                is NotificationUploadResult.Rejected -> {
                    store.put(DEAD_PREFIX + key.removePrefix(PREFIX), raw)
                    store.remove(key)
                    rejected++
                    backoff?.onProgress()
                }
                is NotificationUploadResult.RetryLater -> {
                    val next = backoff?.onRetryLater(result.retryAfterMillis)
                    return DrainReport(accepted, rejected, retryReason = result.reason, nextAttemptAtMillis = next)
                }
            }
        }
        DrainReport(accepted, rejected, retryReason = null)
    }

    data class DrainReport(val accepted: Int, val rejected: Int, val retryReason: String?, val nextAttemptAtMillis: Long? = null)

    companion object {
        const val PREFIX = "upload:"
        const val DEAD_PREFIX = "upload-dead:"
    }
}

/** What one [NotificationOutboxPoller.poll] did. Counts only. */
data class NotificationPollReport(
    val claimed: Int = 0,
    val submitted: Int = 0,
    val failed: Int = 0,
    val replayed: Int = 0,
    val ignored: Int = 0,
    val claimReplayed: Boolean = false,
    val skippedReason: String? = null,
    val nextAttemptAtMillis: Long? = null,
)

/**
 * Claims leased instructions for ONE paired platform and executes them through
 * [NotificationClaimExecutor] (one attempt per key, D-006). Same persistence rules as
 * `SmsOutboxPoller`: the claim key and body are persisted before the request and replayed until the
 * answer is processed, so a lost response never leases twice and a replay cannot extend a deadline;
 * a definitive `400`/`404`/`422` (including D-017's install mismatch) drops the key and backs off.
 */
class NotificationOutboxPoller(
    private val scope: NotificationConnectionScope,
    private val platform: String,
    private val store: NotificationStateStore,
    private val api: WingmanApi,
    private val executor: NotificationClaimExecutor,
    private val clock: NotificationClock = NotificationClock.SYSTEM,
    private val maxInstructions: Int = 5,
    private val backoff: NotificationBackoff = NotificationBackoff(store, NotificationBackoff.claim(platform), clock),
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) {
    private val keyPending = pendingClaimKey(platform)

    fun pendingClaimKey(): String? = pending()?.key

    @Synchronized
    fun poll(): NotificationPollReport {
        if (backoff.isWaiting()) return NotificationPollReport(skippedReason = "backing off", nextAttemptAtMillis = backoff.nextAttemptAtMillis())
        val claim = pending()?.takeIf { it.connectionId == scope.connectionId && clock.nowMillis() - it.firstSentAtMillis < STALE_CLAIM_MILLIS }
            ?: newClaim()
        val started = clock.nowMillis()
        val result = runBlocking {
            api.claimOutboxInstructions(scope.connectionId, OutboxClaimRequest(claim.installId, claim.max), IdempotencyKey(claim.key))
        }
        return when (result) {
            is ApiResult.Success -> {
                val anchor = if (result.idempotentReplayed) claim.firstSentAtMillis else started
                val r = executor.execute(result.value, anchor)
                store.remove(keyPending) // only now is every instruction in the reply ledger
                backoff.onProgress()
                NotificationPollReport(r.claimed, r.submitted, r.failed, r.replayed, r.ignored, claimReplayed = result.idempotentReplayed)
            }
            is ApiResult.Failure -> {
                val e = result.error
                val definitive = e is ApiError.Client ||
                    (e is ApiError.Http && e.status in setOf(400, 404, 422) && e.code != ErrorCode.IDEMPOTENCY_KEY_IN_PROGRESS)
                if (definitive) store.remove(keyPending)
                val next = backoff.onRetryLater((e as? ApiError.Http)?.retryAfterSeconds?.times(1000))
                NotificationPollReport(skippedReason = reason(e), nextAttemptAtMillis = next)
            }
        }
    }

    private fun reason(e: ApiError) = when (e) {
        is ApiError.Http -> "HTTP ${e.status} ${e.problem?.code ?: ""}".trim()
        is ApiError.Network -> "network: ${e.cause.javaClass.simpleName}"
        is ApiError.Decoding -> "unexpected claim response body"
        is ApiError.Client -> "client error"
    }

    private fun newClaim(): Pending {
        val c = Pending(newKey(), scope.connectionId, NotificationInstallId.getOrCreate(store), maxInstructions, clock.nowMillis())
        store.put(
            keyPending,
            buildJsonObject {
                put("key", c.key)
                put("connection_id", c.connectionId)
                put("device_install_id", c.installId)
                put("max_instructions", c.max)
                put("first_sent_at", c.firstSentAtMillis)
            }.toString(),
        )
        return c
    }

    private fun pending(): Pending? {
        val o = store.get(keyPending)?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return null
        return runCatching {
            Pending(
                key = o.getValue("key").jsonPrimitive.content,
                connectionId = o.getValue("connection_id").jsonPrimitive.content,
                installId = o.getValue("device_install_id").jsonPrimitive.content,
                max = o["max_instructions"]?.jsonPrimitive?.intOrNull ?: maxInstructions,
                firstSentAtMillis = o.getValue("first_sent_at").jsonPrimitive.long,
            )
        }.getOrNull()
    }

    private data class Pending(val key: String, val connectionId: String, val installId: String, val max: Int, val firstSentAtMillis: Long)

    companion object {
        const val STALE_CLAIM_MILLIS: Long = 23L * 60 * 60 * 1000
        fun pendingClaimKey(platform: String) = "claim:pending:$platform"
    }
}

internal fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
