package com.wingman.core.api.mock

import com.wingman.core.api.AgentActionFilter
import com.wingman.core.api.ApiError
import com.wingman.core.api.ApiResult
import com.wingman.core.api.ApprovalFilter
import com.wingman.core.api.ConversationFilter
import com.wingman.core.api.IdempotencyKey
import com.wingman.core.api.PageRequest
import com.wingman.core.api.WingmanApi
import com.wingman.core.model.Actor
import com.wingman.core.model.AgentAction
import com.wingman.core.model.AgentActionType
import com.wingman.core.model.Approval
import com.wingman.core.model.ApprovalDecisionResult
import com.wingman.core.model.ApprovalReason
import com.wingman.core.model.ApprovalStatus
import com.wingman.core.model.ApproveRequest
import com.wingman.core.model.AutomationActionRequest
import com.wingman.core.model.Connection
import com.wingman.core.model.ContactAutomationState
import com.wingman.core.model.ContactAutomationStatus
import com.wingman.core.model.ContactPreferenceOverride
import com.wingman.core.model.ContactPreferences
import com.wingman.core.model.Conversation
import com.wingman.core.model.ConversationDetail
import com.wingman.core.model.DeliveryState
import com.wingman.core.model.Draft
import com.wingman.core.model.DraftCreateRequest
import com.wingman.core.model.DraftDecision
import com.wingman.core.model.DraftGrounding
import com.wingman.core.model.ErrorCode
import com.wingman.core.model.GlobalAutomationState
import com.wingman.core.model.GlobalAutomationStatus
import com.wingman.core.model.HealthResponse
import com.wingman.core.model.Message
import com.wingman.core.model.MessageDirection
import com.wingman.core.model.MessageOrigin
import com.wingman.core.model.Page
import com.wingman.core.model.Preferences
import com.wingman.core.model.PreferencesUpdate
import com.wingman.core.model.Problem
import com.wingman.core.model.RejectRequest
import com.wingman.core.model.SendMessageRequest
import com.wingman.core.model.User
import com.wingman.core.model.Verification
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.time.Instant

/**
 * In-memory, offline [WingmanApi] seeded from [MockFixtures]. Works with no backend, for
 * previews, demo builds and tests. It mirrors the contract's behavior closely enough for UI work:
 *
 *  - opaque cursor pagination (`next_cursor: null` at the end);
 *  - idempotency replay: same key + same body returns the stored result with
 *    `idempotentReplayed = true`; same key + different body is `422 idempotency_key_mismatch`;
 *  - problem errors: unknown IDs are `404 not_found`, deciding a non-pending approval is
 *    `409 approval_not_pending`, sending on an UNSUPPORTED connection is `422 capability_unsupported`;
 *  - delivery truth: sends and approvals return `queued` and are never advanced to `sent`;
 *  - takeover cancels the contact's pending approvals; pause/resume update state.
 *
 * Test hooks: [failNextWith] makes the next call fail, [failAllWith] makes every call fail,
 * [latencyMs] adds an artificial delay. [calls] records the operations invoked.
 */
