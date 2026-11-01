package com.wingman.core.api

import com.wingman.core.model.AgentAction
import com.wingman.core.model.AgentActionType
import com.wingman.core.model.Approval
import com.wingman.core.model.ApprovalDecisionResult
import com.wingman.core.model.ApprovalStatus
import com.wingman.core.model.ApproveRequest
import com.wingman.core.model.AutomationActionRequest
import com.wingman.core.model.AutomationMode
import com.wingman.core.model.Connection
import com.wingman.core.model.ConnectorEventIngestResult
import com.wingman.core.model.ContactAutomationState
import com.wingman.core.model.ContactPreferenceOverrideUpdate
import com.wingman.core.model.ContactPreferences
import com.wingman.core.model.Conversation
import com.wingman.core.model.ConversationDetail
import com.wingman.core.model.ConversationStage
import com.wingman.core.model.Draft
import com.wingman.core.model.DraftCreateRequest
import com.wingman.core.model.GlobalAutomationState
import com.wingman.core.model.HealthResponse
import com.wingman.core.model.IntentMode
import com.wingman.core.model.Message
import com.wingman.core.model.OutboxClaimRequest
import com.wingman.core.model.OutboxClaimResult
import com.wingman.core.model.Page
import com.wingman.core.model.Preferences
import com.wingman.core.model.PreferencesUpdate
import com.wingman.core.model.RejectRequest
import com.wingman.core.model.SendMessageRequest
import com.wingman.core.model.User
import kotlinx.serialization.json.JsonObject

/** Request for one page. `cursor == null` is the first page; `limit` is 1..100 (server default 50). */
data class PageRequest(val cursor: String? = null, val limit: Int? = null) {
    init {
        require(limit == null || limit in 1..100) { "limit must be 1..100" }
        require(cursor == null || cursor.length in 1..512) { "cursor must be 1..512 chars" }
    }

    companion object {
        val FIRST = PageRequest()
    }
}

data class ConversationFilter(
    val platform: String? = null,
    val intent: IntentMode? = null,
    val automationMode: AutomationMode? = null,
    val stage: ConversationStage? = null,
)

data class ApprovalFilter(
    /** Null lets the server default to `pending`. */
    val status: ApprovalStatus? = null,
    val conversationId: String? = null,
    val contactId: String? = null,
)

data class AgentActionFilter(
    val conversationId: String? = null,
    val contactId: String? = null,
    val type: AgentActionType? = null,
)

/**
 * Typed client for `contracts/openapi.yaml` (v1.0.0, plus the two v1.1.0 device-connector operations
 * `ingestConnectorEvent` and `claimOutboxInstructions`). One method per `operationId`.
 *
 * Rules every implementation follows:
 *  - Never throws for HTTP, network or decoding failures: they come back as [ApiResult.Failure].
 *  - Every mutating (POST) call takes an [IdempotencyKey] that the CALLER generates once per user
 *    action (see [IdempotencyKeyRegistry]) and reuses across retries.
 *  - Preferences PUTs take an optional `ifMatchVersion`, sent as `If-Match: "<version>"`.
 *
 * Implementations: [com.wingman.core.api.http.HttpWingmanApi] (Retrofit) and
 * [com.wingman.core.api.mock.MockWingmanApi] (offline fixtures).
 */
interface WingmanApi {
    // health / users
    suspend fun getHealth(): ApiResult<HealthResponse>
    suspend fun getMe(): ApiResult<User>

    // preferences (WING-013)
    suspend fun getPreferences(): ApiResult<Preferences>
    suspend fun putPreferences(body: PreferencesUpdate, ifMatchVersion: Int? = null): ApiResult<Preferences>
    suspend fun getContactPreferences(contactId: String): ApiResult<ContactPreferences>

    /** `listPreferenceVersions`: user-wide history, newest first (contract `PreferencesPage`). */
    suspend fun listPreferenceVersions(page: PageRequest = PageRequest.FIRST): ApiResult<Page<Preferences>>

