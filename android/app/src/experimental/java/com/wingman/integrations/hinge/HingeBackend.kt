package com.wingman.integrations.hinge

import com.wingman.core.api.ApiError
import com.wingman.core.api.ApiResult
import com.wingman.core.api.IdempotencyKey
import com.wingman.core.api.WingmanApi
import com.wingman.core.api.WingmanJson
import com.wingman.core.api.http.ProblemParser
import com.wingman.core.auth.TokenProvider
import com.wingman.core.auth.bearerAuth
import com.wingman.core.model.Connection
import com.wingman.core.model.ConnectionUnpairResult
import com.wingman.core.model.ConnectorEventIngestResult
import com.wingman.core.model.OutboxClaimRequest
import com.wingman.core.model.OutboxClaimResult
import com.wingman.core.model.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The WINGMAN backend operations the Hinge connector uses. Nothing in here carries a Hinge or
 * Sendbird credential: pairing sends the platform, display name, install id, the capability
 * claim and the consent record (contract 1.3.0 `ConnectionCreateRequest`); ingest sends
 * `connector_event.v1` envelopes; claims send the install id. The static and dynamic tests in
 * `HingeTokenIsolationTest` check that no session value ever reaches these calls.
 */
interface HingeBackend {
    suspend fun me(): ApiResult<User>
    /** `createConnection` with `platform: hinge`, `transport: unofficial_api`, `experimental: true` and the consent. */
    suspend fun pairExperimental(body: JsonObject, key: IdempotencyKey): ApiResult<Connection>
    suspend fun getConnection(connectionId: String): ApiResult<Connection>
    /** `revokeExperimentalConsent` (1.3.0): unpairs and records `revoked_at`. */
    suspend fun revokeExperimentalConsent(connectionId: String, key: IdempotencyKey): ApiResult<ConnectionUnpairResult>
    suspend fun ingest(connectionId: String, envelope: JsonObject, key: IdempotencyKey): ApiResult<ConnectorEventIngestResult>
    suspend fun claim(connectionId: String, body: OutboxClaimRequest, key: IdempotencyKey): ApiResult<OutboxClaimResult>
}

/**
 * Production [HingeBackend]: the existing typed [WingmanApi] for `getMe`, `getConnection`,
 * `ingestConnectorEvent` and `claimOutboxInstructions`, plus two raw calls for the 1.3.0
 * operations the Android client does not have yet (`createConnection` with the 1.3.0 fields, and
 * `revokeExperimentalConsent`). Those two use the same base URL and the same bearer session as
 * [com.wingman.core.api.http.HttpWingmanApi]. When the integrator adds them to [WingmanApi]
 * (handoff), this class can delegate them too; the interface does not change.
 */
class HttpHingeBackend(
    private val api: WingmanApi,
    baseUrl: String,
    tokenProvider: TokenProvider,
    okHttpClient: OkHttpClient? = null,
) : HingeBackend {
    private val base = baseUrl.trimEnd('/') + "/"
    private val http: OkHttpClient = (okHttpClient ?: OkHttpClient()).newBuilder()
        .bearerAuth(tokenProvider)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun me(): ApiResult<User> = api.getMe()

    override suspend fun getConnection(connectionId: String): ApiResult<Connection> = api.getConnection(connectionId)

    override suspend fun ingest(connectionId: String, envelope: JsonObject, key: IdempotencyKey) = api.ingestConnectorEvent(connectionId, envelope, key)

    override suspend fun claim(connectionId: String, body: OutboxClaimRequest, key: IdempotencyKey) = api.claimOutboxInstructions(connectionId, body, key)

    override suspend fun pairExperimental(body: JsonObject, key: IdempotencyKey): ApiResult<Connection> =
        post("v1/connections", body, key) { WingmanJson.decodeFromString(Connection.serializer(), it) }

    override suspend fun revokeExperimentalConsent(connectionId: String, key: IdempotencyKey): ApiResult<ConnectionUnpairResult> {
        if (!ID.matches(connectionId)) return ApiResult.Failure(ApiError.Client("invalid connection id"))
        return post("v1/connections/$connectionId/experimental-consent/revoke", JsonObject(emptyMap()), key) {
            WingmanJson.decodeFromString(ConnectionUnpairResult.serializer(), it)
        }
    }

    private suspend fun <T> post(path: String, body: JsonObject, key: IdempotencyKey, decode: (String) -> T): ApiResult<T> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(base + path)
            .header(IdempotencyKey.HEADER, key.value)
            .post(body.toString().toRequestBody(JSON))
            .build()
        try {
            http.newCall(request).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (r.isSuccessful) {
                    runCatching { ApiResult.Success(decode(text), r.header("Idempotent-Replayed") == "true") }
                        .getOrElse { ApiResult.Failure(ApiError.Decoding(it)) }
                } else {
                    ApiResult.Failure(ApiError.Http(r.code, ProblemParser.parse(text), r.header("Retry-After")?.toLongOrNull()))
                }
            }
        } catch (e: IOException) {
            ApiResult.Failure(ApiError.Network(e))
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    }
}
