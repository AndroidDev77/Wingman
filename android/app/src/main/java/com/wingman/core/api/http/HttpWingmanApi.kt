package com.wingman.core.api.http

import com.wingman.AppInfo
import com.wingman.core.api.AgentActionFilter
import com.wingman.core.api.ApiError
import com.wingman.core.api.ApiResult
import com.wingman.core.api.ApprovalFilter
import com.wingman.core.api.AssistedDeliveryFilter
import com.wingman.core.api.ConversationFilter
import com.wingman.core.api.IdempotencyKey
import com.wingman.core.api.PageRequest
import com.wingman.core.api.ProfileQueueFilter
import com.wingman.core.api.WingmanApi
import com.wingman.core.api.WingmanJson
import com.wingman.core.auth.BearerTokenAuthenticator
import com.wingman.core.auth.BearerTokenInterceptor
import com.wingman.core.auth.StaticTokenProvider
import com.wingman.core.auth.TokenProvider
import com.wingman.core.auth.bearerAuth
import com.wingman.core.model.AgentAction
import com.wingman.core.model.Approval
import com.wingman.core.model.ApprovalDecisionResult
import com.wingman.core.model.ApproveRequest
import com.wingman.core.model.AssistedDelivery
import com.wingman.core.model.AssistedDeliveryConfirmRequest
import com.wingman.core.model.AssistedDeliveryResult
import com.wingman.core.model.AutomationActionRequest
import com.wingman.core.model.Connection
import com.wingman.core.model.ConnectionSettingsUpdate
import com.wingman.core.model.ConnectionCreateRequest
import com.wingman.core.model.ConnectionUnpairRequest
import com.wingman.core.model.ConnectionUnpairResult
import com.wingman.core.model.ConnectorEventIngestResult
import com.wingman.core.model.ContactAutomationState
import com.wingman.core.model.ContactPreferenceOverrideUpdate
import com.wingman.core.model.ContactPreferences
import com.wingman.core.model.Conversation
import com.wingman.core.model.ConversationDetail
import com.wingman.core.model.Draft
import com.wingman.core.model.DraftCreateRequest
import com.wingman.core.model.GlobalAutomationState
import com.wingman.core.model.HealthResponse
import com.wingman.core.model.InboundNotice
import com.wingman.core.model.Message
import com.wingman.core.model.OutboxClaimRequest
import com.wingman.core.model.OutboxClaimResult
import com.wingman.core.model.Page
import com.wingman.core.model.Preferences
import com.wingman.core.model.Profile
import com.wingman.core.model.ProfileAction
import com.wingman.core.model.ProfileDecisionRequest
import com.wingman.core.model.ProfileImportRequest
import com.wingman.core.model.PreferencesUpdate
import com.wingman.core.model.Problem
import com.wingman.core.model.RejectRequest
import com.wingman.core.model.SendMessageRequest
import com.wingman.core.model.User
import com.wingman.core.model.wireName
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Retrofit + OkHttp implementation of [WingmanApi]. Pure JVM (no android.* imports), so it is
 * unit-tested against MockWebServer.
 *
 * Auth (I-011): [BearerTokenInterceptor] adds `Authorization: Bearer` from [tokenProvider] when it
 * has a token, and [BearerTokenAuthenticator] answers a `401` with one refresh and one retry
 * (a second `401` signs the session out). See core/auth.
 *
 * @param baseUrl normalized with [AppInfo.normalizeBaseUrl].
 * @param tokenProvider the session's bearer token source; [StaticTokenProvider] for a fixed token.
 */
