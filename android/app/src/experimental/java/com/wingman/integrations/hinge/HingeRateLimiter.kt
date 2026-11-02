package com.wingman.integrations.hinge

import com.wingman.integrations.sms.SmsStateStore

/**
 * Conservative limits (dating_platform_matrix.md §6d). The defaults are the MOST permissive values
 * allowed: [HingeLimits] can only be made stricter (fewer calls, longer waits); the constructor
 * rejects anything looser, so no build property or setting can raise them.
 */
data class HingeLimits(
    /** Minimum time between two syncs (background or foreground). */
    val minSyncIntervalMillis: Long = DEFAULT.minSyncIntervalMillis,
    /** Hinge REST + Sendbird REST + WebSocket opens, per UTC day / per hour. */
    val maxCallsPerDay: Int = DEFAULT.maxCallsPerDay,
    val maxCallsPerHour: Int = DEFAULT.maxCallsPerHour,
    /** Minimum spacing between two calls. */
    val minCallSpacingMillis: Long = DEFAULT.minCallSpacingMillis,
    /** Approved sends per UTC day, overall and per conversation, and their spacing. */
    val maxSendsPerDay: Int = DEFAULT.maxSendsPerDay,
    val maxSendsPerConversationPerDay: Int = DEFAULT.maxSendsPerConversationPerDay,
    val minSendSpacingMillis: Long = DEFAULT.minSendSpacingMillis,
    /** Random delay before an approved send. */
    val sendJitterMinMillis: Long = DEFAULT.sendJitterMinMillis,
    val sendJitterMaxMillis: Long = DEFAULT.sendJitterMaxMillis,
    /** Channels whose history is fetched in one sync. */
    val maxChannelsPerSync: Int = DEFAULT.maxChannelsPerSync,
    /** SMS code requests per UTC day (always user-initiated). */
    val maxOtpRequestsPerDay: Int = DEFAULT.maxOtpRequestsPerDay,
    /** Wrong or expired code entries per requested code. */
    val maxCodeAttemptsPerOtp: Int = DEFAULT.maxCodeAttemptsPerOtp,
) {
    init {
        require(minSyncIntervalMillis >= FIFTEEN_MINUTES) { "sync interval can only be raised" }
        require(maxCallsPerDay in 1..MAX_CALLS_PER_DAY) { "daily call cap can only be lowered" }
        require(maxCallsPerHour in 1..MAX_CALLS_PER_HOUR) { "hourly call cap can only be lowered" }
        require(minCallSpacingMillis >= MIN_CALL_SPACING) { "call spacing can only be raised" }
        require(maxSendsPerDay in 1..MAX_SENDS_PER_DAY) { "daily send cap can only be lowered" }
        require(maxSendsPerConversationPerDay in 1..MAX_SENDS_PER_CONVERSATION) { "per-conversation send cap can only be lowered" }
        require(minSendSpacingMillis >= MIN_SEND_SPACING) { "send spacing can only be raised" }
        require(sendJitterMinMillis >= JITTER_MIN) { "send jitter minimum can only be raised" }
        require(sendJitterMaxMillis >= JITTER_MAX && sendJitterMaxMillis >= sendJitterMinMillis) { "send jitter maximum can only be raised" }
        require(sendJitterMaxMillis <= JITTER_CEILING) { "send jitter must fit inside a lease" }
        require(maxChannelsPerSync in 1..MAX_CHANNELS) { "channels per sync can only be lowered" }
        require(maxOtpRequestsPerDay in 1..MAX_OTP) { "code requests can only be lowered" }
        require(maxCodeAttemptsPerOtp in 1..MAX_CODE_ATTEMPTS) { "code attempts can only be lowered" }
    }

    companion object {
        const val FIFTEEN_MINUTES = 15L * 60_000
        const val MAX_CALLS_PER_DAY = 600
        const val MAX_CALLS_PER_HOUR = 120
        const val MIN_CALL_SPACING = 2_000L
        const val MAX_SENDS_PER_DAY = 20
        const val MAX_SENDS_PER_CONVERSATION = 5
        const val MIN_SEND_SPACING = 60_000L
        const val JITTER_MIN = 20_000L
        const val JITTER_MAX = 90_000L
        /** Leases are at most 600 s (ADR 0006); keep the delay well inside one. */
        const val JITTER_CEILING = 300_000L
        const val MAX_CHANNELS = 20
        const val MAX_OTP = 3
        const val MAX_CODE_ATTEMPTS = 3

        val DEFAULT = HingeLimits(
            FIFTEEN_MINUTES, MAX_CALLS_PER_DAY, MAX_CALLS_PER_HOUR, MIN_CALL_SPACING, MAX_SENDS_PER_DAY,
            MAX_SENDS_PER_CONVERSATION, MIN_SEND_SPACING, JITTER_MIN, JITTER_MAX, MAX_CHANNELS, MAX_OTP, MAX_CODE_ATTEMPTS,
        )
    }
}

/** Why the limiter said no. Fixed codes, safe to log. */
enum class HingeLimitDenial(val code: String) {
    DAILY_CALLS("daily_call_cap"),
    HOURLY_CALLS("hourly_call_cap"),
    SYNC_TOO_SOON("sync_interval"),
    DAILY_SENDS("daily_send_cap"),
    CONVERSATION_SENDS("conversation_send_cap"),
    SEND_SPACING("send_spacing"),
    OTP_REQUESTS("daily_code_requests"),
}

