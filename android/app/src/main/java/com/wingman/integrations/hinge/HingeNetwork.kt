package com.wingman.integrations.hinge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Which service an allowlisted endpoint belongs to; its base URL comes from [HingeClientIdentity]. */
enum class HingeHost { HINGE, SENDBIRD_API, SENDBIRD_WS }

/**
 * The COMPLETE list of network endpoints this connector may call. Each one is documented by the
 * reference (hinge-rs @ cffe397); nothing else is compiled in. `{}` is exactly one path segment.
 *
 * Deliberately EXCLUDED (never callable, the allowlist test asserts it): recommendations
 * (`/rec/v2`, `/standouts/v3`), rating and likes (`/rate/v2/...`, `/like/...`, `/likelimit`), Hinge's
 * dead REST send (`/message/send`), Sendbird channel creation (no first messages), profile
 * reads and edits (`/user/...`, `/content/...`; profile_read is UNSUPPORTED in v1), settings,
 * account, export and moderation routes.
 */
enum class HingeEndpoint(val method: String, val host: HingeHost, val template: String) {
    INSTALL("POST", HingeHost.HINGE, "/identity/install"),
    SMS_INITIATE("POST", HingeHost.HINGE, "/auth/sms/v2/initiate"),
    SMS_VERIFY("POST", HingeHost.HINGE, "/auth/sms/v2"),
    DEVICE_VALIDATE("POST", HingeHost.HINGE, "/auth/device/validate"),
    CHAT_AUTHENTICATE("POST", HingeHost.HINGE, "/message/authenticate"),
    MATCHES("GET", HingeHost.HINGE, "/connection/v2"),
    SB_CHANNELS("GET", HingeHost.SENDBIRD_API, "/v3/users/{}/my_group_channels"),
    SB_MESSAGES("GET", HingeHost.SENDBIRD_API, "/v3/group_channels/{}/messages"),
    SB_SOCKET("GET", HingeHost.SENDBIRD_WS, "/"),
    ;

    val segments: List<String> get() = template.trim('/').split('/').filter { it.isNotEmpty() }
}

/** URL matching for [HingeEndpoint]. Pure. */
class HingeAllowlist(private val identity: HingeClientIdentity) {

    fun base(host: HingeHost): HttpUrl? = when (host) {
        HingeHost.HINGE -> identity.hingeBaseUrl.toHttpUrlOrNull()
        HingeHost.SENDBIRD_API -> identity.sendbirdApiUrl.toHttpUrlOrNull()
        HingeHost.SENDBIRD_WS -> wsToHttp(identity.sendbirdWsUrl).toHttpUrlOrNull()
    }

    /** The endpoint [url] + [method] is, or null when it is not allowlisted. */
    fun match(method: String, url: HttpUrl): HingeEndpoint? = HingeEndpoint.entries.firstOrNull { e ->
        if (!e.method.equals(method, ignoreCase = true)) return@firstOrNull false
        val b = base(e.host) ?: return@firstOrNull false
        if (b.scheme != url.scheme || b.host != url.host || b.port != url.port) return@firstOrNull false
        val prefix = b.pathSegments.filter { it.isNotEmpty() }
        val path = url.pathSegments.filter { it.isNotEmpty() }
        if (path.size != prefix.size + e.segments.size || path.take(prefix.size) != prefix) return@firstOrNull false
        path.drop(prefix.size).zip(e.segments).all { (actual, expected) -> if (expected == "{}") actual.isNotEmpty() else actual == expected }
    }

    /** Builds the URL of [endpoint], substituting [args] for `{}` segments (encoded as single segments). */
    fun url(endpoint: HingeEndpoint, args: List<String> = emptyList(), query: List<Pair<String, String>> = emptyList()): HttpUrl {
        val b = requireNotNull(base(endpoint.host)) { "identity not configured" }
        val builder = b.newBuilder()
        var i = 0
        for (s in endpoint.segments) {
            builder.addPathSegment(if (s == "{}") args[i++].also { require(it.isNotEmpty() && '/' !in it) { "bad path argument" } } else s)
        }
        require(i == args.size) { "wrong number of path arguments" }
        if (endpoint.segments.isEmpty() && b.pathSegments.lastOrNull() != "") builder.addPathSegment("")
        query.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        return builder.build()
    }

