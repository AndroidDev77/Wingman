package com.wingman.integrations.sms

import com.wingman.core.api.ApiError
import com.wingman.core.api.ApiResult
import com.wingman.core.api.IdempotencyKey
import com.wingman.core.api.WingmanApi
import com.wingman.core.api.WingmanJson
import com.wingman.core.model.Connection
import com.wingman.core.model.ConnectionCreateRequest
import com.wingman.core.model.ConnectionUnpairResult
import com.wingman.core.model.ConnectorCapabilities
import com.wingman.core.model.ErrorCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/*
 * I-020: pairing this phone's SMS as a Wingman connection (contract 1.2.0, ADR 0007).
 * Pure Kotlin: no android.* imports. All state lives in the connector's [SmsStateStore], next to the
 * dedup claims, the send ledger and the outbox, so it survives process death.
 */

/** Store keys shared by the pairing manager, the connector host and the poller. */
object SmsPairingKeys {
    /** Active scope: present exactly while this install is paired. */
    const val USER = "config:user_id"
    const val CONNECTION = "config:connection_id"

    /** Last `Connection` the server returned for the active scope (JSON), for display. */
    const val SNAPSHOT = "pairing:connection"

    /** Persisted `createConnection` attempt: key + exact body, reused until a final answer. */
    const val PENDING_PAIR = "pairing:pending"

    /** Persisted `unpairConnection` attempt for one connection. */
    const val PENDING_UNPAIR = "pairing:unpair_pending"

    /**
     * Scope of the last connection that stopped being active. Only used to finish leftover work:
     * upload envelopes still queued for it (the server dead-letters inbound ones with 422 and still
     * accepts late send outcomes) and record results of an SMS already handed to the radio.
     * Never used for inbound processing or claims.
     */
    const val RETIRED_USER = "pairing:retired_user_id"
    const val RETIRED_CONNECTION = "pairing:retired_connection_id"

    /** A connection the server reports disconnected but NOT unpaired: it still blocks re-pairing, so the user can unpair it. */
    const val STALE_CONNECTION = "pairing:stale_connection_id"

    /** Why the user must pair again (shown once on the connections screen). */
    const val NOTICE = "pairing:notice"

    const val LAST_SYNC_AT = "sync:last_at"
    const val LAST_SYNC_SUMMARY = "sync:last_summary"
}

/** The per-install UUID used for BOTH `createConnection.device_install_id` and `claimOutboxInstructions`. */
object SmsInstallId {
    const val KEY = "config:device_install_id"

    fun getOrCreate(store: SmsStateStore, newId: () -> String = { UUID.randomUUID().toString() }): String = synchronized(store) {
        store.get(KEY)?.let { return it }
        val id = newId()
        requireWingmanId(id, "device_install_id")
        store.put(KEY, id)
        id
    }
}

/** What the pairing manager knows locally. */
sealed interface SmsPairingState {
    /** Nothing paired. The SMS runtime does nothing (no inbound recording, no claims). */
    data class Unpaired(val notice: String? = null, val staleConnectionId: String? = null) : SmsPairingState

    /** Paired: ingest and claims use [scope]. [connection] is the server's last answer, when known. */
    data class Paired(val scope: SmsConnectionScope, val connection: Connection?) : SmsPairingState
}

sealed interface SmsPairResult {
    /** `201` or `200`: the ID is stored and the runtime runs against it. [connection] is what the server STORED. */
    data class Paired(val connection: Connection, val replayed: Boolean) : SmsPairResult

    /** The server returned a connection that is not paired (disconnected): not used; offer to unpair it. */
    data class ServerDisconnected(val connection: Connection) : SmsPairResult

    /** No final answer or a rejection. [retryable]: the same persisted key is reused on the next try. */
    data class Failed(val error: ApiError, val retryable: Boolean) : SmsPairResult
}

sealed interface SmsUnpairResult {
    /** Done (or the server no longer knows the connection: [result] null). Local state is cleared. */
    data class Unpaired(val result: ConnectionUnpairResult?) : SmsUnpairResult
    data object NotPaired : SmsUnpairResult
    /** The server did not confirm. The connection is still paired; retrying reuses the same key. */
    data class Failed(val error: ApiError) : SmsUnpairResult
}

