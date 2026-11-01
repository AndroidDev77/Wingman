package com.wingman.features.settings

import com.wingman.core.api.ApiResult
import com.wingman.core.api.WingmanApi
import com.wingman.core.model.ApprovalReason
import com.wingman.core.model.AutomationMode
import com.wingman.core.model.Brevity
import com.wingman.core.model.Connection
import com.wingman.core.model.ConnectionStatus
import com.wingman.core.model.EmojiFrequency
import com.wingman.core.model.Humor
import com.wingman.core.model.IntentMode
import com.wingman.core.model.Punctuation
import com.wingman.core.model.ResponsePace
import com.wingman.core.model.Tone
import com.wingman.core.model.Verification
import com.wingman.core.model.Weekday
import com.wingman.features.conversation.loadAllConnections

/*
 * Plain-language copy for preference screens, and the capability-aware explanation shown before
 * automation is turned on. Pure Kotlin, JVM-tested.
 */

/** One messaging channel and whether Wingman may send on it automatically. */
data class ChannelSend(val name: String, val sendStatus: Verification, val connected: Boolean) {
    /** Only a connected channel whose `send` capability is VERIFIED can send automatically (contract AutomationMode). */
    val canAutoSend: Boolean get() = connected && sendStatus == Verification.VERIFIED

    val label: String
        get() = when {
            !connected -> "$name: not connected"
            sendStatus == Verification.VERIFIED -> "$name: sending VERIFIED"
            sendStatus == Verification.UNVERIFIED -> "$name: sending UNVERIFIED (not used for automatic sends)"
            else -> "$name: can't send from Wingman"
        }
}

/** Channels as far as Wingman knows. [known] is false when connections couldn't be loaded. */
data class ChannelInfo(val known: Boolean, val channels: List<ChannelSend> = emptyList()) {
    val autoSendChannels: List<ChannelSend> get() = if (known) channels.filter { it.canAutoSend } else emptyList()

    companion object {
        val UNKNOWN = ChannelInfo(known = false)

        fun of(connections: List<Connection>) = ChannelInfo(
            known = true,
            channels = connections.map {
                ChannelSend(
                    name = it.displayName.ifBlank { it.platform },
                    sendStatus = it.capabilities.capabilities.send.status,
                    connected = it.status == ConnectionStatus.CONNECTED,
                )
            },
        )

        /** Best effort: a failure gives [UNKNOWN], which never claims any channel can send. */
        suspend fun load(api: WingmanApi): ChannelInfo =
            when (val r = loadAllConnections(api)) {
                is ApiResult.Success -> of(r.value.first)
                is ApiResult.Failure -> UNKNOWN
            }
    }
}

data class AutomationExplanation(
    val mode: AutomationMode,
    val title: String,
    val summary: String,
    val points: List<String>,
    /** Shown prominently, e.g. when no channel can send automatically. */
    val warning: String?,
)

object AutomationExplainer {

    fun explain(mode: AutomationMode, settings: AutomationForm, channels: ChannelInfo): AutomationExplanation = when (mode) {
        AutomationMode.MANUAL -> AutomationExplanation(
            mode, "Manual", "Wingman never sends anything for you. It suggests replies only when you ask.",
            emptyList(), warning = null,
        )
        AutomationMode.ASSISTED -> AutomationExplanation(
            mode = mode,
            title = "Turn on Assisted?",
            summary = "Wingman may write draft replies on its own and put them in your approval queue. " +
                "Nothing is sent automatically: every message waits until you approve it.",
            points = listOf("You can edit or reject any draft.") + channelLines(channels),
            warning = null,
        )
        AutomationMode.AUTOPILOT -> {
            val auto = channels.autoSendChannels
            AutomationExplanation(
                mode = mode,
                title = "Turn on Autopilot?",
                summary = "Wingman may send replies for you WITHOUT asking first, within the limits below.",
                points = buildList {
                    add(
                        if (auto.isEmpty()) "Automatic sends happen only on channels with VERIFIED sending. You have none right now."
                        else "Automatic sends happen only on: ${auto.joinToString { it.name }}.",
                    )
                    add(
                        if (settings.activeHoursEnabled && settings.timezone.isNotBlank()) "Only during your active hours (${settings.timezone})."
                        else "At any time of day: you haven't set active hours.",
                    )
                    add(
                        settings.dailySendLimit.trim().toIntOrNull()?.let { "At most $it automatic sends per day." }
                            ?: "Up to the server's default daily limit.",
                    )
                    val reasons = ApprovalReason.entries.filter { it in settings.approvalRequiredFor }
                    add(
                        if (reasons.isEmpty()) "No message kinds are set to always need your approval."
                        else "These still wait for your approval: ${reasons.joinToString { PreferenceLabels.approvalReason(it).lowercase() }}.",
                    )
                    addAll(channelLines(channels))
                    add("You can switch back to Manual at any time.")
                },
                warning = when {
                    !channels.known -> "Wingman couldn't check your channels, so none are treated as able to send automatically. Drafts will wait for your approval."
                    auto.isEmpty() -> "No connected channel has VERIFIED sending, so nothing will be sent automatically yet. Drafts will wait for your approval."
                    else -> null
                },
            )
        }
    }

