package com.wingman.integrations.hinge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One open chat socket. [send] returns false when the frame could not be queued. */
interface ChatSocket {
    fun send(frame: String): Boolean
    fun close()
}

interface ChatSocketListener {
    fun onFrame(text: String)
    /** Closed by either side or failed; no more frames follow. */
    fun onClosed()
}

/** Opens the Sendbird WebSocket. Production: [OkHttpChatSocketFactory]; tests: a fake. */
fun interface ChatSocketFactory {
    fun open(request: Request, listener: ChatSocketListener): ChatSocket
}

/** OkHttp WebSocket (already a dependency). Its client carries [HingeGateInterceptor]. */
class OkHttpChatSocketFactory(private val client: OkHttpClient) : ChatSocketFactory {
    override fun open(request: Request, listener: ChatSocketListener): ChatSocket {
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = listener.onFrame(text)
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                listener.onClosed()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed()
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) = listener.onClosed()
        })
        return object : ChatSocket {
            override fun send(frame: String): Boolean = ws.send(frame)
            override fun close() {
                ws.close(1000, null)
            }
        }
    }
}

/** Result of opening the socket and waiting for `LOGI`. */
sealed interface SendbirdConnect {
    data object Ready : SendbirdConnect
    /** No `LOGI` in time: per the reference, a stale chat token (the handshake does not reject it). */
    data object NoLogin : SendbirdConnect
    /** `LOGI` carried an error. [signal] says whether it is a challenge or ban wording. */
    data class Refused(val signal: HingeSignal) : SendbirdConnect
    data object Closed : SendbirdConnect
}

/** Outcome of one `MESG` frame. Text never appears here. */
sealed interface SendbirdSend {
    /** The echo carried our `req_id`: Sendbird stored it. [messageId] is Sendbird's `msg_id`. */
    data class Acknowledged(val messageId: String, val createdAtMillis: Long?) : SendbirdSend
    /** An `EROR` for our request. [guest]: code 900030 / "Guest is not allowed" (stale chat auth). */
    data class Refused(val code: Long, val guest: Boolean, val signal: HingeSignal) : SendbirdSend
    /** The frame was handed to the socket but no echo or refusal arrived in time: OUTCOME UNKNOWN. */
    data object Timeout : SendbirdSend
    /** The frame was NOT written (socket not open or the write was rejected): nothing was sent. */
    data object NotWritten : SendbirdSend
}

/**
 * Sendbird chat transport (reference: hinge-rs `client/sendbird.rs`, `client/chat.rs`, `ws.rs`).
 *
 * - The socket opens only for a sync pass or a send, and the caller closes it right after
 *   ([close]): no persistent background socket, no foreground service.
 * - The `Session-Key` from `LOGI` is held in memory only and dropped on [close].
 * - Reads use Sendbird REST with that key; sends use a `MESG` frame confirmed by the echo that
 *   carries our `req_id`. A timeout is ambiguous and is reported as [SendbirdSend.Timeout]; this
 *   class never resends anything.
 * - Every socket open goes through [HingeNetGate.admit] (flag, opt-in, breaker, allowlist, budget).
 */
