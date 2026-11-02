package com.wingman.integrations.hinge

import com.wingman.integrations.sms.SmsStateStore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Why the connector stopped (dating_platform_matrix.md §6e). [code] is a fixed string: it is the
 * only thing logged, shown, and sent to Wingman in `connection.status_changed.detail`.
 */
enum class HingeStopReason(val code: String, val userMessage: String) {
    AUTH_REJECTED("auth_rejected", "Hinge no longer accepts this login. Your Hinge session was deleted from this phone; log in again to continue."),
    CLIENT_VERSION_RETIRED("client_version_retired", "Hinge asked for an app update. The experimental connector needs an update before it can be used again."),
    RATE_LIMITED("rate_limited", "Hinge asked Wingman to slow down. The connector is stopped for at least 24 hours; you can re-enable it after that."),
    CHALLENGE_PRESENTED("challenge_presented", "Hinge showed a security check. Wingman never solves these, so the experimental connector is blocked. Use the notification-assisted mode instead."),
    ACCOUNT_RESTRICTED("account_restricted", "Hinge reported a restriction or review on your account. Everything is stopped. Check the official Hinge app."),
    REPEATED_CLIENT_ERRORS("unexpected_4xx", "Hinge rejected several requests. The connector stopped to protect your account."),
    REPEATED_SERVER_ERRORS("server_errors", "Hinge kept failing. The connector stopped; try again later."),
    PROTOCOL_DRIFT("protocol_drift", "Hinge answered in a format this version does not understand. The connector stopped and needs an update."),
    REPEATED_SEND_REFUSALS("sendbird_refused", "Hinge's chat refused several messages. The connector stopped."),
    ;

    /** Re-enabling is refused until this long after the stop (24 h for rate limiting). */
    val cooldownMillis: Long get() = if (this == RATE_LIMITED) 24L * 60 * 60 * 1000 else 0

    /** The session is deleted when this trips (re-login required). */
    val wipesSession: Boolean get() = this == AUTH_REJECTED

    /** Matrix §7 no-go: report as BLOCKED, not as a transient error. */
    val isBlocked: Boolean get() = this == CHALLENGE_PRESENTED || this == ACCOUNT_RESTRICTED

    companion object {
        fun fromCode(code: String): HingeStopReason? = entries.firstOrNull { it.code == code }
    }
}

/** One observed outcome, as classified by [HingeResponseClassifier]. */
sealed interface HingeSignal {
    data object Ok : HingeSignal
    /** An expected 4xx inside the login flow (wrong or expired code): never counted. */
    data object ExpectedClientError : HingeSignal
    data object Unauthorized : HingeSignal
    data object VersionRetired : HingeSignal
    data object RateLimited : HingeSignal
    data object Challenge : HingeSignal
    data object BanWording : HingeSignal
    data object UnexpectedClientError : HingeSignal
    data object ServerError : HingeSignal
    data object Unparseable : HingeSignal
    /** A Sendbird `EROR` on a send, other than the guest refusal. */
    data object SendRefused : HingeSignal
}

/**
 * Classifies a Hinge or Sendbird response without ever logging it. Ban and challenge wording is
 * looked for ONLY in error responses and HTML pages, never in a successful JSON body, which can
 * contain chat text the user's matches wrote.
 */
object HingeResponseClassifier {
    private val CHALLENGE = Regex("captcha|arkose|funcaptcha|hcaptcha|recaptcha|turnstile|challenge|are you a (ro)?bot|verify you are human|cf-chl", RegexOption.IGNORE_CASE)
    private val BAN = Regex("\\bbann?ed\\b|\\bban\\b|suspend|restricted|restriction|deactivated|terminated|under review|account review|blocked your account",RegexOption.IGNORE_CASE)

    fun isHtml(contentType: String?): Boolean = contentType?.lowercase()?.contains("text/html") == true

    /**
     * @param body at most the first few KB of the body (only inspected for errors and HTML).
     * @param headerNames lower-case response header names (challenge vendors add their own).
     */
    fun classify(status: Int, contentType: String?, body: String?, retryAfter: String? = null, headerNames: Set<String> = emptySet()): HingeSignal {
        val text = body.orEmpty()
        if (isHtml(contentType) && status in 200..299) return if (CHALLENGE.containsMatchIn(text)) HingeSignal.Challenge else HingeSignal.Unparseable
        if (status !in 200..299) {
            if (headerNames.any { it.startsWith("cf-mitigated") || it.contains("captcha") || it.contains("challenge") } || CHALLENGE.containsMatchIn(text)) return HingeSignal.Challenge
            if (BAN.containsMatchIn(text)) return HingeSignal.BanWording
        }
        return when {
            status == 426 -> HingeSignal.VersionRetired
            status == 429 || (retryAfter != null && status !in 200..299) -> HingeSignal.RateLimited
            status == 401 || status == 403 -> HingeSignal.Unauthorized
            status == 412 -> HingeSignal.Challenge // outside the login flow (the login flow handles its own 412)
            status in 400..499 -> HingeSignal.UnexpectedClientError
            status >= 500 -> HingeSignal.ServerError
            status in 200..299 -> HingeSignal.Ok
            else -> HingeSignal.Unparseable
        }
    }

    /** Ban or challenge wording in a Sendbird `EROR` message. */
    fun refusalSignal(message: String?): HingeSignal = when {
        message == null -> HingeSignal.SendRefused
        CHALLENGE.containsMatchIn(message) -> HingeSignal.Challenge
        BAN.containsMatchIn(message) -> HingeSignal.BanWording
        else -> HingeSignal.SendRefused
    }
}

