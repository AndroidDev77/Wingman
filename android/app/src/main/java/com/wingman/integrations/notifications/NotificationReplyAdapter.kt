package com.wingman.integrations.notifications

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val IDEMPOTENCY_KEY_PATTERN = Regex("^[A-Za-z0-9._:-]{8,128}$")
private val UUID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

/**
 * One authorized reply, built ONLY from an outbox instruction this device claimed (ADR 0006):
 * [NotificationContractMapping.replyCommandFrom] is the production constructor path. The listener
 * core has no reference to the reply adapter, so nothing inbound can create one.
 *
 * @property externalConversationId the conversation to reply into: the `external_thread_id` of the
 *   instruction, which is the plugin's linked conversation ID.
 * @property idempotencyKey the dispatcher's per-attempt key (D-006).
 * @property authorizedBy what authorized it (`approved_draft/<approval_id>`, `user_manual`, ...).
 * @property leaseDeadlineMillis local wall-clock instant at or after which the action must not be
 *   fired (ADR 0006 deadline, computed by the claim path). Required: there is no unleased reply.
 */
class NotificationReplyCommand(
    val platform: String,
    val instructionId: String,
    val outboundMessageId: String,
    val idempotencyKey: String,
    val externalConversationId: String,
    val text: String,
    val authorizedBy: String,
    val leaseDeadlineMillis: Long,
) {
    init {
        require(NotificationPluginRegistry.PLATFORM_PATTERN.matches(platform)) { "platform key" }
        require(UUID_PATTERN.matches(instructionId)) { "instructionId must be a lowercase canonical UUID" }
        require(UUID_PATTERN.matches(outboundMessageId)) { "outboundMessageId must be a lowercase canonical UUID" }
        require(IDEMPOTENCY_KEY_PATTERN.matches(idempotencyKey)) { "idempotencyKey must match ${IDEMPOTENCY_KEY_PATTERN.pattern}" }
        require(externalConversationId.isNotBlank() && externalConversationId.length <= 256) { "externalConversationId length" }
        require(text.isNotBlank() && text.length <= 20_000) { "text must be 1..20000 chars" }
        require(authorizedBy.isNotBlank() && authorizedBy.length <= 200) { "authorizedBy is required" }
    }

    override fun toString(): String =
        "NotificationReplyCommand(platform=$platform, instruction=$instructionId, outbound=$outboundMessageId, key=$idempotencyKey, " +
            "conversation=#${NotificationHashes.short(externalConversationId)}, text=<redacted ${text.length} chars>, authorizedBy=$authorizedBy)"
}

/** Why a reply did not happen. Each maps to one contract failure code in [NotificationContractMapping]. */
enum class ReplyFailureReason(val retryable: Boolean) {
    /** Notification access is off: the listener is stopped. */
    ACCESS_REVOKED(false),
    /** No plugin for the platform, or the user has not enabled reading it. */
    PLATFORM_UNAVAILABLE(false),
    /** The user has not enabled direct reply for this platform. */
    DIRECT_REPLY_DISABLED(false),
    /** No live notification with a free-form RemoteInput for this conversation. */
    ACTION_MISSING(false),
    /** The live action is older than the allowed age: not used, the app may have moved on. */
    ACTION_STALE(false),
    /** `PendingIntent.send` reported the intent canceled: nothing was handed to the app. */
    ACTION_CANCELED(false),
    /** Firing threw before the intent was sent. */
    FIRE_ERROR(false),
    /** The lease deadline passed before the action could be fired (ADR 0006: `timeout`, retryable). */
    LEASE_DEADLINE_PASSED(true),
    /** The process died between claiming the key and recording the result: the text may have gone out. */
    OUTCOME_UNKNOWN(false),
}

/** Local state of one reply key. CLAIMED is written BEFORE the action fires. One-way transitions. */
@Serializable
enum class ReplyState {
    @SerialName("claimed") CLAIMED,
    /** The PendingIntent fired: the app was handed the text. NOT sent, NOT delivered. */
    @SerialName("submitted") SUBMITTED,
    @SerialName("failed") FAILED,
}

