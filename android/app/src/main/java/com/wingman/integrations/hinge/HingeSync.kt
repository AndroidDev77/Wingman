package com.wingman.integrations.hinge

import com.wingman.integrations.sms.SmsStateStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Counts only. */
data class HingeSyncReport(
    val channels: Int = 0,
    val channelsFetched: Int = 0,
    val enqueued: Int = 0,
    val matches: Int = 0,
    val skippedReason: String? = null,
    val stopped: HingeStopReason? = null,
)

/**
 * One read pass (matrix §6a `HingeSyncWorker` body), at most once per
 * [HingeLimits.minSyncIntervalMillis]:
 *
 * 1. chat token ([HingeAuthFlow.ensureChatToken]) and socket `LOGI` for the `Session-Key`;
 * 2. `my_group_channels` (newest activity first), then the messages of at most
 *    [HingeLimits.maxChannelsPerSync] channels whose last message is newer than the stored cursor;
 * 3. `connection/v2` for new matches (a `match` notice each, at most 20 per pass);
 * 4. envelopes go to [HingeEnvelopeQueue]; the caller drains it to Wingman.
 *
 * A Sendbird 401/403 (or its "Api-Token is missing" 400) gets exactly ONE chat-token refresh and
 * one retry per pass; a second one stops the connector. Every other non-2xx or undecodable answer
 * goes to the breaker. A network error just ends the pass.
 */
