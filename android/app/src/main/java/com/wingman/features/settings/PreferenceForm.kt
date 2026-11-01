package com.wingman.features.settings

import com.wingman.core.model.ActiveHours
import com.wingman.core.model.ActiveHoursWindow
import com.wingman.core.model.ApprovalReason
import com.wingman.core.model.AutomationMode
import com.wingman.core.model.AutomationSettings
import com.wingman.core.model.Brevity
import com.wingman.core.model.DelayRange
import com.wingman.core.model.EmojiFrequency
import com.wingman.core.model.Humor
import com.wingman.core.model.Intent
import com.wingman.core.model.IntentMode
import com.wingman.core.model.Preferences
import com.wingman.core.model.PreferencesUpdate
import com.wingman.core.model.Punctuation
import com.wingman.core.model.ResponsePace
import com.wingman.core.model.StyleProfile
import com.wingman.core.model.Tone
import com.wingman.core.model.Weekday
import java.time.ZoneId

/*
 * Editable preference forms (WING-013). Pure Kotlin: no android.* imports, JVM-tested.
 *
 * The limits below mirror contracts/openapi.yaml v1.0.0 and the WING-007 server validation in
 * backend/app/services/preferences.py, so a form that passes here is not rejected with 422.
 */

/** Fields that can carry a validation message. */
enum class FormField { INTENT_MODE, CUSTOM_DESCRIPTION, STYLE_SAMPLES, TIMEZONE, ACTIVE_WINDOWS, REPLY_DELAY, DAILY_LIMIT }

object Limits {
    const val CUSTOM_DESCRIPTION_MAX = 500
    const val STYLE_SAMPLES_MAX = 20
    const val STYLE_SAMPLE_MAX_CHARS = 1000
    const val WINDOWS_MAX = 14
    const val DELAY_MAX_SECONDS = 86_400
    const val DAILY_LIMIT_MAX = 1000
    val HHMM = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")
}

/**
 * Style attributes. Every attribute is nullable and null means "not set": the form never fills
 * or infers a value the user didn't pick. [samples] are only what the user typed.
 */
data class StyleForm(
    val punctuation: Punctuation? = null,
    val emojiFrequency: EmojiFrequency? = null,
    val brevity: Brevity? = null,
    val humor: Humor? = null,
    val tone: Tone? = null,
    val responsePace: ResponsePace? = null,
    val samples: List<String> = emptyList(),
) {
    /** Blank samples are editing leftovers, not samples: they are dropped, never sent. */
    val cleanSamples: List<String> get() = samples.map { it.trim() }.filter { it.isNotEmpty() }

    val isEmpty: Boolean
        get() = punctuation == null && emojiFrequency == null && brevity == null && humor == null &&
            tone == null && responsePace == null && cleanSamples.isEmpty()

    fun toProfile() = StyleProfile(punctuation, emojiFrequency, brevity, humor, tone, responsePace, cleanSamples)

    companion object {
        fun from(p: StyleProfile?) = if (p == null) StyleForm() else StyleForm(
            p.punctuation, p.emojiFrequency, p.brevity, p.humor, p.tone, p.responsePace, p.styleSamples.orEmpty(),
        )
    }
}

data class WindowForm(
    val days: Set<Weekday> = emptySet(),
    val start: String = "",
    val end: String = "",
)

/**
 * Automation settings. [mode] is deliberately NOT editable through [PreferenceForm] helpers:
 * only [EditorState.requestAutomationMode] / [EditorState.confirmAutomationMode] change it, so
 * automation can only be turned on by an explicit, confirmed user action.
 */
data class AutomationForm(
    val mode: AutomationMode = AutomationMode.MANUAL,
    val activeHoursEnabled: Boolean = false,
    val timezone: String = "",
    val windows: List<WindowForm> = emptyList(),
    val replyDelayEnabled: Boolean = false,
    val replyDelayMin: String = "",
    val replyDelayMax: String = "",
    /** Blank = server default. */
    val dailySendLimit: String = "",
    /** Kept in full, including reasons the UI doesn't offer as toggles, so a save never drops them. */
    val approvalRequiredFor: Set<ApprovalReason> = emptySet(),
) {
    companion object {
        fun from(a: AutomationSettings) = AutomationForm(
            mode = a.mode,
            activeHoursEnabled = a.activeHours != null,
            timezone = a.activeHours?.timezone.orEmpty(),
            windows = a.activeHours?.windows.orEmpty().map { WindowForm(it.days.toSet(), it.start, it.end) },
            replyDelayEnabled = a.replyDelaySeconds != null,
            replyDelayMin = a.replyDelaySeconds?.min?.toString().orEmpty(),
            replyDelayMax = a.replyDelaySeconds?.max?.toString().orEmpty(),
            dailySendLimit = a.dailySendLimit?.toString().orEmpty(),
            approvalRequiredFor = a.approvalRequiredFor.orEmpty().toSet(),
        )
    }
}

/** The whole user-wide form. `PUT /v1/preferences` is a full replacement, so nothing is dropped. */
data class PreferenceForm(
    /** Null until the user picks one: a never-saved user has no chosen intent. */
    val intentMode: IntentMode? = null,
    val customDescription: String = "",
    val style: StyleForm = StyleForm(),
    val automation: AutomationForm = AutomationForm(),
    /** Not edited in WING-013, but carried through every save so it is never wiped. */
    val dealbreakers: List<String> = emptyList(),
) {
    companion object {
        /**
         * Builds the form from server preferences. A never-saved user (`version == 0`) gets no
         * intent selected, every style attribute unset and automation MANUAL, whatever the
         * defaults response says: nothing is pre-chosen for them.
         */
        fun from(p: Preferences): PreferenceForm = if (p.version == 0) {
            PreferenceForm(dealbreakers = p.dealbreakers)
        } else {
            PreferenceForm(
                intentMode = p.intent.mode,
                customDescription = p.intent.customDescription.orEmpty(),
                style = StyleForm.from(p.style),
                automation = AutomationForm.from(p.automation),
                dealbreakers = p.dealbreakers,
            )
        }
    }
}