sealed interface SmsVerifyResult {
    data object NotPaired : SmsVerifyResult
    data class StillPaired(val connection: Connection) : SmsVerifyResult
    /** Unpaired elsewhere (`404`, `unpaired_at`) or disconnected: sync stopped; the user must pair again. */
    data class NeedsRePair(val reason: String) : SmsVerifyResult
    /** Could not check (offline, signed out, server error): keep running with the stored ID. */
    data class Unknown(val error: ApiError) : SmsVerifyResult
}

/**
 * Pairs, verifies and unpairs this install's SMS connection.
 *
 * **Pair** ([pair]): `getMe` (for the envelope `user_id`), then `createConnection` with platform
 * `sms`, a display name, the SAME [SmsInstallId] the poller sends to `claimOutboxInstructions`, and
 * the capability document. The `Idempotency-Key` and the exact body are persisted BEFORE the request
 * and reused until a final answer, so a retry after a lost response replays the original instead of
 * pairing twice. `201` (new) and `200` (this install was already paired, e.g. app data kept across an
 * update) both store the returned ID. A `400`/`404`/`422` drops the attempt; anything else keeps it.
 *
 * **Verify** ([verify], on app start): `getConnection` on the stored ID. `404`, `unpaired_at` set or
 * `disconnected` retire the scope (sync stops) and leave a notice asking to pair again. Offline,
 * `401` or `5xx` keep the stored ID.
 *
 * **Unpair** ([unpair]): `unpairConnection` with a persisted key. On success (or `404`) the scope is
 * retired. Not-yet-uploaded envelopes are KEPT and drained against the retired connection (inbound
 * ones are dead-lettered on `422` by the existing rules; late send outcomes are still accepted).
 *
 * Capabilities are shown as the server STORED them. A client claim of `VERIFIED` comes back
 * `UNVERIFIED` (D-016); nothing here ever upgrades a status.
 *
 * [onScopeChanged] lets the connector host drop its cached connector when the active scope changes.
 */
