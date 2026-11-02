package com.wingman.integrations.hinge

import com.wingman.core.api.ApiError
import com.wingman.core.api.ApiResult
import com.wingman.core.api.IdempotencyKey
import com.wingman.core.model.ErrorCode
import com.wingman.core.model.OutboxAuthorizationOrigin
import com.wingman.core.model.OutboxClaimRequest
import com.wingman.core.model.OutboxInstruction
import com.wingman.core.model.OutboxInstructionState
import com.wingman.integrations.sms.SmsBackoff
import com.wingman.integrations.sms.SmsClock
import com.wingman.integrations.sms.SmsStateStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/** Who and which connection the connector works for. Both are Wingman UUIDs. */
data class HingeScope(val userId: String, val connectionId: String)

/** Counts only: never text, thread ids or tokens. */
data class HingeSendReport(
    val claimed: Int = 0,
    val acknowledged: Int = 0,
    val failed: Int = 0,
    /** Frame written, no echo: outcome unknown, never resent (the lease expires as `lease_expired`). */
    val unknown: Int = 0,
    /** Keys already in the ledger: nothing sent (D-006). */
    val replayed: Int = 0,
    val ignored: Int = 0,
    val skippedReason: String? = null,
)

/** One send ledger entry per instruction idempotency key (D-006). */
@Serializable
internal data class HingeLedgerEntry(
    val state: String,
    /** The outcome envelope, re-enqueued on a replay so a lost upload is recovered. */
    val envelope: String? = null,
    @SerialName("at") val atMillis: Long,
) {
    companion object {
        /** Persisted BEFORE the socket write: if we die now, the outcome is unknown and never resent. */
        const val ATTEMPTING = "attempting"
        const val ACKNOWLEDGED = "acknowledged"
        const val FAILED = "failed"
        const val UNKNOWN = "unknown"
    }
}

/**
 * The downlink: claims leased instructions for THIS connection and executes each one at most once
 * (ADR 0006, D-006). The same claim protocol as `SmsOutboxPoller` (persisted claim key, replay on a
 * lost response, deadline from `server_time`), with Hinge-specific refusals first:
 *
 * 1. **Approval-only, defense in depth.** The server already refuses autopilot for an
 *    experimental-unverified connection (D-022). The device refuses it again: only
 *    `approved_draft` (with an approval id) and `user_manual` are executed; `autopilot` gets
 *    `send_failed` `capability_unsupported` and nothing is sent.
 * 2. **No first messages.** The thread must be a Sendbird channel in which the match has already
 *    written to the user (seen by sync). Channel creation is not even allowlisted.
 * 3. **Rate limits** ([HingeRateLimiter]): 20 a day, 5 per conversation, 60 s apart, and a random
 *    20 to 90 s delay before the frame, always inside the lease deadline.
 * 4. **One attempt per key.** The ledger entry is written BEFORE the `MESG` frame. A key already in
 *    the ledger is never sent again. An echo is `send_acknowledged` (sent, not delivered); a
 *    refusal is `send_failed`; a TIMEOUT reports NOTHING: the server marks the lease
 *    `lease_expired` with outcome unknown, and nothing ever resends it.
 */
