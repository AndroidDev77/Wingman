package com.wingman.integrations.sms

import com.wingman.core.api.WingmanApi

/** Local, server-independent status of the SMS connector, for the connections screen. Counts only. */
data class SmsLocalStatus(
    val pendingEnvelopes: Int,
    val deadLetters: Int,
    val lastSyncAtMillis: Long?,
    val lastSyncSummary: String?,
)

/** What one [SmsConnectorHost.sync] did. */
sealed interface SmsHostSyncReport {
    /** Paired: a full round (upload, claim, execute, upload) for the active connection. */
    data class Active(val report: SmsConnector.SyncReport) : SmsHostSyncReport

    /**
     * Not paired: nothing was claimed and no inbound SMS is recorded. Only leftovers were handled:
     * sends with an unknown outcome were failed, and envelopes still queued were uploaded (inbound
     * ones for an unpaired connection come back `422` and are dead-lettered; late send outcomes are
     * still accepted). [upload] is null when there was nothing to do.
     */
    data class Inactive(val upload: SmsOutbox.DrainReport?) : SmsHostSyncReport
}

/**
 * Process-wide owner of the SMS connector, pure Kotlin so its lifecycle is JVM-tested. The Android
 * `SmsConnectorRuntime` is a thin wrapper that supplies the file store and the radio transport.
 *
 * - **Inert until paired (I-020).** [connector] is null unless [SmsPairingManager] has stored an
 *   active scope. Without it the SMS receiver only writes the system inbox (as any default SMS app
 *   must), nothing is recorded for Wingman and nothing is claimed.
 * - **Pairing changes take effect at once.** The pairing manager calls [invalidate] when the active
 *   scope changes, so the next [connector] is built for the new scope, or is null after an unpair.
 * - **Leftovers are kept.** After an unpair, envelopes still in the outbox are uploaded to the
 *   connection they name, and late "sent"/"delivered" results of an SMS that was already handed to
 *   the radio are routed through [resultSender] and reported to the connection that leased it.
 */
