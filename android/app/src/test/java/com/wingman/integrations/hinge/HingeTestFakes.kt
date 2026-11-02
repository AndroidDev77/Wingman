package com.wingman.integrations.hinge

import com.wingman.core.api.ApiError
import com.wingman.core.api.ApiResult
import com.wingman.core.api.IdempotencyKey
import com.wingman.core.api.WingmanJson
import com.wingman.core.model.Connection
import com.wingman.core.model.ConnectionStatus
import com.wingman.core.model.ConnectionTransport
import com.wingman.core.model.ConnectionUnpairResult
import com.wingman.core.model.ConnectorCapabilities
import com.wingman.core.model.ConnectorEventIngestOutcome
import com.wingman.core.model.ConnectorEventIngestResult
import com.wingman.core.model.OutboxAuthorization
import com.wingman.core.model.OutboxAuthorizationOrigin
import com.wingman.core.model.OutboxClaimRequest
import com.wingman.core.model.OutboxClaimResult
import com.wingman.core.model.OutboxInstruction
import com.wingman.core.model.OutboxInstructionState
import com.wingman.core.model.User
import com.wingman.integrations.sms.InMemorySmsStateStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/*
 * Synthetic fixtures only: no real person, account, number, token or message. Sentinel strings
 * are planted in every private value so the privacy and isolation tests can search for them.
 */
object Sentinels {
    const val PHONE = "+15550109999"
    const val OTP = "424242"
    const val EMAIL_CODE = "777111"
    const val CASE_ID = "CASE-SENTINEL-42"
    const val HINGE_TOKEN = "SENTINEL-HINGE-TOKEN-x1"
    const val CHAT_TOKEN = "SENTINEL-CHAT-TOKEN-y2"
    const val CHAT_TOKEN_2 = "SENTINEL-CHAT-TOKEN-z3"
    const val SESSION_KEY = "SENTINEL-SESSION-KEY-k4"
    const val SELF_ID = "selfid-sentinel-0001"
    const val MATCH_ID = "matchid-sentinel-0002"
    const val CHANNEL = "sendbird_group_channel_sentinel_55"
    const val TEXT = "SENTINEL-TEXT the secret words, with \"quotes\" and a newline\n and emoji 😀"
    const val REPLY = "SENTINEL-REPLY see you at eight"
    val ALL_SECRETS = listOf(PHONE, OTP, EMAIL_CODE, CASE_ID, HINGE_TOKEN, CHAT_TOKEN, CHAT_TOKEN_2, SESSION_KEY)
    val ALL_PRIVATE = ALL_SECRETS + listOf(SELF_ID, MATCH_ID, CHANNEL, "SENTINEL-TEXT", "SENTINEL-REPLY")
}

const val USER_ID = "0192f3a0-0000-7000-8000-000000000001"
const val CONNECTION_ID = "0192f3a0-0000-7000-8000-0000000000a5"
const val OUTBOUND_ID = "0192f3a0-0000-7000-8000-000000000600"
const val INSTRUCTION_ID = "0192f3a0-0000-7000-8000-000000000700"
const val APPROVAL_ID = "0192f3a0-0000-7000-8000-000000000800"
const val T0 = 1_793_600_000_000L // 2026-11-02, a fixed instant

class FakeClock(var now: Long = T0) : HingeClock {
    override fun nowMillis(): Long = now
}

/** Sleeping advances the fake clock; records each wait. */
class FakeSleeper(private val clock: FakeClock) : HingeSleeper {
    val sleeps = mutableListOf<Long>()
    override fun sleep(millis: Long) {
        sleeps += millis
        if (millis > 0) clock.now += millis
    }
}

class FixedRandom(var pick: (Long, Long) -> Long = { from, _ -> from }) : HingeRandom {
    val bounds = mutableListOf<Pair<Long, Long>>()
    override fun nextLong(from: Long, until: Long): Long {
        bounds += from to until
        return pick(from, until)
    }
}

class RecordingHingeLog : HingeLog {
    val lines = CopyOnWriteArrayList<String>()
    override fun log(event: String, fields: Map<String, String>) {
        lines += event + " " + fields.entries.joinToString(" ") { "${it.key}=${it.value}" }
    }
}

/** Reversible, NOT secure: proves the file holds no plaintext. */
class XorCipher : HingeSecretCipher {
    var destroyed = 0
    override fun encrypt(plain: ByteArray): ByteArray = byteArrayOf(7) + plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    override fun decrypt(sealed: ByteArray): ByteArray {
        require(sealed.isNotEmpty() && sealed[0] == 7.toByte()) { "not ours" }
        return sealed.drop(1).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }
    override fun destroyKey() { destroyed++ }
}

