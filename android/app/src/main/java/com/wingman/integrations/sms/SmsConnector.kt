package com.wingman.integrations.sms

import com.wingman.core.api.WingmanApi

/**
 * Pure-Kotlin facade for ONE paired connection ([scope]); built by [SmsConnectorHost] only while
 * this install is paired (I-020). One instance per process and scope; all state lives in [store], so a new instance over the same store after a process
 * restart behaves exactly like the old one (no double upload, no second transport attempt).
 *
 * Inbound and outbound are separate objects on purpose: [inbound] has no path to [sender].
 * The only production path to [sender] is [poller], which executes send instructions the backend
 * dispatcher leased to this device (contract v1.1.0, ADR 0006).
 *
 * @param uploader explicit uploader; when null, [api] gives [WingmanApiSmsEnvelopeUploader], and
 *   without an [api] envelopes stay queued ([NoBackendUploader]).
 * @param api the backend client (AppContainer's `WingmanApi`, which carries the auth token). Null
 *   while no backend is connected: then nothing is claimed and nothing is uploaded.
 */
class SmsConnector(
    val scope: SmsConnectionScope,
    store: SmsStateStore,
    transport: SmsTransport,
    uploader: SmsEnvelopeUploader? = null,
    private val clock: SmsClock = SmsClock.SYSTEM,
    api: WingmanApi? = null,
    pollerConfig: SmsPollerConfig = SmsPollerConfig(),
) {
    private val uploader: SmsEnvelopeUploader = uploader ?: api?.let(::WingmanApiSmsEnvelopeUploader) ?: NoBackendUploader
    val outbox = SmsOutbox(store)
    val uploadBackoff = SmsBackoff(store, SmsBackoff.UPLOAD, clock)
    val inbound = SmsInboundProcessor(scope, SmsInboundDedup(store, clock), outbox)
    val sender = SmsSender(scope, store, transport, outbox, clock)
    val poller: SmsOutboxPoller? = api?.let { SmsOutboxPoller(scope, store, it, sender, clock, pollerConfig) }

    /**
     * Periodic housekeeping (and once on app start): fail sends whose outcome is unknown, then
     * upload pending envelopes, respecting the persisted upload backoff unless [ignoreBackoff].
     * Never sends an SMS.
     */
    fun maintain(maxUploads: Int = 50, ignoreBackoff: Boolean = false): SmsOutbox.DrainReport {
        sender.expireStale()
        return outbox.drain(uploader, maxUploads, uploadBackoff.takeUnless { ignoreBackoff })
    }

    /**
     * One full backend round: [maintain] (upload what is queued), claim and execute leased send
     * instructions, then upload again so refusals (`send_failed`) reach the backend at once.
     * Acknowledgements arrive later through the sent PendingIntents and go out on the next round.
     * Blocking; call from a background thread.
     */
    fun sync(preconditions: SmsSendPreconditions, ignoreBackoff: Boolean = false): SyncReport {
        val before = maintain(ignoreBackoff = ignoreBackoff)
        val poll = poller?.poll(preconditions, ignoreBackoff) ?: SmsPollReport(skippedReason = "no backend connected")
        val after = if (poll.failedBeforeSend > 0) outbox.drain(uploader, backoff = uploadBackoff.takeUnless { ignoreBackoff }) else null
        return SyncReport(before, poll, after)
    }

    data class SyncReport(val uploadBefore: SmsOutbox.DrainReport, val poll: SmsPollReport, val uploadAfter: SmsOutbox.DrainReport?)
}
