package com.wingman.integrations.notifications.android

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.wingman.integrations.notifications.CapabilityStatus
import com.wingman.integrations.notifications.NotificationAccessExplainer
import com.wingman.integrations.notifications.NotificationInspector

/*
 * THIN ANDROID ADAPTER (WING-017). Compiled in CI; never run on a device (I-003).
 *
 * The explainer shown BEFORE the user is sent to the system's notification-access page: what is
 * read, what is never done, and how to revoke. Plain framework views (no new dependency); a
 * Compose screen in Settings > Connections can reuse NotificationAccessExplainer later.
 *
 * On a DEBUG build it also offers the redacted notification inspector toggle for the device test
 * in docs/integrations/notification_matrix.md. The inspector never runs on a release build.
 */
class NotificationAccessActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40) }
        TextView(this).apply { text = NotificationAccessExplainer.TITLE; textSize = 20f }.also(col::addView)
        NotificationAccessExplainer.BODY.forEach { p ->
            TextView(this).apply { text = "• $p"; setPadding(0, 16, 0, 0) }.also(col::addView)
        }
        status = TextView(this).apply { setPadding(0, 32, 0, 16) }.also(col::addView)
        Button(this).apply {
            text = "Open notification access settings"
            setOnClickListener { startActivity(accessSettingsIntent()) }
        }.also(col::addView)

        if (NotificationConnectorRuntime.isDebuggable(this)) {
            Button(this).apply {
                setOnClickListener {
                    val on = !NotificationConnectorRuntime.inspectorEnabled(this@NotificationAccessActivity)
                    NotificationConnectorRuntime.setInspectorEnabled(this@NotificationAccessActivity, on)
                    refresh()
                }
            }.also { inspectorButton = it; col.addView(it) }
        }
        setContentView(ScrollView(this).apply { addView(col) })
    }

    private var inspectorButton: Button? = null

    override fun onResume() {
        super.onResume()
        NotificationConnectorRuntime.refreshAccess(this)
        refresh()
    }

    private fun refresh() {
        val granted = NotificationConnectorRuntime.isAccessGranted(this)
        val core = NotificationConnectorRuntime.core(this)
        status.text = buildString {
            appendLine("Notification access: ${if (granted) "ON" else "OFF"}")
            val caps = core.capabilities()
            if (caps.isEmpty()) appendLine("No dating-app notification plugin is installed yet, so no notification is read.")
            caps.forEach { c ->
                appendLine("${c.platform}: read ${label(c.read.status)}, reply ${label(c.send.status)}, swipe ${label(c.swipe.status)}")
            }
        }
        inspectorButton?.text = if (NotificationConnectorRuntime.inspectorEnabled(this)) {
            "DEBUG: stop redacted inspector (logcat tag ${NotificationConnectorRuntime.INSPECTOR_TAG})"
        } else {
            "DEBUG: start redacted inspector for ${NotificationInspector.CANDIDATE_PACKAGES.values.joinToString("/")} and allowlisted apps"
        }
    }

    private fun label(s: CapabilityStatus) = when (s) {
        CapabilityStatus.VERIFIED -> "verified"
        CapabilityStatus.UNVERIFIED -> "not yet tested"
        CapabilityStatus.UNSUPPORTED -> "unavailable"
    }

    private fun accessSettingsIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                android.content.ComponentName(this, WingmanNotificationListenerService::class.java).flattenToString(),
            )
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
}
