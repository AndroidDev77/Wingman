package com.wingman.integrations.sms

import com.wingman.core.api.ApiError
import com.wingman.core.api.ApiResult
import com.wingman.core.api.IdempotencyKey
import com.wingman.core.api.WingmanApi
import com.wingman.core.model.ErrorCode
import com.wingman.core.model.OutboxAuthorizationOrigin
import com.wingman.core.model.OutboxClaimRequest
import com.wingman.core.model.OutboxClaimResult
import com.wingman.core.model.OutboxInstruction
import com.wingman.core.model.OutboxInstructionState
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
 * WING-016 follow-up: the device side of contract v1.1.0 (ADR 0006).
 *
 *  - [WingmanApiSmsEnvelopeUploader]: uplink, `POST /v1/connections/{id}/events` per envelope.
 *  - [SmsOutboxPoller]: downlink, `POST /v1/connections/{id}/outbox/claim`, executing each leased
 *    instruction through the one-attempt-per-key [SmsSender].
 *
 * Pure Kotlin: no android.* imports. Both are blocking (runBlocking around the suspend client);
 * call them from a background thread, never from the main thread or inside a coroutine.
 * Auth is whatever the [WingmanApi] carries: `HttpWingmanApi` adds the bearer token from
 * AppContainer's token provider (none exists yet, I-011), so until then calls answer 401 and are
 * retried later. Nothing here mints or stores credentials.
 */

/**
 * Production [SmsEnvelopeUploader]: one `ingestConnectorEvent` request per envelope, with
 * `Idempotency-Key` = the envelope's `event_id` and the envelope's own `connection_id` as the path
 * (an envelope recorded under an older scope still goes to the connection it names).
 *
 * Response mapping (contract `ingestConnectorEvent`, ADR 0006):
 *
 * | Response | Result |
 * |---|---|
 * | `202 accepted`, `200 duplicate_event`, `200 duplicate_message` | [SmsUploadResult.Accepted] (removed from the outbox) |
 * | `400`, `404`, `422` | [SmsUploadResult.Rejected] (dead letter, never retried) |
 * | network error, `409`, `429`, `5xx` | [SmsUploadResult.RetryLater], honoring `Retry-After` |
 * | `401` (no token yet, I-011) and any other status | [SmsUploadResult.RetryLater]: never drop an envelope on an unclassified answer |
 * | 2xx with a body that is not a `ConnectorEventIngestResult` | [SmsUploadResult.RetryLater]: the server probably stored it, and the re-upload is answered `200 duplicate_event` |
 * | envelope without a valid `event_id`/`connection_id` | [SmsUploadResult.Rejected] (cannot ever be accepted) |
 */
class WingmanApiSmsEnvelopeUploader(private val api: WingmanApi) : SmsEnvelopeUploader {

    override fun upload(envelope: JsonObject): SmsUploadResult {
        val eventId = envelope.string("event_id") ?: return SmsUploadResult.Rejected("envelope has no event_id")
        val connectionId = envelope.string("connection_id") ?: return SmsUploadResult.Rejected("envelope has no connection_id")
        val key = runCatching { IdempotencyKey(eventId) }.getOrNull()
            ?: return SmsUploadResult.Rejected("event_id is not a valid Idempotency-Key")
        return map(runBlocking { api.ingestConnectorEvent(connectionId, envelope, key) })
    }

    companion object {
        /** Statuses the contract defines as a permanent rejection of this envelope. */
        val REJECTED_STATUSES = setOf(400, 404, 422)

        fun map(result: ApiResult<*>): SmsUploadResult = when (result) {
            is ApiResult.Success -> SmsUploadResult.Accepted
            is ApiResult.Failure -> when (val e = result.error) {
                is ApiError.Http -> if (e.status in REJECTED_STATUSES) {
                    SmsUploadResult.Rejected("HTTP ${e.status} ${e.problem?.code ?: ""}".trim())
                } else {
                    SmsUploadResult.RetryLater("HTTP ${e.status} ${e.problem?.code ?: ""}".trim(), e.retryAfterSeconds?.times(1000))
                }
                is ApiError.Network -> SmsUploadResult.RetryLater("network: ${e.cause.javaClass.simpleName}")
                is ApiError.Decoding -> SmsUploadResult.RetryLater("unexpected 2xx body")
                is ApiError.Client -> SmsUploadResult.Rejected("client: ${e.message}")
            }
        }
    }
}

/** Tuning for [SmsOutboxPoller]. */
data class SmsPollerConfig(
    /** `max_instructions` per claim (contract 1..20). */
    val maxInstructions: Int = 5,
    /**
     * Safety margin subtracted from the lease deadline (ADR 0006): a transport attempt must start
     * at least this long before `lease_expires_at`, to cover clock-rate drift and the time between
     * the deadline check and the radio call.
     */
    val deadlineMarginMillis: Long = 15_000,
) {
    init {
        require(maxInstructions in 1..20) { "maxInstructions must be 1..20" }
        require(deadlineMarginMillis >= 0) { "deadlineMarginMillis must be >= 0" }
    }
}

