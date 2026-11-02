package com.wingman.integrations.notifications.android

import com.wingman.integrations.bumble.BumbleNotificationPlugin
import com.wingman.integrations.hinge.notifications.HingeNotificationPlugin
import com.wingman.integrations.notifications.NotificationAppPlugin
import com.wingman.integrations.tinder.TinderNotificationPlugin

/*
 * Runtime wiring (WING-027/028, I-033): the per-app notification plugins the listener allowlists.
 * One line per platform. Hinge's (WING-029) is the notification-assisted fallback, not the
 * experimental connector: it ships in every build (D-021 item 7). HingeModule.installNotificationPlugin
 * then finds it registered and does nothing.
 */
internal object PlatformPlugins {
    fun all(): List<NotificationAppPlugin> = listOf(
        TinderNotificationPlugin(), // WING-027
        BumbleNotificationPlugin(), // WING-028
        HingeNotificationPlugin(), // WING-029 (notification fallback only)
    )
}
