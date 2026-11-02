package com.wingman.features.connections

import com.wingman.core.model.Capability
import com.wingman.core.model.Connection
import com.wingman.core.model.Verification
import com.wingman.integrations.notifications.NotificationSyncHost
import com.wingman.integrations.notifications.NotificationToggleResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/*
 * I-033 / WING-027/028: the "Dating apps (notifications)" section of Settings > Connections.
 * Pure Kotlin, JVM-tested; NotificationConnectionScreen.kt is the Compose layer. Capabilities shown
 * are the ones the server STORED, never what this app claimed.
 */

object NotificationConnectionCopy {
    const val ASSISTED_ONLY = "Assisted only: Wingman drafts, you send"
    const val COLLAPSED_SUMMARY = "Tinder and Bumble, through their notifications. Assisted only: Wingman drafts, you send."

    const val READING_EXPLANATION =
        "Wingman reads this app's notification previews (sender name and message text, when the app shows them) " +
            "and uploads them to your Wingman account to suggest replies. Matches, likes and expiry alerts are shown " +
            "for information only and are never replied to. Turning this on pairs the app with your account; turning it off " +
            "stops reading at once and disconnects it."

    const val DIRECT_REPLY_DISABLED =
        "Direct reply is off: replying from this app's notifications hasn't been verified on a real device. " +
            "Wingman never taps or types in the app. Copy the draft, open the app and send it yourself."

    const val DIRECT_REPLY_AVAILABLE =
        "Replies go through the app's own notification reply box. Every reply still needs your approval, and Wingman " +
            "can only tell that it handed the text to the app, not that it was delivered."

    const val ACCESS_OFF = "Notification access: OFF. Wingman can't read any app's notifications until you turn it on."
    const val ACCESS_ON = "Notification access: ON."
    const val NEEDS_ACCESS_HINT = "Turn on notification access first."
    const val NO_BACKEND_HINT = "Not connected to a Wingman server."

    fun title(platform: String): String = when (platform) {
        "tinder" -> "Tinder"
        "bumble" -> "Bumble"
        "hinge" -> "Hinge"
        else -> platform.replaceFirstChar { it.uppercase() }
    }

    fun displayName(platform: String): String = "${title(platform)} notifications"
}

data class NotificationPlatformView(
    val platform: String,
    val title: String,
    val assistedLabel: String,
    val readingOn: Boolean,
    val readingToggleEnabled: Boolean,
    val readingHint: String?,
    val directReplyOn: Boolean,
    /** True ONLY when the server stores `send` as VERIFIED for this paired connection. */
    val directReplyToggleEnabled: Boolean,
    val directReplyExplanation: String,
    val statusLabel: String,
    val capabilities: List<CapabilityRow>,
    val notice: String?,
)

data class NotificationConnectionsUi(
    val accessGranted: Boolean,
    val accessLabel: String,
    val platforms: List<NotificationPlatformView>,
    val backendAvailable: Boolean,
    /** Platform whose toggle is running, or null. */
    val busy: String? = null,
    val message: String? = null,
)

object NotificationConnectionPresenter {
    fun capabilityRows(c: Connection): List<CapabilityRow> {
        val s = c.capabilities.capabilities
        fun row(name: String, cap: Capability?, what: String) = (cap ?: Capability(Verification.UNSUPPORTED)).let {
            CapabilityRow(name, it.status, SmsConnectionCopy.verificationLabel(it.status), it.notes ?: what)
        }
        return listOf(
            row("Read notifications", s.read, "Notification previews only."),
            // Drafting happens on the server, not in the app: it is available whenever reading is.
            CapabilityRow(
                "Wingman drafts", s.read.status,
                if (s.read.status == Verification.UNSUPPORTED) "Not available" else "Available: you send each draft by hand",
                "Drafts appear under Send by hand with Copy and Open app.",
            ),
            row("Send from Wingman", s.send, "Not available: you send in the app."),
            row("Profiles", s.profileRead, "Not available through notifications."),
            row("Swipe", s.swipe, "Never automated."),
        )
    }