/** What one [SmsOutboxPoller.poll] did. Counts only; never carries text or destinations. */
data class SmsPollReport(
    /** Instructions in the claim response (newly leased plus still-valid leases of this install). */
    val claimed: Int = 0,
    /** Fresh transport submits made now. */
    val submitted: Int = 0,
    /** Refused before the radio (lease deadline passed, precondition), each reported as `send_failed`. */
    val failedBeforeSend: Int = 0,
    /** Keys this device had already used: answered from the ledger, nothing sent (D-006). */
    val replayed: Int = 0,
    /** Instructions that could not be executed at all (wrong connection, not leased, malformed, key conflict). */
    val ignored: Int = 0,
    /** True when the claim answer was an `Idempotent-Replayed` replay of a lost response. */
    val claimReplayed: Boolean = false,
    /** Why no claim was made or it failed; null on success. */
    val skippedReason: String? = null,
    /** Next allowed claim time after a retryable failure. */
    val nextAttemptAtMillis: Long? = null,
)

/**
 * The downlink half of ADR 0006 and the ONLY production caller of [SmsSender.send] (the other
 * caller is the debug-only demo screen). The incoming-SMS path has no reference to it.
 *
 * One [poll]:
 * 1. Loads or creates the stable `device_install_id` (random UUID, persisted once; a reinstall
 *    wipes the app-private state file, so it becomes a new lease holder, as the contract says).
 * 2. Loads the pending claim attempt or creates one: a fresh `Idempotency-Key`, the exact request
 *    body, and the local time of the first request. It is persisted BEFORE the request, and
 *    reused until the answer has been fully processed, so a lost response or a process death is
 *    recovered by replaying the same key (the server returns the original instructions and
 *    leases nothing new).
 * 3. Executes every instruction: maps it to an [SmsSendCommand] (`authorizedBy` from
 *    `authorization`), computes the start deadline and calls [SmsSender.send]. The sender checks
 *    the deadline right before the radio and performs at most one transport attempt per
 *    instruction key, so a replayed or re-claimed instruction never sends twice.
 * 4. Clears the pending claim. Outcomes reach the backend as `message.send_acknowledged`,
 *    `message.send_failed` and `message.delivery_receipt` envelopes through [SmsOutbox].
 *
 * **Deadline.** ADR 0006: `local_now + (lease_expires_at - server_time) - margin`, where
 * `local_now` must not be later than the moment the server leased. For a fresh answer it is the
 * local time just before THIS request was sent. For an `Idempotent-Replayed` answer the lease was
 * granted during an earlier attempt, so it is the time the key was FIRST sent. Either way the
 * computed deadline is never later than the real one, and replaying an old response cannot
 * extend it. Device wall-clock jumps between the claim and the send are not detected (UNVERIFIED
 * on device).
 *
 * A pending claim key older than [STALE_CLAIM_MILLIS] is dropped: the server keeps keys for at
 * least 24 h (ADR 0004), after which a replay would be a new claim, and any lease from back then
 * has long expired (the maximum lease is 600 s).
 */
