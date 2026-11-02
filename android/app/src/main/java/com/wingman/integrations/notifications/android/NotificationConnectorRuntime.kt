package com.wingman.integrations.notifications.android

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.wingman.integrations.notifications.FileNotificationStateStore
import com.wingman.integrations.notifications.LiveReplyActions
import com.wingman.integrations.notifications.NotificationAppPlugin
import com.wingman.integrations.notifications.NotificationListenerCore
import com.wingman.integrations.notifications.NotificationLog
import com.wingman.integrations.notifications.NotificationPluginRegistry
import com.wingman.integrations.notifications.NotificationReplyAdapter
import com.wingman.integrations.notifications.NotificationStateStore
import java.io.File

/*
 * THIN ANDROID ADAPTER (WING-017). Compiled in CI; never run on a device (I-003).
 */

/**
 * Process-wide holder of the pure-Kotlin core, rebuilt from the app-private state file after
 * process death (the listener service is rebound by the system in a cold process).
 *
 * **Plugins.** The allowlist is [PlatformPlugins.all] (WING-027 Tinder, WING-028 Bumble) unless
 * [installPlugins] replaced it before the core was first built.
 */
object NotificationConnectorRuntime {
    private const val STATE_FILE = "wingman-notification-state.json"
    private const val KEY_INSPECTOR = "debug:inspector"
    const val LOG_TAG = "WingmanNotif"
    const val INSPECTOR_TAG = "WingmanNotifInspector"

    // WING-027/028: the platform plugins are registered by default, so the listener has them even
    // in a cold process the system starts only to bind the service. Reading a platform still needs
    // the user's opt-in (Settings), which pairs it first. [installPlugins] can still override.
    @Volatile private var plugins: List<NotificationAppPlugin> = PlatformPlugins.all()
    @Volatile private var core: NotificationListenerCore? = null
    @Volatile private var adapter: NotificationReplyAdapter? = null
    @Volatile private var store: NotificationStateStore? = null
    @Volatile private var registry: NotificationPluginRegistry? = null

    /** The plugin registry; its packages are the allowlist (empty until [installPlugins]). */
    @Synchronized
    fun registry(): NotificationPluginRegistry = registry ?: NotificationPluginRegistry(plugins).also { registry = it }

    /** Registers the per-app plugins (WING-027..029). Must be called before [core] is first built. */
    @Synchronized
    fun installPlugins(list: List<NotificationAppPlugin>) {
        check(core == null) { "installPlugins must run before the notification core is built" }
        registry = NotificationPluginRegistry(list) // validates
        plugins = list.toList()
    }

    /** Redacted log sink: event names and safe fields only (the core never passes text). */
    private val androidLog = NotificationLog { event, fields ->
        Log.i(LOG_TAG, event + " " + fields.entries.joinToString(" ") { "${it.key}=${it.value}" })
    }

    @Synchronized
    fun core(context: Context): NotificationListenerCore {
        core?.let { return it }
        val s = store(context)
        return NotificationListenerCore(registry(), s, LiveReplyActions(), log = androidLog).also { core = it }
    }

    /** The reply adapter. Its only production caller is the platform connector's claim path. */
    @Synchronized
    fun replyAdapter(context: Context): NotificationReplyAdapter {
        adapter?.let { return it }
        return NotificationReplyAdapter(core(context), store(context), log = androidLog).also { adapter = it }
    }

    /** The app-private state file shared by the core, the reply adapter and the sync host (I-033). */
    @Synchronized
    fun stateStore(context: Context): NotificationStateStore = store(context)

    @Synchronized
    private fun store(context: Context): NotificationStateStore =
        store ?: FileNotificationStateStore(File(context.applicationContext.filesDir, STATE_FILE)).also { store = it }

    /** Whether the user has granted Wingman's listener notification access right now. */
    fun isAccessGranted(context: Context): Boolean {
        val component = ComponentName(context, WingmanNotificationListenerService::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            context.getSystemService(NotificationManager::class.java)?.isNotificationListenerAccessGranted(component) == true
        } else {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: ""
            flat.split(':').any { ComponentName.unflattenFromString(it) == component }
        }
    }

    /**
     * App start / screen resume: reconciles the persisted state with the system setting, so a
     * revocation made while the process was dead is reported as a capability downgrade.
     */
    fun refreshAccess(context: Context) {
        // I-033: the same rule lives in NotificationSyncHost.refreshAccess (JVM-tested); the host
        // also stops claims while access is off.
        NotificationSyncRuntime.host(context).refreshAccess(isAccessGranted(context))
    }

    fun isDebuggable(context: Context): Boolean = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /** DEBUG BUILDS ONLY: whether the redacted inspector logs allowed/candidate notifications. */
    fun inspectorEnabled(context: Context): Boolean = isDebuggable(context) && store(context).get(KEY_INSPECTOR) == "on"

    fun setInspectorEnabled(context: Context, enabled: Boolean) {
        if (!isDebuggable(context)) return
        val s = store(context)
        if (enabled) s.put(KEY_INSPECTOR, "on") else s.remove(KEY_INSPECTOR)
    }
}