/** Persisted ledger entry for one reply key. Holds no text and no conversation ID in clear. */
@Serializable
data class ReplyRecord(
    @SerialName("idempotency_key") val idempotencyKey: String,
    val platform: String,
    @SerialName("instruction_id") val instructionId: String,
    @SerialName("outbound_message_id") val outboundMessageId: String,
    @SerialName("conversation_sha256") val conversationSha256: String,
    @SerialName("text_sha256") val textSha256: String,
    @SerialName("authorized_by") val authorizedBy: String,
    val state: ReplyState,
    @SerialName("claimed_at") val claimedAtMillis: Long,
    @SerialName("updated_at") val updatedAtMillis: Long = claimedAtMillis,
    /** True once [ReplyActionHandle.fire] was called for this key (the one attempt is spent). */
    @SerialName("action_fired") val actionFired: Boolean = false,
    @SerialName("failure_reason") val failureReason: ReplyFailureReason? = null,
    /** Set when the outcome has been handed to the uplink. */
    val reported: Boolean = false,
)

/** Outcome of [NotificationReplyAdapter.reply]. None of these means "sent" or "delivered". */
sealed interface ReplyOutcome {
    val record: ReplyRecord
    /** True when the key was used before and nothing was fired now. */
    val replayed: Boolean

    /** The PendingIntent fired. Status `submitted`. */
    data class Submitted(override val record: ReplyRecord, override val replayed: Boolean) : ReplyOutcome

    /** Typed failure, no send claim. With [replayed], the stored failure is returned and nothing fired. */
    data class Failed(override val record: ReplyRecord, val reason: ReplyFailureReason, override val replayed: Boolean) : ReplyOutcome

    /** The key was reused for a different text or conversation: rejected, nothing fired. */
    data class KeyConflict(override val record: ReplyRecord) : ReplyOutcome {
        override val replayed: Boolean get() = true
    }
}

/**
 * The RemoteInput reply adapter. Rules:
 * - **One attempt per key, persisted before firing (D-006).** The key is written to the ledger as
 *   CLAIMED before any check that could fire; a repeated key never fires again (same body: the
 *   stored outcome with `replayed`; different body or conversation: [ReplyOutcome.KeyConflict]).
 *   A refused precondition also uses up the key.
 * - **Only with consent and access.** Fires only while notification access is granted, the
 *   platform's plugin exists and reading is enabled, and the user enabled direct reply for it.
 * - **Only through a live, fresh action** for exactly that platform and conversation. Missing or
 *   stale: a typed failure and no send claim.
 * - **Submitted, never sent.** A fired PendingIntent is recorded as [ReplyState.SUBMITTED]. The
 *   app may still drop the text; nothing here can observe delivery.
 * - **Lease deadline** checked last, right before firing.
 */