    fun view(host: NotificationSyncHost, platform: String, accessGranted: Boolean, busy: Boolean): NotificationPlatformView {
        val settings = host.core.settings
        val paired = host.isPaired(platform)
        val connection = host.pairingState(platform)
        val readingOn = paired && settings.readEnabled(platform)
        val allowReply = host.directReplyAllowed(platform)
        val canTurnOn = accessGranted && host.backendAvailable()
        return NotificationPlatformView(
            platform = platform,
            title = NotificationConnectionCopy.title(platform),
            assistedLabel = NotificationConnectionCopy.ASSISTED_ONLY,
            readingOn = readingOn,
            // Turning OFF is always allowed; turning ON needs access and a server.
            readingToggleEnabled = !busy && (readingOn || canTurnOn),
            readingHint = when {
                readingOn -> null
                !accessGranted -> NotificationConnectionCopy.NEEDS_ACCESS_HINT
                !host.backendAvailable() -> NotificationConnectionCopy.NO_BACKEND_HINT
                else -> null
            },
            directReplyOn = settings.directReplyEnabled(platform) && allowReply,
            directReplyToggleEnabled = !busy && readingOn && allowReply,
            directReplyExplanation = if (allowReply) NotificationConnectionCopy.DIRECT_REPLY_AVAILABLE else NotificationConnectionCopy.DIRECT_REPLY_DISABLED,
            statusLabel = when {
                readingOn -> SmsConnectionCopy.statusLabel(connection)
                paired -> "Paired, reading off"
                else -> "Off"
            },
            capabilities = connection?.let(::capabilityRows).orEmpty(),
            notice = host.notice(platform),
        )
    }
}

/**
 * State holder for the section. Toggles run in [io] (blocking network calls; a background
 * dispatcher on Android) and one at a time: a second tap while one runs is dropped.
 */
class NotificationConnectionsState(
    private val scope: CoroutineScope,
    private val host: NotificationSyncHost,
    private val accessGranted: () -> Boolean,
    private val io: CoroutineContext = EmptyCoroutineContext,
    private val afterChange: () -> Unit = {},
) {
    private val _state = MutableStateFlow(snapshot(null, null))
    val state: StateFlow<NotificationConnectionsUi> = _state

    private fun snapshot(busy: String?, message: String?): NotificationConnectionsUi {
        val granted = accessGranted()
        return NotificationConnectionsUi(
            accessGranted = granted,
            accessLabel = if (granted) NotificationConnectionCopy.ACCESS_ON else NotificationConnectionCopy.ACCESS_OFF,
            platforms = host.platforms.map { NotificationConnectionPresenter.view(host, it, granted, busy != null) },
            backendAvailable = host.backendAvailable(),
            busy = busy,
            message = message,
        )
    }

    /** Re-reads access and local state (e.g. back from the system's notification-access page). */
    fun refresh() {
        host.refreshAccess(accessGranted())
        _state.value = snapshot(_state.value.busy, _state.value.message)
    }

    fun setReading(platform: String, on: Boolean) = run(platform) { host.setReadingEnabled(platform, on, NotificationConnectionCopy.displayName(platform)) }

    fun setDirectReply(platform: String, on: Boolean) = run(platform) { host.setDirectReplyEnabled(platform, on) }

    private fun run(platform: String, action: () -> NotificationToggleResult) {
        if (_state.value.busy != null) return
        _state.update { snapshot(platform, null) }
        scope.launch {
            val r = withContext(io) { action() }
            val msg = when (r) {
                is NotificationToggleResult.Done -> r.message
                is NotificationToggleResult.Refused -> r.message
                is NotificationToggleResult.Failed -> r.message
            }
            _state.value = snapshot(null, msg)
            if (r is NotificationToggleResult.Done) afterChange()
        }
    }
}