/** A fake Sendbird socket. Frames are answered synchronously according to [mode]. */
class FakeSockets : ChatSocketFactory {
    enum class Mode { ECHO, GUEST, REFUSE, BAN, SILENT, CLOSE, FAIL_WRITE }

    var loginKey: String? = Sentinels.SESSION_KEY
    var loginFrame: String? = null
    var mode = Mode.ECHO
    var nextMsgId = 9001L
    val opened = CopyOnWriteArrayList<Request>()
    val frames = CopyOnWriteArrayList<String>()
    val mesgFrames: List<String> get() = frames.filter { it.startsWith("MESG") }

    override fun open(request: Request, listener: ChatSocketListener): ChatSocket {
        opened += request
        val socket = object : ChatSocket {
            override fun send(frame: String): Boolean {
                if (mode == Mode.FAIL_WRITE) return false
                frames += frame
                if (!frame.startsWith("MESG")) return true
                val reqId = Regex("\"req_id\":\"([^\"]+)\"").find(frame)!!.groupValues[1]
                when (mode) {
                    Mode.ECHO -> listener.onFrame("MESG{\"msg_id\":${nextMsgId++},\"req_id\":\"$reqId\",\"created_at\":$T0,\"channel_url\":\"x\"}")
                    Mode.GUEST -> listener.onFrame("EROR{\"error\":true,\"code\":900030,\"message\":\"Guest is not allowed.\",\"req_id\":\"$reqId\"}")
                    Mode.REFUSE -> listener.onFrame("EROR{\"error\":true,\"code\":900041,\"message\":\"User is muted.\",\"req_id\":\"$reqId\"}")
                    Mode.BAN -> listener.onFrame("EROR{\"error\":true,\"code\":400108,\"message\":\"User is banned.\",\"req_id\":\"$reqId\"}")
                    Mode.CLOSE -> listener.onClosed()
                    Mode.SILENT, Mode.FAIL_WRITE -> Unit
                }
                return true
            }
            override fun close() {}
        }
        when {
            loginFrame != null -> listener.onFrame(loginFrame!!)
            loginKey != null -> listener.onFrame("LOGI{\"key\":\"$loginKey\",\"user_id\":\"u\",\"ping_interval\":15}")
        }
        return socket
    }
}

/** Records every call and every body that would go to WINGMAN (never Hinge). */
class FakeHingeBackend : HingeBackend {
    val calls = CopyOnWriteArrayList<String>()
    val wire = CopyOnWriteArrayList<String>()
    val ingested = CopyOnWriteArrayList<JsonObject>()
    val claimScript = ArrayDeque<() -> ApiResult<OutboxClaimResult>>()
    var revokeResult: () -> ApiResult<ConnectionUnpairResult>? = { null }
    var pairError: ApiError? = null

    override suspend fun me(): ApiResult<User> {
        calls += "me"
        return ApiResult.Success(User(USER_ID, createdAt = Instant.ofEpochMilli(T0)))
    }

    override suspend fun pairExperimental(body: JsonObject, key: IdempotencyKey): ApiResult<Connection> {
        calls += "pair"
        wire += body.toString() + key.value
        pairError?.let { return ApiResult.Failure(it) }
        val caps = WingmanJson.decodeFromJsonElement(ConnectorCapabilities.serializer(), body["capabilities"]!!)
        return ApiResult.Success(connection(caps, body["device_install_id"].toString().trim('"')))
    }

    override suspend fun getConnection(connectionId: String): ApiResult<Connection> {
        calls += "get"
        return ApiResult.Failure(ApiError.Http(404, null))
    }

    override suspend fun revokeExperimentalConsent(connectionId: String, key: IdempotencyKey): ApiResult<ConnectionUnpairResult> {
        calls += "revoke"
        wire += connectionId + key.value
        revokeResult()?.let { return it }
        val c = connection(null, null)
        return ApiResult.Success(ConnectionUnpairResult(c.copy(status = ConnectionStatus.DISCONNECTED, unpairedAt = Instant.ofEpochMilli(T0)), 0, 0, 0, 0))
    }

    override suspend fun ingest(connectionId: String, envelope: JsonObject, key: IdempotencyKey): ApiResult<ConnectorEventIngestResult> {
        calls += "ingest"
        wire += envelope.toString() + key.value
        ingested += envelope
        return ApiResult.Success(ConnectorEventIngestResult(key.value, ConnectorEventIngestOutcome.ACCEPTED, null, Instant.ofEpochMilli(T0)))
    }

    override suspend fun claim(connectionId: String, body: OutboxClaimRequest, key: IdempotencyKey): ApiResult<OutboxClaimResult> {
        calls += "claim"
        wire += body.toString() + key.value
        return claimScript.removeFirstOrNull()?.invoke() ?: ApiResult.Success(OutboxClaimResult(emptyList(), Instant.ofEpochMilli(T0)))
    }

