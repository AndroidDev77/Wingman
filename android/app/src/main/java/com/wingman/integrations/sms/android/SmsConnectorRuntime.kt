package com.wingman.integrations.sms.android

import android.content.Context
import android.content.pm.ApplicationInfo
import com.wingman.core.api.WingmanApi
import com.wingman.integrations.sms.FileSmsStateStore
import com.wingman.integrations.sms.IncomingSms
import com.wingman.integrations.sms.SmsConnectionScope
import com.wingman.integrations.sms.SmsConnector
import com.wingman.integrations.sms.SmsSendCommand
import com.wingman.integrations.sms.SmsStateStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File

/*
 * THIN ANDROID ADAPTER (WING-016). UNVERIFIED: never compiled against the Android SDK in the
 * authoring environment (I-001/I-008) and never run on a device (I-003). All logic lives in the
 * pure-Kotlin package `com.wingman.integrations.sms`, which is unit-tested.
 */

/**
 * Process-wide holder. BroadcastReceivers run in a cold process after a reboot or process death,
 * so the connector must be rebuildable from disk alone: the connection scope is persisted in the
 * same app-private state file as the dedup claims, send ledger and outbox.
 *
 * Integrator wiring (AppContainer, D-003): call [configure] once the user enables the SMS
 * connection (scope = the Wingman user and connection IDs), and [connectBackend] with
 * `AppContainer.api`. That client carries the bearer token from AppContainer's token provider;
 * there is none yet (I-011), so until then every backend call answers 401 and is retried later.
 * Then call [syncInBackground] periodically (WorkManager, requested) and on app start.
 */
object SmsConnectorRuntime {
    private const val STATE_FILE = "wingman-sms-state.json"
    private const val KEY_USER = "config:user_id"
    private const val KEY_CONNECTION = "config:connection_id"

    @Volatile private var store: SmsStateStore? = null
    @Volatile private var connector: SmsConnector? = null
    @Volatile private var api: WingmanApi? = null

    @Synchronized
    private fun store(context: Context): SmsStateStore =
        store ?: FileSmsStateStore(File(context.applicationContext.filesDir, STATE_FILE)).also { store = it }

    /** Persists the scope and (re)builds the connector. */
    @Synchronized
    fun configure(context: Context, scope: SmsConnectionScope) {
        val s = store(context)
        s.put(KEY_USER, scope.userId)
        s.put(KEY_CONNECTION, scope.connectionId)
        connector = null
    }

    /**
     * Connects the connector to the backend: envelope upload (`ingestConnectorEvent`) and the
     * outbox claim poller (`claimOutboxInstructions`). Null disconnects (envelopes stay queued,
     * nothing is claimed). Auth comes from [api] itself; this class never handles tokens.
     */
    @Synchronized
    fun connectBackend(newApi: WingmanApi?) {
        // Same client (e.g. MainActivity recreated): keep the connector. Rebuilding it would give a
        // second SmsSender/SmsOutboxPoller, whose per-instance locks would not exclude a sync still
        // running on the old one.
        if (newApi === api) return
        api = newApi
        connector = null
    }

    /** Serializes sync rounds process-wide, across connector rebuilds (one claim/send round at a time). */
    private val syncLock = Any()

    /**
     * One upload + claim + execute round on a background thread (never the main thread: it does
     * blocking network I/O). Returns immediately. Safe to call repeatedly: the connector's methods
     * are synchronized and every step is idempotent.
     */
    fun syncInBackground(context: Context, onDone: ((SmsConnector.SyncReport?) -> Unit)? = null) {
        val app = context.applicationContext
        Thread {
            val report = synchronized(syncLock) {
                runCatching {
                    get(app)?.sync(SmsRoleHelper.sendPreconditions(app))
                }.getOrNull()
            }
            onDone?.invoke(report)
        }.apply { name = "wingman-sms-sync" }.start()
    }

    /** The connector, or null while the user has not enabled/configured the SMS connection. */
    @Synchronized
    fun get(context: Context): SmsConnector? {
        connector?.let { return it }
        val s = store(context)
        val user = s.get(KEY_USER) ?: return null
        val connection = s.get(KEY_CONNECTION) ?: return null
        val scope = runCatching { SmsConnectionScope(user, connection) }.getOrNull() ?: return null
        return SmsConnector(scope, s, AndroidSmsTransport(context.applicationContext), api = api).also { connector = it }
    }

    // ------------------------------------------------------------ debug-only device test hooks

    private const val DEBUG_LAST_INBOUND = "debug:last_inbound"
    private const val DEBUG_LAST_COMMAND = "debug:last_command"

    fun isDebuggable(context: Context): Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /**
     * DEBUG BUILDS ONLY: remembers the fields of the last real SMS so the checklist can replay
     * it after a process kill (a forged SMS_DELIVER broadcast is impossible: it is protected by
     * the signature permission BROADCAST_SMS). Stored in the same app-private file that already
     * holds the envelope text in the outbox.
     */
    fun rememberDebugInbound(context: Context, sms: IncomingSms) {
        if (!isDebuggable(context)) return
        store(context).put(DEBUG_LAST_INBOUND, buildJsonObject {
            put("address", sms.originatingAddress)
            put("body", sms.body)
            put("smsc_ts", sms.serviceCenterTimestampMillis)
            put("received_at", sms.receivedAtMillis)
            sms.subscriptionId?.let { put("sub", it) }
            put("parts", sms.partCount)
        }.toString())
    }

    fun debugLastInbound(context: Context): IncomingSms? {
        if (!isDebuggable(context)) return null
        val o = store(context).get(DEBUG_LAST_INBOUND)?.let { Json.parseToJsonElement(it).jsonObject } ?: return null
        return IncomingSms(
            originatingAddress = o.getValue("address").jsonPrimitive.content,
            body = o.getValue("body").jsonPrimitive.content,
            serviceCenterTimestampMillis = o.getValue("smsc_ts").jsonPrimitive.long,
            // A redelivery is observed later; the identity must not depend on this.
            receivedAtMillis = System.currentTimeMillis(),
            subscriptionId = o["sub"]?.jsonPrimitive?.intOrNull,
            partCount = o.getValue("parts").jsonPrimitive.int,
        )
    }

    /** DEBUG BUILDS ONLY: remembers the last demo send command so it can be re-issued after a kill. */
    fun rememberDebugCommand(context: Context, c: SmsSendCommand) {
        if (!isDebuggable(context)) return
        store(context).put(DEBUG_LAST_COMMAND, buildJsonObject {
            put("outbound_message_id", c.outboundMessageId)
            put("idempotency_key", c.idempotencyKey)
            put("external_thread_id", c.externalThreadId)
            put("text", c.text)
            put("authorized_by", c.authorizedBy)
        }.toString())
    }

    fun debugLastCommand(context: Context): SmsSendCommand? {
        if (!isDebuggable(context)) return null
        val o = store(context).get(DEBUG_LAST_COMMAND)?.let { Json.parseToJsonElement(it).jsonObject } ?: return null
        fun f(k: String) = o.getValue(k).jsonPrimitive.content
        return SmsSendCommand(f("outbound_message_id"), f("idempotency_key"), f("external_thread_id"), f("text"), f("authorized_by"))
    }
}