class SmsOutboxPoller(
    private val scope: SmsConnectionScope,
    private val store: SmsStateStore,
    private val api: WingmanApi,
    private val sender: SmsSender,
    private val clock: SmsClock = SmsClock.SYSTEM,
    private val config: SmsPollerConfig = SmsPollerConfig(),
    private val backoff: SmsBackoff = SmsBackoff(store, SmsBackoff.CLAIM, clock),
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) {
    /** Stable per-install lease holder ID (contract `OutboxClaimRequest.device_install_id`). */
    /** The SAME ID `createConnection` binds the connection to (I-020, [SmsInstallId]). */
    fun deviceInstallId(): String = SmsInstallId.getOrCreate(store)

    /** The claim key that is persisted and not yet fully processed, if any (for diagnostics and tests). */
    fun pendingClaimKey(): String? = pendingClaim()?.key

    @Synchronized
    fun poll(preconditions: SmsSendPreconditions, ignoreBackoff: Boolean = false): SmsPollReport {
        if (!ignoreBackoff && backoff.isWaiting()) {
            return SmsPollReport(skippedReason = "backing off", nextAttemptAtMillis = backoff.nextAttemptAtMillis())
        }
        val claim = pendingClaim()?.takeIf { clock.nowMillis() - it.firstSentAtMillis < STALE_CLAIM_MILLIS } ?: newClaim()
        val request = OutboxClaimRequest(claim.deviceInstallId, claim.maxInstructions)
        val attemptStartedAt = clock.nowMillis()
        val result = runBlocking { api.claimOutboxInstructions(scope.connectionId, request, IdempotencyKey(claim.key)) }
        return when (result) {
            is ApiResult.Success -> {
                val anchor = if (result.idempotentReplayed) claim.firstSentAtMillis else attemptStartedAt
                val report = execute(result.value, anchor, preconditions).copy(claimReplayed = result.idempotentReplayed)
                // Only now is the answer fully processed: every instruction is in the send ledger.
                store.remove(KEY_PENDING_CLAIM)
                backoff.onProgress()
                report
            }
            is ApiResult.Failure -> onClaimFailure(result.error)
        }
    }

    private fun onClaimFailure(error: ApiError): SmsPollReport {
        val reason = when (error) {
            is ApiError.Http -> "HTTP ${error.status} ${error.problem?.code ?: ""}".trim()
            is ApiError.Network -> "network: ${error.cause.javaClass.simpleName}"
            is ApiError.Decoding -> "unexpected claim response body"
            is ApiError.Client -> "client: ${error.message}"
        }
        // A definitive rejection of THIS request (it was not processed, so nothing was leased under
        // the key): start a new attempt next time. Everything else, including a lost or undecodable
        // response, keeps the key so the replay can recover leases the server may have granted.
        val definitive = error is ApiError.Client ||
            (error is ApiError.Http && (error.status == 400 || error.status == 404 || error.status == 422) &&
                error.code != ErrorCode.IDEMPOTENCY_KEY_IN_PROGRESS)
        if (definitive) store.remove(KEY_PENDING_CLAIM)
        val retryAfter = (error as? ApiError.Http)?.retryAfterSeconds?.times(1000)
        val next = backoff.onRetryLater(retryAfter)
        return SmsPollReport(skippedReason = reason, nextAttemptAtMillis = next)
    }

    private fun execute(result: OutboxClaimResult, anchorMillis: Long, preconditions: SmsSendPreconditions): SmsPollReport {
        var submitted = 0
        var failed = 0
        var replayed = 0
        var ignored = 0
        for (instruction in result.instructions) {
            val command = toCommand(instruction)
            if (command == null) {
                ignored++
                continue
            }
            val leaseMillis = instruction.leaseExpiresAt.toEpochMilli() - result.serverTime.toEpochMilli()
            val deadline = anchorMillis + leaseMillis - config.deadlineMarginMillis
            when (val outcome = sender.send(command, preconditions, startDeadlineMillis = deadline)) {
                is SmsSendOutcome.KeyConflict -> ignored++
                else -> when {
                    outcome.replayed -> replayed++
                    outcome is SmsSendOutcome.Failed -> failed++
                    else -> submitted++
                }
            }
        }
        return SmsPollReport(claimed = result.instructions.size, submitted = submitted, failedBeforeSend = failed, replayed = replayed, ignored = ignored)
    }

    /** Null when the instruction is not one this device may execute; it is then left to expire on the server. */
    internal fun toCommand(i: OutboxInstruction): SmsSendCommand? {
        if (i.connectionId != scope.connectionId || i.state != OutboxInstructionState.LEASED) return null
        return runCatching {
            SmsSendCommand(
                outboundMessageId = i.outboundMessageId,
                idempotencyKey = i.idempotencyKey,
                externalThreadId = i.externalThreadId,
                text = i.text,
                authorizedBy = authorizedBy(i),
            )
        }.getOrNull()
    }

    private fun pendingClaim(): PendingClaim? {
        val o = store.get(KEY_PENDING_CLAIM)?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?: return null
        return runCatching {
            PendingClaim(
                key = o.getValue("key").jsonPrimitive.content,
                deviceInstallId = o.getValue("device_install_id").jsonPrimitive.content,
                maxInstructions = o["max_instructions"]?.jsonPrimitive?.intOrNull,
                firstSentAtMillis = o.getValue("first_sent_at").jsonPrimitive.long,
            )
        }.getOrNull()
    }

    private fun newClaim(): PendingClaim {
        val claim = PendingClaim(newKey(), deviceInstallId(), config.maxInstructions, clock.nowMillis())
        // Persisted BEFORE the request is sent (contract: "Devices persist the key before sending").
        store.put(
            KEY_PENDING_CLAIM,
            buildJsonObject {
                put("key", claim.key)
                put("device_install_id", claim.deviceInstallId)
                claim.maxInstructions?.let { put("max_instructions", it) }
                put("first_sent_at", claim.firstSentAtMillis)
            }.toString(),
        )
        return claim
    }

    /** The persisted claim attempt. The body is stored with the key: a replay with a different body is `422 idempotency_key_mismatch`. */
    private data class PendingClaim(val key: String, val deviceInstallId: String, val maxInstructions: Int?, val firstSentAtMillis: Long)

    companion object {
        const val KEY_INSTALL_ID = SmsInstallId.KEY
        const val KEY_PENDING_CLAIM = "claim:pending"

        /** A claim key is replayed for at most this long (server retention is at least 24 h, ADR 0004). */
        const val STALE_CLAIM_MILLIS: Long = 23L * 60 * 60 * 1000

        /** `SmsSendCommand.authorizedBy`: `origin`, or `approved_draft/<approval_id>`. */
        fun authorizedBy(i: OutboxInstruction): String {
            val origin = when (i.authorization.origin) {
                OutboxAuthorizationOrigin.USER_MANUAL -> "user_manual"
                OutboxAuthorizationOrigin.APPROVED_DRAFT -> "approved_draft"
                OutboxAuthorizationOrigin.AUTOPILOT -> "autopilot"
            }
            return i.authorization.approvalId?.let { "$origin/$it" } ?: origin
        }
    }
}

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
