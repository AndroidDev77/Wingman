package com.wingman.features.conversation

import com.wingman.core.api.ApiResult
import com.wingman.core.api.PageRequest
import com.wingman.core.api.WingmanApi
import com.wingman.core.model.AutomationMode
import com.wingman.core.model.Connection
import com.wingman.core.model.ConnectionStatus
import com.wingman.core.model.ContactAutomationStatus
import com.wingman.core.model.ConversationAutomation
import com.wingman.core.model.ConversationStage
import com.wingman.core.model.GlobalAutomationStatus
import com.wingman.core.model.IntentMode
import com.wingman.core.model.Verification
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Shared, pure-Kotlin display helpers for the inbox (WING-014) and conversation screens.
 * No android.* / androidx.* imports: everything here is JVM unit-tested.
 *
 * Platform rule (ticket constraint): `platform` is an opaque string (ADR 0001). Nothing here
 * branches on a specific platform name; what a platform can do comes only from the
 * connection's advertised capabilities (ADR 0005).
 */

/** Visual tone for a label. The Compose layer maps these to theme colors; no hard-coded colors. */
enum class LabelTone { NEUTRAL, PENDING, SUCCESS, WARNING, ERROR }

/* ------------------------------------------------------------------ time */

object TimeFormat {
    /**
     * Compact relative time for list rows. Timestamps are UTC [Instant]s from the contract;
     * [zone] only matters once we fall back to a calendar date. A timestamp in the future
     * (clock skew) is shown as "now" rather than a negative duration.
     */
    fun relative(then: Instant, now: Instant, zone: ZoneId, locale: Locale = Locale.getDefault()): String {
        val d = Duration.between(then, now)
        if (d.isNegative || d.seconds < 60) return "now"
        val minutes = d.toMinutes()
        if (minutes < 60) return "${minutes}m ago"
        val hours = d.toHours()
        if (hours < 24) return "${hours}h ago"
        val days = d.toDays()
        if (days < 7) return "${days}d ago"
        val thenZoned = then.atZone(zone)
        val pattern = if (thenZoned.year == now.atZone(zone).year) "MMM d" else "MMM d, yyyy"
        return DateTimeFormatter.ofPattern(pattern, locale).format(thenZoned)
    }

    /** Absolute message timestamp, e.g. "Oct 31, 17:50", in the viewer's [zone]. */
    fun messageTime(at: Instant, zone: ZoneId, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern("MMM d, HH:mm", locale).format(at.atZone(zone))
}

/* ------------------------------------------------------------------ enum labels */

object Labels {
    fun intent(mode: IntentMode): String = when (mode) {
        IntentMode.SERIOUS -> "Serious relationship"
        IntentMode.CASUAL_DATING -> "Casual dating"
        IntentMode.CASUAL_PHYSICAL -> "Casual physical"
        IntentMode.OPEN -> "Open to anything"
        IntentMode.CUSTOM -> "Custom goal"
    }

    fun automationMode(mode: AutomationMode): String = when (mode) {
        AutomationMode.MANUAL -> "Manual"
        AutomationMode.ASSISTED -> "Assisted"
        AutomationMode.AUTOPILOT -> "Autopilot"
    }

    fun stage(stage: ConversationStage): String = when (stage) {
        ConversationStage.NEW -> "New"
        ConversationStage.INTRODUCTION -> "Introduction"
        ConversationStage.RAPPORT -> "Rapport"
        ConversationStage.INTEREST -> "Interest"
        ConversationStage.DATE_PROPOSAL -> "Date proposal"
        ConversationStage.SCHEDULING -> "Scheduling"
        ConversationStage.CONFIRMED -> "Confirmed"
        ConversationStage.FOLLOW_UP -> "Follow-up"
        ConversationStage.PAUSED -> "Paused"
    }

    /**
     * Automation status for a conversation. A global pause or a contact pause/takeover is always
     * visible: it overrides what the effective mode alone would suggest.
     */
    fun automationStatus(a: ConversationAutomation): String {
        val mode = automationMode(a.effectiveMode)
        return when {
            a.globalStatus == GlobalAutomationStatus.PAUSED -> "$mode · all automation paused"
            a.contactStatus == ContactAutomationStatus.TAKEN_OVER -> "$mode · you took over"
            a.contactStatus == ContactAutomationStatus.PAUSED -> "$mode · paused"
            else -> mode
        }
    }

