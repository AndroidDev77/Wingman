package com.wingman.integrations.sms

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The platform send primitive, implemented on device by `android/AndroidSmsTransport`
 * (SmsManager). [submit] hands the message to the radio and returns the number of parts it
 * was split into. For each part `i` the platform later fires a "sent" PendingIntent and, if
 * requested, a "delivered" PendingIntent; the adapter routes them to
 * [SmsSender.onPartSent] / [SmsSender.onPartDelivered] with the same key and part index.
 *
 * [submit] throws when the platform refuses synchronously (e.g. SecurityException); in that
 * case nothing was transmitted.
 */
interface SmsTransport {
    fun submit(
        destination: String,
        text: String,
        subscriptionId: Int?,
        idempotencyKey: String,
        requestDeliveryReports: Boolean,
    ): Int
}

/** Local state of one send key. Transitions are one-way; see [SmsSender]. */
@Serializable
enum class SmsSendState {
    /** Key claimed and persisted; about to call the transport (or died while calling it). */
    @SerialName("claimed") CLAIMED,
    /** Transport accepted the submit; waiting for every part's "sent" result. */
    @SerialName("submitted") SUBMITTED,
    /** Every part reported RESULT_OK: the radio/SMSC accepted it. Contract status `sent`, NOT delivered. */
    @SerialName("acknowledged") ACKNOWLEDGED,
    /** Every part's status report said delivered. */
    @SerialName("delivered") DELIVERED,
    /** Status report said the SMSC gave up. Stays `sent` in the contract (it has no event for this). */
    @SerialName("delivery_failed") DELIVERY_FAILED,
    /** The single attempt failed (or its outcome is unknown after a timeout). Final. */
    @SerialName("failed") FAILED,
}

/** Persisted ledger entry for one idempotency key. Contains no message text and no phone number. */
@Serializable
data class SmsSendRecord(
    @SerialName("idempotency_key") val idempotencyKey: String,
    @SerialName("outbound_message_id") val outboundMessageId: String,
    @SerialName("external_thread_id") val externalThreadId: String,
    @SerialName("text_sha256") val textSha256: String,
    @SerialName("authorized_by") val authorizedBy: String,
    val state: SmsSendState,
    @SerialName("claimed_at") val claimedAtMillis: Long,
    @SerialName("part_count") val partCount: Int = 0,
    /** Per part: null = no result yet, "ok", or a failure code wire value. */
    @SerialName("part_sent") val partSent: List<String?> = emptyList(),
    @SerialName("part_delivery") val partDelivery: List<String?> = emptyList(),
    @SerialName("failure_code") val failureCode: String? = null,
    @SerialName("failure_retryable") val failureRetryable: Boolean? = null,
    @SerialName("failure_detail") val failureDetail: String? = null,
    @SerialName("updated_at") val updatedAtMillis: Long = claimedAtMillis,
    /** True once the transport's submit was called for this key (the one attempt is spent on the radio). */
    @SerialName("transport_called") val transportCalled: Boolean = false,
    /**
     * I-020: the scope the send was made for. A result that arrives after the connection was unpaired
     * (or re-paired as a new connection) is still reported to the connection that leased it, which
     * accepts late outcomes (ADR 0007). Null in records written before I-020: the current scope is used.
     */
    @SerialName("user_id") val userId: String? = null,
    @SerialName("connection_id") val connectionId: String? = null,
) {
    val failure: SmsSendFailure?
        get() = failureCode?.let { SmsSendFailure(SmsFailureCode.fromWire(it), failureRetryable ?: false, failureDetail ?: "") }

    val isTerminalForSend: Boolean
        get() = state != SmsSendState.CLAIMED && state != SmsSendState.SUBMITTED
}

/** Outcome of one [SmsSender.send] call. */
sealed interface SmsSendOutcome {
    val record: SmsSendRecord

    /** True when this key had been used before and no transport call was made now. */
    val replayed: Boolean

    /** The submit went to the radio; the final result arrives through the sent PendingIntents. */
    data class Submitted(override val record: SmsSendRecord, override val replayed: Boolean) : SmsSendOutcome

    /** Replay of a key whose send already completed (acknowledged/delivered). */
    data class Acknowledged(override val record: SmsSendRecord, override val replayed: Boolean) : SmsSendOutcome