/** Persisted stop state. */
@Serializable
data class HingeStopState(
    val reason: String,
    @SerialName("stopped_at") val stoppedAtMillis: Long,
) {
    val stopReason: HingeStopReason? get() = HingeStopReason.fromCode(reason)
}

/**
 * The enforcement circuit breaker (matrix §6e). Every Hinge or Sendbird outcome goes through
 * [observe]. A trip is PERSISTED, survives restarts, blocks every further call ([HingeNetGate]
 * checks [isStopped] before each one) and is cleared ONLY by [reEnable], which the UI calls when
 * the user explicitly re-enables after seeing the reason. Nothing re-enables it automatically.
 *
 * Thresholds: 2 unexpected 4xx within 1 h; 3 consecutive 5xx; 2 non-guest send refusals within
 * 24 h; any 401/403 that is not recovered by the one allowed chat-token refresh; any 426, 429,
 * challenge, ban wording or unparseable response.
 */
class HingeCircuitBreaker(
    private val store: SmsStateStore,
    private val clock: HingeClock = HingeClock.SYSTEM,
    private val log: HingeLog = HingeLog.NONE,
    /** Called once per trip, after the state is persisted (closes sockets, cancels work, notifies). */
    var onTrip: (HingeStopReason) -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun state(): HingeStopState? = store.get(K_STOP)?.let { runCatching { json.decodeFromString(HingeStopState.serializer(), it) }.getOrNull() }
        ?: store.get(K_STOP)?.let { HingeStopState(HingeStopReason.PROTOCOL_DRIFT.code, 0) } // unreadable state stays stopped

    fun isStopped(): Boolean = store.get(K_STOP) != null

    fun reason(): HingeStopReason? = state()?.stopReason

    /** Observes one outcome; returns the stop reason when this outcome tripped the breaker. */
    @Synchronized
    fun observe(signal: HingeSignal): HingeStopReason? {
        val now = clock.nowMillis()
        val reason: HingeStopReason? = when (signal) {
            HingeSignal.Ok -> {
                if (store.get(K_5XX) != null) store.remove(K_5XX)
                null
            }
            HingeSignal.ExpectedClientError -> null
            HingeSignal.Unauthorized -> HingeStopReason.AUTH_REJECTED
            HingeSignal.VersionRetired -> HingeStopReason.CLIENT_VERSION_RETIRED
            HingeSignal.RateLimited -> HingeStopReason.RATE_LIMITED
            HingeSignal.Challenge -> HingeStopReason.CHALLENGE_PRESENTED
            HingeSignal.BanWording -> HingeStopReason.ACCOUNT_RESTRICTED
            HingeSignal.Unparseable -> HingeStopReason.PROTOCOL_DRIFT
            HingeSignal.UnexpectedClientError -> if (recordWindowed(K_4XX, now, HOUR) >= 2) HingeStopReason.REPEATED_CLIENT_ERRORS else null
            HingeSignal.SendRefused -> if (recordWindowed(K_REFUSALS, now, DAY) >= 2) HingeStopReason.REPEATED_SEND_REFUSALS else null
            HingeSignal.ServerError -> {
                val n = (store.get(K_5XX)?.toIntOrNull() ?: 0) + 1
                store.put(K_5XX, n.toString())
                if (n >= 3) HingeStopReason.REPEATED_SERVER_ERRORS else null
            }
        }
        if (reason != null) trip(reason)
        return reason
    }

    /** Trips immediately (also used for user-visible no-go conditions found outside a response). */
    @Synchronized
    fun trip(reason: HingeStopReason) {
        if (isStopped()) return
        store.put(K_STOP, json.encodeToString(HingeStopState.serializer(), HingeStopState(reason.code, clock.nowMillis())))
        log.log("hinge.breaker.tripped", mapOf("reason" to reason.code, "blocked" to reason.isBlocked.toString()))
        runCatching { onTrip(reason) }
    }

    /** True when the user may re-enable now (stopped, and any cooldown has passed). */
    fun canReEnable(): Boolean {
        val s = state() ?: return false
        val cooldown = s.stopReason?.cooldownMillis ?: 0
        return clock.nowMillis() >= s.stoppedAtMillis + cooldown
    }

    /** Manual re-enable, after the user saw the reason. Returns false during a cooldown. */
    @Synchronized
    fun reEnable(): Boolean {
        if (!isStopped()) return true
        if (!canReEnable()) return false
        listOf(K_STOP, K_5XX, K_4XX, K_REFUSALS).forEach(store::remove)
        log.log("hinge.breaker.re_enabled", emptyMap())
        return true
    }

    /** Opt-out clears everything (a later opt-in starts clean but still needs a new login). */
    fun clearAll() = listOf(K_STOP, K_5XX, K_4XX, K_REFUSALS).forEach(store::remove)

    private fun recordWindowed(key: String, now: Long, window: Long): Int {
        val kept = store.get(key).orEmpty().split(',').mapNotNull { it.toLongOrNull() }.filter { now - it < window } + now
        store.put(key, kept.joinToString(","))
        return kept.size
    }

    companion object {
        private const val HOUR = 60L * 60 * 1000
        private const val DAY = 24 * HOUR
        const val K_STOP = "breaker:stopped"
        const val K_5XX = "breaker:consecutive_5xx"
        const val K_4XX = "breaker:recent_4xx"
        const val K_REFUSALS = "breaker:recent_refusals"
    }
}