class SmsPairingManager(
    private val store: SmsStateStore,
    private val api: WingmanApi,
    private val clock: SmsClock = SmsClock.SYSTEM,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
    private val onScopeChanged: (SmsConnectionScope?) -> Unit = {},
) {
    fun deviceInstallId(): String = SmsInstallId.getOrCreate(store)

    fun activeScope(): SmsConnectionScope? = scopeFrom(SmsPairingKeys.USER, SmsPairingKeys.CONNECTION)

    fun retiredScope(): SmsConnectionScope? = scopeFrom(SmsPairingKeys.RETIRED_USER, SmsPairingKeys.RETIRED_CONNECTION)

    fun storedConnection(): Connection? = store.get(SmsPairingKeys.SNAPSHOT)?.let {
        runCatching { WingmanJson.decodeFromString(Connection.serializer(), it) }.getOrNull()
    }

    fun state(): SmsPairingState = activeScope()?.let { SmsPairingState.Paired(it, storedConnection()) }
        ?: SmsPairingState.Unpaired(store.get(SmsPairingKeys.NOTICE), store.get(SmsPairingKeys.STALE_CONNECTION))

    /** The pair key persisted and not yet answered, if any (diagnostics and tests). */
    fun pendingPairKey(): String? = pendingPair()?.key

    fun pendingUnpairKey(): String? = pendingUnpair()?.key

    /**
     * Pairs this install. [capabilities] is the `connector_capabilities.v1` document from
     * [SmsCapabilities.build]; it is a claim, never evidence.
     */
    suspend fun pair(displayName: String, capabilities: JsonObject): SmsPairResult {
        activeScope()?.let { scope ->
            storedConnection()?.takeIf { it.id == scope.connectionId }?.let { return SmsPairResult.Paired(it, replayed = true) }
        }
        val me = when (val r = api.getMe()) {
            is ApiResult.Success -> r.value
            is ApiResult.Failure -> return SmsPairResult.Failed(r.error, r.error.isRetryable || r.error is ApiError.Network)
        }
        val attempt = pendingPair()
            ?.takeIf { it.userId == me.id && clock.nowMillis() - it.createdAtMillis < STALE_ATTEMPT_MILLIS }
            ?: newPairAttempt(me.id, displayName, capabilities)
        val key = IdempotencyKey(attempt.key)
        return when (val r = api.createConnection(attempt.body, key)) {
            is ApiResult.Success -> onPaired(attempt, r.value, r.idempotentReplayed)
            is ApiResult.Failure -> {
                val definitive = isDefinitive(r.error)
                if (definitive) store.remove(SmsPairingKeys.PENDING_PAIR)
                SmsPairResult.Failed(r.error, retryable = !definitive)
            }
        }
    }

    private fun onPaired(attempt: PendingPair, connection: Connection, replayed: Boolean): SmsPairResult {
        val install = attempt.body.deviceInstallId
        if (connection.platform != SMS_PLATFORM || (connection.deviceInstallId != null && connection.deviceInstallId != install)) {
            store.remove(SmsPairingKeys.PENDING_PAIR)
            return SmsPairResult.Failed(ApiError.Client("The server returned a connection for another platform or install."), retryable = false)
        }
        val scope = runCatching { SmsConnectionScope(attempt.userId, connection.id) }.getOrElse {
            store.remove(SmsPairingKeys.PENDING_PAIR)
            return SmsPairResult.Failed(ApiError.Client("The server returned an invalid connection ID."), retryable = false)
        }
        store.remove(SmsPairingKeys.PENDING_PAIR)
        if (!connection.isPaired) {
            store.put(SmsPairingKeys.STALE_CONNECTION, connection.id)
            return SmsPairResult.ServerDisconnected(connection)
        }
        val previous = activeScope()
        if (previous?.connectionId != scope.connectionId) resetClaimState()
        store.put(SmsPairingKeys.USER, scope.userId)
        store.put(SmsPairingKeys.CONNECTION, scope.connectionId)
        saveSnapshot(connection)
        store.remove(SmsPairingKeys.NOTICE)
        if (store.get(SmsPairingKeys.STALE_CONNECTION) == connection.id) store.remove(SmsPairingKeys.STALE_CONNECTION)
        onScopeChanged(scope)
        return SmsPairResult.Paired(connection, replayed)
    }

    /** App start: checks the stored connection still exists and is paired. */
    suspend fun verify(): SmsVerifyResult {
        val scope = activeScope() ?: return SmsVerifyResult.NotPaired
        return when (val r = api.getConnection(scope.connectionId)) {
            is ApiResult.Success -> {
                val c = r.value
                when {
                    c.unpairedAt != null -> retire(scope, NOTICE_UNPAIRED_ELSEWHERE, stale = null).let { SmsVerifyResult.NeedsRePair(NOTICE_UNPAIRED_ELSEWHERE) }
                    !c.isPaired -> retire(scope, NOTICE_DISCONNECTED, stale = c.id).let { SmsVerifyResult.NeedsRePair(NOTICE_DISCONNECTED) }
                    else -> {
                        saveSnapshot(c)
                        SmsVerifyResult.StillPaired(c)
                    }
                }
            }
            is ApiResult.Failure -> {
                val e = r.error
                if (e is ApiError.Http && e.status == 404) {
                    retire(scope, NOTICE_NOT_FOUND, stale = null)
                    SmsVerifyResult.NeedsRePair(NOTICE_NOT_FOUND)
                } else {
                    SmsVerifyResult.Unknown(e)
                }
            }
        }
    }

    /** Unpairs the active connection (or a stale disconnected one that still blocks re-pairing). */
    suspend fun unpair(): SmsUnpairResult {
        val active = activeScope()
        val connectionId = active?.connectionId ?: store.get(SmsPairingKeys.STALE_CONNECTION) ?: return SmsUnpairResult.NotPaired
        val attempt = pendingUnpair()?.takeIf { it.connectionId == connectionId && clock.nowMillis() - it.createdAtMillis < STALE_ATTEMPT_MILLIS }
            ?: PendingUnpair(newKey(), connectionId, clock.nowMillis()).also {
                store.put(SmsPairingKeys.PENDING_UNPAIR, WingmanJson.encodeToString(PendingUnpair.serializer(), it))
            }
        val r = api.unpairConnection(connectionId, IdempotencyKey(attempt.key))
        val result = when (r) {
            is ApiResult.Success -> r.value
            is ApiResult.Failure -> {
                val e = r.error
                if (!(e is ApiError.Http && e.status == 404)) {
                    if (isDefinitive(e)) store.remove(SmsPairingKeys.PENDING_UNPAIR)
                    return SmsUnpairResult.Failed(e)
                }
                null // already gone on the server
            }
        }
        store.remove(SmsPairingKeys.PENDING_UNPAIR)
        if (store.get(SmsPairingKeys.STALE_CONNECTION) == connectionId) store.remove(SmsPairingKeys.STALE_CONNECTION)
        if (active != null) retire(active, notice = null, stale = null)
        return SmsUnpairResult.Unpaired(result)
    }

    /** Stops using [scope]: inbound recording and claims end now; leftover envelopes still drain. */
    private fun retire(scope: SmsConnectionScope, notice: String?, stale: String?) {
        store.put(SmsPairingKeys.RETIRED_USER, scope.userId)
        store.put(SmsPairingKeys.RETIRED_CONNECTION, scope.connectionId)
        store.remove(SmsPairingKeys.CONNECTION)
        store.remove(SmsPairingKeys.USER)
        store.remove(SmsPairingKeys.SNAPSHOT)
        resetClaimState()
        if (notice != null) store.put(SmsPairingKeys.NOTICE, notice) else store.remove(SmsPairingKeys.NOTICE)
        if (stale != null) store.put(SmsPairingKeys.STALE_CONNECTION, stale)
        onScopeChanged(null)
    }

    /** A claim key belongs to one connection's route: never replay it against another connection. */
    private fun resetClaimState() {
        store.remove(SmsOutboxPoller.KEY_PENDING_CLAIM)
        SmsBackoff(store, SmsBackoff.CLAIM, clock).onProgress()
    }

    private fun saveSnapshot(c: Connection) = store.put(SmsPairingKeys.SNAPSHOT, WingmanJson.encodeToString(Connection.serializer(), c))

    private fun scopeFrom(userKey: String, connectionKey: String): SmsConnectionScope? {
        val user = store.get(userKey) ?: return null
        val connection = store.get(connectionKey) ?: return null
        return runCatching { SmsConnectionScope(user, connection) }.getOrNull()
    }

    private fun newPairAttempt(userId: String, displayName: String, capabilities: JsonObject): PendingPair {
        val caps = WingmanJson.decodeFromJsonElement(ConnectorCapabilities.serializer(), capabilities)
        val body = ConnectionCreateRequest(
            platform = SMS_PLATFORM,
            displayName = displayName.trim().take(120).ifEmpty { DEFAULT_DISPLAY_NAME },
            deviceInstallId = deviceInstallId(),
            capabilities = caps,
        )
        val attempt = PendingPair(newKey(), userId, body, clock.nowMillis())
        // Persisted BEFORE the request: a lost response is recovered by replaying the same key and body.
        store.put(SmsPairingKeys.PENDING_PAIR, WingmanJson.encodeToString(PendingPair.serializer(), attempt))
        return attempt
    }

    private fun pendingPair(): PendingPair? = store.get(SmsPairingKeys.PENDING_PAIR)?.let {
        runCatching { WingmanJson.decodeFromString(PendingPair.serializer(), it) }.getOrNull()
    }

    private fun pendingUnpair(): PendingUnpair? = store.get(SmsPairingKeys.PENDING_UNPAIR)?.let {
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
        const val DEFAULT_DISPLAY_NAME = "Android SMS"

        /** Server keeps idempotency keys at least 24 h (ADR 0004); after that a replay would be a new request. */
        const val STALE_ATTEMPT_MILLIS: Long = 23L * 60 * 60 * 1000

        const val NOTICE_NOT_FOUND = "This phone's SMS connection no longer exists on Wingman (it may have been removed from another device). Pair again to keep using SMS."
        const val NOTICE_UNPAIRED_ELSEWHERE = "This phone's SMS connection was disconnected elsewhere. Pair again to keep using SMS."
        const val NOTICE_DISCONNECTED = "Wingman reports this phone's SMS connection as disconnected. Disconnect it here, then pair again."

        /** The request was answered and not processed: drop the attempt so the next try is a new action. */
        fun isDefinitive(e: ApiError): Boolean = e is ApiError.Client ||
            (e is ApiError.Http && e.status in setOf(400, 404, 422) && e.code != ErrorCode.IDEMPOTENCY_KEY_IN_PROGRESS)

        /** `display_name` for this phone, e.g. "Pixel 8 SMS" (1..120 chars). */
        fun displayNameFor(manufacturer: String?, model: String?): String {
            val m = model?.trim().orEmpty()
            val maker = manufacturer?.trim().orEmpty()
            val base = when {
                m.isEmpty() -> maker
                maker.isEmpty() || m.startsWith(maker, ignoreCase = true) -> m
                else -> "${maker.replaceFirstChar { it.uppercase() }} $m"
            }
            return if (base.isEmpty()) DEFAULT_DISPLAY_NAME else "${base.take(110)} SMS"
        }
    }
}
