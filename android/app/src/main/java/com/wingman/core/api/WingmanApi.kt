package com.wingman.core.api

import com.wingman.core.model.AgentAction
import com.wingman.core.model.AgentActionType
import com.wingman.core.model.Approval
import com.wingman.core.model.ApprovalDecisionResult
import com.wingman.core.model.ApprovalStatus
import com.wingman.core.model.ApproveRequest
import com.wingman.core.model.AssistedDelivery
import com.wingman.core.model.AssistedDeliveryConfirmRequest
import com.wingman.core.model.AssistedDeliveryResult
import com.wingman.core.model.AssistedDeliveryStatus
import com.wingman.core.model.AutomationActionRequest
import com.wingman.core.model.AutomationMode
import com.wingman.core.model.Connection
import com.wingman.core.model.ConnectionCreateRequest
import com.wingman.core.model.ConnectionUnpairRequest
import com.wingman.core.model.ConnectionUnpairResult
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
import com.wingman.core.model.InboundNotice
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

/** (1.3.0) `listAssistedDeliveries` filters. Null [status] lets the server default to `pending`. */
data class AssistedDeliveryFilter(
    val status: AssistedDeliveryStatus? = null,
    val conversationId: String? = null,
)

data class AgentActionFilter(
    val conversationId: String? = null,
    val contactId: String? = null,
    val type: AgentActionType? = null,
)

/**
 * Typed client for `contracts/openapi.yaml` (v1.0.0, plus the two v1.1.0 device-connector operations
 * `ingestConnectorEvent` and `claimOutboxInstructions`, and the 1.2.0 pairing operations
 * `createConnection`, `getConnection` and `unpairConnection`). One method per `operationId`.
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

    /**
     * `createConnection` (1.2.0, ADR 0007): pairs a connector with the caller's account. `201` (new)
     * and `200` (this install is already paired for the platform) are both [ApiResult.Success] with
     * the connection AS STORED: a client-claimed `VERIFIED` comes back `UNVERIFIED`. Persist [key]
     * before calling and reuse it if the response is lost, so a retry never creates a duplicate.
     */
    suspend fun createConnection(body: ConnectionCreateRequest, key: IdempotencyKey): ApiResult<Connection>

    /** `getConnection` (1.2.0): one connection, paired or unpaired. Another user's is `404`. */
    suspend fun getConnection(connectionId: String): ApiResult<Connection>

    /**
     * `unpairConnection` (1.2.0): effective before the response. The connection becomes
     * `disconnected`, its queued sends, unclaimed instructions and pending approvals are canceled; an
     * instruction already leased to a device cannot be recalled (`in_flight_message_count`).
     */
    suspend fun unpairConnection(
        connectionId: String,
        key: IdempotencyKey,
        body: ConnectionUnpairRequest = ConnectionUnpairRequest,
    ): ApiResult<ConnectionUnpairResult>

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

    // assisted delivery and inbound notices (contract v1.3.0, ADR 0008; WING-027/028)

    /**
     * `listAssistedDeliveries`: drafts waiting for the user to send them by hand, newest first.
     * Nothing listed here was or will be transmitted by Wingman. `text` is private: never log it.
     */
    suspend fun listAssistedDeliveries(
        page: PageRequest = PageRequest.FIRST,
        filter: AssistedDeliveryFilter = AssistedDeliveryFilter(),
    ): ApiResult<Page<AssistedDelivery>>

    /**
     * `confirmAssistedDelivery`: the USER reports they sent it in the platform app. The server
     * records an `assisted_draft` message in `user_reported_sent` (never `sent`/`delivered`).
     * `409 assisted_delivery_not_pending` when it was already confirmed, dismissed or canceled.
     */
    suspend fun confirmAssistedDelivery(
        assistedDeliveryId: String,
        body: AssistedDeliveryConfirmRequest,
        key: IdempotencyKey,
    ): ApiResult<AssistedDeliveryResult>

    /** `dismissAssistedDelivery`: the user will not send it. Nothing is recorded as sent. */
    suspend fun dismissAssistedDelivery(
        assistedDeliveryId: String,
        body: RejectRequest,
        key: IdempotencyKey,
    ): ApiResult<AssistedDeliveryResult>

    /** `listInboundNotices`: matches, likes, expiry and other notices; never reply targets. */
    suspend fun listInboundNotices(
        page: PageRequest = PageRequest.FIRST,
        connectionId: String? = null,
    ): ApiResult<Page<InboundNotice>>
}