    fun ingestedOfType(type: String) = ingested.filter { it["type"].toString().trim('"') == type }

    private fun connection(caps: ConnectorCapabilities?, install: String?) = Connection(
        id = CONNECTION_ID, platform = "hinge", displayName = "Hinge (experimental)", status = ConnectionStatus.CONNECTED,
        capabilities = caps ?: WingmanJson.decodeFromJsonElement(ConnectorCapabilities.serializer(), HingeCapabilities.document(T0)),
        createdAt = Instant.ofEpochMilli(T0), updatedAt = Instant.ofEpochMilli(T0), deviceInstallId = install,
        pairedAt = Instant.ofEpochMilli(T0), transport = ConnectionTransport.UNOFFICIAL_API, experimental = true,
    )
}

fun instruction(
    key: String = "send:$OUTBOUND_ID:1",
    thread: String = Sentinels.CHANNEL,
    text: String = Sentinels.REPLY,
    origin: OutboxAuthorizationOrigin = OutboxAuthorizationOrigin.APPROVED_DRAFT,
    approvalId: String? = if (origin == OutboxAuthorizationOrigin.APPROVED_DRAFT) APPROVAL_ID else null,
    leaseSeconds: Long = 600,
) = OutboxInstruction(
    id = INSTRUCTION_ID, connectionId = CONNECTION_ID, outboundMessageId = OUTBOUND_ID, idempotencyKey = key, attempt = 1,
    state = OutboxInstructionState.LEASED, createdAt = Instant.ofEpochMilli(T0), leasedAt = Instant.ofEpochMilli(T0),
    leaseExpiresAt = Instant.ofEpochMilli(T0 + leaseSeconds * 1000), conversationId = "0192f3a0-0000-7000-8000-000000000900",
    externalThreadId = thread, text = text, authorization = OutboxAuthorization(origin, approvalId),
)

fun claimOf(vararg i: OutboxInstruction): () -> ApiResult<OutboxClaimResult> = { ApiResult.Success(OutboxClaimResult(i.toList(), Instant.ofEpochMilli(T0))) }

/** Hinge + Sendbird on one MockWebServer, routed by path prefix. Unscripted calls answer 418 and are recorded. */
class FakeHingeServer {
    val server = MockWebServer()
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    val unexpected = CopyOnWriteArrayList<String>()
    private val scripted = HashMap<String, ArrayDeque<MockResponse>>()
    private val defaults = HashMap<String, () -> MockResponse>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val k = request.method + " " + request.requestUrl!!.encodedPath
                synchronized(scripted) { scripted[k]?.removeFirstOrNull()?.let { return it } }
                defaults[k]?.let { return it() }
                unexpected += k
                return MockResponse().setResponseCode(418)
            }
        }
        server.start()
    }

    fun on(methodPath: String, response: MockResponse) = synchronized(scripted) { scripted.getOrPut(methodPath) { ArrayDeque() }.addLast(response) }
    fun default(methodPath: String, response: () -> MockResponse) { defaults[methodPath] = response }
    fun count(): Int = requests.size
    fun paths(): List<String> = requests.map { it.method + " " + it.requestUrl!!.encodedPath }

    fun identity() = HingeClientIdentity(
        hingeBaseUrl = server.url("/hinge/").toString(),
        sendbirdApiUrl = server.url("/sb/").toString(),
        sendbirdWsUrl = server.url("/ws/").toString().replaceFirst("http://", "ws://"),
        sendbirdAppId = "TEST-APP-ID", sendbirdSdkVersion = "4.0.0", appVersion = "10.0.0", buildNumber = "1",
        osVersion = "26.0", devicePlatform = "iOS", deviceModelCode = "TestModel1,1", deviceRegion = "US", acceptLanguage = "en-US", userAgent = "TestAgent/1",
    )

    fun shutdown() { runCatching { server.shutdown() } }
}

fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("content-type", "application/json").setBody(body)

object HingeBodies {
    val expires = "2026-12-31T00:00:00Z"
    fun loginTokens(chat: Boolean = true) = buildJsonObject {
        putJsonObject("hingeAuthToken") { put("identityId", Sentinels.SELF_ID); put("token", Sentinels.HINGE_TOKEN); put("expires", expires) }
        if (chat) putJsonObject("sendbirdAuthToken") { put("token", Sentinels.CHAT_TOKEN); put("expires", expires) }
    }.toString()
    fun hingeToken() = buildJsonObject { put("identityId", Sentinels.SELF_ID); put("token", Sentinels.HINGE_TOKEN); put("expires", expires) }.toString()
    fun chatToken(token: String = Sentinels.CHAT_TOKEN_2) = buildJsonObject { put("token", token); put("expires", expires) }.toString()

