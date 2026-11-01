package com.wingman.core.api.http

import com.wingman.AppInfo
import com.wingman.core.api.AgentActionFilter
import com.wingman.core.api.ApiError
import com.wingman.core.api.ApiResult
import com.wingman.core.api.ApprovalFilter
import com.wingman.core.api.ConversationFilter
import com.wingman.core.api.IdempotencyKey
import com.wingman.core.api.PageRequest
import com.wingman.core.api.WingmanApi
import com.wingman.core.api.WingmanJson
import com.wingman.core.model.AgentAction
import com.wingman.core.model.Approval
import com.wingman.core.model.ApprovalDecisionResult
import com.wingman.core.model.ApproveRequest
import com.wingman.core.model.AutomationActionRequest
import com.wingman.core.model.Connection
import com.wingman.core.model.ContactAutomationState
import com.wingman.core.model.ContactPreferences
import com.wingman.core.model.Conversation
import com.wingman.core.model.ConversationDetail
import com.wingman.core.model.Draft
import com.wingman.core.model.DraftCreateRequest
import com.wingman.core.model.GlobalAutomationState
import com.wingman.core.model.HealthResponse
import com.wingman.core.model.Message
import com.wingman.core.model.Page
import com.wingman.core.model.Preferences
import com.wingman.core.model.PreferencesUpdate
import com.wingman.core.model.Problem
import com.wingman.core.model.RejectRequest
import com.wingman.core.model.SendMessageRequest
import com.wingman.core.model.User
import com.wingman.core.model.wireName
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
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
 * @param baseUrl normalized with [AppInfo.normalizeBaseUrl].
 * @param tokenProvider returns the current bearer JWT (WING-004), or null when signed out.
 */
class HttpWingmanApi(
    baseUrl: String,
    tokenProvider: () -> String? = { null },
    okHttpClient: OkHttpClient? = null,
) : WingmanApi {

    private val service: WingmanService

    init {
        val client = (okHttpClient?.newBuilder() ?: OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS))
            .addInterceptor(authInterceptor(tokenProvider))
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

    override suspend fun listConnections(page: PageRequest): ApiResult<Page<Connection>> =
        call { service.listConnections(page.cursor, page.limit) }

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

    private fun ifMatch(version: Int?): String? = version?.let { "\"$it\"" }

    private suspend fun <T : Any> call(block: suspend () -> Response<T>): ApiResult<T> = try {
        toResult(block())
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
        internal fun authInterceptor(tokenProvider: () -> String?) = Interceptor { chain ->
            val token = tokenProvider()
            val request = chain.request().newBuilder()
                .header("Accept", "application/json, application/problem+json")
                .apply { if (!token.isNullOrBlank()) header("Authorization", "Bearer $token") }
                .build()
            chain.proceed(request)
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
