package com.wingman.integrations.notifications

import com.wingman.core.api.ApiError
import com.wingman.core.api.ApiResult
import com.wingman.core.api.IdempotencyKey
import com.wingman.core.api.WingmanApi
import com.wingman.core.api.WingmanJson
import com.wingman.core.model.Connection
import com.wingman.core.model.ConnectionCreateRequest
import com.wingman.core.model.ConnectionTransport
import com.wingman.core.model.ConnectionUnpairResult
import com.wingman.core.model.ConnectorCapabilities
import com.wingman.core.model.ErrorCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/*
 * I-033 (WING-027/028): pairing one dating app's notifications as its own Wingman connection
 * (`POST /v1/connections`, platform `tinder`/`bumble`, `transport: notification`, contract 1.3.0).
 * Pure Kotlin. Same rules as SMS pairing (I-020): the key and exact body are persisted before the
 * request and replayed after a lost response; `201` and `200` both store the returned connection;
 * `400`/`404`/`422` drop the attempt. State lives in the notification state file, per platform.
 */

sealed interface NotificationPairResult {
    data class Paired(val connection: Connection, val replayed: Boolean) : NotificationPairResult
    data class Failed(val error: ApiError, val retryable: Boolean) : NotificationPairResult
}

sealed interface NotificationUnpairResult {
    /** Done, or the server no longer knew the connection ([result] null). Local scope cleared. */
    data class Unpaired(val result: ConnectionUnpairResult?) : NotificationUnpairResult
    data object NotPaired : NotificationUnpairResult
    data class Failed(val error: ApiError) : NotificationUnpairResult
}

sealed interface NotificationVerifyResult {
    data object NotPaired : NotificationVerifyResult
    data class StillPaired(val connection: Connection) : NotificationVerifyResult
    data class NeedsRePair(val reason: String) : NotificationVerifyResult
    data class Unknown(val error: ApiError) : NotificationVerifyResult
}