class NotificationReplyAdapter(
    private val core: NotificationListenerCore,
    private val store: NotificationStateStore,
    private val clock: NotificationClock = NotificationClock.SYSTEM,
    private val log: NotificationLog = NotificationLog.NONE,
) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private fun key(k: String) = "$PREFIX$k"

    fun record(idempotencyKey: String): ReplyRecord? =
        store.get(key(idempotencyKey))?.let { json.decodeFromString(ReplyRecord.serializer(), it) }

    /** How many times [ReplyActionHandle.fire] was called for this key: 0 or 1, across restarts. */
    fun fireAttempts(idempotencyKey: String): Int = if (record(idempotencyKey)?.actionFired == true) 1 else 0

    private fun save(r: ReplyRecord) = store.put(key(r.idempotencyKey), json.encodeToString(ReplyRecord.serializer(), r))

    @Synchronized
    fun reply(command: NotificationReplyCommand): ReplyOutcome {
        record(command.idempotencyKey)?.let { return replay(it, command) }
        val now = clock.nowMillis()
        val claimed = ReplyRecord(
            idempotencyKey = command.idempotencyKey,
            platform = command.platform,
            instructionId = command.instructionId,
            outboundMessageId = command.outboundMessageId,
            conversationSha256 = NotificationHashes.sha256Hex(command.externalConversationId),
            textSha256 = NotificationHashes.sha256Hex(command.text),
            authorizedBy = command.authorizedBy,
            state = ReplyState.CLAIMED,
            claimedAtMillis = now,
        )
        save(claimed) // durable BEFORE anything can fire

        val settings = core.settings
        val live = core.liveActions.latestFor(command.platform, command.externalConversationId)
        val precheck = when {
            !core.isActive -> ReplyFailureReason.ACCESS_REVOKED
            core.capabilities(command.platform).read.status == CapabilityStatus.UNSUPPORTED -> ReplyFailureReason.PLATFORM_UNAVAILABLE
            !settings.directReplyEnabled(command.platform) -> ReplyFailureReason.DIRECT_REPLY_DISABLED
            live == null -> ReplyFailureReason.ACTION_MISSING
            core.liveActions.isStale(live, clock.nowMillis()) -> ReplyFailureReason.ACTION_STALE
            clock.nowMillis() >= command.leaseDeadlineMillis -> ReplyFailureReason.LEASE_DEADLINE_PASSED
            else -> null
        }
        if (precheck != null) return fail(claimed, precheck, fired = false)

        val fired = claimed.copy(actionFired = true, updatedAtMillis = clock.nowMillis())
        save(fired) // the one attempt is spent from here on, even if the process dies inside fire()
        val result = runCatching { live!!.handle.fire(command.text) }.getOrElse { FireResult.Error(it.javaClass.simpleName) }
        return when (result) {
            FireResult.Fired -> {
                // The notification's action is single-use: the app usually updates or cancels it.
                core.liveActions.remove(live!!.notificationKey)
                val submitted = fired.copy(state = ReplyState.SUBMITTED, updatedAtMillis = clock.nowMillis())
                save(submitted)
                log.log("notif.reply", mapOf("platform" to command.platform, "key" to command.idempotencyKey, "state" to "submitted"))
                ReplyOutcome.Submitted(submitted, replayed = false)
            }
            FireResult.Canceled -> {
                core.liveActions.remove(live!!.notificationKey)
                fail(fired, ReplyFailureReason.ACTION_CANCELED, fired = true)
            }
            is FireResult.Error -> fail(fired, ReplyFailureReason.FIRE_ERROR, fired = true)
        }
    }

    /**
     * After process death: a key still CLAIMED has no recorded result. If the action had been
     * fired the text may be out, so it is failed as [ReplyFailureReason.OUTCOME_UNKNOWN] (not
     * retryable); otherwise nothing fired and it is failed the same way, conservatively.
     * Call at listener start, before any new reply.
     */
    @Synchronized
    fun recoverAfterRestart(): Int {
        var n = 0
        for (k in store.keys(PREFIX)) {
            val r = store.get(k)?.let { json.decodeFromString(ReplyRecord.serializer(), it) } ?: continue
            if (r.state == ReplyState.CLAIMED) {
                save(r.copy(state = ReplyState.FAILED, failureReason = ReplyFailureReason.OUTCOME_UNKNOWN, updatedAtMillis = clock.nowMillis()))
                n++
            }
        }
        return n
    }

    /** Outcomes not yet handed to the uplink (submitted or failed), oldest first. */
    fun unreported(): List<ReplyRecord> = store.keys(PREFIX).mapNotNull { store.get(it) }
        .map { json.decodeFromString(ReplyRecord.serializer(), it) }
        .filter { !it.reported && it.state != ReplyState.CLAIMED }

    @Synchronized
    fun markReported(idempotencyKey: String) {
        record(idempotencyKey)?.let { save(it.copy(reported = true)) }
    }

    private fun fail(r: ReplyRecord, reason: ReplyFailureReason, fired: Boolean): ReplyOutcome.Failed {
        val failed = r.copy(state = ReplyState.FAILED, failureReason = reason, actionFired = fired, updatedAtMillis = clock.nowMillis())
        save(failed)
        log.log("notif.reply", mapOf("platform" to r.platform, "key" to r.idempotencyKey, "state" to "failed", "reason" to reason.name.lowercase()))
        return ReplyOutcome.Failed(failed, reason, replayed = false)
    }

    private fun replay(existing: ReplyRecord, command: NotificationReplyCommand): ReplyOutcome {
        val same = existing.textSha256 == NotificationHashes.sha256Hex(command.text) &&
            existing.conversationSha256 == NotificationHashes.sha256Hex(command.externalConversationId) &&
            existing.platform == command.platform
        if (!same) return ReplyOutcome.KeyConflict(existing)
        return when (existing.state) {
            ReplyState.SUBMITTED -> ReplyOutcome.Submitted(existing, replayed = true)
            ReplyState.FAILED -> ReplyOutcome.Failed(existing, existing.failureReason ?: ReplyFailureReason.OUTCOME_UNKNOWN, replayed = true)
            // Claimed but no result: in this process another call is impossible (synchronized), so a
            // restart happened. Never fire again: treat as unknown.
            ReplyState.CLAIMED -> {
                val failed = existing.copy(state = ReplyState.FAILED, failureReason = ReplyFailureReason.OUTCOME_UNKNOWN, updatedAtMillis = clock.nowMillis())
                save(failed)
                ReplyOutcome.Failed(failed, ReplyFailureReason.OUTCOME_UNKNOWN, replayed = true)
            }
        }
    }

    companion object {
        const val PREFIX = "reply:"
    }
}