class HttpWingmanApi(
    baseUrl: String,
    tokenProvider: TokenProvider,
    okHttpClient: OkHttpClient? = null,
) : WingmanApi {

    /** Fixed token (or none): tests and previews. A 401 is returned unchanged. */
    constructor(baseUrl: String, tokenProvider: () -> String? = { null }, okHttpClient: OkHttpClient? = null) :
        this(baseUrl, StaticTokenProvider(tokenProvider), okHttpClient)

    private val service: WingmanService

    init {
        val client = (okHttpClient?.newBuilder() ?: OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS))
            .addInterceptor(acceptInterceptor)
            .bearerAuth(tokenProvider)
            .build()
        service = Retrofit.Builder()
            .baseUrl(AppInfo.normalizeBaseUrl(baseUrl))
            .client(client)
            .addConverterFactory(WingmanJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(WingmanService::class.java)
    }

    override suspend fun getHealth(): ApiResult<HealthResponse> = call { service.getHealth() }
    override suspend fun getMe(): ApiResult<User> = call { service.getMe() }

    override suspend fun getPreferences(): ApiResult<Preferences> = call { service.getPreferences() }
    override suspend fun putPreferences(body: PreferencesUpdate, ifMatchVersion: Int?): ApiResult<Preferences> =
        call { service.putPreferences(ifMatch(ifMatchVersion), body) }
    override suspend fun getContactPreferences(contactId: String): ApiResult<ContactPreferences> =
        call { service.getContactPreferences(contactId) }
    override suspend fun listPreferenceVersions(page: PageRequest): ApiResult<Page<Preferences>> =
        call { service.listPreferenceVersions(page.cursor, page.limit) }
    override suspend fun putContactPreferences(
        contactId: String,
        body: ContactPreferenceOverrideUpdate,
        ifMatchVersion: Int?,
    ): ApiResult<ContactPreferences> = call { service.putContactPreferences(contactId, ifMatch(ifMatchVersion), body) }
    override suspend fun deleteContactPreferences(contactId: String): ApiResult<Unit> =
        callNoContent { service.deleteContactPreferences(contactId) }

    override suspend fun listConnections(page: PageRequest): ApiResult<Page<Connection>> =
        call { service.listConnections(page.cursor, page.limit) }

    override suspend fun createConnection(body: ConnectionCreateRequest, key: IdempotencyKey): ApiResult<Connection> =
        call { service.createConnection(key.value, body) }

    override suspend fun getConnection(connectionId: String): ApiResult<Connection> =
        call { service.getConnection(connectionId) }

    override suspend fun unpairConnection(
        connectionId: String,
        key: IdempotencyKey,
        body: ConnectionUnpairRequest,
    ): ApiResult<ConnectionUnpairResult> = call { service.unpairConnection(connectionId, key.value, body) }

    override suspend fun listConversations(page: PageRequest, filter: ConversationFilter): ApiResult<Page<Conversation>> =
        call {
            service.listConversations(
                page.cursor, page.limit, filter.platform,
                filter.intent?.wireName(), filter.automationMode?.wireName(), filter.stage?.wireName(),
            )
        }

    override suspend fun getConversation(conversationId: String): ApiResult<ConversationDetail> =
        call { service.getConversation(conversationId) }

    override suspend fun listMessages(conversationId: String, page: PageRequest): ApiResult<Page<Message>> =
        call { service.listMessages(conversationId, page.cursor, page.limit) }

    override suspend fun sendMessage(conversationId: String, body: SendMessageRequest, key: IdempotencyKey): ApiResult<Message> =
        call { service.sendMessage(conversationId, key.value, body) }

    override suspend fun createDraft(conversationId: String, body: DraftCreateRequest, key: IdempotencyKey): ApiResult<Draft> =
        call { service.createDraft(conversationId, key.value, body) }

    override suspend fun listApprovals(page: PageRequest, filter: ApprovalFilter): ApiResult<Page<Approval>> =
        call { service.listApprovals(page.cursor, page.limit, filter.status?.wireName(), filter.conversationId, filter.contactId) }

    override suspend fun getApproval(approvalId: String): ApiResult<Approval> = call { service.getApproval(approvalId) }

    override suspend fun approveApproval(approvalId: String, body: ApproveRequest, key: IdempotencyKey): ApiResult<ApprovalDecisionResult> =
        call { service.approveApproval(approvalId, key.value, body) }

    override suspend fun rejectApproval(approvalId: String, body: RejectRequest, key: IdempotencyKey): ApiResult<ApprovalDecisionResult> =
        call { service.rejectApproval(approvalId, key.value, body) }

    override suspend fun getGlobalAutomationState(): ApiResult<GlobalAutomationState> = call { service.getGlobalAutomationState() }

    override suspend fun pauseAllAutomation(body: AutomationActionRequest, key: IdempotencyKey): ApiResult<GlobalAutomationState> =
        call { service.pauseAllAutomation(key.value, body) }

    override suspend fun resumeAllAutomation(body: AutomationActionRequest, key: IdempotencyKey): ApiResult<GlobalAutomationState> =
        call { service.resumeAllAutomation(key.value, body) }

    override suspend fun getContactAutomationState(contactId: String): ApiResult<ContactAutomationState> =
        call { service.getContactAutomationState(contactId) }

    override suspend fun pauseContactAutomation(contactId: String, body: AutomationActionRequest, key: IdempotencyKey): ApiResult<ContactAutomationState> =
        call { service.pauseContactAutomation(contactId, key.value, body) }

    override suspend fun resumeContactAutomation(contactId: String, body: AutomationActionRequest, key: IdempotencyKey): ApiResult<ContactAutomationState> =
        call { service.resumeContactAutomation(contactId, key.value, body) }

    override suspend fun takeoverContact(contactId: String, body: AutomationActionRequest, key: IdempotencyKey): ApiResult<ContactAutomationState> =
        call { service.takeoverContact(contactId, key.value, body) }

    override suspend fun listAgentActions(page: PageRequest, filter: AgentActionFilter): ApiResult<Page<AgentAction>> =
        call { service.listAgentActions(page.cursor, page.limit, filter.conversationId, filter.contactId, filter.type?.wireName()) }

    override suspend fun ingestConnectorEvent(connectionId: String, envelope: JsonObject, key: IdempotencyKey): ApiResult<ConnectorEventIngestResult> {
        eventKeyMismatch(envelope, key)?.let { return it }
        return call { service.ingestConnectorEvent(connectionId, key.value, envelope) }
    }

    override suspend fun claimOutboxInstructions(connectionId: String, body: OutboxClaimRequest, key: IdempotencyKey): ApiResult<OutboxClaimResult> =
        call { service.claimOutboxInstructions(connectionId, key.value, body) }

    override suspend fun listAssistedDeliveries(page: PageRequest, filter: AssistedDeliveryFilter): ApiResult<Page<AssistedDelivery>> =
        call { service.listAssistedDeliveries(page.cursor, page.limit, filter.status?.wireName(), filter.conversationId) }

    override suspend fun confirmAssistedDelivery(
        assistedDeliveryId: String,
        body: AssistedDeliveryConfirmRequest,
        key: IdempotencyKey,
    ): ApiResult<AssistedDeliveryResult> = call { service.confirmAssistedDelivery(assistedDeliveryId, key.value, body) }

    override suspend fun dismissAssistedDelivery(
        assistedDeliveryId: String,
        body: RejectRequest,
        key: IdempotencyKey,
    ): ApiResult<AssistedDeliveryResult> = call { service.dismissAssistedDelivery(assistedDeliveryId, key.value, body) }

    override suspend fun listInboundNotices(page: PageRequest, connectionId: String?): ApiResult<Page<InboundNotice>> =
        call { service.listInboundNotices(page.cursor, page.limit, connectionId) }

    override suspend fun importProfile(body: ProfileImportRequest, key: IdempotencyKey): ApiResult<Profile> =
        call { service.importProfile(key.value, body) }

    override suspend fun listProfileReviewQueue(page: PageRequest, filter: ProfileQueueFilter): ApiResult<Page<Profile>> =
        call {
            service.listProfileReviewQueue(
                page.cursor, page.limit, filter.provider?.wireName(),
                // Sent only when true: absent is the contract default (false).
                filter.includeExcluded.takeIf { it },
            )
        }

    override suspend fun recordProfileDecision(profileId: String, body: ProfileDecisionRequest, key: IdempotencyKey): ApiResult<ProfileAction> =
        call { service.recordProfileDecision(profileId, key.value, body) }

    override suspend fun requestProfileActionExecution(profileActionId: String, key: IdempotencyKey): ApiResult<ProfileAction> =
        call { service.requestProfileActionExecution(profileActionId, key.value, ConnectionUnpairRequest) }

    override suspend fun putConnectionSettings(connectionId: String, body: ConnectionSettingsUpdate): ApiResult<Connection> =
        call { service.putConnectionSettings(connectionId, body) }

    private fun ifMatch(version: Int?): String? = version?.let { "\"$it\"" }

    private suspend fun <T : Any> call(block: suspend () -> Response<T>): ApiResult<T> = guard { toResult(block()) }

    /** For 204 responses: Retrofit gives a null body, which [toResult] would treat as a decoding error. */
    private suspend fun callNoContent(block: suspend () -> Response<Unit>): ApiResult<Unit> = guard {
        val response = block()
        if (response.isSuccessful) ApiResult.Success(Unit) else toResult(response)
    }

    private suspend fun <T> guard(block: suspend () -> ApiResult<T>): ApiResult<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        ApiResult.Failure(ApiError.Network(e))
    } catch (e: SerializationException) {
        ApiResult.Failure(ApiError.Decoding(e))
    } catch (e: IllegalArgumentException) {
        // kotlinx.serialization reports some shape mismatches as IllegalArgumentException.
        ApiResult.Failure(ApiError.Decoding(e))
    }

    companion object {
        /** `ingestConnectorEvent` requires `Idempotency-Key` == the envelope's `event_id`; refuse locally otherwise. */
        internal fun eventKeyMismatch(envelope: JsonObject, key: IdempotencyKey): ApiResult.Failure? {
            val eventId = (envelope["event_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            return if (eventId == key.value) null else ApiResult.Failure(ApiError.Client("Idempotency-Key must equal the envelope's event_id"))
        }

        private val acceptInterceptor = Interceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("Accept", "application/json, application/problem+json").build())
        }

        internal fun <T : Any> toResult(response: Response<T>): ApiResult<T> {
            if (response.isSuccessful) {
                val body = response.body()
                    ?: return ApiResult.Failure(ApiError.Decoding(IllegalStateException("Empty body on ${response.code()}")))
                val replayed = response.headers()["Idempotent-Replayed"].equals("true", ignoreCase = true)
                return ApiResult.Success(body, idempotentReplayed = replayed)
            }
            val raw = runCatching { response.errorBody()?.string() }.getOrNull()
            return ApiResult.Failure(
                ApiError.Http(
                    status = response.code(),
                    problem = ProblemParser.parse(raw),
                    retryAfterSeconds = response.headers()["Retry-After"]?.trim()?.toLongOrNull(),
                ),
            )
        }
    }
}

/** Parses an `application/problem+json` body; returns null for anything that isn't one. */
object ProblemParser {
    fun parse(raw: String?): Problem? {
        if (raw.isNullOrBlank()) return null
        return try {
            WingmanJson.decodeFromString(Problem.serializer(), raw)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
