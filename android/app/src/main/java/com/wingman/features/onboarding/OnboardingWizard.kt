package com.wingman.features.onboarding

import com.wingman.core.api.WingmanApi
import com.wingman.core.model.Preferences
import com.wingman.core.ui.ActionRunner
import com.wingman.core.ui.UiState
import com.wingman.core.ui.toUiState
import com.wingman.features.settings.FormField
import com.wingman.features.settings.PreferenceForm
import com.wingman.features.settings.PreferenceValidation
import com.wingman.features.settings.PreferencesEditor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Wizard steps in order. Style is optional; Review saves. */
enum class OnboardingStep(val title: String) {
    Intent("What are you looking for?"),
    Style("How do you text?"),
    Autonomy("How much should Wingman do?"),
    Review("Review and save"),
    ;

    val index: Int get() = ordinal
    val isFirst: Boolean get() = this == entries.first()
    val isLast: Boolean get() = this == entries.last()

    /** Validation for this step only. Style fields are optional, but samples must fit the contract. */
    fun validate(form: PreferenceForm): Map<FormField, String> = when (this) {
        Intent -> PreferenceValidation.intent(form.intentMode, form.customDescription)
        Style -> PreferenceValidation.style(form.style)
        Autonomy -> PreferenceValidation.automation(form.automation)
        Review -> PreferenceValidation.all(form)
    }

    companion object {
        val COUNT = entries.size
    }
}

/**
 * Should the app open onboarding? True only when the server says preferences were never saved
 * (`version == 0`). Unknown (loading or failed) is false, so a network error never traps a user
 * who already onboarded on another device in the wizard.
 */
object OnboardingGate {
    fun needsOnboarding(prefs: UiState<Preferences>): Boolean =
        prefs is UiState.Content && prefs.data.version == 0

    /** First-launch rule: one `GET /v1/preferences`; true only for a confirmed never-saved user. */
    suspend fun check(api: WingmanApi): Boolean = needsOnboarding(api.getPreferences().toUiState())
}

/**
 * Onboarding wizard: a step machine over a [PreferencesEditor]. Pure Kotlin.
 *
 * Forward navigation validates the current step; Back never validates. Automation mode is only
 * changed through the editor's explicit request/confirm flow, so finishing the wizard without
 * touching it saves MANUAL. [finish] saves with `If-Match: "0"`-style versioning (the loaded
 * version), so a second device that onboarded first produces a conflict, not an overwrite.
 */
class OnboardingWizard(
    scope: CoroutineScope,
    api: WingmanApi,
    runner: ActionRunner = ActionRunner(scope),
    val editor: PreferencesEditor = PreferencesEditor(scope, api, runner),
) {
    private val _step = MutableStateFlow(OnboardingStep.Intent)
    val step: StateFlow<OnboardingStep> = _step.asStateFlow()

    private val _finished = MutableStateFlow<Preferences?>(null)
    /** The saved preferences once onboarding completed; the screen then leaves the wizard. */
    val finished: StateFlow<Preferences?> = _finished.asStateFlow()

    fun load() = editor.load()

    /** Validates the current step and advances. Returns false when blocked by validation. */
    fun next(): Boolean {
        val current = _step.value
        if (current.isLast) return false
        if (!editor.validate(current::validate)) return false
        _step.value = OnboardingStep.entries[current.index + 1]
        return true
    }

    fun back(): Boolean {
        val current = _step.value
        if (current.isFirst) return false
        editor.validate { emptyMap() }
        _step.value = OnboardingStep.entries[current.index - 1]
        return true
    }

    /** Skip is offered only on the optional Style step; it keeps whatever the user entered (possibly nothing). */
    fun skipStyle(): Boolean {
        if (_step.value != OnboardingStep.Style) return false
        editor.validate { emptyMap() }
        _step.value = OnboardingStep.Autonomy
        return true
    }

    /** Jumps back to an earlier step from Review (e.g. "Edit intent"). */
    fun goTo(step: OnboardingStep) {
        if (step.index <= _step.value.index) {
            editor.validate { emptyMap() }
            _step.value = step
        }
    }

    /** Saves from the Review step. On a validation error it jumps to the first step with an error. */
    fun finish(): Boolean {
        if (_step.value != OnboardingStep.Review) return false
        val form = editor.content?.form ?: return false
        val firstInvalid = OnboardingStep.entries.firstOrNull { it != OnboardingStep.Review && it.validate(form).isNotEmpty() }
        if (firstInvalid != null) {
            _step.value = firstInvalid
            editor.validate(firstInvalid::validate)
            return false
        }
        return editor.save { _finished.value = it }
    }
}
