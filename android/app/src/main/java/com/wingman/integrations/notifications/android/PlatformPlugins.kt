package com.wingman.integrations.notifications.android

import com.wingman.integrations.bumble.BumbleNotificationPlugin
import com.wingman.integrations.notifications.NotificationAppPlugin
import com.wingman.integrations.tinder.TinderNotificationPlugin

/*
 * Runtime wiring (WING-027/028, I-033): the per-app notification plugins the listener allowlists.
 * One line per platform. WING-029 adds Hinge's plugin here.
 */
internal object PlatformPlugins {
    fun all(): List<NotificationAppPlugin> = listOf(
        TinderNotificationPlugin(), // WING-027
        BumbleNotificationPlugin(), // WING-028
    )
}
