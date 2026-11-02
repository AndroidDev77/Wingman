package com.wingman.integrations.hinge

import com.wingman.core.api.ApiError
import com.wingman.core.api.ApiResult
import com.wingman.core.api.IdempotencyKey
import com.wingman.core.model.Connection
import com.wingman.core.model.ConnectionTransport
import com.wingman.integrations.sms.SmsBackoff
import com.wingman.integrations.sms.SmsClock
import com.wingman.integrations.sms.SmsEnvelopeUploader
import com.wingman.integrations.sms.SmsStateStore
import com.wingman.integrations.sms.SmsUploadResult
import com.wingman.integrations.sms.WingmanApiSmsEnvelopeUploader
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import java.util.UUID

/** Everything the opt-in screen shows. No secrets, no text. */
data class HingeConnectorStatus(
    /** Experimental build flag on AND client identity configured. False: the screen is not shown. */
    val available: Boolean,
    val optedIn: Boolean,
    val consent: HingeConsentRecord?,
    val login: HingeLoginState,
    val paired: Boolean,
    val stop: HingeStopReason?,
    val canReEnable: Boolean,
    val revokePending: Boolean,
    val queuedUploads: Int,
    val sendsToday: Int,
    val callsToday: Int,
    val notice: String?,
)

sealed interface HingePairResult {
    data class Paired(val connection: Connection) : HingePairResult
    data class Failed(val code: String, val retryable: Boolean) : HingePairResult
}

/** Result of [HingeConnector.optOut]. The local wipe has ALWAYS happened when this returns. */
data class HingeOptOutResult(val sessionWiped: Boolean, val revokedOnServer: Boolean, val revokePending: Boolean)

/**
 * Facade of the experimental connector: consent, login, pairing, sync, send, opt-out and the manual
 * re-enable. Pure Kotlin, blocking: call it from a background thread (the WorkManager worker or the
 * screen's background executor), never from the main thread.
 *
 * **Off by default.** Nothing calls Hinge or Sendbird unless [HingeBuildFlags.connectorAvailable],
 * the user accepted the warning ([acceptRisk]) and the breaker is closed ([HingeNetGate]).
 *
 * **Opt-out ([optOut])** is effective before it returns: the opt-in flag is cleared first (so every
 * further Hinge/Sendbird call is refused at the gate), the socket is closed, the session is wiped
 * and the Keystore key destroyed, scheduled work is cancelled, queued inbound envelopes are dropped,
 * and then `revokeExperimentalConsent` is called on Wingman (persisted and retried by [finishPendingWork]
 * if offline). After it, the connector makes ZERO Hinge or Sendbird calls (tested).
 */