    /** `putContactPreferences`: replaces the per-contact override; never writes user-wide preferences. */
    suspend fun putContactPreferences(
        contactId: String,
        body: ContactPreferenceOverrideUpdate,
        ifMatchVersion: Int? = null,
    ): ApiResult<ContactPreferences>

    /** `deleteContactPreferences`: clears the override (204, also when none existed). */
    suspend fun deleteContactPreferences(contactId: String): ApiResult<Unit>

    // connections
    suspend fun listConnections(page: PageRequest = PageRequest.FIRST): ApiResult<Page<Connection>>

    // conversations and messages (WING-014)
    suspend fun listConversations(
        page: PageRequest = PageRequest.FIRST,
        filter: ConversationFilter = ConversationFilter(),
    ): ApiResult<Page<Conversation>>

    suspend fun getConversation(conversationId: String): ApiResult<ConversationDetail>
    suspend fun listMessages(conversationId: String, page: PageRequest = PageRequest.FIRST): ApiResult<Page<Message>>
    suspend fun sendMessage(conversationId: String, body: SendMessageRequest, key: IdempotencyKey): ApiResult<Message>
    suspend fun createDraft(conversationId: String, body: DraftCreateRequest, key: IdempotencyKey): ApiResult<Draft>

    // approvals (WING-015)
    suspend fun listApprovals(
        page: PageRequest = PageRequest.FIRST,
        filter: ApprovalFilter = ApprovalFilter(),
    ): ApiResult<Page<Approval>>

    suspend fun getApproval(approvalId: String): ApiResult<Approval>
    suspend fun approveApproval(approvalId: String, body: ApproveRequest, key: IdempotencyKey): ApiResult<ApprovalDecisionResult>
    suspend fun rejectApproval(approvalId: String, body: RejectRequest, key: IdempotencyKey): ApiResult<ApprovalDecisionResult>

    // automation (WING-015)
    suspend fun getGlobalAutomationState(): ApiResult<GlobalAutomationState>
    suspend fun pauseAllAutomation(body: AutomationActionRequest, key: IdempotencyKey): ApiResult<GlobalAutomationState>
    suspend fun resumeAllAutomation(body: AutomationActionRequest, key: IdempotencyKey): ApiResult<GlobalAutomationState>
    suspend fun getContactAutomationState(contactId: String): ApiResult<ContactAutomationState>
    suspend fun pauseContactAutomation(contactId: String, body: AutomationActionRequest, key: IdempotencyKey): ApiResult<ContactAutomationState>
    suspend fun resumeContactAutomation(contactId: String, body: AutomationActionRequest, key: IdempotencyKey): ApiResult<ContactAutomationState>
    suspend fun takeoverContact(contactId: String, body: AutomationActionRequest, key: IdempotencyKey): ApiResult<ContactAutomationState>

    // audit (WING-015)
    suspend fun listAgentActions(
        page: PageRequest = PageRequest.FIRST,
        filter: AgentActionFilter = AgentActionFilter(),
    ): ApiResult<Page<AgentAction>>

    // device-side connectors (contract v1.1.0, ADR 0006; used by the WING-016 SMS connector)

    /**
     * `ingestConnectorEvent`: reports one `connector_event.v1` [envelope]. [key] MUST equal the
     * envelope's `event_id` (implementations return [ApiError.Client] otherwise, without a request).
     * `202 accepted` and `200 duplicate_*` are both [ApiResult.Success]; branch on
     * [ConnectorEventIngestResult.outcome]. The envelope may hold private text: never log it.
     */
    suspend fun ingestConnectorEvent(connectionId: String, envelope: JsonObject, key: IdempotencyKey): ApiResult<ConnectorEventIngestResult>

    /**
     * `claimOutboxInstructions`: leases dispatcher-created send instructions to this install.
     * Persist [key] before calling and reuse it if the response is lost: a replay returns the
     * original instructions with `idempotentReplayed = true`.
     */
    suspend fun claimOutboxInstructions(connectionId: String, body: OutboxClaimRequest, key: IdempotencyKey): ApiResult<OutboxClaimResult>
}