class HingeSync(
    private val scope: HingeScope,
    private val store: SmsStateStore,
    private val gate: HingeNetGate,
    private val http: HingeHttp,
    private val auth: HingeAuthFlow,
    private val sessions: HingeSessionStore,
    private val transport: SendbirdChatTransport,
    private val limiter: HingeRateLimiter,
    private val breaker: HingeCircuitBreaker,
    private val queue: HingeEnvelopeQueue,
    private val clock: HingeClock = HingeClock.SYSTEM,
    private val log: HingeLog = HingeLog.NONE,
    private val messagesPerChannel: Int = 30,
    private val maxMatchNoticesPerPass: Int = 20,
) {
    private val mapper = HingeEventMapper(scope.userId, scope.connectionId)
    private var refreshedThisPass = false

    @Synchronized
    fun run(): HingeSyncReport {
        try {
            gate.checkActive()
        } catch (e: HingeCallRefused) {
            return HingeSyncReport(skippedReason = e.code)
        }
        limiter.syncDenial()?.let { return HingeSyncReport(skippedReason = it.code) }
        if (sessions.load()?.hasHingeAuth(clock.nowMillis()) != true) return HingeSyncReport(skippedReason = "not_logged_in")
        limiter.recordSync()
        refreshedThisPass = false
        return try {
            pass()
        } catch (e: HingeCallRefused) {
            HingeSyncReport(skippedReason = e.code, stopped = breaker.reason())
        } finally {
            transport.close()
        }
    }

    private fun pass(): HingeSyncReport {
        var session = auth.ensureChatToken() ?: return ended("chat_token_unavailable")
        if (!connect(session)) return ended("chat_unavailable")
        session = sessions.load() ?: return ended("no_session")
        val self = session.identityId ?: return ended("no_session")

        val channelsJson = read { transport.listChannels(sessions.load() ?: session, limiter.config.maxChannelsPerSync) } ?: return ended("channels_unavailable")
        val channels = (channelsJson["channels"] as? JsonArray)?.mapNotNull { it as? JsonObject }
            ?: return drift()
        var fetched = 0
        var enqueued = 0
        for (c in channels) {
            if (fetched >= limiter.config.maxChannelsPerSync) break
            val url = (c["channel_url"] as? JsonPrimitive)?.contentOrNull ?: return drift()
            val cursorKey = K_CURSOR + HingeHashes.short(url)
            val cursor = store.get(cursorKey)?.toLongOrNull()
            val last = (c["last_message"] as? JsonObject)?.get("created_at")?.let { (it as? JsonPrimitive)?.longOrNull }
            if (cursor != null && last != null && last <= cursor) continue
            fetched++
            val page = read { transport.messages(sessions.load() ?: session, url, messagesPerChannel) } ?: return ended("messages_unavailable", channels.size, fetched, enqueued)
            val raw = (page["messages"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return drift()
            val parsed = raw.mapNotNull(SendbirdMessage::parse)
            if (raw.isNotEmpty() && parsed.isEmpty()) return drift()
            var newest = cursor ?: 0L
            for (m in parsed.sortedBy { it.createdAtMillis }) {
                if (m.channelUrl != url) continue // a message must belong to the channel it was read from
                if (cursor != null && m.createdAtMillis <= cursor) continue
                newest = maxOf(newest, m.createdAtMillis)
                val env = mapper.received(m, self, clock.nowMillis()) ?: continue
                if (mapper.classify(m) == HingeEventKind.CHAT_MESSAGE) store.put(HingeOutboxExecutor.knownConversationKey(url), "1")
                queue.enqueue(env)
                enqueued++
            }
            if (newest > 0) store.put(cursorKey, newest.toString())
        }

        val matches = syncMatches(sessions.load() ?: session, self)
        log.log("hinge.sync.done", mapOf("channels" to channels.size.toString(), "fetched" to fetched.toString(), "enqueued" to enqueued.toString(), "matches" to matches.toString()))
        return HingeSyncReport(channels.size, fetched, enqueued + matches, matches)
    }

    private fun syncMatches(session: HingeSession, self: String): Int {
        val r = http.hinge(HingeEndpoint.MATCHES, session)
        val body = accept(r) ?: return 0
        val list = (body["connections"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: run {
            breaker.observe(HingeSignal.Unparseable)
            return 0
        }
        var n = 0
        for (c in list) {
            if (n >= maxMatchNoticesPerPass) break
            val initiator = (c["initiatorId"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val subject = (c["subjectId"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val other = if (initiator == self) subject else initiator
            if (other.isBlank() || other == self) continue
            val seenKey = K_MATCH_SEEN + HingeHashes.short(other)
            if (store.get(seenKey) != null) continue
            val at = ((c["receivedTime"] ?: c["sentTime"]) as? JsonPrimitive)?.contentOrNull?.let(HingeAuthFlow::parseInstant) ?: clock.nowMillis()
            val env = mapper.match(other, at, clock.nowMillis()) ?: continue
            queue.enqueue(env)
            store.put(seenKey, "1")
            n++
        }
        return n
    }

    /** Opens the socket; one forced chat-token refresh when no `LOGI` arrives. */
    private fun connect(session: HingeSession): Boolean {
        when (val c = transport.connect(session)) {
            SendbirdConnect.Ready -> return true
            SendbirdConnect.Closed -> return false
            is SendbirdConnect.Refused -> {
                breaker.observe(c.signal)
                return false
            }
            SendbirdConnect.NoLogin -> Unit
        }
        if (refreshedThisPass) {
            breaker.observe(HingeSignal.Unauthorized)
            return false
        }
        refreshedThisPass = true
        val fresh = auth.ensureChatToken(force = true) ?: return false
        return when (val c = transport.connect(fresh)) {
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

    /** A Sendbird read with the one-refresh rule. Null when the pass must end. */
    private fun read(call: () -> HingeResponse): JsonObject? {
        val r = call()
        if (isStaleChatAuth(r) && !refreshedThisPass) {
            refreshedThisPass = true
            transport.close()
            val fresh = auth.ensureChatToken(force = true) ?: return null
            if (transport.connect(fresh) != SendbirdConnect.Ready) {
                breaker.observe(HingeSignal.Unauthorized)
                return null
            }
            val again = call()
            if (isStaleChatAuth(again)) {
                breaker.observe(HingeSignal.Unauthorized)
                return null
            }
            return accept(again)
        }
        if (isStaleChatAuth(r)) {
            breaker.observe(HingeSignal.Unauthorized)
            return null
        }
        return accept(r)
    }

    /** Sendbird's stale-auth answers: 401/403, or 400 with code 400401 ("Api-Token is missing", per the reference). */
    private fun isStaleChatAuth(r: HingeResponse): Boolean {
        if (r.status == 401 || r.status == 403) {
            return HingeResponseClassifier.classify(r.status, r.contentType, r.body, r.retryAfter, r.headerNames) == HingeSignal.Unauthorized
        }
        if (r.status != 400) return false
        val code = (r.json() as? JsonObject)?.get("code")?.let { (it as? JsonPrimitive)?.longOrNull }
        return code == 400401L
    }

    /** 2xx JSON object -> it; anything else -> breaker (network errors just end the pass). */
    private fun accept(r: HingeResponse): JsonObject? {
        if (r.networkError != null) return null
        if (!r.isSuccess) {
            breaker.observe(HingeResponseClassifier.classify(r.status, r.contentType, r.body, r.retryAfter, r.headerNames).let { if (it == HingeSignal.Ok) HingeSignal.Unparseable else it })
            return null
        }
        val signal = HingeResponseClassifier.classify(r.status, r.contentType, r.body, r.retryAfter, r.headerNames)
        val obj = r.json() as? JsonObject
        if (signal != HingeSignal.Ok || obj == null) {
            breaker.observe(if (signal == HingeSignal.Ok) HingeSignal.Unparseable else signal)
            return null
        }
        breaker.observe(HingeSignal.Ok)
        return obj
    }

    private fun drift(): HingeSyncReport {
        breaker.observe(HingeSignal.Unparseable)
        return HingeSyncReport(skippedReason = "protocol_drift", stopped = breaker.reason())
    }

    private fun ended(reason: String, channels: Int = 0, fetched: Int = 0, enqueued: Int = 0) =
        HingeSyncReport(channels, fetched, enqueued, skippedReason = reason, stopped = breaker.reason())

    companion object {
        const val K_CURSOR = "chan:cursor:"
        const val K_MATCH_SEEN = "match:seen:"
    }
}
