package com.wingman.integrations.notifications.android

import android.content.Context
import android.util.Log
import com.wingman.core.api.WingmanApi
import com.wingman.integrations.notifications.NotificationLog
import com.wingman.integrations.notifications.NotificationSyncHost
import com.wingman.integrations.notifications.NotificationSyncReport
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/*
 * THIN ANDROID ADAPTER (I-033, WING-027/028). Compiled in CI; never run on a device (I-003).
 * All rules live in the pure-Kotlin NotificationSyncHost (JVM-tested).
 */

/**
 * Process-wide holder of the [NotificationSyncHost] over WING-017's core, reply adapter and state
 * file. MainActivity calls [connectBackend] with the app's API client and [onAppResume] on every
 * resume; the listener service calls [requestSync] after it records new events or access changes.
 * Every round runs on one background thread, coalesced, never on the main thread.
 */
object NotificationSyncRuntime {
    @Volatile private var host: NotificationSyncHost? = null
    @Volatile private var api: WingmanApi? = null
    @Volatile private var verifiedFor: WingmanApi? = null

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "wingman-notif-sync").apply { isDaemon = true } }
    private val scheduled = AtomicBoolean(false)

    private val log = NotificationLog { event, fields ->
        Log.i(NotificationConnectorRuntime.LOG_TAG, event + " " + fields.entries.joinToString(" ") { "${it.key}=${it.value}" })
    }

    @Synchronized
    fun host(context: Context): NotificationSyncHost {
        host?.let { return it }
        val app = context.applicationContext
        return NotificationSyncHost(
            core = NotificationConnectorRuntime.core(app),
            adapter = NotificationConnectorRuntime.replyAdapter(app),
            store = NotificationConnectorRuntime.stateStore(app),
            api = api,
            log = log,
        ).also { host = it }
    }

    /** Sets the backend client (null disconnects: events stay queued, nothing pairs or claims). */
    @Synchronized
    fun connectBackend(newApi: WingmanApi?) {
        if (newApi === api) return
        api = newApi
        host?.connectBackend(newApi)
    }

    /**
     * App resume: `refreshAccess` first (a revocation made while Wingman was in the background stops
     * reading, drops live reply actions and blocks claims at once), then, once per backend client,
     * verifies the paired connections, then one sync round in the background.
     */
    fun onAppResume(context: Context) {
        val app = context.applicationContext
        NotificationConnectorRuntime.refreshAccess(app)
        val client = api
        val verify = client != null && verifiedFor !== client
        if (verify) verifiedFor = client
        executor.execute {
            runCatching {
                val h = host(app)
                if (verify) h.verifyAll()
                h.sync()
            }
        }
    }

    /** Schedules one sync round soon; calls while one is already scheduled are merged. */
    fun requestSync(context: Context, onDone: ((NotificationSyncReport?) -> Unit)? = null) {
        val app = context.applicationContext
        if (!scheduled.compareAndSet(false, true) && onDone == null) return
        executor.execute {
            scheduled.set(false)
            val report = runCatching { host(app).sync() }.getOrNull()
            onDone?.invoke(report)
        }
    }

    /** Runs [block] on the sync thread (toggles: pairing and unpairing are blocking network calls). */
    fun runInBackground(block: () -> Unit) = executor.execute { runCatching(block) }
}