/**
 * Persisted counters (they survive process death, so a crash loop cannot reset a cap). Shared by
 * Hinge REST, Sendbird REST and the WebSocket: [admitCall] runs before every network call.
 * Day and hour buckets are UTC.
 */
class HingeRateLimiter(
    private val store: SmsStateStore,
    private val limits: HingeLimits = HingeLimits.DEFAULT,
    private val clock: HingeClock = HingeClock.SYSTEM,
    private val sleeper: HingeSleeper = HingeSleeper.SYSTEM,
) {
    val config: HingeLimits get() = limits

    private fun day(now: Long = clock.nowMillis()) = now / DAY
    private fun hour(now: Long = clock.nowMillis()) = now / HOUR
    private fun int(key: String) = store.get(key)?.toIntOrNull() ?: 0
    private fun long(key: String) = store.get(key)?.toLongOrNull()

    /**
     * Admits one call or returns the denial. Waits out [HingeLimits.minCallSpacingMillis] (a few
     * seconds at most) instead of failing. Counts the call when admitted.
     */
    @Synchronized
    fun admitCall(): HingeLimitDenial? {
        pruneOld()
        val now = clock.nowMillis()
        if (int(K_DAY_CALLS + day(now)) >= limits.maxCallsPerDay) return HingeLimitDenial.DAILY_CALLS
        if (int(K_HOUR_CALLS + hour(now)) >= limits.maxCallsPerHour) return HingeLimitDenial.HOURLY_CALLS
        val last = long(K_LAST_CALL)
        if (last != null) {
            val wait = last + limits.minCallSpacingMillis - now
            if (wait > 0) sleeper.sleep(wait)
        }
        val at = clock.nowMillis()
        store.put(K_DAY_CALLS + day(at), (int(K_DAY_CALLS + day(at)) + 1).toString())
        store.put(K_HOUR_CALLS + hour(at), (int(K_HOUR_CALLS + hour(at)) + 1).toString())
        store.put(K_LAST_CALL, at.toString())
        return null
    }

    fun callsToday(): Int = int(K_DAY_CALLS + day())

    /** Whether a sync may start now. Does not record it; [recordSync] does. */
    fun syncDenial(): HingeLimitDenial? {
        val last = long(K_LAST_SYNC) ?: return null
        return if (clock.nowMillis() - last < limits.minSyncIntervalMillis) HingeLimitDenial.SYNC_TOO_SOON else null
    }

    fun recordSync() = store.put(K_LAST_SYNC, clock.nowMillis().toString())

    /** Whether an approved send to [conversationKey] (a hash) may go now. Does not record it. */
    @Synchronized
    fun sendDenial(conversationKey: String): HingeLimitDenial? {
        val now = clock.nowMillis()
        if (int(K_DAY_SENDS + day(now)) >= limits.maxSendsPerDay) return HingeLimitDenial.DAILY_SENDS
        if (int(K_CONV_SENDS + day(now) + ":" + conversationKey) >= limits.maxSendsPerConversationPerDay) return HingeLimitDenial.CONVERSATION_SENDS
        val last = long(K_LAST_SEND)
        if (last != null && now - last < limits.minSendSpacingMillis) return HingeLimitDenial.SEND_SPACING
        return null
    }

    /** Counts one send attempt (recorded BEFORE the frame is written, like the D-006 ledger). */
    @Synchronized
    fun recordSend(conversationKey: String) {
        val now = clock.nowMillis()
        store.put(K_DAY_SENDS + day(now), (int(K_DAY_SENDS + day(now)) + 1).toString())
        val ck = K_CONV_SENDS + day(now) + ":" + conversationKey
        store.put(ck, (int(ck) + 1).toString())
        store.put(K_LAST_SEND, now.toString())
    }

    fun sendsToday(): Int = int(K_DAY_SENDS + day())

    @Synchronized
    fun otpDenial(): HingeLimitDenial? =
        if (int(K_DAY_OTP + day()) >= limits.maxOtpRequestsPerDay) HingeLimitDenial.OTP_REQUESTS else null

    @Synchronized
    fun recordOtpRequest() = store.put(K_DAY_OTP + day(), (int(K_DAY_OTP + day()) + 1).toString())

    /** Drops counters of earlier days/hours so the store stays small. */
    private fun pruneOld() {
        val today = day()
        val thisHour = hour()
        for (prefix in listOf(K_DAY_CALLS, K_DAY_SENDS, K_DAY_OTP)) {
            store.keys(prefix).filter { it.removePrefix(prefix).toLongOrNull()?.let { d -> d < today } == true }.forEach(store::remove)
        }
        store.keys(K_HOUR_CALLS).filter { it.removePrefix(K_HOUR_CALLS).toLongOrNull()?.let { h -> h < thisHour } == true }.forEach(store::remove)
        store.keys(K_CONV_SENDS).filter { it.removePrefix(K_CONV_SENDS).substringBefore(':').toLongOrNull()?.let { d -> d < today } == true }.forEach(store::remove)
    }

    companion object {
        private const val DAY = 24L * 60 * 60 * 1000
        private const val HOUR = 60L * 60 * 1000
        const val K_DAY_CALLS = "rl:calls:day:"
        const val K_HOUR_CALLS = "rl:calls:hour:"
        const val K_LAST_CALL = "rl:last_call_at"
        const val K_LAST_SYNC = "rl:last_sync_at"
        const val K_DAY_SENDS = "rl:sends:day:"
        const val K_CONV_SENDS = "rl:sends:conv:"
        const val K_LAST_SEND = "rl:last_send_at"
        const val K_DAY_OTP = "rl:otp:day:"
    }
}