class NotificationPairingManager(
    private val store: NotificationStateStore,
    /** Null: local state only (pair, verify and unpair answer "no backend"). */
    private val api: WingmanApi?,
    val platform: String,
    private val clock: NotificationClock = NotificationClock.SYSTEM,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) {
    init {
        require(NotificationPluginRegistry.PLATFORM_PATTERN.matches(platform)) { "platform key" }
    }

    private val kUser = "pairing:$platform:user_id"
    private val kConnection = "pairing:$platform:connection_id"
    private val kSnapshot = "pairing:$platform:connection"
    private val kPending = "pairing:$platform:pending"
    private val kPendingUnpair = "pairing:$platform:unpair_pending"
    private val kRetiredUser = "pairing:$platform:retired_user_id"
    private val kRetiredConnection = "pairing:$platform:retired_connection_id"
    private val kNotice = "pairing:$platform:notice"

    fun deviceInstallId(): String = NotificationInstallId.getOrCreate(store)

    fun activeScope(): NotificationConnectionScope? = scope(kUser, kConnection)

    /** The last connection that stopped being active: only for late send outcomes. */
    fun retiredScope(): NotificationConnectionScope? = scope(kRetiredUser, kRetiredConnection)

    fun storedConnection(): Connection? = store.get(kSnapshot)?.let {
        runCatching { WingmanJson.decodeFromString(Connection.serializer(), it) }.getOrNull()
    }

    fun notice(): String? = store.get(kNotice)

    fun pendingPairKey(): String? = pendingPair()?.key

    /** Pairs with [capabilities] (`connector_capabilities.v1`, a claim, never evidence). */
    suspend fun pair(displayName: String, capabilities: JsonObject): NotificationPairResult {
        activeScope()?.let { s -> storedConnection()?.takeIf { it.id == s.connectionId }?.let { return NotificationPairResult.Paired(it, true) } }
        val api = api ?: return NotificationPairResult.Failed(NO_BACKEND, retryable = true)
        val me = when (val r = api.getMe()) {
            is ApiResult.Success -> r.value
            is ApiResult.Failure -> return NotificationPairResult.Failed(r.error, r.error.isRetryable || r.error is ApiError.Network)
        }
        val attempt = pendingPair()?.takeIf { it.userId == me.id && clock.nowMillis() - it.createdAtMillis < STALE_ATTEMPT_MILLIS }
            ?: newAttempt(me.id, displayName, capabilities)
        return when (val r = api.createConnection(attempt.body, IdempotencyKey(attempt.key))) {
            is ApiResult.Success -> onPaired(attempt, r.value, r.idempotentReplayed)
            is ApiResult.Failure -> {
                val definitive = isDefinitive(r.error)
                if (definitive) store.remove(kPending)
                NotificationPairResult.Failed(r.error, retryable = !definitive)
            }
        }
    }

    private fun onPaired(attempt: PendingPair, c: Connection, replayed: Boolean): NotificationPairResult {
        store.remove(kPending)
        val wrong = c.platform != platform || (c.deviceInstallId != null && c.deviceInstallId != attempt.body.deviceInstallId) ||
            (c.transport != null && c.transport != ConnectionTransport.NOTIFICATION)
        if (wrong) return NotificationPairResult.Failed(ApiError.Client("The server returned a connection for another platform, install or transport."), false)
        if (!c.isPaired) return NotificationPairResult.Failed(ApiError.Client("The server returned a disconnected connection."), false)
        val scope = runCatching { NotificationConnectionScope(attempt.userId, c.id) }.getOrElse {
            return NotificationPairResult.Failed(ApiError.Client("The server returned an invalid connection ID."), false)
        }
        if (activeScope()?.connectionId != scope.connectionId) resetClaim()
        store.put(kUser, scope.userId)
        store.put(kConnection, scope.connectionId)
        saveSnapshot(c)
        store.remove(kNotice)
        return NotificationPairResult.Paired(c, replayed)
    }

    /** App start / resume: the stored connection still exists and is paired. */
    suspend fun verify(): NotificationVerifyResult {
        val scope = activeScope() ?: return NotificationVerifyResult.NotPaired
        val api = api ?: return NotificationVerifyResult.Unknown(NO_BACKEND)
        return when (val r = api.getConnection(scope.connectionId)) {
            is ApiResult.Success -> when {
                r.value.unpairedAt != null || !r.value.isPaired -> {
                    retire(scope, NOTICE_DISCONNECTED)
                    NotificationVerifyResult.NeedsRePair(NOTICE_DISCONNECTED)
                }
                else -> {
                    saveSnapshot(r.value)
                    NotificationVerifyResult.StillPaired(r.value)
                }
            }
            is ApiResult.Failure -> {
                val e = r.error
                if (e is ApiError.Http && e.status == 404) {
                    retire(scope, NOTICE_NOT_FOUND)
                    NotificationVerifyResult.NeedsRePair(NOTICE_NOT_FOUND)
                } else {
                    NotificationVerifyResult.Unknown(e)
                }
            }
        }
    }

    suspend fun unpair(): NotificationUnpairResult {
        val active = activeScope() ?: return NotificationUnpairResult.NotPaired
        val api = api ?: return NotificationUnpairResult.Failed(NO_BACKEND)
        val attempt = pendingUnpair()?.takeIf { it.connectionId == active.connectionId && clock.nowMillis() - it.createdAtMillis < STALE_ATTEMPT_MILLIS }
            ?: PendingUnpair(newKey(), active.connectionId, clock.nowMillis()).also {
                store.put(kPendingUnpair, WingmanJson.encodeToString(PendingUnpair.serializer(), it))
            }
        val result = when (val r = api.unpairConnection(active.connectionId, IdempotencyKey(attempt.key))) {
            is ApiResult.Success -> r.value
            is ApiResult.Failure -> {
                val e = r.error
                if (!(e is ApiError.Http && e.status == 404)) {
                    if (isDefinitive(e)) store.remove(kPendingUnpair)
                    return NotificationUnpairResult.Failed(e)
                }
                null
            }
        }
        store.remove(kPendingUnpair)
        retire(active, notice = null)
        return NotificationUnpairResult.Unpaired(result)
    }

    private fun retire(scope: NotificationConnectionScope, notice: String?) {
        store.put(kRetiredUser, scope.userId)
        store.put(kRetiredConnection, scope.connectionId)
        store.remove(kConnection)
        store.remove(kUser)
        store.remove(kSnapshot)
        resetClaim()
        if (notice != null) store.put(kNotice, notice) else if (store.get(kNotice) != null) store.remove(kNotice)
    }

    private fun resetClaim() {
        store.remove(NotificationOutboxPoller.pendingClaimKey(platform))
        NotificationBackoff(store, NotificationBackoff.claim(platform), clock).onProgress()
    }

    private fun saveSnapshot(c: Connection) = store.put(kSnapshot, WingmanJson.encodeToString(Connection.serializer(), c))

    private fun scope(userKey: String, connectionKey: String): NotificationConnectionScope? {
        val u = store.get(userKey) ?: return null
        val c = store.get(connectionKey) ?: return null
        return runCatching { NotificationConnectionScope(u, c) }.getOrNull()
    }

    private fun newAttempt(userId: String, displayName: String, capabilities: JsonObject): PendingPair {
        val caps = WingmanJson.decodeFromJsonElement(ConnectorCapabilities.serializer(), capabilities)
        val body = ConnectionCreateRequest(
            platform = platform,
            displayName = displayName.trim().take(120).ifEmpty { platform },
            deviceInstallId = deviceInstallId(),
            capabilities = caps,
            transport = ConnectionTransport.NOTIFICATION,
        )
        val attempt = PendingPair(newKey(), userId, body, clock.nowMillis())
        store.put(kPending, WingmanJson.encodeToString(PendingPair.serializer(), attempt)) // before the request
        return attempt
    }

    private fun pendingPair(): PendingPair? = store.get(kPending)?.let {
        runCatching { WingmanJson.decodeFromString(PendingPair.serializer(), it) }.getOrNull()
    }

    private fun pendingUnpair(): PendingUnpair? = store.get(kPendingUnpair)?.let {
        runCatching { WingmanJson.decodeFromString(PendingUnpair.serializer(), it) }.getOrNull()
    }

    @Serializable
    private data class PendingPair(
        val key: String,
        @SerialName("user_id") val userId: String,
        val body: ConnectionCreateRequest,
        @SerialName("created_at") val createdAtMillis: Long,
    )

    @Serializable
    private data class PendingUnpair(
        val key: String,
        @SerialName("connection_id") val connectionId: String,
        @SerialName("created_at") val createdAtMillis: Long,
    )

    companion object {
        val NO_BACKEND = ApiError.Client("Wingman isn't connected to a server.")
        const val STALE_ATTEMPT_MILLIS: Long = 23L * 60 * 60 * 1000
        const val NOTICE_NOT_FOUND = "This app's notification connection no longer exists on Wingman. Turn reading on again to pair."
        const val NOTICE_DISCONNECTED = "This app's notification connection was disconnected. Turn reading on again to pair."

        fun isDefinitive(e: ApiError): Boolean = e is ApiError.Client ||
            (e is ApiError.Http && e.status in setOf(400, 404, 422) && e.code != ErrorCode.IDEMPOTENCY_KEY_IN_PROGRESS)
    }
}