class SendbirdChatTransport(
    private val http: HingeHttp,
    private val gate: HingeNetGate,
    private val identity: HingeClientIdentity,
    private val sockets: ChatSocketFactory,
    private val clock: HingeClock = HingeClock.SYSTEM,
    private val log: HingeLog = HingeLog.NONE,
    private val loginTimeoutMillis: Long = 10_000,
    private val newRequestId: () -> String = { UUID.randomUUID().toString().uppercase() },
) {
    @Volatile private var socket: ChatSocket? = null
    @Volatile private var sessionKey: String? = null
    @Volatile private var loginLatch: CountDownLatch? = null
    @Volatile private var loginRefusal: HingeSignal? = null
    @Volatile private var closed = false
    private val pending = ConcurrentHashMap<String, Waiter>()

    private class Waiter {
        val latch = CountDownLatch(1)
        @Volatile var frame: JsonObject? = null
        @Volatile var isError = false
    }

    val isConnected: Boolean get() = socket != null && sessionKey != null && !closed

    /** Opens the socket with the chat token and waits for `LOGI`. */
    @Synchronized
    fun connect(session: HingeSession): SendbirdConnect {
        if (isConnected) return SendbirdConnect.Ready
        close()
        val token = session.chatToken ?: return SendbirdConnect.NoLogin
        val userId = session.identityId ?: return SendbirdConnect.NoLogin
        val url = http.allowlist.url(
            HingeEndpoint.SB_SOCKET,
            query = listOf(
                "p" to identity.devicePlatform, "sv" to identity.sendbirdSdkVersion, "pv" to identity.osVersion,
                "uikit_config" to "0", "use_local_cache" to "0",
                "include_extra_data" to "premium_feature_list,file_upload_size_limit,emoji_hash,application_attributes,notifications,message_template,ai_agent",
                "include_poll_details" to "1", "user_id" to userId, "ai" to identity.sendbirdAppId,
                "pmce" to "1", "expiring_session" to "0", "config_ts" to "0",
            ),
        )
        gate.admit("GET", url) // flag, opt-in, breaker, allowlist, budget: throws before any connect
        val request = Request.Builder().url(url)
            .header("SENDBIRD-WS-TOKEN", token)
            .header("Accept", "*/*")
            .header("Accept-Language", identity.acceptLanguage)
            .header("Request-Sent-Timestamp", clock.nowMillis().toString())
            .header("x-session-key", session.sessionId)
            .header("x-device-id", session.deviceId)
            .header("x-install-id", session.installId)
            .header("sb-user-id", userId)
            .header("sb-access-token", token)
            .header("SendBird", identity.sendbirdHeader())
            .header("SB-User-Agent", identity.sendbirdUserAgent())
            .header("SB-SDK-User-Agent", identity.sendbirdSdkUserAgent())
            .header("User-Agent", identity.hingeUserAgent())
            .apply { if (identity.sendbirdWsOrigin.isNotBlank()) header("Origin", identity.sendbirdWsOrigin) }
            .build()
        val latch = CountDownLatch(1)
        loginLatch = latch
        loginRefusal = null
        closed = false
        socket = sockets.open(request, Listener())
        val gotLogin = latch.await(loginTimeoutMillis, TimeUnit.MILLISECONDS)
        loginRefusal?.let { r ->
            close()
            return SendbirdConnect.Refused(r)
        }
        if (closed && sessionKey == null) {
            close()
            return SendbirdConnect.Closed
        }
        if (!gotLogin || sessionKey == null) {
            log.log("hinge.chat.no_login", emptyMap())
            close()
            return SendbirdConnect.NoLogin
        }
        log.log("hinge.chat.connected", emptyMap())
        return SendbirdConnect.Ready
    }

    /** Closes the socket and forgets the in-memory `Session-Key`. Idempotent. */
    fun close() {
        val s = socket
        socket = null
        sessionKey = null
        closed = true
        pending.values.forEach { it.latch.countDown() }
        pending.clear()
        runCatching { s?.close() }
    }

    /** `GET /v3/users/{me}/my_group_channels`, newest activity first. */
    fun listChannels(session: HingeSession, limit: Int): HingeResponse = http.sendbird(
        HingeEndpoint.SB_CHANNELS, session, sessionKey, listOf(session.identityId.orEmpty()),
        listOf(
            "include_left_channel" to "false", "member_state_filter" to "all", "super_mode" to "all",
            "show_latest_message" to "false", "show_pinned_messages" to "false", "unread_filter" to "all",
            "show_delivery_receipt" to "true", "show_conversation" to "false", "show_member" to "true",
            "show_empty" to "true", "limit" to limit.coerceIn(1, 100).toString(), "user_id" to session.identityId.orEmpty(),
            "is_feed_channel" to "false", "order" to "latest_last_message", "hidden_mode" to "unhidden_only",
            "distinct_mode" to "all", "show_read_receipt" to "true", "show_metadata" to "true",
            "is_explicit_request" to "true", "show_frozen" to "true", "public_mode" to "all",
            "include_chat_notification" to "false",
        ),
    )

    /** `GET /v3/group_channels/{channel}/messages`: the [prevLimit] messages before now. */
    fun messages(session: HingeSession, channelUrl: String, prevLimit: Int): HingeResponse = http.sendbird(
        HingeEndpoint.SB_MESSAGES, session, sessionKey, listOf(channelUrl),
        listOf(
            "include_reply_type" to "all", "sdk_source" to "external_legacy", "with_sorted_meta_array" to "true",
            "message_ts" to clock.nowMillis().toString(), "is_sdk" to "true", "include_reactions_summary" to "true",
            "include_parent_message_info" to "false", "reverse" to "true", "prev_limit" to prevLimit.coerceIn(1, 100).toString(),
            "custom_types" to "*", "include" to "false", "next_limit" to "0", "include_poll_details" to "true",
            "show_subchannel_messages_only" to "false", "include_thread_info" to "false",
        ),
    )

    /**
     * Writes ONE `MESG` frame and waits for its echo or refusal. Never retries and never resends.
     * The frame body is built as JSON, so quotes, newlines and emoji in [text] cannot break it.
     */
    fun send(channelUrl: String, text: String, timeoutMillis: Long): SendbirdSend {
        val s = socket
        if (s == null || sessionKey == null || closed) return SendbirdSend.NotWritten
        val reqId = newRequestId()
        val waiter = Waiter()
        pending[reqId] = waiter
        val frame = "MESG" + buildJsonObject {
            put("channel_url", channelUrl)
            put("message", text)
            put("req_id", reqId)
            put("mention_type", "users")
        }.toString() + "\n"
        if (!s.send(frame)) {
            pending.remove(reqId)
            return SendbirdSend.NotWritten
        }
        val answered = waiter.latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
        pending.remove(reqId)
        val body = waiter.frame
        if (!answered || body == null) {
            log.log("hinge.chat.send_timeout", emptyMap())
            return SendbirdSend.Timeout
        }
        if (waiter.isError || body.isRefusal()) {
            val code = body["code"]?.jsonPrimitive?.longOrNull ?: 0
            val message = body["message"]?.jsonPrimitive?.contentOrNull
            val guest = code == GUEST_NOT_ALLOWED || message?.lowercase()?.contains("guest is not allowed") == true
            val signal = if (guest) HingeSignal.Ok else HingeResponseClassifier.refusalSignal(message)
            log.log("hinge.chat.send_refused", mapOf("code" to code.toString(), "guest" to guest.toString()))
            return SendbirdSend.Refused(code, guest, signal)
        }
        val msgId = body["msg_id"]?.jsonPrimitive?.let { it.contentOrNull ?: it.longOrNull?.toString() }
            ?: body["message_id"]?.jsonPrimitive?.contentOrNull
        if (msgId.isNullOrEmpty()) {
            // An echo we cannot read: we cannot say it was stored, and we must not say it failed.
            log.log("hinge.chat.send_echo_unreadable", emptyMap())
            return SendbirdSend.Timeout
        }
        log.log("hinge.chat.send_acknowledged", emptyMap())
        return SendbirdSend.Acknowledged(msgId, body["created_at"]?.jsonPrimitive?.longOrNull ?: body["ts"]?.jsonPrimitive?.longOrNull)
    }

    private fun JsonObject.isRefusal(): Boolean =
        this["error"]?.jsonPrimitive?.booleanOrNull == true || (this["code"] is JsonPrimitive && this["msg_id"] == null && this["message_id"] == null)

    private inner class Listener : ChatSocketListener {
        override fun onFrame(text: String) {
            val kind = text.take(4)
            val start = text.indexOf('{')
            val body = if (start < 0) null else runCatching { Json.parseToJsonElement(text.substring(start).trim()).jsonObject }.getOrNull()
            when (kind) {
                "LOGI" -> {
                    if (body == null) {
                        loginRefusal = HingeSignal.Unparseable
                    } else if (body["error"]?.jsonPrimitive?.booleanOrNull == true) {
                        loginRefusal = HingeResponseClassifier.refusalSignal(body["message"]?.jsonPrimitive?.contentOrNull).let {
                            if (it == HingeSignal.SendRefused) HingeSignal.Unauthorized else it
                        }
                    } else {
                        val key = (body["key"] ?: body["session_key"])?.jsonPrimitive?.contentOrNull
                        if (key.isNullOrEmpty()) loginRefusal = HingeSignal.Unparseable else sessionKey = key
                    }
                    loginLatch?.countDown()
                }
                "PING" -> {
                    val now = clock.nowMillis()
                    runCatching { socket?.send("PONG" + buildJsonObject { put("sts", now); put("ts", now) }.toString() + "\n") }
                }
                "MESG", "EROR" -> {
                    if (body == null) return
                    val reqId = body["req_id"]?.jsonPrimitive?.contentOrNull
                    val waiter = when {
                        reqId != null -> pending[reqId]
                        kind == "EROR" && pending.size == 1 -> pending.values.first() // Sendbird may omit req_id on an error
                        else -> null
                    } ?: return
                    waiter.isError = kind == "EROR"
                    waiter.frame = body
                    waiter.latch.countDown()
                }
                else -> Unit // READ, SYEV, broadcasts: ignored (inbound arrives through the REST sync)
            }
        }

        override fun onClosed() {
            closed = true
            loginLatch?.countDown()
            pending.values.forEach { it.latch.countDown() }
        }
    }

    companion object {
        const val GUEST_NOT_ALLOWED = 900030L
    }
}