sealed interface Validated<out T> {
    data class Valid<out T>(val value: T) : Validated<T>
    data class Invalid(val errors: Map<FormField, String>) : Validated<Nothing>
}

/** Validation rules, one function per section so the wizard can validate a step at a time. */
object PreferenceValidation {

    fun intent(mode: IntentMode?, customDescription: String): Map<FormField, String> = buildMap {
        when {
            mode == null -> put(FormField.INTENT_MODE, "Choose what you're looking for.")
            mode == IntentMode.CUSTOM && customDescription.isBlank() ->
                put(FormField.CUSTOM_DESCRIPTION, "Describe your goal to use a custom intent.")
            mode == IntentMode.CUSTOM && customDescription.trim().length > Limits.CUSTOM_DESCRIPTION_MAX ->
                put(FormField.CUSTOM_DESCRIPTION, "Keep the description under ${Limits.CUSTOM_DESCRIPTION_MAX} characters.")
        }
    }

    fun style(style: StyleForm): Map<FormField, String> = buildMap {
        val samples = style.cleanSamples
        when {
            samples.size > Limits.STYLE_SAMPLES_MAX ->
                put(FormField.STYLE_SAMPLES, "Add at most ${Limits.STYLE_SAMPLES_MAX} samples.")
            samples.any { it.length > Limits.STYLE_SAMPLE_MAX_CHARS } ->
                put(FormField.STYLE_SAMPLES, "Each sample must be under ${Limits.STYLE_SAMPLE_MAX_CHARS} characters.")
        }
    }

    fun automation(a: AutomationForm): Map<FormField, String> = buildMap {
        if (a.activeHoursEnabled) {
            if (!isValidZone(a.timezone)) put(FormField.TIMEZONE, "Enter a time zone such as America/Chicago.")
            val windowError = when {
                a.windows.isEmpty() -> "Add at least one time window."
                a.windows.size > Limits.WINDOWS_MAX -> "Use at most ${Limits.WINDOWS_MAX} windows."
                a.windows.any { it.days.isEmpty() } -> "Pick at least one day for each window."
                a.windows.any { !Limits.HHMM.matches(it.start) || !Limits.HHMM.matches(it.end) } ->
                    "Times use 24-hour HH:MM, e.g. 09:00 or 22:30."
                else -> null
            }
            windowError?.let { put(FormField.ACTIVE_WINDOWS, it) }
        }
        if (a.replyDelayEnabled) {
            val min = seconds(a.replyDelayMin)
            val max = seconds(a.replyDelayMax)
            when {
                min == null || max == null -> put(FormField.REPLY_DELAY, "Enter delays from 0 to ${Limits.DELAY_MAX_SECONDS} seconds.")
                min > max -> put(FormField.REPLY_DELAY, "The minimum delay can't be longer than the maximum.")
            }
        }
        if (a.dailySendLimit.isNotBlank()) {
            val limit = a.dailySendLimit.trim().toIntOrNull()
            if (limit == null || limit !in 0..Limits.DAILY_LIMIT_MAX) {
                put(FormField.DAILY_LIMIT, "Enter a number from 0 to ${Limits.DAILY_LIMIT_MAX}, or leave it blank.")
            }
        }
    }

    fun all(form: PreferenceForm): Map<FormField, String> =
        intent(form.intentMode, form.customDescription) + style(form.style) + automation(form.automation)

    /** Server-accepted request body, or the errors that stop it from being sent. */
    fun toUpdate(form: PreferenceForm): Validated<PreferencesUpdate> {
        val errors = all(form)
        if (errors.isNotEmpty()) return Validated.Invalid(errors)
        val mode = checkNotNull(form.intentMode)
        return Validated.Valid(
            PreferencesUpdate(
                intent = toIntent(mode, form.customDescription),
                style = form.style.toProfile(),
                automation = toAutomation(form.automation),
                dealbreakers = form.dealbreakers,
            ),
        )
    }

    /** `custom_description` is the trimmed text for CUSTOM and null otherwise (the server rejects anything else). */
    fun toIntent(mode: IntentMode, customDescription: String) =
        Intent(mode, if (mode == IntentMode.CUSTOM) customDescription.trim() else null)

    fun toAutomation(a: AutomationForm) = AutomationSettings(
        mode = a.mode,
        activeHours = if (a.activeHoursEnabled) {
            ActiveHours(
                a.timezone.trim(),
                a.windows.map { w -> ActiveHoursWindow(Weekday.entries.filter { it in w.days }, w.start, w.end) },
            )
        } else {
            null
        },
        replyDelaySeconds = if (a.replyDelayEnabled) DelayRange(seconds(a.replyDelayMin)!!, seconds(a.replyDelayMax)!!) else null,
        dailySendLimit = a.dailySendLimit.trim().toIntOrNull(),
        approvalRequiredFor = ApprovalReason.entries.filter { it in a.approvalRequiredFor },
    )

    private fun seconds(raw: String): Int? = raw.trim().toIntOrNull()?.takeIf { it in 0..Limits.DELAY_MAX_SECONDS }

    /** IANA region names only (what the server's `ZoneInfo` accepts); offsets like `+02:00` are rejected. */
    fun isValidZone(raw: String): Boolean {
        val zone = raw.trim()
        return zone.isNotEmpty() && zone.length <= 64 && zone in ZoneId.getAvailableZoneIds()
    }
}
