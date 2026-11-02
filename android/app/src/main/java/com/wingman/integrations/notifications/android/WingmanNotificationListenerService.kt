package com.wingman.integrations.notifications.android

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.wingman.integrations.notifications.NotificationInspector
import com.wingman.integrations.notifications.NotificationPluginRegistry
import com.wingman.integrations.notifications.ReplyActionHandle

/*
 * THIN ANDROID ADAPTER (WING-017). Compiled in CI; never run on a device (I-003).
 *
 * The system binds this service only after the user grants notification access in system
 * settings (manifest: BIND_NOTIFICATION_LISTENER_SERVICE). Everything is decided by the pure-Kotlin
 * NotificationListenerCore:
 *  - packages outside the plugin allowlist (empty by default) are dropped before any extra is read;
 *  - the service never replies by itself: replies go only through NotificationReplyAdapter, which
 *    only the authorized claim path calls;
 *  - nothing here logs notification text. The debug inspector logs a REDACTED structure only.
 */
class WingmanNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        val core = NotificationConnectorRuntime.core(this)
        core.onAccessGranted()
        // A reply key claimed before a process death has an unknown outcome; settle it first.
        NotificationConnectorRuntime.replyAdapter(this).recoverAfterRestart()
        // Rebuild the in-memory live reply actions; snapshot dedup keeps recorded events from repeating.
        runCatching { activeNotifications }.getOrNull()?.forEach { handle(it) }
    }

    override fun onListenerDisconnected() {
        val core = NotificationConnectorRuntime.core(this)
        if (NotificationConnectorRuntime.isAccessGranted(this)) {
            // Unbound by the system but still allowed: PendingIntents may be stale, drop them.
            core.liveActions.clear()
        } else {
            core.onAccessRevoked()
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        handle(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NotificationConnectorRuntime.core(this).onRemoved(sbn.key)
    }

    private fun handle(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        val core = NotificationConnectorRuntime.core(this)
        val inspect = NotificationConnectorRuntime.inspectorEnabled(this) &&
            pkg in NotificationInspector.targets(registryView())
        val allowlisted = pkg in registryView().allowlist
        if (!allowlisted && !inspect) return // nothing is read from any other app's notification

        val snapshot = runCatching { NotificationSnapshotExtractor.snapshot(sbn) }.getOrNull() ?: return
        val source = NotificationSnapshotExtractor.source(this, pkg)
        if (inspect) {
            Log.i(NotificationConnectorRuntime.INSPECTOR_TAG, NotificationInspector.line(NotificationInspector.describe(source, snapshot)))
        }
        if (!allowlisted) return
        val actions = sbn.notification.actions
        core.onPosted(source, snapshot) { action ->
            val platformAction = actions?.getOrNull(action.index)
            if (platformAction == null) ReplyActionHandle { com.wingman.integrations.notifications.FireResult.Error("ActionGone") }
            else AndroidReplyActionHandle(applicationContext, platformAction)
        }
    }

    private fun registryView(): NotificationPluginRegistry = NotificationConnectorRuntime.registry()
}
