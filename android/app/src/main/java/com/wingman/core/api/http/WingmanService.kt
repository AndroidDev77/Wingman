package com.wingman.core.api.http

import com.wingman.core.model.AgentAction
import com.wingman.core.model.Approval
import com.wingman.core.model.ApprovalDecisionResult
import com.wingman.core.model.ApproveRequest
import com.wingman.core.model.AutomationActionRequest
import com.wingman.core.model.Connection
import com.wingman.core.model.ContactAutomationState
import com.wingman.core.model.ContactPreferenceOverrideUpdate
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
import com.wingman.core.model.RejectRequest
import com.wingman.core.model.SendMessageRequest
import com.wingman.core.model.User
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit binding of contracts/openapi.yaml v1.0.0. Paths are relative (no leading slash) so they
 * resolve against the normalized base URL. Internal: callers use [com.wingman.core.api.WingmanApi].
 */
internal interface WingmanService {
    @GET("health")
    suspend fun getHealth(): Response<HealthResponse>

    @GET("v1/me")
    suspend fun getMe(): Response<User>

    @GET("v1/preferences")
    suspend fun getPreferences(): Response<Preferences>

    @PUT("v1/preferences")
    suspend fun putPreferences(@Header("If-Match") ifMatch: String?, @Body body: PreferencesUpdate): Response<Preferences>

    @GET("v1/contacts/{contact_id}/preferences")
    suspend fun getContactPreferences(@Path("contact_id") contactId: String): Response<ContactPreferences>

    @GET("v1/preferences/versions")
    suspend fun listPreferenceVersions(
        @Query("cursor") cursor: String?,
        @Query("limit") limit: Int?,
    ): Response<Page<Preferences>>

    @PUT("v1/contacts/{contact_id}/preferences")
    suspend fun putContactPreferences(
        @Path("contact_id") contactId: String,
        @Header("If-Match") ifMatch: String?,
        @Body body: ContactPreferenceOverrideUpdate,
    ): Response<ContactPreferences>

    @DELETE("v1/contacts/{contact_id}/preferences")
    suspend fun deleteContactPreferences(@Path("contact_id") contactId: String): Response<Unit>

    @GET("v1/connections")
    suspend fun listConnections(
        @Query("cursor") cursor: String?,
        @Query("limit") limit: Int?,
    ): Response<Page<Connection>>

    @GET("v1/conversations")
    suspend fun listConversations(
        @Query("cursor") cursor: String?,
        @Query("limit") limit: Int?,
        @Query("platform") platform: String?,
        @Query("intent") intent: String?,
        @Query("automation_mode") automationMode: String?,
        @Query("stage") stage: String?,
    ): Response<Page<Conversation>>

    @GET("v1/conversations/{conversation_id}")
    suspend fun getConversation(@Path("conversation_id") conversationId: String): Response<ConversationDetail>

    @GET("v1/conversations/{conversation_id}/messages")
    suspend fun listMessages(
        @Path("conversation_id") conversationId: String,
        @Query("cursor") cursor: String?,
        @Query("limit") limit: Int?,
    ): Response<Page<Message>>

    @POST("v1/conversations/{conversation_id}/messages")
    suspend fun sendMessage(
        @Path("conversation_id") conversationId: String,
        @Header("Idempotency-Key") key: String,
        @Body body: SendMessageRequest,
    ): Response<Message>

    @POST("v1/conversations/{conversation_id}/drafts")
    suspend fun createDraft(
        @Path("conversation_id") conversationId: String,
        @Header("Idempotency-Key") key: String,
        @Body body: DraftCreateRequest,
    ): Response<Draft>

    @GET("v1/approvals")
    suspend fun listApprovals(
        @Query("cursor") cursor: String?,
        @Query("limit") limit: Int?,
        @Query("status") status: String?,
        @Query("conversation_id") conversationId: String?,
        @Query("contact_id") contactId: String?,
    ): Response<Page<Approval>>

    @GET("v1/approvals/{approval_id}")
    suspend fun getApproval(@Path("approval_id") approvalId: String): Response<Approval>

    @POST("v1/approvals/{approval_id}/approve")
    suspend fun approveApproval(
        @Path("approval_id") approvalId: String,
        @Header("Idempotency-Key") key: String,
        @Body body: ApproveRequest,
    ): Response<ApprovalDecisionResult>

    @POST("v1/approvals/{approval_id}/reject")
    suspend fun rejectApproval(
        @Path("approval_id") approvalId: String,
        @Header("Idempotency-Key") key: String,
        @Body body: RejectRequest,
    ): Response<ApprovalDecisionResult>

    @GET("v1/automation")
    suspend fun getGlobalAutomationState(): Response<GlobalAutomationState>

    @POST("v1/automation/pause")
    suspend fun pauseAllAutomation(
        @Header("Idempotency-Key") key: String,
        @Body body: AutomationActionRequest,
    ): Response<GlobalAutomationState>

    @POST("v1/automation/resume")
    suspend fun resumeAllAutomation(
        @Header("Idempotency-Key") key: String,
        @Body body: AutomationActionRequest,
    ): Response<GlobalAutomationState>

    @GET("v1/contacts/{contact_id}/automation")
    suspend fun getContactAutomationState(@Path("contact_id") contactId: String): Response<ContactAutomationState>

    @POST("v1/contacts/{contact_id}/automation/pause")
    suspend fun pauseContactAutomation(
        @Path("contact_id") contactId: String,
        @Header("Idempotency-Key") key: String,
        @Body body: AutomationActionRequest,
    ): Response<ContactAutomationState>

    @POST("v1/contacts/{contact_id}/automation/resume")
    suspend fun resumeContactAutomation(
        @Path("contact_id") contactId: String,
        @Header("Idempotency-Key") key: String,
        @Body body: AutomationActionRequest,
    ): Response<ContactAutomationState>

    @POST("v1/contacts/{contact_id}/automation/takeover")
    suspend fun takeoverContact(
        @Path("contact_id") contactId: String,
        @Header("Idempotency-Key") key: String,
        @Body body: AutomationActionRequest,
    ): Response<ContactAutomationState>

    @GET("v1/agent-actions")
    suspend fun listAgentActions(
        @Query("cursor") cursor: String?,
        @Query("limit") limit: Int?,
        @Query("conversation_id") conversationId: String?,
        @Query("contact_id") contactId: String?,
        @Query("type") type: String?,
    ): Response<Page<AgentAction>>
}