class HingeOutboxExecutor(
    private val scope: HingeScope,
    private val deviceInstallId: String,
    private val store: SmsStateStore,
    private val backend: HingeBackend,
    private val gate: HingeNetGate,
    private val auth: HingeAuthFlow,
    private val sessions: HingeSessionStore,
    private val transport: SendbirdChatTransport,
    private val limiter: HingeRateLimiter,
    private val breaker: HingeCircuitBreaker,
    private val queue: HingeEnvelopeQueue,
    private val clock: HingeClock = HingeClock.SYSTEM,
    private val sleeper: HingeSleeper = HingeSleeper.SYSTEM,
    private val random: HingeRandom = HingeRandom.SYSTEM,
    private val log: HingeLog = HingeLog.NONE,
    private val echoTimeoutMillis: Long = 15_000,
    private val deadlineMarginMillis: Long = 15_000,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mapper = HingeEventMapper(scope.userId, scope.connectionId)
    private val backoff = SmsBackoff(store, "hinge-claim", SmsClock { clock.nowMillis() })

    @Synchronized
    fun poll(): HingeSendReport {
        try {
            gate.checkActive()
        } catch (e: HingeCallRefused) {
            // Not claiming: a lease we cannot use would only expire.
            return HingeSendReport(skippedReason = e.code)
        }
        if (sessions.load()?.hasHingeAuth(clock.nowMillis()) != true) return HingeSendReport(skippedReason = "not_logged_in")
        if (backoff.isWaiting()) return HingeSendReport(skippedReason = "claim_backoff")
        val claim = pendingClaim()?.takeIf { clock.nowMillis() - it.firstSentAt < STALE_CLAIM_MILLIS } ?: newClaim()
        val startedAt = clock.nowMillis()
        val result = runBlocking { backend.claim(scope.connectionId, OutboxClaimRequest(deviceInstallId, 1), IdempotencyKey(claim.key)) }
        return when (result) {
            is ApiResult.Failure -> {
                val e = result.error
                val definitive = e is ApiError.Client || (e is ApiError.Http && e.status in setOf(400, 404, 422) && e.code != ErrorCode.IDEMPOTENCY_KEY_IN_PROGRESS)
                if (definitive) store.remove(K_PENDING_CLAIM)
                backoff.onRetryLater((e as? ApiError.Http)?.retryAfterSeconds?.times(1000))
                HingeSendReport(skippedReason = "claim_failed")
            }
            is ApiResult.Success -> {
                val anchor = if (result.idempotentReplayed) claim.firstSentAt else startedAt
                var report = HingeSendReport(claimed = result.value.instructions.size)
                try {
                    for (i in result.value.instructions) {
                        val lease = i.leaseExpiresAt.toEpochMilli() - result.value.serverTime.toEpochMilli()
                        report = add(report, execute(i, anchor + lease - deadlineMarginMillis))
                    }
                } finally {
                    transport.close()
                }
                store.remove(K_PENDING_CLAIM)
                backoff.onProgress()
                report
            }
        }
    }

    private enum class Outcome { ACK, FAILED, UNKNOWN, REPLAYED, IGNORED }

    private fun add(r: HingeSendReport, o: Outcome) = when (o) {
        Outcome.ACK -> r.copy(acknowledged = r.acknowledged + 1)
        Outcome.FAILED -> r.copy(failed = r.failed + 1)
        Outcome.UNKNOWN -> r.copy(unknown = r.unknown + 1)
        Outcome.REPLAYED -> r.copy(replayed = r.replayed + 1)
        Outcome.IGNORED -> r.copy(ignored = r.ignored + 1)
    }

    private fun execute(i: OutboxInstruction, deadline: Long): Outcome {
        if (i.connectionId != scope.connectionId || i.state != OutboxInstructionState.LEASED) return Outcome.IGNORED
        if (!IdempotencyKey.PATTERN.matches(i.idempotencyKey)) return Outcome.IGNORED
        val ledgerKey = K_LEDGER + i.idempotencyKey
        ledger(ledgerKey)?.let { entry ->
            entry.envelope?.let { queue.enqueue(HingeEnvelope(eventIdOf(it), "replay", json.parseToJsonElement(it) as kotlinx.serialization.json.JsonObject)) }
            log.log("hinge.send.replayed", mapOf("state" to entry.state))
            return Outcome.REPLAYED
        }
        // 1. Approval-only (defense in depth; the server enforces it too).
        val origin = i.authorization.origin
        val approved = (origin == OutboxAuthorizationOrigin.APPROVED_DRAFT && !i.authorization.approvalId.isNullOrEmpty()) ||
            origin == OutboxAuthorizationOrigin.USER_MANUAL
        if (!approved) return fail(i, ledgerKey, "capability_unsupported", "origin_not_allowed", retryable = false)
        // 2. No first messages: only an existing conversation the match wrote in.
        if (store.get(knownConversationKey(i.externalThreadId)) == null) {
            return fail(i, ledgerKey, "capability_unsupported", "no_existing_conversation", retryable = false)
        }
        if (i.text.isBlank()) return fail(i, ledgerKey, "rejected_by_platform", "empty_text", retryable = false)
        // 3. Local limits and the pre-send delay, inside the lease.
        val conv = HingeHashes.short(i.externalThreadId)
        limiter.sendDenial(conv)?.let { return fail(i, ledgerKey, "transport_error", "local_limit_" + it.code, retryable = false) }
        val delay = random.nextLong(limiter.config.sendJitterMinMillis, limiter.config.sendJitterMaxMillis)
        if (clock.nowMillis() + delay >= deadline) return fail(i, ledgerKey, "timeout", "lease_too_short_for_delay", retryable = false)
        sleeper.sleep(delay)
        try {
            gate.checkActive()
        } catch (e: HingeCallRefused) {
            return fail(i, ledgerKey, "not_connected", "connector_" + e.code, retryable = false)
        }
        if (clock.nowMillis() >= deadline) return fail(i, ledgerKey, "timeout", "lease_deadline_passed", retryable = false)
        // 4. Chat session (one forced refresh when the socket comes up without LOGI).
        val ready = try {
            openChat()
        } catch (e: HingeCallRefused) {
            return fail(i, ledgerKey, "not_connected", "connector_" + e.code, retryable = false)
        }
        if (!ready) {
            val stopped = breaker.isStopped()
            return fail(i, ledgerKey, "not_connected", if (stopped) "connector_stopped" else "chat_unavailable", retryable = !stopped)
        }
        if (clock.nowMillis() >= deadline) return fail(i, ledgerKey, "timeout", "lease_deadline_passed", retryable = false)
        // 5. Persist the attempt, then write exactly one frame.
        putLedger(ledgerKey, HingeLedgerEntry(HingeLedgerEntry.ATTEMPTING, null, clock.nowMillis()))
        limiter.recordSend(conv)
        return when (val r = transport.send(i.externalThreadId, i.text, echoTimeoutMillis)) {
            is SendbirdSend.Acknowledged -> {
                breaker.observe(HingeSignal.Ok)
                val env = mapper.sendAcknowledged(i.outboundMessageId, i.idempotencyKey, i.externalThreadId, r.messageId, clock.nowMillis())
                putLedger(ledgerKey, HingeLedgerEntry(HingeLedgerEntry.ACKNOWLEDGED, env.json.toString(), clock.nowMillis()))
                queue.enqueue(env)
                Outcome.ACK
            }
            is SendbirdSend.Refused -> {
                if (r.guest) {
                    // Stale chat auth (reference: 900030). Nothing was stored. Drop the chat token
                    // so the NEXT attempt (a new key from the dispatcher) re-authenticates first.
                    sessions.load()?.let { sessions.save(it.copy(chatToken = null, chatExpiresAtMillis = null)) }
                    finish(i, ledgerKey, "rejected_by_platform", "chat_session_stale", retryable = true)
                } else {
                    breaker.observe(r.signal)
                    finish(i, ledgerKey, "rejected_by_platform", "sendbird_refused_${r.code}", retryable = false)
                }
            }
            SendbirdSend.NotWritten -> finish(i, ledgerKey, "not_connected", "socket_unavailable", retryable = true)
            SendbirdSend.Timeout -> {
                putLedger(ledgerKey, HingeLedgerEntry(HingeLedgerEntry.UNKNOWN, null, clock.nowMillis()))
                log.log("hinge.send.outcome_unknown", emptyMap())
                Outcome.UNKNOWN
            }
        }
    }

    /** Ensures a chat token and an open socket. One forced token refresh when `LOGI` is missing. */
    private fun openChat(): Boolean {
        val session = auth.ensureChatToken() ?: return false
        when (val c = transport.connect(session)) {
            SendbirdConnect.Ready -> return true
            SendbirdConnect.Closed -> return false
            is SendbirdConnect.Refused -> {
                breaker.observe(c.signal)
                return false
            }
            SendbirdConnect.NoLogin -> Unit
        }
        val refreshed = auth.ensureChatToken(force = true) ?: return false
        return when (val c = transport.connect(refreshed)) {
            SendbirdConnect.Ready -> true
            SendbirdConnect.NoLogin -> {
                breaker.observe(HingeSignal.Unauthorized)
                false
            }
            is SendbirdConnect.Refused -> {
                breaker.observe(c.signal)
                false
            }
            SendbirdConnect.Closed -> false
        }
    }

    /** Refused before anything was written: ledger + `send_failed`. */
    private fun fail(i: OutboxInstruction, ledgerKey: String, code: String, detail: String, retryable: Boolean): Outcome {
        log.log("hinge.send.refused_before_write", mapOf("detail" to detail))
        return finish(i, ledgerKey, code, detail, retryable)
    }

    private fun finish(i: OutboxInstruction, ledgerKey: String, code: String, detail: String, retryable: Boolean): Outcome {
        val env = mapper.sendFailed(i.outboundMessageId, i.idempotencyKey, i.externalThreadId, code, detail, retryable, clock.nowMillis())
        putLedger(ledgerKey, HingeLedgerEntry(HingeLedgerEntry.FAILED, env.json.toString(), clock.nowMillis()))
        queue.enqueue(env)
        return Outcome.FAILED
    }

    private fun eventIdOf(raw: String): String =
        (json.parseToJsonElement(raw) as kotlinx.serialization.json.JsonObject)["event_id"].toString().trim('"')

    private fun ledger(key: String): HingeLedgerEntry? = store.get(key)?.let { runCatching { json.decodeFromString(HingeLedgerEntry.serializer(), it) }.getOrNull() }
        ?: store.get(key)?.let { HingeLedgerEntry(HingeLedgerEntry.UNKNOWN, null, 0) }

    private fun putLedger(key: String, e: HingeLedgerEntry) = store.put(key, json.encodeToString(HingeLedgerEntry.serializer(), e))

    @Serializable
    private data class PendingClaim(val key: String, @SerialName("first_sent_at") val firstSentAt: Long)

    private fun pendingClaim(): PendingClaim? = store.get(K_PENDING_CLAIM)?.let { runCatching { json.decodeFromString(PendingClaim.serializer(), it) }.getOrNull() }

    private fun newClaim(): PendingClaim = PendingClaim(newKey(), clock.nowMillis()).also {
        store.put(K_PENDING_CLAIM, json.encodeToString(PendingClaim.serializer(), it)) // before the request
    }

    companion object {
        const val K_LEDGER = "send:"
        const val K_PENDING_CLAIM = "claim:pending"
        const val K_KNOWN_CONVERSATION = "chan:inbound:"
        const val STALE_CLAIM_MILLIS: Long = 23L * 60 * 60 * 1000

        fun knownConversationKey(threadId: String) = K_KNOWN_CONVERSATION + HingeHashes.short(threadId)
    }
}
