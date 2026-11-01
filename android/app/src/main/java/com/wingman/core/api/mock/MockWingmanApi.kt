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
import com.wingman.core.model.wireName
import com.wingman.core.model.ApprovalStatus
import com.wingman.core.model.ApproveRequest
import com.wingman.core.model.AutomationActionRequest
import com.wingman.core.model.Capability
import com.wingman.core.model.CapabilitySet
import com.wingman.core.model.Connection
import com.wingman.core.model.ConnectionCreateRequest
import com.wingman.core.model.FieldError
import com.wingman.core.model.ConnectionStatus
import com.wingman.core.model.ConnectionUnpairRequest
import com.wingman.core.model.ConnectionUnpairResult
import com.wingman.core.model.ConnectorCapabilities
import com.wingman.core.model.ConnectorEventIngestOutcome
import com.wingman.core.model.ConnectorEventIngestResult
import com.wingman.core.model.ContactAutomationState
import com.wingman.core.model.ContactAutomationStatus
import com.wingman.core.model.ContactPreferenceOverride
import com.wingman.core.model.ContactPreferenceOverrideUpdate
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
import com.wingman.core.model.Intent
import com.wingman.core.model.IntentMode
import com.wingman.core.model.Message
import com.wingman.core.model.MessageDirection
import com.wingman.core.model.MessageOrigin
import com.wingman.core.model.OutboxAuthorization
import com.wingman.core.model.OutboxAuthorizationOrigin
import com.wingman.core.model.OutboxClaimRequest
import com.wingman.core.model.OutboxClaimResult
import com.wingman.core.model.OutboxInstruction
import com.wingman.core.model.OutboxInstructionState
import com.wingman.core.model.Page
import com.wingman.core.model.Preferences
import com.wingman.core.model.PreferencesUpdate
import com.wingman.core.model.Problem
import com.wingman.core.model.RejectRequest
import com.wingman.core.model.SendMessageRequest
import com.wingman.core.model.StyleProfile
import com.wingman.core.model.User
import com.wingman.core.model.Verification
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 *  - D-014 pause/takeover: pause (global or per contact) cancels queued `autopilot` messages only and
 *    keeps pending approvals; an approved draft and the user's own send still go while paused.
 *    Takeover cancels the contact's pending approvals AND its queued automated messages
 *    (`approved_draft` and `autopilot`); `user_manual` is never canceled. Each cancel is audited
 *    (`approval_canceled` / `send_canceled` with reason `automation_paused|automation_takeover`);
 *  - delivery advances only through [simulateDelivery] (the connector's role), forward only;
 *  - device connectors (v1.1.0): `ingestConnectorEvent` dedups by `event_id` (`duplicate_event`), refuses
 *    `mock.*` on a real connection and send outcomes without a leased instruction (422);
 *    `claimOutboxInstructions` leases instructions queued with [queueOutboxInstruction] at most once,
 *    returns the caller's own unexpired leases, and replays the same key. Only the install a device-side
 *    connection is paired with may claim (I-018, D-017): another install, an unpaired connection or a
 *    never-paired device-side connection is `422 validation_error` at `/device_install_id`;
 *  - pairing (v1.2.0, ADR 0007): `createConnection` answers a new connection, or the existing one when
 *    this `device_install_id` is already paired for the platform (the HTTP 200 case); a replay of the
 *    same key returns the original. A client-claimed `VERIFIED` is stored as `UNVERIFIED` (with the
 *    backend's note), `evidence`/`verified_at` are dropped, and nothing the client sends can make a
 *    capability `VERIFIED`. `unpairConnection` disconnects, marks every capability `UNSUPPORTED`,
 *    cancels the connection's pending instructions, queued outbound messages (any origin) and pending
 *    approvals, counts leased instructions as in flight, and is a zero-count no-op the second time.
 *    An unpaired connection accepts only send outcomes on ingest (422 otherwise);
 *  - preferences: `If-Match` mismatch is `412 precondition_failed`; per-contact overrides have their
 *    own version sequence, never touch user-wide preferences, and `effective` follows the
 *    backend's `merge_effective` (override field when non-null > user-wide); `custom` intent
 *    without a description is `422 validation_error`.
 *
 * Test hooks: [failNextWith] makes the next call fail, [failAllWith] makes every call fail,
 * [latencyMs] adds an artificial delay. [calls] records the operations invoked. [ingestedEvents]
 * holds accepted envelopes; [queueOutboxInstruction] plays the dispatcher (WING-011) for the outbox.
 * [queueAutopilot] plays the policy engine queuing an autopilot reply; [simulateDelivery] plays the
 * connector acknowledging a send. The audit log is seeded with the fixture approvals' history.
 */
class MockWingmanApi(
    private val clock: () -> Instant = { MockFixtures.NOW },
    var latencyMs: Long = 0,
) : WingmanApi {

    private val mutex = Mutex()
    private var idCounter = 0

    private var preferences: Preferences = MockFixtures.defaultPreferences
    private val preferenceHistory = mutableListOf<Preferences>()
    private val contactOverrides = mutableMapOf<String, ContactPreferenceOverride>()
    private val connections = MockFixtures.connections.toMutableList()
    private val conversations = MockFixtures.conversations.toMutableList()
    private val messages = MockFixtures.messages.mapValues { it.value.toMutableList() }.toMutableMap()
    private val approvals = MockFixtures.approvals.toMutableList()
    private val auditLog = mutableListOf<AgentAction>()
    private var globalState = GlobalAutomationState(GlobalAutomationStatus.ACTIVE, changedAt = null)
    private val contactStates = mutableMapOf<String, ContactAutomationState>()
    private val idempotency = mutableMapOf<String, Pair<Any, ApiResult<*>>>()
    private val ingested = LinkedHashMap<String, Pair<JsonObject, ConnectorEventIngestResult>>()
    private val inboundExternalIds = mutableMapOf<String, ConnectorEventIngestResult>()
    private val outbox = mutableListOf<MockOutboxEntry>()

    init {
        // Seed the audit timeline with the history behind the fixture approvals.
        for (a in approvals) {
            val msg = a.draft.grounding.messageIds.firstOrNull()
            auditLog += AgentAction(
                id = nextId(), type = AgentActionType.MESSAGE_RECEIVED, actor = Actor.CONNECTOR, conversationId = a.conversationId,
                contactId = a.contactId, messageId = msg, approvalId = null, reasonCode = null,
                occurredAt = a.createdAt.minusSeconds(10), details = JsonObject(emptyMap()),
            )
            auditLog += AgentAction(
                id = nextId(), type = AgentActionType.APPROVAL_REQUESTED, actor = Actor.AGENT, conversationId = a.conversationId,
                contactId = a.contactId, messageId = null, approvalId = a.id, reasonCode = a.reasons.firstOrNull()?.wireName(),
                occurredAt = a.createdAt, details = JsonObject(emptyMap()),
            )
        }
    }

    /** Accepted (not duplicate) ingest envelopes, oldest first. */
    val ingestedEvents: List<JsonObject> get() = ingested.values.map { it.first }

    /** A dispatcher-created instruction and its mock lease state. */
    private data class MockOutboxEntry(
        val instruction: OutboxInstruction,
        var state: OutboxInstructionState = OutboxInstructionState.PENDING,
        var holder: String? = null,
        var leasedAt: Instant? = null,
        var leaseExpiresAt: Instant? = null,
    )

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
            invalidIntent(body.intent)?.let { return@op it }
            preferences = Preferences(
                version = preferences.version + 1,
                intent = body.intent,
                dealbreakers = body.dealbreakers ?: emptyList(),
                style = body.style,
                automation = body.automation,
                updatedAt = clock(),
            )
            preferenceHistory += preferences
            audit(AgentActionType.PREFERENCES_UPDATED, Actor.USER)
            ok(preferences)
        }

    override suspend fun listPreferenceVersions(page: PageRequest): ApiResult<Page<Preferences>> =
        op("listPreferenceVersions") { paginate(preferenceHistory.reversed(), page) }

    override suspend fun getContactPreferences(contactId: String): ApiResult<ContactPreferences> =
        op("getContactPreferences") {
            if (!isKnownContact(contactId)) return@op notFound()
            ok(contactPreferences(contactId))
        }

    override suspend fun putContactPreferences(
        contactId: String,
        body: ContactPreferenceOverrideUpdate,
        ifMatchVersion: Int?,
    ): ApiResult<ContactPreferences> = op("putContactPreferences") {
        if (!isKnownContact(contactId)) return@op notFound()
        val current = contactOverrides[contactId] ?: ContactPreferenceOverride(version = 0)
        if (ifMatchVersion != null && ifMatchVersion != current.version) {
            return@op problem(412, ErrorCode.PRECONDITION_FAILED, "Override changed since version $ifMatchVersion")
        }
        body.intent?.let { intent -> invalidIntent(intent)?.let { return@op it } }
        contactOverrides[contactId] = ContactPreferenceOverride(
            version = current.version + 1,
            intent = body.intent,
            style = body.style,
            automationMode = body.automationMode,
            updatedAt = clock(),
        )
        audit(AgentActionType.PREFERENCES_UPDATED, Actor.USER, contactId = contactId)
        ok(contactPreferences(contactId))
    }

    override suspend fun deleteContactPreferences(contactId: String): ApiResult<Unit> =
        op("deleteContactPreferences") {
            if (!isKnownContact(contactId)) return@op notFound()
            val current = contactOverrides[contactId]
            // Like the backend: clearing appends an all-null version, and is a no-op when already empty.
            if (current != null && !current.isEmpty()) {
                contactOverrides[contactId] = ContactPreferenceOverride(version = current.version + 1, updatedAt = clock())
                audit(AgentActionType.PREFERENCES_UPDATED, Actor.USER, contactId = contactId)
            }
            ok(Unit)
        }

    override suspend fun listConnections(page: PageRequest): ApiResult<Page<Connection>> =
        op("listConnections") { paginate(connections, page) }

    /* ------------------------------------------------------------ pairing (v1.2.0, ADR 0007) */

    override suspend fun createConnection(body: ConnectionCreateRequest, key: IdempotencyKey): ApiResult<Connection> =
        idempotent("createConnection", "POST /v1/connections", key, body) {
            val device = body.platform in ConnectionCreateRequest.DEVICE_PLATFORMS
            if (body.platform == "mock") {
                if (body.deviceInstallId != null) return@idempotent problem(422, ErrorCode.VALIDATION_ERROR, "device_install_id must be absent for platform 'mock'")
                if (body.capabilities != null) return@idempotent problem(422, ErrorCode.VALIDATION_ERROR, "capabilities must be absent for platform 'mock'")
            }
            if (device) {
                val install = body.deviceInstallId ?: return@idempotent problem(422, ErrorCode.VALIDATION_ERROR, "device-side platforms need device_install_id")
                if (!ID_PATTERN.matches(install)) return@idempotent problem(422, ErrorCode.VALIDATION_ERROR, "device_install_id must be a lowercase UUID")
                val caps = body.capabilities ?: return@idempotent problem(422, ErrorCode.VALIDATION_ERROR, "device-side platforms need capabilities")
                if (caps.platform != body.platform) return@idempotent problem(422, ErrorCode.VALIDATION_ERROR, "capabilities platform does not match")
                // One paired connection per (platform, install): pairing again returns it unchanged (HTTP 200).
                connections.firstOrNull { it.platform == body.platform && it.deviceInstallId == install && it.unpairedAt == null }
                    ?.let { return@idempotent ok(it) }
            }
            val now = clock()
            val stored = if (device) trustClaimedCapabilities(body.capabilities!!) else mockSimulatorCapabilities(now)
            val created = Connection(
                id = nextId(), platform = body.platform, displayName = body.displayName, status = ConnectionStatus.CONNECTED,
                capabilities = stored, createdAt = now, updatedAt = now,
                deviceInstallId = body.deviceInstallId, pairedAt = now, unpairedAt = null,
            )
            connections += created
            val claimed = body.capabilities?.capabilities?.let(::verifiedNames).orEmpty()
            audit(
                AgentActionType.CONNECTION_PAIRED, Actor.USER,
                details = details("connection_id" to created.id, "platform" to created.platform, "claimed_verified" to claimed.joinToString(",")),
            )
            ok(created)
        }

    override suspend fun getConnection(connectionId: String): ApiResult<Connection> =
        op("getConnection") { connections.find { it.id == connectionId }?.let(::ok) ?: notFound() }

    override suspend fun unpairConnection(
        connectionId: String,
        key: IdempotencyKey,
        body: ConnectionUnpairRequest,
    ): ApiResult<ConnectionUnpairResult> {
        // Ownership first, like the backend: an unknown connection is 404 before any key is stored.
        if (mutex.withLock { connections.none { it.id == connectionId } }) return op("unpairConnection") { notFound() }
        return idempotent("unpairConnection", "POST /v1/connections/{id}/unpair", key, connectionId to body) {
            val index = connections.indexOfFirst { it.id == connectionId }
            val current = connections[index]
            if (current.unpairedAt != null) {
                return@idempotent ok(ConnectionUnpairResult(current, 0, 0, 0, 0))
            }
            val now = clock()
            var canceledInstructions = 0
            var inFlight = 0
            for (e in outbox.filter { it.instruction.connectionId == connectionId }) {
                when (e.state) {
                    OutboxInstructionState.PENDING -> { e.state = OutboxInstructionState.CANCELED; canceledInstructions++ }
                    OutboxInstructionState.LEASED -> inFlight++
                    else -> Unit
                }
            }
            val convs = conversations.filter { it.connectionId == connectionId }
            var canceledMessages = 0
            for (conv in convs) {
                messages[conv.id]?.replaceAll { m ->
                    if (m.direction == MessageDirection.OUTBOUND && m.deliveryState == DeliveryState.QUEUED) {
                        canceledMessages++
                        audit(AgentActionType.SEND_CANCELED, Actor.USER, conv.id, conv.contact.id, m.id, m.approvalId, reasonCode = "connection_unavailable")
                        m.copy(deliveryState = DeliveryState.CANCELED)
                    } else m
                }
            }
            var canceledApprovals = 0
            val convIds = convs.map { it.id }.toSet()
            approvals.replaceAll { a ->
                if (a.conversationId in convIds && a.status == ApprovalStatus.PENDING) {
                    canceledApprovals++
                    audit(AgentActionType.APPROVAL_CANCELED, Actor.USER, a.conversationId, a.contactId, approvalId = a.id, reasonCode = "connection_unavailable")
                    adjustPendingCount(a.conversationId, -1)
                    a.copy(status = ApprovalStatus.CANCELED, decidedAt = now)
                } else a
            }
            val unsupported = Capability(Verification.UNSUPPORTED, notes = UNPAIRED_NOTE)
            val unpaired = current.copy(
                status = ConnectionStatus.DISCONNECTED,
                capabilities = current.capabilities.copy(
                    capabilities = CapabilitySet(unsupported, unsupported, unsupported, unsupported, unsupported),
                ),
                updatedAt = now,
                unpairedAt = now,
            )
            connections[index] = unpaired
            audit(
                AgentActionType.CONNECTION_UNPAIRED, Actor.USER,
                details = details(
                    "connection_id" to connectionId, "canceled_messages" to canceledMessages,
                    "canceled_instructions" to canceledInstructions, "canceled_approvals" to canceledApprovals, "in_flight" to inFlight,
                ),
            )
            ok(ConnectionUnpairResult(unpaired, canceledMessages, canceledInstructions, canceledApprovals, inFlight))
        }
    }

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
            audit(
                AgentActionType.APPROVAL_APPROVED, Actor.USER, current.conversationId, current.contactId, approvalId = approvalId,
                details = details("edited" to (body.editedText != null && body.editedText != current.draft.text)),
            )
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
            val changed = globalState.status != GlobalAutomationStatus.PAUSED
            if (changed) globalState = GlobalAutomationState(GlobalAutomationStatus.PAUSED, clock())
            syncGlobalStatus()
            // D-014: a pause cancels queued AUTOPILOT sends only; approvals and approved drafts are kept.
            val canceled = cancelQueued(contactId = null, origins = PAUSE_CANCELS, reason = "automation_paused")
            audit(AgentActionType.AUTOMATION_PAUSED, Actor.USER, details = details("scope" to "global", "changed" to changed, "canceled_messages" to canceled))
            ok(globalState)
        }

    override suspend fun resumeAllAutomation(body: AutomationActionRequest, key: IdempotencyKey): ApiResult<GlobalAutomationState> =
        idempotent("resumeAllAutomation", "POST /v1/automation/resume", key, body) {
            val changed = globalState.status != GlobalAutomationStatus.ACTIVE
            if (changed) globalState = GlobalAutomationState(GlobalAutomationStatus.ACTIVE, clock())
            syncGlobalStatus()
            audit(AgentActionType.AUTOMATION_RESUMED, Actor.USER, details = details("scope" to "global", "changed" to changed))
            ok(globalState)
        }

    override suspend fun pauseContactAutomation(contactId: String, body: AutomationActionRequest, key: IdempotencyKey): ApiResult<ContactAutomationState> =
        idempotent("pauseContactAutomation", "POST /v1/contacts/{id}/automation/pause", key, contactId to body) {
            setContactStatus(contactId, ContactAutomationStatus.PAUSED)
        }

    override suspend fun resumeContactAutomation(contactId: String, body: AutomationActionRequest, key: IdempotencyKey): ApiResult<ContactAutomationState> =
        idempotent("resumeContactAutomation", "POST /v1/contacts/{id}/automation/resume", key, contactId to body) {
            setContactStatus(contactId, ContactAutomationStatus.ACTIVE)
        }

    override suspend fun takeoverContact(contactId: String, body: AutomationActionRequest, key: IdempotencyKey): ApiResult<ContactAutomationState> =
        idempotent("takeoverContact", "POST /v1/contacts/{id}/automation/takeover", key, contactId to body) {
            setContactStatus(contactId, ContactAutomationStatus.TAKEN_OVER)
        }

    /* ------------------------------------------------------------ device connectors (v1.1.0) */

    /**
     * Test hook standing in for the WING-011 dispatcher: queues a `pending` instruction on
     * [connectionId]. Returns its per-attempt key (`send:<outbound>:<attempt>`, D-006).
     */
    fun queueOutboxInstruction(
        connectionId: String,
        externalThreadId: String,
        text: String,
        authorization: OutboxAuthorization = OutboxAuthorization(OutboxAuthorizationOrigin.USER_MANUAL, null),
        attempt: Int = 1,
        outboundMessageId: String = nextId(),
        conversationId: String = MockFixtures.CONVERSATION_SAM,
    ): String {
        val key = "send:$outboundMessageId:$attempt"
        // Lease fields are placeholders until claimed; the returned copy carries the real lease.
        outbox += MockOutboxEntry(
            OutboxInstruction(
                id = nextId(), connectionId = connectionId, outboundMessageId = outboundMessageId, idempotencyKey = key,
                attempt = attempt, state = OutboxInstructionState.PENDING, createdAt = clock(), leasedAt = clock(),
                leaseExpiresAt = clock(), conversationId = conversationId, externalThreadId = externalThreadId,
                text = text, authorization = authorization,
            ),
        )
        return key
    }

    /** Lease length the mock grants (contract default 120 s). */
    var leaseSeconds: Long = 120

    override suspend fun ingestConnectorEvent(connectionId: String, envelope: JsonObject, key: IdempotencyKey): ApiResult<ConnectorEventIngestResult> =
        op("ingestConnectorEvent") {
            fun field(name: String) = (envelope[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val connection = connections.find { it.id == connectionId }
            if (connection == null || field("user_id") != MockFixtures.USER_ID) return@op notFound()
            val eventId = field("event_id")
            if (eventId != key.value) return@op problem(422, ErrorCode.VALIDATION_ERROR, "Idempotency-Key must equal event_id")
            ingested[eventId]?.let { (body, result) ->
                return@op if (body == envelope) {
                    ApiResult.Success(result.copy(outcome = ConnectorEventIngestOutcome.DUPLICATE_EVENT), idempotentReplayed = true)
                } else {
                    problem(422, ErrorCode.IDEMPOTENCY_KEY_MISMATCH, "Idempotency-Key reused with a different body")
                }
            }
            val type = field("type").orEmpty()
            if (field("connection_id") != connectionId || field("platform") != connection.platform) {
                return@op problem(422, ErrorCode.VALIDATION_ERROR, "connection_id/platform do not match the connection")
            }
            if (type.startsWith("mock.") && connection.platform != "mock") {
                return@op problem(422, ErrorCode.VALIDATION_ERROR, "mock.* events are only accepted on a mock connection")
            }
            val isSendOutcome = type.endsWith("send_acknowledged") || type.endsWith("send_failed") || type.endsWith("delivery_receipt")
            if (connection.unpairedAt != null && !isSendOutcome) {
                // ADR 0007: an unpaired connection accepts only late outcomes of already-leased sends.
                return@op problem(422, ErrorCode.VALIDATION_ERROR, "connection is unpaired; only send outcomes are accepted")
            }
            val payload = (envelope["payload"] as? JsonObject) ?: JsonObject(emptyMap())
            fun payloadField(name: String) = (payload[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
            var messageId: String? = null
            if (type.endsWith("send_acknowledged") || type.endsWith("send_failed") || type.endsWith("delivery_receipt")) {
                val outbound = payloadField("outbound_message_id")
                val sendKey = payloadField("idempotency_key")
                val match = outbox.firstOrNull { e ->
                    e.instruction.connectionId == connectionId && e.instruction.outboundMessageId == outbound &&
                        (sendKey == null || e.instruction.idempotencyKey == sendKey) &&
                        e.state in setOf(OutboxInstructionState.LEASED, OutboxInstructionState.EXPIRED, OutboxInstructionState.COMPLETED)
                } ?: return@op problem(422, ErrorCode.VALIDATION_ERROR, "send result does not match a send instruction leased on this connection")
                if (!type.endsWith("delivery_receipt")) match.state = OutboxInstructionState.COMPLETED
                messageId = outbound
            }
            val externalId = field("external_message_id")
            if (type.endsWith("message.received") || type == "mock.inbound_message_injected") {
                val dedupKey = "${connection.platform}|$externalId"
                inboundExternalIds[dedupKey]?.let { first ->
                    return@op ok(ConnectorEventIngestResult(eventId, ConnectorEventIngestOutcome.DUPLICATE_MESSAGE, first.messageId, clock()))
                }
                messageId = nextId()
                inboundExternalIds[dedupKey] = ConnectorEventIngestResult(eventId, ConnectorEventIngestOutcome.ACCEPTED, messageId, clock())
            }
            val result = ConnectorEventIngestResult(eventId, ConnectorEventIngestOutcome.ACCEPTED, messageId, clock())
            ingested[eventId] = envelope to result
            ok(result)
        }

    override suspend fun claimOutboxInstructions(connectionId: String, body: OutboxClaimRequest, key: IdempotencyKey): ApiResult<OutboxClaimResult> =
        idempotent("claimOutboxInstructions", "POST /v1/connections/{id}/outbox/claim", key, connectionId to body) {
            val connection = connections.firstOrNull { it.id == connectionId } ?: return@idempotent notFound()
            installBindingProblem(connection, body.deviceInstallId)?.let { return@idempotent it }
            val now = clock()
            val max = body.maxInstructions ?: 1
            outbox.filter { it.state == OutboxInstructionState.LEASED && it.leaseExpiresAt!! <= now }
                .forEach { it.state = OutboxInstructionState.EXPIRED }
            val held = outbox.filter {
                it.instruction.connectionId == connectionId && it.state == OutboxInstructionState.LEASED && it.holder == body.deviceInstallId
            }
            val fresh = outbox.filter { it.instruction.connectionId == connectionId && it.state == OutboxInstructionState.PENDING }
                .take((max - held.size).coerceAtLeast(0))
            fresh.forEach { e ->
                e.state = OutboxInstructionState.LEASED
                e.holder = body.deviceInstallId
                e.leasedAt = now
                e.leaseExpiresAt = now.plusSeconds(leaseSeconds)
            }
            val leased = (held + fresh).take(max).map { e ->
                e.instruction.copy(state = OutboxInstructionState.LEASED, leasedAt = e.leasedAt!!, leaseExpiresAt = e.leaseExpiresAt!!)
            }
            ok(OutboxClaimResult(leased, now))
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

    private fun isKnownContact(contactId: String): Boolean = conversations.any { it.contact.id == contactId }

    private fun contactPreferences(contactId: String): ContactPreferences {
        val override = contactOverrides[contactId] ?: ContactPreferenceOverride(version = 0)
        return ContactPreferences(contactId, override, mergeEffective(preferences, override))
    }

    private fun invalidIntent(intent: Intent): ApiResult<Nothing>? = when {
        intent.mode == IntentMode.CUSTOM && intent.customDescription.isNullOrEmpty() ->
            problem(422, ErrorCode.VALIDATION_ERROR, "custom_description is required when mode is 'custom'")
        intent.mode != IntentMode.CUSTOM && intent.customDescription != null ->
            problem(422, ErrorCode.VALIDATION_ERROR, "custom_description must be null unless mode is 'custom'")
        (intent.customDescription?.length ?: 0) > 500 ->
            problem(422, ErrorCode.VALIDATION_ERROR, "custom_description must be at most 500 characters")
        else -> null
    }

    private fun nextId(): String = "0192f3a0-0000-7000-9000-" + (++idCounter).toString().padStart(12, '0')

    private fun <T> ok(value: T): ApiResult<T> = ApiResult.Success(value)

    /**
     * I-018 / D-017, as the backend's `OutboxService._check_binding`: only the install a device-side
     * connection is paired with may claim. An unpaired connection, a never-paired device-side
     * connection or another install gets `422 validation_error` at `/device_install_id`, and nothing
     * is leased or recovered. A backend-side connection (`mock`, no binding) answers normally.
     */
    private fun installBindingProblem(connection: Connection, deviceInstallId: String): ApiResult<Nothing>? {
        val reason = when {
            connection.unpairedAt != null -> "this connection is unpaired; no install may claim on it"
            connection.deviceInstallId == null ->
                if (connection.platform in ConnectionCreateRequest.DEVICE_PLATFORMS) "this connection is not paired with any app install" else null
            connection.deviceInstallId != deviceInstallId -> "not the app install this connection is paired with"
            else -> null
        } ?: return null
        return ApiResult.Failure(
            ApiError.Http(
                status = 422,
                problem = Problem(
                    type = "https://wingman.invalid/problems/${ErrorCode.VALIDATION_ERROR.wire}",
                    title = "This install may not claim instructions on this connection",
                    status = 422,
                    code = ErrorCode.VALIDATION_ERROR.wire,
                    errors = listOf(FieldError(pointer = "/device_install_id", message = reason)),
                ),
            ),
        )
    }

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
        contactStates[contactId]?.let { return it.copy(globalStatus = globalState.status, canceledApprovalCount = 0, canceledMessageCount = 0) }
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

    /** D-014, mirroring backend `AutomationController.set_contact_status`. */
    private fun setContactStatus(contactId: String, status: ContactAutomationStatus): ApiResult<ContactAutomationState> {
        val base = contactState(contactId) ?: return notFound()
        val changed = base.status != status
        var canceledApprovals = 0
        if (status == ContactAutomationStatus.TAKEN_OVER) {
            approvals.replaceAll { a ->
                if (a.contactId == contactId && a.status == ApprovalStatus.PENDING) {
                    canceledApprovals++
                    audit(AgentActionType.APPROVAL_CANCELED, Actor.USER, a.conversationId, contactId, approvalId = a.id, reasonCode = "automation_takeover")
                    a.copy(status = ApprovalStatus.CANCELED, decidedAt = clock())
                } else a
            }
        }
        val canceledMessages = when (status) {
            ContactAutomationStatus.TAKEN_OVER -> cancelQueued(contactId, AUTOMATED_ORIGINS, "automation_takeover")
            ContactAutomationStatus.PAUSED -> cancelQueued(contactId, PAUSE_CANCELS, "automation_paused")
            ContactAutomationStatus.ACTIVE -> 0
        }
        conversations.replaceAll { c ->
            if (c.contact.id != contactId) c else c.copy(
                automation = c.automation.copy(contactStatus = status, globalStatus = globalState.status),
                pendingApprovalCount = approvals.count { it.conversationId == c.id && it.status == ApprovalStatus.PENDING },
            )
        }
        val state = base.copy(
            status = status,
            globalStatus = globalState.status,
            changedAt = if (changed) clock() else base.changedAt,
            canceledApprovalCount = canceledApprovals,
            canceledMessageCount = canceledMessages,
        )
        contactStates[contactId] = state
        val auditType = when (status) {
            ContactAutomationStatus.ACTIVE -> AgentActionType.AUTOMATION_RESUMED
            ContactAutomationStatus.PAUSED -> AgentActionType.AUTOMATION_PAUSED
            ContactAutomationStatus.TAKEN_OVER -> AgentActionType.AUTOMATION_TAKEOVER
        }
        audit(
            auditType, Actor.USER, contactId = contactId,
            details = details("scope" to "contact", "changed" to changed, "canceled_approvals" to canceledApprovals, "canceled_messages" to canceledMessages),
        )
        return ok(state)
    }

    /** Cancels QUEUED outbound messages with one of [origins] (never `user_manual`); `sending` can't be recalled. */
    private fun cancelQueued(contactId: String?, origins: Set<MessageOrigin>, reason: String): Int {
        var count = 0
        for (conv in conversations.filter { contactId == null || it.contact.id == contactId }) {
            val list = messages[conv.id] ?: continue
            list.replaceAll { m ->
                if (m.direction == MessageDirection.OUTBOUND && m.deliveryState == DeliveryState.QUEUED && m.origin in origins) {
                    count++
                    audit(AgentActionType.SEND_CANCELED, Actor.USER, conv.id, conv.contact.id, m.id, m.approvalId, reasonCode = reason)
                    m.copy(deliveryState = DeliveryState.CANCELED)
                } else m
            }
        }
        return count
    }

    private fun syncGlobalStatus() {
        conversations.replaceAll { it.copy(automation = it.automation.copy(globalStatus = globalState.status)) }
    }

    /* ------------------------------------------------------------ test hooks: policy engine and connector */

    /**
     * Plays the WING-011 policy engine queuing an AUTOPILOT reply (no approval). Returns null, and
     * queues nothing, when the contact is paused or taken over or automation is globally paused.
     */
    suspend fun queueAutopilot(conversationId: String, text: String): Message? = mutex.withLock {
        val conv = conversations.find { it.id == conversationId } ?: return@withLock null
        if (globalState.status == GlobalAutomationStatus.PAUSED) return@withLock null
        if ((contactState(conv.contact.id)?.status ?: ContactAutomationStatus.ACTIVE) != ContactAutomationStatus.ACTIVE) return@withLock null
        queueOutbound(conversationId, text, MessageOrigin.AUTOPILOT, approvalId = null).also {
            audit(AgentActionType.SEND_QUEUED, Actor.DISPATCHER, conversationId, conv.contact.id, it.id)
        }
    }

    /**
     * Plays the connector: moves an outbound message FORWARD one or more steps
     * (`queued -> sending -> sent -> delivered`), auditing each step. Returns false (no change)
     * for an unknown message, a backward or terminal transition, or a canceled message.
     */
    suspend fun simulateDelivery(messageId: String, target: DeliveryState): Boolean = mutex.withLock {
        val order = listOf(DeliveryState.QUEUED, DeliveryState.SENDING, DeliveryState.SENT, DeliveryState.DELIVERED)
        for ((convId, list) in messages) {
            val i = list.indexOfFirst { it.id == messageId }
            if (i < 0) continue
            val m = list[i]
            val from = order.indexOf(m.deliveryState)
            val to = order.indexOf(target)
            if (from < 0 || to <= from) return@withLock false
            val contactId = conversations.find { it.id == convId }?.contact?.id
            for (step in order.subList(from + 1, to + 1)) {
                val type = when (step) {
                    DeliveryState.SENDING -> AgentActionType.SEND_ATTEMPTED
                    DeliveryState.SENT -> AgentActionType.SEND_ACKNOWLEDGED
                    else -> AgentActionType.DELIVERY_CONFIRMED
                }
                audit(type, if (step == DeliveryState.SENDING) Actor.DISPATCHER else Actor.CONNECTOR, convId, contactId, m.id, m.approvalId)
            }
            val now = clock()
            list[i] = m.copy(
                deliveryState = target,
                attemptCount = maxOf(m.attemptCount, 1),
                sentAt = if (to >= 2) m.sentAt ?: now else null,
                deliveredAt = if (to >= 3) now else null,
                externalMessageId = if (to >= 2) m.externalMessageId ?: "mock-out-${m.id.takeLast(6)}" else m.externalMessageId,
            )
            return@withLock true
        }
        false
    }

    private fun audit(
        type: AgentActionType,
        actor: Actor,
        conversationId: String? = null,
        contactId: String? = null,
        messageId: String? = null,
        approvalId: String? = null,
        reasonCode: String? = null,
        details: JsonObject = JsonObject(emptyMap()),
    ) {
        auditLog += AgentAction(
            id = nextId(), type = type, actor = actor, conversationId = conversationId, contactId = contactId,
            messageId = messageId, approvalId = approvalId, reasonCode = reasonCode,
            occurredAt = clock(), details = details,
        )
    }

    /** Redacted audit details: IDs, counts and codes only (never message text). */
    private fun details(vararg pairs: Pair<String, Any>): JsonObject = JsonObject(
        pairs.associate { (k, v) ->
            k to when (v) {
                is Boolean -> JsonPrimitive(v)
                is Number -> JsonPrimitive(v)
                else -> JsonPrimitive(v.toString())
            }
        },
    )

    companion object {
        private const val CURSOR_PREFIX = "mock:"
        private val ID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

        /** Backend `CLIENT_VERIFIED_NOTE` (app.services.connections). */
        const val CLIENT_VERIFIED_NOTE: String =
            "Claimed VERIFIED by the client; stored as UNVERIFIED until server-side evidence " +
                "of a reproduced device run is recorded (ADR 0005, ADR 0007)."

        /** Backend `UNPAIRED_NOTE`. */
        const val UNPAIRED_NOTE: String = "Connection unpaired (ADR 0007)."

        private fun verifiedNames(set: CapabilitySet): List<String> = listOfNotNull(
            "read".takeIf { set.read.status == Verification.VERIFIED },
            "draft".takeIf { set.draft.status == Verification.VERIFIED },
            "send".takeIf { set.send.status == Verification.VERIFIED },
            "swipe".takeIf { set.swipe.status == Verification.VERIFIED },
            "calendar_write".takeIf { set.calendarWrite.status == Verification.VERIFIED },
        )

        /**
         * Mirror of backend `trust_claimed_capabilities` for a NEW pairing (no server-held document):
         * a claimed `VERIFIED` becomes `UNVERIFIED` with [CLIENT_VERIFIED_NOTE] (keeping only
         * `reports_delivery_receipts`); otherwise client `evidence`/`verified_at` are dropped.
         */
        fun trustClaimedCapabilities(claimed: ConnectorCapabilities): ConnectorCapabilities {
            fun trust(c: Capability): Capability = when (c.status) {
                Verification.VERIFIED -> Capability(Verification.UNVERIFIED, notes = CLIENT_VERIFIED_NOTE, reportsDeliveryReceipts = c.reportsDeliveryReceipts)
                else -> c.copy(evidence = null, verifiedAt = null)
            }
            val s = claimed.capabilities
            return claimed.copy(
                capabilities = CapabilitySet(trust(s.read), trust(s.draft), trust(s.send), trust(s.swipe), trust(s.calendarWrite)),
            )
        }

        private fun mockSimulatorCapabilities(now: Instant): ConnectorCapabilities {
            val unsupported = Capability(Verification.UNSUPPORTED)
            fun simulated(receipts: Boolean? = null) = Capability(
                Verification.VERIFIED,
                evidence = "backend/tests/connectors (deterministic simulator)",
                verifiedAt = now,
                notes = "Simulated transport. VERIFIED means the simulator contract is tested, not a real platform.",
                reportsDeliveryReceipts = receipts,
            )
            return ConnectorCapabilities(
                "connector_capabilities.v1", "mock", "0.1.0",
                CapabilitySet(simulated(), unsupported, simulated(receipts = true), unsupported, unsupported), now,
            )
        }

        /** D-014, as backend `PAUSE_CANCELS`: a pause cancels queued autopilot sends only. */
        val PAUSE_CANCELS: Set<MessageOrigin> = setOf(MessageOrigin.AUTOPILOT)

        /** D-014, as backend `AUTOMATED_ORIGINS`: takeover also cancels approved-but-unsent drafts. */
        val AUTOMATED_ORIGINS: Set<MessageOrigin> = setOf(MessageOrigin.AUTOPILOT, MessageOrigin.APPROVED_DRAFT)

        private fun ContactPreferenceOverride.isEmpty() = intent == null && style == null && automationMode == null

        /**
         * Mirror of backend `merge_effective` (WING-007): intent replaced as a whole, style merged per
         * attribute (samples replaced only when the override has some), `automation_mode` replaces
         * only `automation.mode`. Version and timestamp stay the user-wide ones.
         */
        fun mergeEffective(userWide: Preferences, override: ContactPreferenceOverride?): Preferences {
            if (override == null || override.isEmpty()) return userWide
            val base = userWide.style
            val style = override.style?.let { o ->
                StyleProfile(
                    punctuation = o.punctuation ?: base.punctuation,
                    emojiFrequency = o.emojiFrequency ?: base.emojiFrequency,
                    brevity = o.brevity ?: base.brevity,
                    humor = o.humor ?: base.humor,
                    tone = o.tone ?: base.tone,
                    responsePace = o.responsePace ?: base.responsePace,
                    styleSamples = o.styleSamples?.takeIf { it.isNotEmpty() } ?: base.styleSamples,
                )
            } ?: base
            return userWide.copy(
                intent = override.intent ?: userWide.intent,
                style = style,
                automation = override.automationMode?.let { userWide.automation.copy(mode = it) } ?: userWide.automation,
            )
        }

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