    /** The attempt failed. With [replayed] true, the stored failure is returned and nothing was sent. */
    data class Failed(override val record: SmsSendRecord, val failure: SmsSendFailure, override val replayed: Boolean) : SmsSendOutcome

    /** The same key was reused with a different body: rejected, nothing sent (ADR 0004 mismatch). */
    data class KeyConflict(override val record: SmsSendRecord) : SmsSendOutcome {
        override val replayed: Boolean get() = true
    }
}

/** Device preconditions for sending, read by the adapter right before the send. */
data class SmsSendPreconditions(val hasTelephony: Boolean, val sendPermissionGranted: Boolean, val sendCapabilitySupported: Boolean = true)

/**
 * Sender module core: idempotent send bookkeeping and the result state machine.
 *
 * Rules:
 * - **One transport attempt per idempotency key, whatever the outcome (D-006).** The key is
 *   claimed in the durable ledger BEFORE the transport is called. A repeated key never calls
 *   the transport again: same body returns the stored outcome with `replayed = true`, a
 *   different body returns [SmsSendOutcome.KeyConflict]. To retry, the dispatcher sends a new
 *   key (e.g. `send:<message_id>:2`).
 * - **Acknowledged is not delivered.** All parts RESULT_OK emits `message.send_acknowledged`
 *   (drives `sent`). Only when every part's status report says delivered is
 *   `message.delivery_receipt` emitted.
 * - **Unknown outcome is not retryable.** If the process died after claiming, or no "sent"
 *   result arrives within [sentResultTimeoutMillis], [expireStale] fails the key with
 *   `timeout`, `retryable = false`, because the message may already be out.
 * - **Only the authorized dispatch path calls [send].** In production that is
 *   [SmsOutboxPoller], which executes instructions the backend dispatcher leased to this
 *   device (ADR 0006); the only other caller is the debug-only demo screen. Nothing in the
 *   inbound path holds a reference to this class.
 */