class HingeConnector(
    val flags: HingeBuildFlags,
    private val store: SmsStateStore,
    private val sessions: HingeSessionStore,
    private val backend: HingeBackend,
    limits: HingeLimits = HingeLimits.DEFAULT,
    baseHttpClient: OkHttpClient = OkHttpClient(),
    socketFactory: ((HingeHttp) -> ChatSocketFactory)? = null,
    private val clock: HingeClock = HingeClock.SYSTEM,
    sleeper: HingeSleeper = HingeSleeper.SYSTEM,
    random: HingeRandom = HingeRandom.SYSTEM,
    private val log: HingeLog = HingeLog.NONE,
    /** In-app/system notice when the breaker trips (the Android adapter posts a notification). */
    private val onStopped: (HingeStopReason) -> Unit = {},
    /** Cancels scheduled background work (WorkManager) on opt-out. */
    private val cancelWork: () -> Unit = {},
    private val newKey: () -> String = { UUID.randomUUID().toString() },
    echoTimeoutMillis: Long = 15_000,
    loginTimeoutMillis: Long = 10_000,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val breaker = HingeCircuitBreaker(store, clock, log)
    val limiter = HingeRateLimiter(store, limits, clock, sleeper)
    val gate = HingeNetGate(flags, ::isOptedIn, breaker, limiter, log)
    val http = HingeHttp(gate, flags.identity, baseHttpClient, clock, log)
    val transport = SendbirdChatTransport(
        http, gate, flags.identity,
        socketFactory?.invoke(http) ?: OkHttpChatSocketFactory(http.webSocketClient()),
        clock, log, loginTimeoutMillis,
    )
    val auth = HingeAuthFlow(http, sessions, limiter, breaker, clock, log)
    val queue = HingeEnvelopeQueue(store)
    private val uploadBackoff = SmsBackoff(store, "hinge-upload", SmsClock { clock.nowMillis() })
    private val executorFactory = { s: HingeScope ->
        HingeOutboxExecutor(s, deviceInstallId(), store, backend, gate, auth, sessions, transport, limiter, breaker, queue, clock, sleeper, random, log, echoTimeoutMillis, newKey = newKey)
    }
    private val syncFactory = { s: HingeScope -> HingeSync(s, store, gate, http, auth, sessions, transport, limiter, breaker, queue, clock, log) }

    init {
        breaker.onTrip = { reason ->
            transport.close()
            if (reason.wipesSession) {
                sessions.wipe()
                auth.reset()
            }
            scope()?.let { queue.enqueue(HingeEventMapper(it.userId, it.connectionId).statusChanged("disconnected", reason.code, clock.nowMillis())) }
            runCatching { onStopped(reason) }
        }
    }

    // --- opt-in -----------------------------------------------------------------------------

    fun isOptedIn(): Boolean = store.get(K_OPTED_IN) == "true" && consent() != null

    fun consent(): HingeConsentRecord? = store.get(K_CONSENT)?.let { runCatching { json.decodeFromString(HingeConsentRecord.serializer(), it) }.getOrNull() }

    /** The user ticked the acknowledgement and tapped "I accept the risk". No network call. */
    @Synchronized
    fun acceptRisk(banRiskAcknowledged: Boolean, textVersion: String = HingeConsentText.VERSION): Boolean {
        if (!flags.connectorAvailable || !banRiskAcknowledged) return false
        val record = HingeConsentRecord(textVersion, clock.nowMillis(), true)
        store.put(K_CONSENT, json.encodeToString(HingeConsentRecord.serializer(), record))
        store.put(K_OPTED_IN, "true")
        store.remove(K_NOTICE)
        log.log("hinge.optin.accepted", mapOf("version" to textVersion))
        return true
    }

    fun requestCode(phoneNumber: String): HingeLoginStep = auth.requestCode(phoneNumber)
    fun submitCode(code: String): HingeLoginStep = auth.submitCode(code)
    fun submitEmailCode(code: String): HingeLoginStep = auth.submitEmailCode(code)

    // --- pairing (Wingman) ------------------------------------------------------------------

    fun deviceInstallId(): String = synchronized(store) {
        store.get(K_INSTALL) ?: UUID.randomUUID().toString().also { store.put(K_INSTALL, it) }
    }

    fun scope(): HingeScope? {
        val u = store.get(K_USER) ?: return null
        val c = store.get(K_CONNECTION) ?: return null
        return HingeScope(u, c)
    }

    /**
     * `createConnection` with `platform: hinge`, `transport: unofficial_api`, `experimental: true`
     * and the recorded consent. Only after consent and a completed Hinge login. The key and body
     * are persisted first and replayed until a final answer (ADR 0004).
     */
    @Synchronized
    fun pair(): HingePairResult {
        if (!flags.connectorAvailable) return HingePairResult.Failed(HingeCallRefused.FLAG_OFF, false)
        val consent = consent()?.takeIf { isOptedIn() } ?: return HingePairResult.Failed(HingeCallRefused.NOT_OPTED_IN, false)
        if (auth.state != HingeLoginState.ChatReady) return HingePairResult.Failed("login_required", false)
        scope()?.let { return HingePairResult.Failed("already_paired", false) }
        val me = when (val r = runBlocking { backend.me() }) {
            is ApiResult.Success -> r.value
            is ApiResult.Failure -> return HingePairResult.Failed("me_" + errorCode(r.error), r.error.isRetryable)
        }
        val pending = store.get(K_PENDING_PAIR)?.let { runCatching { json.decodeFromString(PendingPair.serializer(), it) }.getOrNull() }
            ?.takeIf { it.userId == me.id && clock.nowMillis() - it.createdAt < STALE_MILLIS }
            ?: PendingPair(newKey(), me.id, HingeCapabilities.pairingBody(deviceInstallId(), consent, clock.nowMillis()).toString(), clock.nowMillis())
                .also { store.put(K_PENDING_PAIR, json.encodeToString(PendingPair.serializer(), it)) }
        val body = json.parseToJsonElement(pending.body) as JsonObject
        return when (val r = runBlocking { backend.pairExperimental(body, IdempotencyKey(pending.key)) }) {
            is ApiResult.Success -> {
                store.remove(K_PENDING_PAIR)
                val c = r.value
                if (c.platform != HINGE_PLATFORM || c.transport != ConnectionTransport.UNOFFICIAL_API || c.experimental != true || !c.isPaired ||
                    (c.deviceInstallId != null && c.deviceInstallId != deviceInstallId())
                ) {
                    return HingePairResult.Failed("unexpected_connection", false)
                }
                store.put(K_USER, me.id)
                store.put(K_CONNECTION, c.id)
                log.log("hinge.pair.paired", mapOf("replayed" to r.idempotentReplayed.toString()))
                HingePairResult.Paired(c)
            }
            is ApiResult.Failure -> {
                val e = r.error
                val definitive = e is ApiError.Client || (e is ApiError.Http && e.status in setOf(400, 404, 409, 422))
                if (definitive) store.remove(K_PENDING_PAIR)
                HingePairResult.Failed("pair_" + errorCode(e), !definitive)
            }
        }
    }

    // --- periodic work ----------------------------------------------------------------------

    /** One read pass, then upload. Requires a paired scope. */
    fun syncOnce(): HingeSyncReport {
        val s = scope() ?: return HingeSyncReport(skippedReason = "not_paired")
        val report = syncFactory(s).run()
        drainUploads()
        return report
    }

    /** One claim-and-send pass, then upload. */
    fun sendOnce(): HingeSendReport {
        val s = scope() ?: return HingeSendReport(skippedReason = "not_paired")
        val report = executorFactory(s).poll()
        drainUploads()
        return report
    }

    /**
     * The worker body: finish Wingman-side leftovers (always), then sync and send (only when
     * active). Opted out or stopped: no Hinge or Sendbird call is made, only Wingman uploads.
     */
    fun runPeriodic(): HingeSyncReport {
        finishPendingWork()
        if (scope() == null || !isOptedIn() || breaker.isStopped() || !flags.connectorAvailable) return HingeSyncReport(skippedReason = "inactive")
        val report = syncOnce()
        sendOnce()
        return report
    }

    /** Uploads queued envelopes to Wingman (never a Hinge call). */
    fun drainUploads(): Int = queue.drain(uploader, uploadBackoff)

    private val uploader = SmsEnvelopeUploader { envelope ->
        val eventId = (envelope["event_id"] as? JsonPrimitive)?.content ?: return@SmsEnvelopeUploader SmsUploadResult.Rejected("no event_id")
        val connectionId = (envelope["connection_id"] as? JsonPrimitive)?.content ?: return@SmsEnvelopeUploader SmsUploadResult.Rejected("no connection_id")
        val key = runCatching { IdempotencyKey(eventId) }.getOrNull() ?: return@SmsEnvelopeUploader SmsUploadResult.Rejected("bad event_id")
        WingmanApiSmsEnvelopeUploader.map(runBlocking { backend.ingest(connectionId, envelope, key) })
    }

    // --- stop, re-enable, opt-out -----------------------------------------------------------

    /** Manual re-enable after a stop, once the user has seen the reason. False during a cooldown. */
    fun reEnable(): Boolean {
        val ok = breaker.reEnable()
        if (ok) {
            auth.reset()
            scope()?.let { queue.enqueue(HingeEventMapper(it.userId, it.connectionId).statusChanged("connected", "user_re_enabled", clock.nowMillis())) }
            drainUploads()
        }
        return ok
    }

    @Synchronized
    fun optOut(): HingeOptOutResult {
        store.put(K_OPTED_IN, "false") // FIRST: the gate refuses every Hinge/Sendbird call from here on
        store.remove(K_CONSENT)
        transport.close()
        sessions.wipe()
        auth.reset()
        runCatching { cancelWork() }
        queue.dropInbound()
        store.remove(K_PENDING_PAIR)
        log.log("hinge.optout.local_wipe_done", emptyMap())
        val s = scope()
        if (s == null) return HingeOptOutResult(sessionWiped = true, revokedOnServer = false, revokePending = false)
        store.put(K_PENDING_REVOKE, json.encodeToString(PendingRevoke.serializer(), PendingRevoke(s.connectionId, newKey())))
        clearScope()
        val revoked = finishPendingWork()
        return HingeOptOutResult(sessionWiped = true, revokedOnServer = revoked, revokePending = store.get(K_PENDING_REVOKE) != null)
    }

    /** Retries a pending consent revocation and drains uploads. Wingman calls only. Returns true when no revocation is pending. */
    fun finishPendingWork(): Boolean {
        val p = store.get(K_PENDING_REVOKE)?.let { runCatching { json.decodeFromString(PendingRevoke.serializer(), it) }.getOrNull() }
        var done = true
        if (p != null) {
            when (val r = runBlocking { backend.revokeExperimentalConsent(p.connectionId, IdempotencyKey(p.key)) }) {
                is ApiResult.Success -> store.remove(K_PENDING_REVOKE)
                is ApiResult.Failure -> {
                    val e = r.error
                    if (e is ApiError.Http && (e.status == 404 || e.status == 422)) store.remove(K_PENDING_REVOKE) else done = false
                }
            }
            if (done) log.log("hinge.optout.revoked", emptyMap())
        }
        drainUploads()
        return done
    }

    private fun clearScope() {
        store.remove(K_USER)
        store.remove(K_CONNECTION)
        store.remove(HingeOutboxExecutor.K_PENDING_CLAIM)
    }

    fun status(): HingeConnectorStatus = HingeConnectorStatus(
        available = flags.connectorAvailable,
        optedIn = isOptedIn(),
        consent = consent(),
        login = auth.state,
        paired = scope() != null,
        stop = breaker.reason(),
        canReEnable = breaker.isStopped() && breaker.canReEnable(),
        revokePending = store.get(K_PENDING_REVOKE) != null,
        queuedUploads = queue.size(),
        sendsToday = limiter.sendsToday(),
        callsToday = limiter.callsToday(),
        notice = breaker.reason()?.userMessage,
    )

    private fun errorCode(e: ApiError): String = when (e) {
        is ApiError.Http -> "http_${e.status}"
        is ApiError.Network -> "network"
        is ApiError.Decoding -> "decoding"
        is ApiError.Client -> "client"
    }

    @Serializable
    private data class PendingPair(val key: String, @SerialName("user_id") val userId: String, val body: String, @SerialName("created_at") val createdAt: Long)

    @Serializable
    private data class PendingRevoke(@SerialName("connection_id") val connectionId: String, val key: String)

    companion object {
        const val K_OPTED_IN = "optin:enabled"
        const val K_CONSENT = "optin:consent"
        const val K_NOTICE = "optin:notice"
        const val K_INSTALL = "config:device_install_id"
        const val K_USER = "config:user_id"
        const val K_CONNECTION = "config:connection_id"
        const val K_PENDING_PAIR = "pairing:pending"
        const val K_PENDING_REVOKE = "pairing:revoke_pending"
        private const val STALE_MILLIS = 23L * 60 * 60 * 1000
    }
}