    private fun channelLines(channels: ChannelInfo): List<String> = when {
        !channels.known -> listOf("Channel status couldn't be loaded.")
        channels.channels.isEmpty() -> listOf("You have no messaging channels connected.")
        else -> channels.channels.map { it.label }
    }
}

object PreferenceLabels {
    fun intent(mode: IntentMode): String = when (mode) {
        IntentMode.SERIOUS -> "Serious relationship"
        IntentMode.CASUAL_DATING -> "Casual dating"
        IntentMode.CASUAL_PHYSICAL -> "Casual physical"
        IntentMode.OPEN -> "Open to anything"
        IntentMode.CUSTOM -> "Custom goal"
    }

    fun intentDescription(mode: IntentMode): String = when (mode) {
        IntentMode.SERIOUS -> "Looking for a long-term relationship."
        IntentMode.CASUAL_DATING -> "Dating without pressure to commit."
        IntentMode.CASUAL_PHYSICAL -> "Something physical between consenting adults who both want it."
        IntentMode.OPEN -> "Seeing where things go."
        IntentMode.CUSTOM -> "Describe your own goal in your words."
    }

    fun automation(mode: AutomationMode): String = when (mode) {
        AutomationMode.MANUAL -> "Manual (off)"
        AutomationMode.ASSISTED -> "Assisted"
        AutomationMode.AUTOPILOT -> "Autopilot"
    }

    fun automationDescription(mode: AutomationMode): String = when (mode) {
        AutomationMode.MANUAL -> "Wingman never sends. You ask for suggestions."
        AutomationMode.ASSISTED -> "Wingman drafts replies; you approve every send."
        AutomationMode.AUTOPILOT -> "Wingman may send on VERIFIED channels within your limits."
    }

    /** Approval kinds the user can toggle. Other reasons are system-applied and kept as they are. */
    val userApprovalReasons: List<ApprovalReason> = listOf(
        ApprovalReason.FIRST_MESSAGE,
        ApprovalReason.DATE_OR_COMMITMENT,
        ApprovalReason.SENSITIVE_CONTENT,
        ApprovalReason.LOW_CONFIDENCE,
        ApprovalReason.OUTSIDE_ACTIVE_HOURS,
    )

    fun approvalReason(r: ApprovalReason): String = when (r) {
        ApprovalReason.ASSISTED_MODE -> "Assisted mode"
        ApprovalReason.SENSITIVE_CONTENT -> "Sensitive topics"
        ApprovalReason.DATE_OR_COMMITMENT -> "Dates and commitments"
        ApprovalReason.LOW_CONFIDENCE -> "Replies Wingman is unsure about"
        ApprovalReason.FIRST_MESSAGE -> "First message to someone"
        ApprovalReason.OUTSIDE_ACTIVE_HOURS -> "Outside active hours"
        ApprovalReason.CAPABILITY_UNVERIFIED -> "Unverified channel"
        ApprovalReason.SEND_LIMIT_REACHED -> "Daily limit reached"
        ApprovalReason.USER_RULE -> "Your own rule"
    }

    fun punctuation(v: Punctuation) = when (v) {
        Punctuation.MINIMAL -> "Minimal"; Punctuation.STANDARD -> "Standard"; Punctuation.EXPRESSIVE -> "Expressive!"
    }

    fun emoji(v: EmojiFrequency) = when (v) {
        EmojiFrequency.NONE -> "None"; EmojiFrequency.RARE -> "Rare"; EmojiFrequency.OCCASIONAL -> "Occasional"; EmojiFrequency.FREQUENT -> "Frequent"
    }

    fun brevity(v: Brevity) = when (v) {
        Brevity.VERY_SHORT -> "Very short"; Brevity.SHORT -> "Short"; Brevity.MEDIUM -> "Medium"; Brevity.LONG -> "Long"
    }

    fun humor(v: Humor) = when (v) {
        Humor.NONE -> "None"; Humor.LIGHT -> "Light"; Humor.PLAYFUL -> "Playful"; Humor.WITTY -> "Witty"
    }

    fun tone(v: Tone) = when (v) {
        Tone.WARM -> "Warm"; Tone.DIRECT -> "Direct"; Tone.PLAYFUL -> "Playful"; Tone.FLIRTY -> "Flirty"
        Tone.RESERVED -> "Reserved"; Tone.FORMAL -> "Formal"
    }

    fun pace(v: ResponsePace) = when (v) {
        ResponsePace.IMMEDIATE -> "Immediate"; ResponsePace.PROMPT -> "Prompt"; ResponsePace.RELAXED -> "Relaxed"; ResponsePace.SLOW -> "Slow"
    }

    fun day(d: Weekday) = when (d) {
        Weekday.MON -> "Mon"; Weekday.TUE -> "Tue"; Weekday.WED -> "Wed"; Weekday.THU -> "Thu"
        Weekday.FRI -> "Fri"; Weekday.SAT -> "Sat"; Weekday.SUN -> "Sun"
    }

    const val NOT_SET = "Not set"
}