class SmsConnectorHost(
    val store: SmsStateStore,
    private val transport: SmsTransport,
    private val clock: SmsClock = SmsClock.SYSTEM,
    private val pollerConfig: SmsPollerConfig = SmsPollerConfig(),
) {
    @Volatile private var api: WingmanApi? = null
    private var connector: SmsConnector? = null
    private var pairing: SmsPairingManager? = null

    /** Same client again (e.g. the activity was recreated): keeps the connector and its locks. Returns true when it changed. */
    @Synchronized
    fun connectBackend(newApi: WingmanApi?): Boolean {
        if (newApi === api) return false
        api = newApi
        connector = null
        pairing = null
        return true
    }

    fun backend(): WingmanApi? = api

    /** Serializes sync rounds and pairing changes process-wide, across connector rebuilds. */
    private val exclusiveLock = Any()

    /**
     * Runs [block] while no sync round runs, so a pair or unpair never changes the scope in the middle
     * of an upload/claim/send round. Blocking: call from a background thread.
     */
    fun <T> exclusive(block: () -> T): T = synchronized(exclusiveLock) { block() }

    /** The pairing manager, or null while no backend client is connected. */
    @Synchronized
    fun pairing(): SmsPairingManager? {
        pairing?.let { return it }
        val a = api ?: return null
        return SmsPairingManager(store, a, clock, onScopeChanged = { invalidate() }).also { pairing = it }
    }

    /** Drops the cached connector; the next [connector] call reads the scope from the store again. */
    @Synchronized
    fun invalidate() {
        connector = null
    }

    fun activeScope(): SmsConnectionScope? = scope(SmsPairingKeys.USER, SmsPairingKeys.CONNECTION)

    /** The connector for the ACTIVE paired connection, or null while unpaired. */
    @Synchronized
    fun connector(): SmsConnector? {
        val scope = activeScope()
        val cached = connector
        if (cached != null && cached.scope == scope) return cached
        if (scope == null) {
            connector = null
            return null
        }
        return SmsConnector(scope, store, transport, clock = clock, api = api, pollerConfig = pollerConfig).also { connector = it }
    }

    /**
     * Where the platform's per-part results go. Paired: the active connector's sender. Unpaired: a
     * sender for the retired scope, so a result for an SMS already on the radio is still recorded and
     * reported to the connection that leased it. Null when nothing was ever paired.
     */
    @Synchronized
    fun resultSender(): SmsSender? {
        connector()?.let { return it.sender }
        val retired = scope(SmsPairingKeys.RETIRED_USER, SmsPairingKeys.RETIRED_CONNECTION) ?: return null
        return SmsSender(retired, store, transport, SmsOutbox(store), clock)
    }

    /** One backend round. Blocking: call from a background thread, never the main thread. */
    fun sync(preconditions: SmsSendPreconditions, ignoreBackoff: Boolean = false): SmsHostSyncReport = exclusive {
        syncLocked(preconditions, ignoreBackoff)
    }

    private fun syncLocked(preconditions: SmsSendPreconditions, ignoreBackoff: Boolean): SmsHostSyncReport {
        val active = connector()
        val report: SmsHostSyncReport = if (active != null) {
            SmsHostSyncReport.Active(active.sync(preconditions, ignoreBackoff))
        } else {
            SmsHostSyncReport.Inactive(finishLeftovers(ignoreBackoff))
        }
        store.put(SmsPairingKeys.LAST_SYNC_AT, clock.nowMillis().toString())
        store.put(SmsPairingKeys.LAST_SYNC_SUMMARY, summarize(report))
        return report
    }

    private fun finishLeftovers(ignoreBackoff: Boolean): SmsOutbox.DrainReport? {
        resultSender()?.expireStale()
        val outbox = SmsOutbox(store)
        if (outbox.size() == 0) return null
        val uploader = api?.let(::WingmanApiSmsEnvelopeUploader) ?: NoBackendUploader
        val backoff = SmsBackoff(store, SmsBackoff.UPLOAD, clock)
        return outbox.drain(uploader, backoff = backoff.takeUnless { ignoreBackoff })
    }

    fun localStatus(): SmsLocalStatus {
        val outbox = SmsOutbox(store)
        return SmsLocalStatus(
            pendingEnvelopes = outbox.size(),
            deadLetters = outbox.deadLetterCount(),
            lastSyncAtMillis = store.get(SmsPairingKeys.LAST_SYNC_AT)?.toLongOrNull(),
            lastSyncSummary = store.get(SmsPairingKeys.LAST_SYNC_SUMMARY),
        )
    }

    private fun scope(userKey: String, connectionKey: String): SmsConnectionScope? {
        val user = store.get(userKey) ?: return null
        val connection = store.get(connectionKey) ?: return null
        return runCatching { SmsConnectionScope(user, connection) }.getOrNull()
    }

    companion object {
        /** Plain-language one-liner; counts and reasons only, never text or numbers. */
        fun summarize(report: SmsHostSyncReport): String = when (report) {
            is SmsHostSyncReport.Active -> {
                val up = report.report.uploadBefore.accepted + (report.report.uploadAfter?.accepted ?: 0)
                val poll = report.report.poll
                val waiting = report.report.uploadBefore.retryReason ?: poll.skippedReason
                buildString {
                    append("uploaded $up, sent ${poll.submitted}")
                    if (poll.failedBeforeSend > 0) append(", refused ${poll.failedBeforeSend}")
                    if (waiting != null) append(" (waiting: $waiting)")
                }
            }
            is SmsHostSyncReport.Inactive -> when (val u = report.upload) {
                null -> "not paired; nothing to do"
                else -> "not paired; uploaded ${u.accepted} leftover, dead-lettered ${u.rejected}" +
                    (u.retryReason?.let { " (waiting: $it)" } ?: "")
            }
        }
    }
}
