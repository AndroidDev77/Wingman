package com.wingman.integrations.sms.android

import android.content.Context
import android.content.pm.ApplicationInfo
import com.wingman.core.api.WingmanApi
import com.wingman.integrations.sms.FileSmsStateStore
import com.wingman.integrations.sms.IncomingSms
import com.wingman.integrations.sms.SmsConnectionScope
import com.wingman.integrations.sms.SmsConnector
import com.wingman.integrations.sms.SmsConnectorHost
import com.wingman.integrations.sms.SmsHostSyncReport
import com.wingman.integrations.sms.SmsPairingKeys
import com.wingman.integrations.sms.SmsSendCommand
import com.wingman.integrations.sms.SmsSender
import kotlinx.coroutines.runBlocking
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
 * Process-wide holder around the pure-Kotlin [SmsConnectorHost]. BroadcastReceivers run in a cold
 * process after a reboot or process death, so everything is rebuilt from the app-private state file:
 * the paired connection, the dedup claims, the send ledger and the outbox.
 *
 * I-020: **inert until paired.** [get] is null until the user pairs this phone on the Settings
 * screen ("Phone SMS"), which calls `createConnection` and stores the returned connection ID. Unpair
 * (or a connection found unpaired on app start) clears it again, so ingest and claims stop at once.
 * MainActivity calls [connectBackend] with `AppContainer.api` (bearer auth from the I-011 session),
 * then [startInBackground] (verify the stored connection, then one sync round).
 */
object SmsConnectorRuntime {
    private const val STATE_FILE = "wingman-sms-state.json"

    @Volatile private var host: SmsConnectorHost? = null
    @Volatile private var api: WingmanApi? = null

    /** The host over the app-private state file. Applies the backend client set by [connectBackend]. */
    @Synchronized
    fun host(context: Context): SmsConnectorHost {
        host?.let { return it }
        val app = context.applicationContext
        val store = FileSmsStateStore(File(app.filesDir, STATE_FILE))
        return SmsConnectorHost(store, AndroidSmsTransport(app)).also {
            it.connectBackend(api)
            host = it
        }
    }

    /**
     * DEBUG HARNESS ONLY (SmsDemoActivity): sets a scope directly, bypassing pairing. The app itself
     * pairs through [SmsPairingManager]; a scope set here is checked with `getConnection` on the next
     * app start like any other.
     */
    @Synchronized
    fun configure(context: Context, scope: SmsConnectionScope) {
        val h = host(context)
        h.store.put(SmsPairingKeys.USER, scope.userId)
        h.store.put(SmsPairingKeys.CONNECTION, scope.connectionId)
        h.invalidate()
    }

    /**
     * Connects the backend client: envelope upload, claims and pairing all go through it. Null
     * disconnects (envelopes stay queued, nothing is claimed, pairing is unavailable). The same client
     * again (e.g. MainActivity recreated) keeps the connector, so its per-instance locks still exclude
     * a sync running on it.
     */
    @Synchronized
    fun connectBackend(newApi: WingmanApi?) {
        if (newApi === api) return
        api = newApi
        host?.connectBackend(newApi)
    }

    @Volatile private var startedFor: WingmanApi? = null

    /**
     * App start: verifies the stored connection with `getConnection` (a `404`, `unpaired_at` or
     * `disconnected` stops sync and leaves a "pair again" notice), then runs one sync round. Once per
     * backend client per process. Off the main thread.
     */
    fun startInBackground(context: Context, onDone: ((SmsHostSyncReport?) -> Unit)? = null) {
        val app = context.applicationContext
        val client = api
        if (client == null || startedFor === client) return
        startedFor = client
        Thread {
            val report = runCatching {
                val h = host(app)
                h.exclusive { h.pairing()?.let { p -> runBlocking { p.verify() } } }
                h.sync(SmsRoleHelper.sendPreconditions(app))
            }.getOrNull()
            onDone?.invoke(report)
        }.apply { name = "wingman-sms-start" }.start()
    }

    /**
     * One upload + claim + execute round on a background thread (never the main thread: it does
     * blocking network I/O). Returns immediately. While unpaired it only uploads leftovers.
     */
    fun syncInBackground(context: Context, onDone: ((SmsHostSyncReport?) -> Unit)? = null) {
        val app = context.applicationContext
        Thread {
            // SmsConnectorHost.sync holds the host's lock: one round at a time, never during a pair/unpair.
            val report = runCatching { host(app).sync(SmsRoleHelper.sendPreconditions(app)) }.getOrNull()
            onDone?.invoke(report)
        }.apply { name = "wingman-sms-sync" }.start()
    }

    /** The connector for the paired connection, or null while this phone is not paired. */
    fun get(context: Context): SmsConnector? = host(context).connector()

    /** Where per-part sent/delivered results go, also for a send made just before an unpair. */
    fun resultSender(context: Context): SmsSender? = host(context).resultSender()

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
        host(context).store.put(DEBUG_LAST_INBOUND, buildJsonObject {
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
        val o = host(context).store.get(DEBUG_LAST_INBOUND)?.let { Json.parseToJsonElement(it).jsonObject } ?: return null
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
        host(context).store.put(DEBUG_LAST_COMMAND, buildJsonObject {
            put("outbound_message_id", c.outboundMessageId)
            put("idempotency_key", c.idempotencyKey)
            put("external_thread_id", c.externalThreadId)
            put("text", c.text)
            put("authorized_by", c.authorizedBy)
        }.toString())
    }

    fun debugLastCommand(context: Context): SmsSendCommand? {
        if (!isDebuggable(context)) return null
        val o = host(context).store.get(DEBUG_LAST_COMMAND)?.let { Json.parseToJsonElement(it).jsonObject } ?: return null
        fun f(k: String) = o.getValue(k).jsonPrimitive.content
        return SmsSendCommand(f("outbound_message_id"), f("idempotency_key"), f("external_thread_id"), f("text"), f("authorized_by"))
    }
}