    fun channels(vararg urls: String, lastAt: Long = T0 - 1000) = buildJsonObject {
        put("channels", buildJsonArray {
            urls.forEach { u -> add(buildJsonObject { put("channel_url", u); putJsonObject("last_message") { put("created_at", lastAt) } }) }
        })
    }.toString()

    fun message(id: Long, sender: String = Sentinels.MATCH_ID, text: String = Sentinels.TEXT, type: String = "MESG", custom: String = "", at: Long = T0 - 5000, channel: String = Sentinels.CHANNEL) =
        buildJsonObject {
            put("type", type); put("message_id", id); put("message", text); put("custom_type", custom); put("created_at", at)
            put("channel_url", channel)
            putJsonObject("user") { put("user_id", sender); put("nickname", "Riley") }
        }

    fun messages(vararg m: JsonObject) = buildJsonObject { put("messages", buildJsonArray { m.forEach { add(it) } }) }.toString()
    fun connections(vararg others: String) = buildJsonObject {
        put("connections", buildJsonArray { others.forEach { o -> add(buildJsonObject { put("initiatorId", o); put("subjectId", Sentinels.SELF_ID) }) } })
    }.toString()
}

/**
 * A fully wired connector against [FakeHingeServer], a fake socket and [FakeHingeBackend].
 * Defaults: echo timeout 100 ms, LOGI wait 100 ms, jitter = its minimum (20 s on the fake clock).
 */
class HingeRig(
    val flagsOn: Boolean = true,
    limits: HingeLimits = HingeLimits.DEFAULT,
    identityOverride: HingeClientIdentity? = null,
    val sessions: HingeSessionStore = InMemoryHingeSessionStore(),
) {
    val hs = FakeHingeServer()
    val clock = FakeClock()
    val sleeper = FakeSleeper(clock)
    val random = FixedRandom()
    val store = InMemorySmsStateStore()
    val backend = FakeHingeBackend()
    val log = RecordingHingeLog()
    val sockets = FakeSockets()
    val stops = mutableListOf<HingeStopReason>()
    var cancelled = 0
    val flags = HingeBuildFlags(flagsOn, identityOverride ?: hs.identity())
    val c = HingeConnector(
        flags, store, sessions, backend, limits, OkHttpClient(), { sockets }, clock, sleeper, random, log,
        onStopped = { stops += it }, cancelWork = { cancelled++ }, echoTimeoutMillis = 100, loginTimeoutMillis = 100,
    )

    fun scriptLogin(email: Boolean = false) {
        hs.on("POST /hinge/identity/install", json("{}"))
        hs.on("POST /hinge/auth/sms/v2/initiate", json("{}"))
        if (email) {
            hs.on("POST /hinge/auth/sms/v2", json("{\"caseId\":\"${Sentinels.CASE_ID}\",\"email\":\"r***@example.com\"}", 412))
            hs.on("POST /hinge/auth/device/validate", json(HingeBodies.hingeToken()))
            hs.on("POST /hinge/message/authenticate", json(HingeBodies.chatToken(Sentinels.CHAT_TOKEN)))
        } else {
            hs.on("POST /hinge/auth/sms/v2", json(HingeBodies.loginTokens()))
        }
    }

    /** Consent, login (typed codes), pairing. */
    fun loginAndPair(email: Boolean = false) {
        scriptLogin(email)
        check(c.acceptRisk(true))
        check(c.requestCode(Sentinels.PHONE) is HingeLoginStep.Moved)
        c.submitCode(Sentinels.OTP)
        if (email) c.submitEmailCode(Sentinels.EMAIL_CODE)
        check(c.auth.state == HingeLoginState.ChatReady) { "login failed: ${c.auth.state}" }
        check(c.pair() is HingePairResult.Paired)
    }

    /** Scripts one sync pass reading [messages] from one channel and no new match. */
    fun scriptSync(vararg messages: JsonObject, channel: String = Sentinels.CHANNEL, matches: String = HingeBodies.connections()) {
        hs.on("GET /sb/v3/users/${Sentinels.SELF_ID}/my_group_channels", json(HingeBodies.channels(channel)))
        hs.on("GET /sb/v3/group_channels/$channel/messages", json(HingeBodies.messages(*messages)))
        hs.on("GET /hinge/connection/v2", json(matches))
    }

    /** One sync that makes the default channel a known conversation (the match wrote first). */
    fun syncOneInbound() {
        scriptSync(HingeBodies.message(1))
        val r = c.syncOnce()
        check(r.enqueued >= 1 && r.stopped == null) { "sync failed: $r" }
        clock.now += HingeLimits.FIFTEEN_MINUTES
    }

    fun close() { hs.shutdown() }
}