    companion object {
        fun wsToHttp(url: String): String = when {
            url.startsWith("wss://") -> "https://" + url.removePrefix("wss://")
            url.startsWith("ws://") -> "http://" + url.removePrefix("ws://")
            else -> url
        }
    }
}

/**
 * The single admission check in front of EVERY Hinge and Sendbird network call (REST through the
 * OkHttp interceptor, the WebSocket explicitly). In order: experimental flag, complete identity,
 * user opt-in, circuit breaker, endpoint allowlist, rate limiter. Each refusal is an
 * [HingeCallRefused] thrown before anything reaches the network.
 */
class HingeNetGate(
    private val flags: HingeBuildFlags,
    private val isOptedIn: () -> Boolean,
    private val breaker: HingeCircuitBreaker,
    private val limiter: HingeRateLimiter,
    private val log: HingeLog = HingeLog.NONE,
) {
    val allowlist = HingeAllowlist(flags.identity)

    /** Calls admitted to the network since process start (the live test's redacted counter, L9). */
    val admittedCalls = AtomicInteger(0)

    /** Checks everything except the allowlist and the budget: may the connector act at all? */
    fun checkActive() {
        if (!flags.experimentalEnabled) throw HingeCallRefused(HingeCallRefused.FLAG_OFF)
        if (!flags.identity.isComplete) throw HingeCallRefused(HingeCallRefused.NOT_CONFIGURED)
        if (!isOptedIn()) throw HingeCallRefused(HingeCallRefused.NOT_OPTED_IN)
        if (breaker.isStopped()) throw HingeCallRefused(HingeCallRefused.STOPPED)
    }

    /** Admits one call to [url]; [countBudget] false only for the WS client's second check. */
    fun admit(method: String, url: HttpUrl, countBudget: Boolean = true): HingeEndpoint {
        checkActive()
        val endpoint = allowlist.match(method, url) ?: run {
            log.log("hinge.net.refused", mapOf("reason" to HingeCallRefused.NOT_ALLOWLISTED))
            throw HingeCallRefused(HingeCallRefused.NOT_ALLOWLISTED)
        }
        if (countBudget) {
            limiter.admitCall()?.let { denial ->
                log.log("hinge.net.rate_limited", mapOf("endpoint" to endpoint.name, "limit" to denial.code))
                throw HingeRateLimited(denial)
            }
            // Re-check after a spacing wait: an opt-out or a trip during the wait wins.
            checkActive()
            admittedCalls.incrementAndGet()
        }
        return endpoint
    }
}

class HingeRateLimited(val denial: HingeLimitDenial) : HingeCallRefused(RATE_LIMITED)

/** OkHttp application interceptor: no request leaves without [HingeNetGate.admit]. */
class HingeGateInterceptor(private val gate: HingeNetGate, private val countBudget: Boolean = true) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        gate.admit(request.method, request.url, countBudget)
        return chain.proceed(request)
    }
}

/**
 * One response, kept only as long as it is parsed. [body] is private and never logged.
 * [networkError] is set (and [status] is 0) when no HTTP response arrived.
 */
class HingeResponse(
    val status: Int,
    val contentType: String?,
    val body: String?,
    val retryAfter: String?,
    val headerNames: Set<String>,
    val networkError: String? = null,
) {
    val isSuccess: Boolean get() = status in 200..299
    fun json(): JsonElement? = body?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
    override fun toString(): String = "HingeResponse(status=$status, body=${redacted(body)}, networkError=$networkError)"
}

/**
 * Blocking HTTP for the allowlisted endpoints. Builds the reference's headers from
 * [HingeClientIdentity] and the session, runs through [HingeGateInterceptor], and returns the raw
 * response for the caller to classify. No retries here: retry policy belongs to callers, and
 * almost every failure stops (matrix §6e). Logs endpoint names and status codes only.
 */