class MockWingmanApi(
    private val clock: () -> Instant = { MockFixtures.NOW },
    var latencyMs: Long = 0,
) : WingmanApi {

    private val mutex = Mutex()
    private var idCounter = 0

    private var preferences: Preferences = MockFixtures.defaultPreferences
    private val connections = MockFixtures.connections.toMutableList()
    private val conversations = MockFixtures.conversations.toMutableList()
    private val messages = MockFixtures.messages.mapValues { it.value.toMutableList() }.toMutableMap()
    private val approvals = MockFixtures.approvals.toMutableList()
    private val auditLog = mutableListOf<AgentAction>()
    private var globalState = GlobalAutomationState(GlobalAutomationStatus.ACTIVE, changedAt = null)
    private val contactStates = mutableMapOf<String, ContactAutomationState>()
    private val idempotency = mutableMapOf<String, Pair<Any, ApiResult<*>>>()

    private var failNext: ApiError? = null
    private var failAll: ApiError? = null

    /** Operation IDs (contract `operationId`) in call order. */
    val calls: MutableList<String> = mutableListOf()

    fun failNextWith(error: ApiError = networkError()) { failNext = error }
    fun failAllWith(error: ApiError?) { failAll = error }

    /* ------------------------------------------------------------ reads */

    override suspend fun getHealth(): ApiResult<HealthResponse> = op("getHealth") { ok(MockFixtures.health.copy(time = clock())) }

    override suspend fun getMe(): ApiResult<User> = op("getMe") { ok(MockFixtures.me) }

    override suspend fun getPreferences(): ApiResult<Preferences> = op("getPreferences") { ok(preferences) }

    override suspend fun putPreferences(body: PreferencesUpdate, ifMatchVersion: Int?): ApiResult<Preferences> =
        op("putPreferences") {
            if (ifMatchVersion != null && ifMatchVersion != preferences.version) {
                return@op problem(412, ErrorCode.PRECONDITION_FAILED, "Preferences changed since version $ifMatchVersion")
            }
            preferences = Preferences(
                version = preferences.version + 1,
                intent = body.intent,
                dealbreakers = body.dealbreakers ?: emptyList(),
                style = body.style,
                automation = body.automation,
                updatedAt = clock(),
            )
            audit(AgentActionType.PREFERENCES_UPDATED, Actor.USER)
            ok(preferences)
        }

    override suspend fun getContactPreferences(contactId: String): ApiResult<ContactPreferences> =
        op("getContactPreferences") {
            if (conversations.none { it.contact.id == contactId }) return@op notFound()
            ok(ContactPreferences(contactId, ContactPreferenceOverride(version = 0), preferences))
        }

    override suspend fun listConnections(page: PageRequest): ApiResult<Page<Connection>> =
        op("listConnections") { paginate(connections, page) }

    override suspend fun listConversations(page: PageRequest, filter: ConversationFilter): ApiResult<Page<Conversation>> =
        op("listConversations") {
            val items = conversations
                .filter { filter.platform == null || it.platform == filter.platform }
                .filter { filter.intent == null || it.effectiveIntent == filter.intent }
                .filter { filter.automationMode == null || it.automation.effectiveMode == filter.automationMode }
                .filter { filter.stage == null || it.stage == filter.stage }
                .sortedWith(compareByDescending<Conversation> { it.lastActivityAt }.thenByDescending { it.id })
            paginate(items, page)
        }

    override suspend fun getConversation(conversationId: String): ApiResult<ConversationDetail> =
        op("getConversation") {
            val c = conversations.find { it.id == conversationId } ?: return@op notFound()
            ok(ConversationDetail.of(c, MockFixtures.analyses[conversationId]))
        }

    override suspend fun listMessages(conversationId: String, page: PageRequest): ApiResult<Page<Message>> =
        op("listMessages") {
            if (conversations.none { it.id == conversationId }) return@op notFound()
            val items = messages[conversationId].orEmpty().sortedWith(compareBy<Message> { it.createdAt }.thenBy { it.id })
            paginate(items, page)
        }

    override suspend fun listApprovals(page: PageRequest, filter: ApprovalFilter): ApiResult<Page<Approval>> =
        op("listApprovals") {
            val status = filter.status ?: ApprovalStatus.PENDING
            val items = approvals
                .filter { it.status == status }
                .filter { filter.conversationId == null || it.conversationId == filter.conversationId }
                .filter { filter.contactId == null || it.contactId == filter.contactId }
                .sortedWith(compareByDescending<Approval> { it.createdAt }.thenByDescending { it.id })
            paginate(items, page)
        }

    override suspend fun getApproval(approvalId: String): ApiResult<Approval> =
        op("getApproval") { approvals.find { it.id == approvalId }?.let { ok(it) } ?: notFound() }

    override suspend fun getGlobalAutomationState(): ApiResult<GlobalAutomationState> =
        op("getGlobalAutomationState") { ok(globalState) }

    override suspend fun getContactAutomationState(contactId: String): ApiResult<ContactAutomationState> =
        op("getContactAutomationState") { contactState(contactId)?.let { ok(it) } ?: notFound() }

    override suspend fun listAgentActions(page: PageRequest, filter: AgentActionFilter): ApiResult<Page<AgentAction>> =
        op("listAgentActions") {
            val items = auditLog
                .filter { filter.conversationId == null || it.conversationId == filter.conversationId }
                .filter { filter.contactId == null || it.contactId == filter.contactId }
                .filter { filter.type == null || it.type == filter.type }
                .sortedWith(compareByDescending<AgentAction> { it.occurredAt }.thenByDescending { it.id })
            paginate(items, page)
        }

    /* ------------------------------------------------------------ mutations (idempotent) */

    override suspend fun sendMessage(conversationId: String, body: SendMessageRequest, key: IdempotencyKey): ApiResult<Message> =
        idempotent("sendMessage", "POST /v1/conversations/{id}/messages", key, conversationId to body) {
            val conv = conversations.find { it.id == conversationId } ?: return@idempotent notFound()
            val connection = connections.find { it.id == conv.connectionId }
            if (connection == null || connection.capabilities.capabilities.send.status == Verification.UNSUPPORTED) {
                return@idempotent problem(422, ErrorCode.CAPABILITY_UNSUPPORTED, "Connection capability 'send' is UNSUPPORTED.")
            }
            if (body.text.isEmpty() || body.text.length > 4000) {
                return@idempotent problem(422, ErrorCode.VALIDATION_ERROR, "text must be 1..4000 chars")
            }
            val message = queueOutbound(conversationId, body.text, MessageOrigin.USER_MANUAL, approvalId = null)
            audit(AgentActionType.SEND_QUEUED, Actor.DISPATCHER, conversationId = conversationId, contactId = conv.contact.id, messageId = message.id)
            ok(message)
        }

    override suspend fun createDraft(conversationId: String, body: DraftCreateRequest, key: IdempotencyKey): ApiResult<Draft> =
        idempotent("createDraft", "POST /v1/conversations/{id}/drafts", key, conversationId to body) {
            val conv = conversations.find { it.id == conversationId } ?: return@idempotent notFound()
            val lastInbound = messages[conversationId].orEmpty().lastOrNull { it.direction == MessageDirection.INBOUND }
            val draft = Draft(
                id = nextId(),
                conversationId = conversationId,
                decision = DraftDecision.SEND_CANDIDATE,
                text = "That sounds great! Tell me more.",
                questionForUser = null,
                rationale = "Mock draft: deterministic placeholder, not model output.",
                confidence = 0.5,
                requiresApproval = false,
                approvalReasons = emptyList(),
                intentMode = conv.effectiveIntent,
                grounding = DraftGrounding(emptyList(), listOfNotNull(lastInbound?.id)),
                createdAt = clock(),
            )
            audit(AgentActionType.DRAFT_GENERATED, Actor.AGENT, conversationId = conversationId, contactId = conv.contact.id)
            ok(draft)
        }

    override suspend fun approveApproval(approvalId: String, body: ApproveRequest, key: IdempotencyKey): ApiResult<ApprovalDecisionResult> =
        idempotent("approveApproval", "POST /v1/approvals/{id}/approve", key, approvalId to body) {
            val index = approvals.indexOfFirst { it.id == approvalId }
            if (index < 0) return@idempotent notFound()
            val current = approvals[index]
            if (current.status != ApprovalStatus.PENDING) {
                return@idempotent problem(409, ErrorCode.APPROVAL_NOT_PENDING, "Approval is ${current.status.name.lowercase()}")
            }
            val text = body.editedText ?: current.draft.text
                ?: return@idempotent problem(422, ErrorCode.VALIDATION_ERROR, "Draft has no text to send")
            val message = queueOutbound(current.conversationId, text, MessageOrigin.APPROVED_DRAFT, approvalId)
            val updated = current.copy(status = ApprovalStatus.APPROVED, finalText = text, messageId = message.id, decidedAt = clock())
            approvals[index] = updated
            adjustPendingCount(current.conversationId, -1)
            audit(AgentActionType.APPROVAL_APPROVED, Actor.USER, current.conversationId, current.contactId, approvalId = approvalId)
            audit(AgentActionType.SEND_QUEUED, Actor.DISPATCHER, current.conversationId, current.contactId, message.id, approvalId)
            ok(ApprovalDecisionResult(updated, message))
        }

    override suspend fun rejectApproval(approvalId: String, body: RejectRequest, key: IdempotencyKey): ApiResult<ApprovalDecisionResult> =
        idempotent("rejectApproval", "POST /v1/approvals/{id}/reject", key, approvalId to body) {
            val index = approvals.indexOfFirst { it.id == approvalId }
            if (index < 0) return@idempotent notFound()
            val current = approvals[index]
            if (current.status != ApprovalStatus.PENDING) {
                return@idempotent problem(409, ErrorCode.APPROVAL_NOT_PENDING, "Approval is ${current.status.name.lowercase()}")
            }
            val updated = current.copy(status = ApprovalStatus.REJECTED, rejectionReason = body.reason, decidedAt = clock())
            approvals[index] = updated
            adjustPendingCount(current.conversationId, -1)
            audit(AgentActionType.APPROVAL_REJECTED, Actor.USER, current.conversationId, current.contactId, approvalId = approvalId)
            ok(ApprovalDecisionResult(updated, message = null))
        }

    override suspend fun pauseAllAutomation(body: AutomationActionRequest, key: IdempotencyKey): ApiResult<GlobalAutomationState> =
        idempotent("pauseAllAutomation", "POST /v1/automation/pause", key, body) {
            globalState = GlobalAutomationState(GlobalAutomationStatus.PAUSED, clock())
            audit(AgentActionType.AUTOMATION_PAUSED, Actor.USER)
            ok(globalState)
        }

    override suspend fun resumeAllAutomation(body: AutomationActionRequest, key: IdempotencyKey): ApiResult<GlobalAutomationState> =
        idempotent("resumeAllAutomation", "POST /v1/automation/resume", key, body) {
            globalState = GlobalAutomationState(GlobalAutomationStatus.ACTIVE, clock())
            audit(AgentActionType.AUTOMATION_RESUMED, Actor.USER)
            ok(globalState)
        }

    override suspend fun pauseContactAutomation(contactId: String, body: AutomationActionRequest, key: IdempotencyKey): ApiResult<ContactAutomationState> =
        idempotent("pauseContactAutomation", "POST /v1/contacts/{id}/automation/pause", key, contactId to body) {
            setContactStatus(contactId, ContactAutomationStatus.PAUSED, AgentActionType.AUTOMATION_PAUSED, cancelApprovals = false)
        }

    override suspend fun resumeContactAutomation(contactId: String, body: AutomationActionRequest, key: IdempotencyKey): ApiResult<ContactAutomationState> =
        idempotent("resumeContactAutomation", "POST /v1/contacts/{id}/automation/resume", key, contactId to body) {
            setContactStatus(contactId, ContactAutomationStatus.ACTIVE, AgentActionType.AUTOMATION_RESUMED, cancelApprovals = false)
        }

    override suspend fun takeoverContact(contactId: String, body: AutomationActionRequest, key: IdempotencyKey): ApiResult<ContactAutomationState> =
        idempotent("takeoverContact", "POST /v1/contacts/{id}/automation/takeover", key, contactId to body) {
            setContactStatus(contactId, ContactAutomationStatus.TAKEN_OVER, AgentActionType.AUTOMATION_TAKEOVER, cancelApprovals = true)
        }

    /* ------------------------------------------------------------ internals */

    private suspend fun <T> op(operationId: String, block: suspend () -> ApiResult<T>): ApiResult<T> {
        if (latencyMs > 0) delay(latencyMs)
        return mutex.withLock {
            calls += operationId
            val injected = failNext ?: failAll
            failNext = null
            if (injected != null) ApiResult.Failure(injected) else block()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> idempotent(
        operationId: String,
        route: String,
        key: IdempotencyKey,
        body: Any,
        block: suspend () -> ApiResult<T>,
    ): ApiResult<T> = op(operationId) {
        val storeKey = "$route|${key.value}"
        val stored = idempotency[storeKey]
        when {
            stored == null -> block().also { result ->
                // Only completed responses are stored; there are no 5xx here, so store everything.
                idempotency[storeKey] = body to result
            }
            stored.first != body -> problem(422, ErrorCode.IDEMPOTENCY_KEY_MISMATCH, "Idempotency-Key reused with a different body")
            else -> when (val r = stored.second as ApiResult<T>) {
                is ApiResult.Success -> r.copy(idempotentReplayed = true)
                is ApiResult.Failure -> r
            }
        }
    }

    private fun nextId(): String = "0192f3a0-0000-7000-9000-" + (++idCounter).toString().padStart(12, '0')

    private fun <T> ok(value: T): ApiResult<T> = ApiResult.Success(value)

    private fun notFound(): ApiResult<Nothing> = problem(404, ErrorCode.NOT_FOUND, "Not found")

    private fun <T> paginate(all: List<T>, page: PageRequest): ApiResult<Page<T>> {
        val offset = when (val c = page.cursor) {
            null -> 0
            else -> c.removePrefix(CURSOR_PREFIX).toIntOrNull()?.takeIf { c.startsWith(CURSOR_PREFIX) && it in 0..all.size }
                ?: return problem(422, ErrorCode.VALIDATION_ERROR, "Invalid cursor")
        }
        val limit = page.limit ?: 50
        val end = (offset + limit).coerceAtMost(all.size)
        return ok(Page(all.subList(offset, end).toList(), if (end < all.size) "$CURSOR_PREFIX$end" else null))
    }

    private fun queueOutbound(conversationId: String, text: String, origin: MessageOrigin, approvalId: String?): Message {
        val message = Message(
            id = nextId(), conversationId = conversationId, direction = MessageDirection.OUTBOUND, origin = origin,
            text = text, deliveryState = DeliveryState.QUEUED, externalMessageId = null, approvalId = approvalId,
            attemptCount = 0, failure = null, createdAt = clock(), sentAt = null, deliveredAt = null,
        )
        messages.getOrPut(conversationId) { mutableListOf() }.add(message)
        return message
    }

    private fun adjustPendingCount(conversationId: String, delta: Int) {
        val i = conversations.indexOfFirst { it.id == conversationId }
        if (i >= 0) {
            val c = conversations[i]
            conversations[i] = c.copy(pendingApprovalCount = (c.pendingApprovalCount + delta).coerceAtLeast(0))
        }
    }

    private fun contactState(contactId: String): ContactAutomationState? {
        contactStates[contactId]?.let { return it }
        val conv = conversations.find { it.contact.id == contactId } ?: return null
        return ContactAutomationState(
            contactId = contactId,
            status = conv.automation.contactStatus,
            effectiveMode = conv.automation.effectiveMode,
            globalStatus = globalState.status,
            changedAt = null,
            canceledApprovalCount = 0,
            canceledMessageCount = 0,
        )
    }

    private fun setContactStatus(
        contactId: String,
        status: ContactAutomationStatus,
        auditType: AgentActionType,
        cancelApprovals: Boolean,
    ): ApiResult<ContactAutomationState> {
        val base = contactState(contactId) ?: return notFound()
        var canceled = 0
        if (cancelApprovals) {
            approvals.replaceAll { a ->
                if (a.contactId == contactId && a.status == ApprovalStatus.PENDING) {
                    canceled++
                    audit(AgentActionType.APPROVAL_CANCELED, Actor.SYSTEM, a.conversationId, contactId, approvalId = a.id)
                    a.copy(status = ApprovalStatus.CANCELED, decidedAt = clock())
                } else a
            }
        }
        conversations.replaceAll { c ->
            if (c.contact.id != contactId) c else c.copy(
                automation = c.automation.copy(contactStatus = status, globalStatus = globalState.status),
                pendingApprovalCount = if (cancelApprovals) 0 else c.pendingApprovalCount,
            )
        }
        val state = base.copy(
            status = status,
            globalStatus = globalState.status,
            changedAt = clock(),
            canceledApprovalCount = canceled,
            canceledMessageCount = 0,
        )
        contactStates[contactId] = state
        audit(auditType, Actor.USER, contactId = contactId)
        return ok(state)
    }

    private fun audit(
        type: AgentActionType,
        actor: Actor,
        conversationId: String? = null,
        contactId: String? = null,
        messageId: String? = null,
        approvalId: String? = null,
        reasonCode: ApprovalReason? = null,
    ) {
        auditLog += AgentAction(
            id = nextId(), type = type, actor = actor, conversationId = conversationId, contactId = contactId,
            messageId = messageId, approvalId = approvalId, reasonCode = reasonCode?.name?.lowercase(),
            occurredAt = clock(), details = JsonObject(emptyMap()),
        )
    }

    companion object {
        private const val CURSOR_PREFIX = "mock:"

        fun networkError(): ApiError = ApiError.Network(IOException("Simulated network failure (MockWingmanApi)"))

        fun problem(status: Int, code: ErrorCode, title: String): ApiResult<Nothing> = ApiResult.Failure(
            ApiError.Http(
                status = status,
                problem = Problem(
                    type = "https://wingman.invalid/problems/${code.wire}",
                    title = title,
                    status = status,
                    code = code.wire,
                ),
            ),
        )
    }
}
