package com.wingman.integrations.hinge

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Where the on-phone login is (matrix §6b). The user types every code into Wingman; nothing here
 * reads SMS or notifications (no such permission or API is used by this package; static test).
 */
sealed interface HingeLoginState {
    data object Idle : HingeLoginState
    /** Hinge texted a code. [rejected]: the last code was wrong or expired. */
    data class CodeRequested(val attemptsLeft: Int, val rejected: Boolean = false) : HingeLoginState
    /** Hinge asked for the code it emailed (HTTP 412 with a case id). */
    data class EmailCodeRequired(val attemptsLeft: Int, val rejected: Boolean = false) : HingeLoginState
    /** Logged in and the chat token is present. */
    data object ChatReady : HingeLoginState
    /** A stop signal (challenge, 426, 429, ban wording, ...): the breaker is open. */
    data class Blocked(val reason: HingeStopReason) : HingeLoginState
}

/** Result of one login step, for the screen. Codes are fixed strings, safe to show and log. */
sealed interface HingeLoginStep {
    data class Moved(val state: HingeLoginState) : HingeLoginStep
    /** Nothing changed; [code] says why (network, server error, local limit, wrong state). */
    data class Failed(val code: String, val state: HingeLoginState) : HingeLoginStep
}

/**
 * The login state machine (reference: hinge-rs `client/auth.rs` at cffe397):
 *
 * `Idle` -> (install registration, once per install) -> `POST /auth/sms/v2/initiate` -> `CodeRequested`
 * -> `POST /auth/sms/v2` -> 200 with tokens -> (`POST /message/authenticate` when no chat token) -> `ChatReady`
 *                         -> 412 with `caseId` -> `EmailCodeRequired` -> `POST /auth/device/validate` -> ... -> `ChatReady`
 *                         -> other 4xx -> `CodeRequested(rejected)` (wrong or expired: the reference cannot tell them apart)
 *
 * Any stop signal (challenge or captcha, 426, 429, ban wording, unparseable body, a 412 without a
 * case id) trips the breaker and ends in `Blocked`; nothing is retried and no challenge is ever
 * solved. Codes are requested at most [HingeLimits.maxOtpRequestsPerDay] times a day and only when
 * the user taps; each code may be typed [HingeLimits.maxCodeAttemptsPerOtp] times.
 *
 * Tokens are persisted the moment they arrive, before any other call. The reference documents no
 * refresh for the Hinge bearer token, so none is attempted: when it expires or is rejected, the
 * user logs in again. The chat token is renewed through `/message/authenticate` ([ensureChatToken]).
 *
 * The phone number and the email case id live in memory only, and only until the login ends.
 */