    /** "99+" cap so a badge never grows unbounded. Zero or less means no badge (null). */
    fun unreadBadge(count: Int): String? = when {
        count <= 0 -> null
        count > 99 -> "99+"
        else -> count.toString()
    }
}

/* ------------------------------------------------------------------ platform availability */

/**
 * What the UI may claim about a conversation's platform, derived ONLY from the connection's
 * status and its `read` / `send` capability labels. Never claims "connected" or sendable unless
 * the connection is CONNECTED and `send` is VERIFIED.
 */
data class PlatformAvailability(
    /** Badge text: the connection's display name, or the raw platform string if unknown. */
    val badge: String,
    /** Short status, e.g. "Can't send from Wingman". */
    val label: String,
    /** Longer, honest explanation for the detail screen. */
    val detail: String,
    val tone: LabelTone,
    /** True only when connection is CONNECTED and `send` is VERIFIED. */
    val canSend: Boolean,
    /** True when `send` (or the connection itself) is UNVERIFIED / unknown and must be flagged. */
    val unverified: Boolean,
)

object Availability {
    fun of(platform: String, connection: Connection?): PlatformAvailability {
        if (connection == null) {
            return PlatformAvailability(
                badge = platform,
                label = "Status unknown",
                detail = "Wingman couldn't load this connection's capabilities, so it is treated as unavailable.",
                tone = LabelTone.WARNING,
                canSend = false,
                unverified = true,
            )
        }
        val badge = connection.displayName.ifBlank { platform }
        when (connection.status) {
            ConnectionStatus.DISCONNECTED -> return PlatformAvailability(
                badge, "Disconnected", "This connection is disconnected. Reconnect it to read or send.",
                LabelTone.ERROR, canSend = false, unverified = false,
            )
            ConnectionStatus.ERROR -> return PlatformAvailability(
                badge, "Connection error", "This connection reported an error. Messages may be missing.",
                LabelTone.ERROR, canSend = false, unverified = false,
            )
            ConnectionStatus.CONNECTED -> Unit
        }
        val caps = connection.capabilities.capabilities
        if (caps.read.status == Verification.UNSUPPORTED && caps.send.status == Verification.UNSUPPORTED) {
            return PlatformAvailability(
                badge, "Unsupported", "Wingman can't read or send on this platform.",
                LabelTone.ERROR, canSend = false, unverified = false,
            )
        }
        return when (caps.send.status) {
            Verification.UNSUPPORTED -> PlatformAvailability(
                badge, "Can't send from Wingman",
                "Sending is unsupported on this platform. Wingman can show messages and suggest replies, which you send yourself.",
                LabelTone.WARNING, canSend = false, unverified = caps.read.status == Verification.UNVERIFIED,
            )
            Verification.UNVERIFIED -> PlatformAvailability(
                badge, "Send UNVERIFIED",
                "Sending on this platform hasn't been verified on a real device yet. Sends may fail and need approval.",
                LabelTone.WARNING, canSend = false, unverified = true,
            )
            Verification.VERIFIED -> PlatformAvailability(
                badge, "Connected",
                connection.capabilities.capabilities.send.notes ?: "Sending is verified for this connection.",
                LabelTone.SUCCESS, canSend = true, unverified = false,
            )
        }
    }
}

/**
 * Loads every connection page (there is no single-connection GET in contract v1.0.0). Stops after
 * [maxPages] so a misbehaving server can't loop forever; returns what it has plus `complete=false`.
 */
suspend fun loadAllConnections(api: WingmanApi, maxPages: Int = 10): ApiResult<Pair<List<Connection>, Boolean>> {
    val all = mutableListOf<Connection>()
    var cursor: String? = null
    repeat(maxPages) {
        when (val r = api.listConnections(PageRequest(cursor, 100))) {
            is ApiResult.Failure -> return r
            is ApiResult.Success -> {
                all += r.value.items
                cursor = r.value.nextCursor ?: return ApiResult.Success(all.toList() to true)
            }
        }
    }
    return ApiResult.Success(all.toList() to false)
}