class HingeHttp(
    private val gate: HingeNetGate,
    private val identity: HingeClientIdentity,
    baseClient: OkHttpClient = OkHttpClient(),
    private val clock: HingeClock = HingeClock.SYSTEM,
    private val log: HingeLog = HingeLog.NONE,
) {
    private val client: OkHttpClient = baseClient.newBuilder()
        .addInterceptor(HingeGateInterceptor(gate))
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    val allowlist: HingeAllowlist get() = gate.allowlist

    /** Throws [HingeCallRefused] unless the connector may act now (flag, identity, opt-in, breaker). */
    fun checkActive() = gate.checkActive()

    /** A Hinge REST call. [session] supplies ids and, when present, the bearer token. */
    fun hinge(endpoint: HingeEndpoint, session: HingeSession, body: JsonObject? = null, withBearer: Boolean = true): HingeResponse {
        require(endpoint.host == HingeHost.HINGE)
        val url = allowlist.url(endpoint)
        val b = Request.Builder().url(url)
            .header("content-type", "application/json")
            .header("accept", "*/*")
            .header("accept-language", identity.acceptLanguage)
            .header("x-device-model-code", identity.deviceModelCode)
            .header("x-device-model", "unknown")
            .header("x-device-region", identity.deviceRegion)
            .header("x-session-id", session.sessionId)
            .header("x-device-id", session.deviceId)
            .header("x-install-id", session.installId)
            .header("x-device-platform", identity.devicePlatform)
            .header("x-app-version", identity.appVersion)
            .header("x-build-number", identity.buildNumber)
            .header("x-os-version", identity.osVersion)
            .header("user-agent", identity.hingeUserAgent())
        if (withBearer && !session.hingeToken.isNullOrEmpty()) b.header("authorization", "Bearer " + session.hingeToken)
        if (endpoint.method == "POST") b.post((body ?: JsonObject(emptyMap())).toString().toRequestBody(JSON)) else b.get()
        return execute(endpoint, b.build())
    }

    /** A Sendbird REST read with the in-memory [sessionKey]. */
    fun sendbird(endpoint: HingeEndpoint, session: HingeSession, sessionKey: String?, args: List<String>, query: List<Pair<String, String>>): HingeResponse {
        require(endpoint.host == HingeHost.SENDBIRD_API)
        val url = allowlist.url(endpoint, args, query)
        val b = Request.Builder().url(url).get()
            .header("accept", "*/*")
            .header("accept-language", identity.acceptLanguage)
            .header("x-session-key", session.sessionId)
            .header("x-device-id", session.deviceId)
            .header("x-install-id", session.installId)
            .header("sb-user-id", session.identityId.orEmpty())
            .header("Request-Sent-Timestamp", clock.nowMillis().toString())
            .header("SendBird", identity.sendbirdHeader())
            .header("SB-User-Agent", identity.sendbirdUserAgent())
            .header("SB-SDK-User-Agent", identity.sendbirdSdkUserAgent())
            .header("user-agent", identity.sendbirdRestUserAgent())
        session.chatToken?.takeIf { it.isNotEmpty() }?.let { b.header("sb-access-token", it) }
        sessionKey?.takeIf { it.isNotEmpty() }?.let { b.header("Session-Key", it) }
        return execute(endpoint, b.build())
    }

    private fun execute(endpoint: HingeEndpoint, request: Request): HingeResponse = try {
        client.newCall(request).execute().use { r ->
            val body = runCatching { r.peekBody(MAX_BODY).string() }.getOrNull()
            log.log("hinge.net.response", mapOf("endpoint" to endpoint.name, "status" to r.code.toString()))
            HingeResponse(r.code, r.header("content-type"), body, r.header("retry-after"), r.headers.names().map { it.lowercase() }.toSet())
        }
    } catch (e: HingeCallRefused) {
        throw e
    } catch (e: IOException) {
        log.log("hinge.net.network_error", mapOf("endpoint" to endpoint.name, "error" to e.javaClass.simpleName))
        HingeResponse(0, null, null, null, emptySet(), networkError = e.javaClass.simpleName)
    }

    /** The OkHttp client for the WebSocket: same gate, budget already counted by the transport. */
    fun webSocketClient(baseClient: OkHttpClient = client): OkHttpClient = baseClient.newBuilder()
        .apply { interceptors().clear() }
        .addInterceptor(HingeGateInterceptor(gate, countBudget = false))
        .pingInterval(0, TimeUnit.SECONDS)
        .build()

    companion object {
        private val JSON = "application/json".toMediaType()
        const val MAX_BODY: Long = 4L * 1024 * 1024
    }
}
