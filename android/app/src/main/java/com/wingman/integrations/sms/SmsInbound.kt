package com.wingman.integrations.sms

/**
 * Durable first-writer-wins claims on the contract's inbound dedup key
 * `(user_id, platform, external_message_id)`. Survives process death because the claims
 * live in [SmsStateStore].
 *
 * Claims older than [retentionMillis] are pruned (opportunistically, on claim) so the store
 * stays small. The OS redelivers an unhandled SMS_DELIVER within seconds to minutes, so the
 * default 7 days is generous; the backend dedups on the same key permanently anyway.
 */
class SmsInboundDedup(
    private val store: SmsStateStore,
    private val clock: SmsClock = SmsClock.SYSTEM,
    private val retentionMillis: Long = 7L * 24 * 60 * 60 * 1000,
) {
    private fun key(scope: SmsConnectionScope, externalMessageId: String) =
        "$PREFIX${scope.userId}|$SMS_PLATFORM|$externalMessageId"

    fun isClaimed(scope: SmsConnectionScope, externalMessageId: String): Boolean =
        store.get(key(scope, externalMessageId)) != null

    /** True only for the first claim of this key. */
    @Synchronized
    fun claim(scope: SmsConnectionScope, externalMessageId: String): Boolean {
        val k = key(scope, externalMessageId)
        if (store.get(k) != null) return false
        val now = clock.nowMillis()
        store.put(k, now.toString())
        prune(now)
        return true
    }

    private fun prune(now: Long) {
        for (k in store.keys(PREFIX)) {
            val at = store.get(k)?.toLongOrNull() ?: continue
            if (now - at > retentionMillis) store.remove(k)
        }
    }

    companion object {
        const val PREFIX = "dedup:"
    }
}

/** What happened to one incoming SMS. */
sealed interface SmsInboundOutcome {
    val externalMessageId: String

    /** New message: its envelope is durably queued for upload. */
    data class Accepted(override val externalMessageId: String, val envelope: SmsEnvelope) : SmsInboundOutcome

    /** Already seen (duplicate broadcast or post-restart redelivery): dropped, nothing queued. */
    data class Duplicate(override val externalMessageId: String) : SmsInboundOutcome
}

/**
 * Receiver module core. Maps an [IncomingSms] into a `message.received` envelope, drops
 * duplicates, and queues new envelopes durably for upload.
 *
 * Deliberately has NO reference to [SmsSender]: an inbound message can never cause a send
 * on the device. Replies only come back as authorized [SmsSendCommand]s through the
 * dispatch path (backend automation controller, WING-011), one transport attempt per key.
 *
 * Crash safety: the envelope is enqueued BEFORE the dedup claim. If the process dies between
 * the two, the redelivered broadcast re-enqueues the same envelope (same deterministic
 * `event_id`, stored once by [SmsOutbox]) and then claims. If it dies after the claim, the
 * envelope is already durable. Either way the message is uploaded exactly once per event_id
 * from the device's point of view, and the backend dedups on the same key.
 */
class SmsInboundProcessor(
    private val scope: SmsConnectionScope,
    private val dedup: SmsInboundDedup,
    private val outbox: SmsOutbox,
    private val factory: SmsEnvelopeFactory = SmsEnvelopeFactory(scope),
) {
    @Synchronized
    fun onSmsReceived(sms: IncomingSms): SmsInboundOutcome {
        val externalId = SmsIds.inboundExternalMessageId(sms)
        if (dedup.isClaimed(scope, externalId)) return SmsInboundOutcome.Duplicate(externalId)
        val envelope = factory.received(sms)
        outbox.enqueue(envelope)
        return if (dedup.claim(scope, externalId)) {
            SmsInboundOutcome.Accepted(externalId, envelope)
        } else {
            SmsInboundOutcome.Duplicate(externalId)
        }
    }
}