class SmsSender(
    private val scope: SmsConnectionScope,
    private val store: SmsStateStore,
    private val transport: SmsTransport,
    private val outbox: SmsOutbox,
    private val clock: SmsClock = SmsClock.SYSTEM,
    private val sentResultTimeoutMillis: Long = 2L * 60 * 1000,
    private val factory: SmsEnvelopeFactory = SmsEnvelopeFactory(scope),
) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private fun key(idempotencyKey: String) = "$PREFIX$idempotencyKey"

    /** Envelopes for a send go to the connection the send was made for (see [SmsSendRecord.connectionId]). */
    private fun factoryFor(r: SmsSendRecord): SmsEnvelopeFactory {
        val u = r.userId
        val c = r.connectionId
        if (u == null || c == null || (u == scope.userId && c == scope.connectionId)) return factory
        return runCatching { SmsEnvelopeFactory(SmsConnectionScope(u, c)) }.getOrDefault(factory)
    }

    fun record(idempotencyKey: String): SmsSendRecord? =
        store.get(key(idempotencyKey))?.let { json.decodeFromString(SmsSendRecord.serializer(), it) }

    /** How many transport submits this ledger has ever made for [idempotencyKey]: 0 or 1. */
    fun transportAttempts(idempotencyKey: String): Int = if (record(idempotencyKey)?.transportCalled == true) 1 else 0

    private fun save(r: SmsSendRecord) = store.put(key(r.idempotencyKey), json.encodeToString(SmsSendRecord.serializer(), r))

    /**
     * Performs the single transport attempt for [command]'s key, or replays the stored outcome.
     *
     * @param startDeadlineMillis local wall-clock instant (from [clock]) at or after which the
     *   transport must NOT be called: the leased-instruction deadline of ADR 0006, computed by
     *   [SmsOutboxPoller]. Checked last, immediately before the submit. When it has passed, the
     *   key is used up without a transport call and `send_failed` / `timeout` / `retryable: true`
     *   is emitted. Null means no deadline (the debug demo screen).
     */
    @Synchronized
    fun send(
        command: SmsSendCommand,
        preconditions: SmsSendPreconditions,
        subscriptionId: Int? = null,
        requestDeliveryReports: Boolean = true,
        startDeadlineMillis: Long? = null,
    ): SmsSendOutcome {
        val existing = record(command.idempotencyKey)
        if (existing != null) return replay(existing, command)

        val now = clock.nowMillis()
        val claimed = SmsSendRecord(
            idempotencyKey = command.idempotencyKey,
            outboundMessageId = command.outboundMessageId,
            externalThreadId = command.externalThreadId,
            textSha256 = SmsIds.textFingerprint(command.text),
            authorizedBy = command.authorizedBy,
            state = SmsSendState.CLAIMED,
            claimedAtMillis = now,
            userId = scope.userId,
            connectionId = scope.connectionId,
        )

        // Preconditions are checked after claiming, so a refused key is also "used" (one attempt per key).
        val destination = SmsAddress.destinationFromThreadId(command.externalThreadId)
        val precheck = when {
            !preconditions.sendCapabilitySupported -> SmsFailureMapper.CAPABILITY_UNSUPPORTED
            !preconditions.hasTelephony -> SmsFailureMapper.NO_TELEPHONY
            !preconditions.sendPermissionGranted -> SmsFailureMapper.PERMISSION_MISSING
            destination == null -> SmsFailureMapper.INVALID_THREAD
            else -> null
        }
        if (precheck != null) return fail(claimed, precheck)
        // Lease deadline (ADR 0006): checked as late as possible, right before the radio is called.
        if (startDeadlineMillis != null && clock.nowMillis() >= startDeadlineMillis) {
            return fail(claimed, SmsFailureMapper.LEASE_DEADLINE_PASSED)
        }

        val attempting = claimed.copy(transportCalled = true)
        save(attempting) // durable claim BEFORE the side effect: a crash from here on never resends
        val parts = try {
            transport.submit(destination!!, command.text, subscriptionId, command.idempotencyKey, requestDeliveryReports)
        } catch (e: Exception) {
            return fail(attempting, SmsFailureMapper.fromSubmitException(e))
        }
        require(parts >= 1) { "transport reported $parts parts" }
        val submitted = (record(command.idempotencyKey) ?: attempting).let { current ->
            // A sent result can race ahead of this line only if the adapter delivers synchronously.
            if (current.state != SmsSendState.CLAIMED) return@let current
            current.copy(
                state = SmsSendState.SUBMITTED,
                partCount = parts,
                partSent = List(parts) { null },
                partDelivery = List(parts) { null },
                updatedAtMillis = clock.nowMillis(),
            )
        }
        save(submitted)
        return SmsSendOutcome.Submitted(submitted, replayed = false)
    }

    private fun replay(existing: SmsSendRecord, command: SmsSendCommand): SmsSendOutcome {
        val same = existing.outboundMessageId == command.outboundMessageId &&
            existing.externalThreadId == command.externalThreadId &&
            existing.textSha256 == SmsIds.textFingerprint(command.text)
        if (!same) return SmsSendOutcome.KeyConflict(existing)
        return outcomeOf(existing, replayed = true)
    }

    private fun outcomeOf(r: SmsSendRecord, replayed: Boolean): SmsSendOutcome = when (r.state) {
        SmsSendState.CLAIMED, SmsSendState.SUBMITTED -> SmsSendOutcome.Submitted(r, replayed)
        SmsSendState.ACKNOWLEDGED, SmsSendState.DELIVERED, SmsSendState.DELIVERY_FAILED -> SmsSendOutcome.Acknowledged(r, replayed)
        SmsSendState.FAILED -> SmsSendOutcome.Failed(r, r.failure!!, replayed)
    }

    private fun fail(r: SmsSendRecord, failure: SmsSendFailure): SmsSendOutcome.Failed {
        val now = clock.nowMillis()
        val failed = r.copy(
            state = SmsSendState.FAILED,
            failureCode = failure.code.wire,
            failureRetryable = failure.retryable,
            failureDetail = failure.detail,
            updatedAtMillis = now,
        )
        outbox.enqueue(factoryFor(failed).sendFailed(failed, failure, now))
        save(failed)
        return SmsSendOutcome.Failed(failed, failure, replayed = false)
    }

    /**
     * Result of the "sent" PendingIntent for one part. Idempotent: a repeated or late
     * result for a part that already has one, or for a key that is already final, is ignored.
     * Returns the record after the update, or null for an unknown key.
     */
    @Synchronized
    fun onPartSent(idempotencyKey: String, partIndex: Int, result: SmsSentResult): SmsSendRecord? {
        val r = record(idempotencyKey) ?: return null
        if (r.state != SmsSendState.SUBMITTED) return r
        if (partIndex !in 0 until r.partCount || r.partSent[partIndex] != null) return r
        val failure = SmsFailureMapper.fromSentResult(result)
        val partSent = r.partSent.toMutableList().also { it[partIndex] = failure?.code?.wire ?: OK }
        var updated = r.copy(partSent = partSent, updatedAtMillis = clock.nowMillis())
        // Remember the first failing part; it becomes the reported failure once all parts are in.
        if (failure != null && updated.failureCode == null) {
            updated = updated.copy(failureCode = failure.code.wire, failureRetryable = failure.retryable, failureDetail = failure.detail)
        }
        if (partSent.any { it == null }) {
            save(updated)
            return updated
        }
        // Every part has a result: decide the single outcome of this attempt.
        val firstFailure = updated.failure
        if (firstFailure != null) {
            val failedParts = partSent.count { it != OK }
            val combined = if (partSent.any { it == OK }) {
                SmsFailureMapper.combine(List(failedParts) { firstFailure }, anyPartOk = true)
            } else {
                firstFailure
            }
            return fail(updated, combined).record
        }
        val now = clock.nowMillis()
        var acked = updated.copy(state = SmsSendState.ACKNOWLEDGED, updatedAtMillis = now)
        outbox.enqueue(factoryFor(acked).sendAcknowledged(acked, now))
        // Status reports can race ahead of the last sent result; apply them now.
        acked = promoteDelivery(acked)
        save(acked)
        return acked
    }

    private fun promoteDelivery(r: SmsSendRecord): SmsSendRecord = when {
        r.state != SmsSendState.ACKNOWLEDGED -> r
        r.partDelivery.any { it == FAILED_DELIVERY } -> r.copy(state = SmsSendState.DELIVERY_FAILED)
        r.partDelivery.isNotEmpty() && r.partDelivery.all { it == DELIVERED } -> {
            val delivered = r.copy(state = SmsSendState.DELIVERED)
            outbox.enqueue(factoryFor(delivered).deliveryReceipt(delivered, delivered.updatedAtMillis))
            delivered
        }
        else -> r
    }

    /**
     * Status report for one part. Only meaningful after ACKNOWLEDGED. Emits
     * `message.delivery_receipt` once every part is DELIVERED; a FAILED report marks the
     * record DELIVERY_FAILED locally (the contract has no event for it, so the message stays `sent`).
     */
    @Synchronized
    fun onPartDelivered(idempotencyKey: String, partIndex: Int, status: SmsDeliveryStatus): SmsSendRecord? {
        val r = record(idempotencyKey) ?: return null
        if (r.state != SmsSendState.ACKNOWLEDGED && r.state != SmsSendState.SUBMITTED) return r
        if (partIndex !in 0 until r.partCount) return r
        if (status == SmsDeliveryStatus.PENDING) return r
        if (r.partDelivery[partIndex] != null) return r
        val delivery = r.partDelivery.toMutableList().also { it[partIndex] = if (status == SmsDeliveryStatus.DELIVERED) DELIVERED else FAILED_DELIVERY }
        // While SUBMITTED, an early status report is only recorded; promotion waits for the ack.
        val updated = promoteDelivery(r.copy(partDelivery = delivery, updatedAtMillis = clock.nowMillis()))
        save(updated)
        return updated
    }

    /**
     * Fails keys whose outcome is unknown: claimed (process died during submit) or submitted
     * with missing sent results for longer than [sentResultTimeoutMillis]. Call on app start
     * and from the periodic upload worker. Never resends. Returns the keys it failed.
     */
    @Synchronized
    fun expireStale(): List<String> {
        val now = clock.nowMillis()
        val expired = mutableListOf<String>()
        for (k in store.keys(PREFIX)) {
            val r = store.get(k)?.let { json.decodeFromString(SmsSendRecord.serializer(), it) } ?: continue
            if (r.isTerminalForSend) continue
            if (now - r.updatedAtMillis < sentResultTimeoutMillis) continue
            val anyOk = r.partSent.any { it == OK }
            val failure = if (anyOk) SmsFailureMapper.combine(listOf(SmsFailureMapper.SENT_RESULT_TIMEOUT), anyPartOk = true) else SmsFailureMapper.SENT_RESULT_TIMEOUT
            fail(r, failure)
            expired += r.idempotencyKey
        }
        return expired
    }

    companion object {
        const val PREFIX = "send:"
        private const val OK = "ok"
        private const val DELIVERED = "delivered"
        private const val FAILED_DELIVERY = "failed"
    }
}