class HingeAuthFlow(
    private val http: HingeHttp,
    private val sessions: HingeSessionStore,
    private val limiter: HingeRateLimiter,
    private val breaker: HingeCircuitBreaker,
    private val clock: HingeClock = HingeClock.SYSTEM,
    private val log: HingeLog = HingeLog.NONE,
) {
    @Volatile var state: HingeLoginState = initialState()
        private set

    private var phone: String? = null
    private var caseId: String? = null

    private fun initialState(): HingeLoginState = when {
        breaker.reason() != null -> HingeLoginState.Blocked(breaker.reason()!!)
        sessions.load()?.let { it.hasHingeAuth(clock.nowMillis()) } == true -> HingeLoginState.ChatReady
        else -> HingeLoginState.Idle
    }

    /** Re-reads persisted state (after opt-out, a trip or a restart). */
    @Synchronized
    fun reset() {
        phone = null
        caseId = null
        state = initialState()
    }

    /** Step 1: the user typed their phone number and tapped "Text me a code". */
    @Synchronized
    fun requestCode(phoneNumber: String): HingeLoginStep {
        val normalized = phoneNumber.trim()
        if (!PHONE.matches(normalized)) return HingeLoginStep.Failed("invalid_phone_number", state)
        return guarded {
            limiter.otpDenial()?.let { return@guarded HingeLoginStep.Failed(it.code, state) }
            var session = sessions.load() ?: HingeSession.newInstall().also(sessions::save)
            if (!session.installRegistered) {
                val r = http.hinge(HingeEndpoint.INSTALL, session, buildJsonObject { put("installId", session.installId) }, withBearer = false)
                if (!r.isSuccess) return@guarded failure(r)
                breaker.observe(HingeSignal.Ok)
                session = session.copy(installRegistered = true).also(sessions::save)
            }
            limiter.recordOtpRequest() // counted before the call: a lost response still uses one
            val r = http.hinge(
                HingeEndpoint.SMS_INITIATE, session,
                buildJsonObject { put("deviceId", session.deviceId); put("phoneNumber", normalized) }, withBearer = false,
            )
            if (!r.isSuccess) return@guarded failure(r)
            breaker.observe(HingeSignal.Ok)
            phone = normalized
            caseId = null
            moved(HingeLoginState.CodeRequested(limiter.config.maxCodeAttemptsPerOtp))
        }
    }

    /** Step 2: the user typed the code Hinge texted. */
    @Synchronized
    fun submitCode(code: String): HingeLoginStep {
        val current = state as? HingeLoginState.CodeRequested ?: return HingeLoginStep.Failed("no_code_requested", state)
        val number = phone ?: return HingeLoginStep.Failed("no_code_requested", state)
        val otp = code.trim()
        if (!CODE.matches(otp)) return HingeLoginStep.Failed("invalid_code_format", state)
        return guarded {
            val session = sessions.load() ?: return@guarded HingeLoginStep.Failed("no_session", state)
            val r = http.hinge(
                HingeEndpoint.SMS_VERIFY, session,
                buildJsonObject {
                    put("installId", session.installId); put("deviceId", session.deviceId)
                    put("phoneNumber", number); put("otp", otp)
                },
                withBearer = false,
            )
            when {
                r.isSuccess -> onTokens(session, r, fromEmail = false)
                r.status == 412 && !isStopWording(r) -> {
                    val id = r.json()?.let { runCatching { it.jsonObject["caseId"]?.jsonPrimitive?.contentOrNull }.getOrNull() }
                    if (id.isNullOrBlank()) {
                        stop(HingeSignal.Challenge)
                    } else {
                        caseId = id
                        log.log("hinge.login.email_code_required", emptyMap())
                        moved(HingeLoginState.EmailCodeRequired(limiter.config.maxCodeAttemptsPerOtp))
                    }
                }
                isCodeRejection(r) -> {
                    breaker.observe(HingeSignal.ExpectedClientError)
                    val left = current.attemptsLeft - 1
                    log.log("hinge.login.code_rejected", mapOf("attempts_left" to left.toString()))
                    if (left <= 0) {
                        phone = null
                        moved(HingeLoginState.Idle)
                    } else {
                        moved(HingeLoginState.CodeRequested(left, rejected = true))
                    }
                }
                else -> failure(r)
            }
        }
    }

    /** Step 2b: the user typed the code Hinge emailed. */
    @Synchronized
    fun submitEmailCode(code: String): HingeLoginStep {
        val current = state as? HingeLoginState.EmailCodeRequired ?: return HingeLoginStep.Failed("no_email_code_requested", state)
        val case = caseId ?: return HingeLoginStep.Failed("no_email_code_requested", state)
        val emailCode = code.trim()
        if (!CODE.matches(emailCode)) return HingeLoginStep.Failed("invalid_code_format", state)
        return guarded {
            val session = sessions.load() ?: return@guarded HingeLoginStep.Failed("no_session", state)
            val r = http.hinge(
                HingeEndpoint.DEVICE_VALIDATE, session,
                buildJsonObject {
                    put("installId", session.installId); put("code", emailCode)
                    put("caseId", case); put("deviceId", session.deviceId)
                },
                withBearer = false,
            )
            when {
                r.isSuccess -> onTokens(session, r, fromEmail = true)
                isCodeRejection(r) -> {
                    breaker.observe(HingeSignal.ExpectedClientError)
                    val left = current.attemptsLeft - 1
                    if (left <= 0) {
                        phone = null
                        caseId = null
                        moved(HingeLoginState.Idle)
                    } else {
                        moved(HingeLoginState.EmailCodeRequired(left, rejected = true))
                    }
                }
                else -> failure(r)
            }
        }
    }

    /**
     * Makes sure an unexpired chat token is stored, fetching one with `/message/authenticate` when
     * missing, expired, or [force]d (the one refresh allowed after a Sendbird 401/403 or a guest
     * refusal). Returns the session to use, or null when it could not (the breaker decides
     * whether that stops the connector).
     */
    @Synchronized
    fun ensureChatToken(force: Boolean = false): HingeSession? {
        val session = sessions.load() ?: return null
        val now = clock.nowMillis()
        if (!session.hasHingeAuth(now)) return null
        if (!force && session.hasChatToken(now)) return session
        val r = http.hinge(HingeEndpoint.CHAT_AUTHENTICATE, session, buildJsonObject { put("refresh", false) })
        if (!r.isSuccess) {
            observeFailure(r)
            return null
        }
        val obj = r.json() as? JsonObject
        val token = obj?.get("token")?.jsonPrimitive?.contentOrNull
        val expires = obj?.get("expires")?.jsonPrimitive?.contentOrNull?.let(::parseInstant)
        if (token.isNullOrEmpty() || expires == null) {
            breaker.observe(HingeSignal.Unparseable)
            return null
        }
        breaker.observe(HingeSignal.Ok)
        return session.copy(chatToken = token, chatExpiresAtMillis = expires).also(sessions::save)
    }

    private fun onTokens(session: HingeSession, r: HingeResponse, fromEmail: Boolean): HingeLoginStep {
        val obj = r.json() as? JsonObject ?: return stop(HingeSignal.Unparseable)
        // /auth/sms/v2 wraps them ({hingeAuthToken, sendbirdAuthToken}); /auth/device/validate returns the Hinge token itself.
        val hinge = (if (fromEmail) obj else obj["hingeAuthToken"] as? JsonObject) ?: return stop(HingeSignal.Unparseable)
        val identityId = hinge["identityId"]?.jsonPrimitive?.contentOrNull
        val token = hinge["token"]?.jsonPrimitive?.contentOrNull
        val expires = hinge["expires"]?.jsonPrimitive?.contentOrNull?.let(::parseInstant)
        if (identityId.isNullOrEmpty() || token.isNullOrEmpty() || expires == null) return stop(HingeSignal.Unparseable)
        val chat = if (fromEmail) null else obj["sendbirdAuthToken"] as? JsonObject
        val chatToken = chat?.get("token")?.jsonPrimitive?.contentOrNull
        val chatExpires = chat?.get("expires")?.jsonPrimitive?.contentOrNull?.let(::parseInstant)
        breaker.observe(HingeSignal.Ok)
        // Persisted BEFORE any other call.
        sessions.save(session.copy(identityId = identityId, hingeToken = token, hingeExpiresAtMillis = expires, chatToken = chatToken, chatExpiresAtMillis = chatExpires))
        phone = null
        caseId = null
        log.log("hinge.login.authenticated", mapOf("email_code" to fromEmail.toString()))
        if (ensureChatToken() == null) {
            breaker.reason()?.let { return moved(HingeLoginState.Blocked(it)) }
            // Logged in; the chat token is fetched again before the first sync or send.
            state = HingeLoginState.ChatReady
            return HingeLoginStep.Failed("chat_token_pending", state)
        }
        return moved(HingeLoginState.ChatReady)
    }

    private fun isStopWording(r: HingeResponse): Boolean =
        HingeResponseClassifier.classify(r.status, r.contentType, r.body, r.retryAfter, r.headerNames).let {
            it == HingeSignal.BanWording || (it == HingeSignal.Challenge && r.json()?.let { j -> (j as? JsonObject)?.containsKey("caseId") } != true)
        }

    /** A wrong or expired code: a plain 4xx without stop wording (not 412, 426 or 429). */
    private fun isCodeRejection(r: HingeResponse): Boolean {
        if (r.status !in CODE_REJECTION_STATUSES) return false
        val signal = HingeResponseClassifier.classify(r.status, r.contentType, r.body, r.retryAfter, r.headerNames)
        return signal == HingeSignal.UnexpectedClientError || signal == HingeSignal.Unauthorized
    }

    private fun failure(r: HingeResponse): HingeLoginStep {
        if (r.networkError != null) return HingeLoginStep.Failed("network_error", state)
        val reason = observeFailure(r)
        return if (reason != null) moved(HingeLoginState.Blocked(reason)) else HingeLoginStep.Failed("http_${r.status}", state)
    }

    /** Feeds a non-2xx response to the breaker; returns the stop reason if it tripped. */
    private fun observeFailure(r: HingeResponse): HingeStopReason? {
        if (r.networkError != null) return null
        val signal = HingeResponseClassifier.classify(r.status, r.contentType, r.body, r.retryAfter, r.headerNames)
        return breaker.observe(if (signal == HingeSignal.Ok) HingeSignal.Unparseable else signal) ?: breaker.reason()
    }

    private fun stop(signal: HingeSignal): HingeLoginStep {
        val reason = breaker.observe(signal) ?: breaker.reason() ?: HingeStopReason.PROTOCOL_DRIFT.also(breaker::trip)
        return moved(HingeLoginState.Blocked(reason))
    }

    private fun moved(next: HingeLoginState): HingeLoginStep {
        state = next
        return HingeLoginStep.Moved(next)
    }

    /** Converts a gate refusal (flag off, opted out, stopped, limit) into a Failed step. */
    private inline fun guarded(block: () -> HingeLoginStep): HingeLoginStep = try {
        http.checkActive()
        block()
    } catch (e: HingeCallRefused) {
        breaker.reason()?.let { moved(HingeLoginState.Blocked(it)) } ?: HingeLoginStep.Failed(e.code, state)
    }

    companion object {
        private val PHONE = Regex("^\\+[1-9][0-9]{6,14}$")
        private val CODE = Regex("^[0-9A-Za-z]{4,10}$")
        private val CODE_REJECTION_STATUSES = setOf(400, 401, 403, 404, 409, 410, 422)

        internal fun parseInstant(raw: String): Long? =
            runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
                ?: runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()
    }
}
