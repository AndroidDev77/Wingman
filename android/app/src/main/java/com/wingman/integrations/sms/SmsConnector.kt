package com.wingman.integrations.sms

/**
 * Pure-Kotlin facade the Android adapters and (later) the AppContainer use. One instance per
 * process; all state lives in [store], so a new instance over the same store after a process
 * restart behaves exactly like the old one (no double upload, no second transport attempt).
 *
 * Inbound and outbound are separate objects on purpose: [inbound] has no path to [sender].
 */
class SmsConnector(
    val scope: SmsConnectionScope,
    store: SmsStateStore,
    transport: SmsTransport,
    private val uploader: SmsEnvelopeUploader = NoIngestRouteUploader,
    private val clock: SmsClock = SmsClock.SYSTEM,
) {
    val outbox = SmsOutbox(store)
    val inbound = SmsInboundProcessor(scope, SmsInboundDedup(store, clock), outbox)
    val sender = SmsSender(scope, store, transport, outbox, clock)

    /**
     * Periodic housekeeping (WorkManager on device, and once on app start): fail sends whose
     * outcome is unknown, then upload pending envelopes. Never sends an SMS.
     */
    fun maintain(maxUploads: Int = 50): SmsOutbox.DrainReport {
        sender.expireStale()
        return outbox.drain(uploader, maxUploads)
    }
}
