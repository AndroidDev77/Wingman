package com.wingman.features.settings

import com.wingman.core.api.ApiResult
import com.wingman.core.api.WingmanApi
import com.wingman.core.model.AutomationMode
import com.wingman.core.model.ErrorCode
import com.wingman.core.model.Preferences
import com.wingman.core.model.PreferencesUpdate
import com.wingman.core.ui.ActionRunner
import com.wingman.core.ui.ActionState
import com.wingman.core.ui.UiError
import com.wingman.core.ui.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Save progress shown next to the Save button. */
sealed interface SaveStatus {
    data object Idle : SaveStatus
    data object Saving : SaveStatus
    data class Saved(val version: Int) : SaveStatus
    data class Failed(val error: UiError) : SaveStatus
}

/**
 * The server changed since the form was loaded (`412 precondition_failed`). The user's edits are
 * kept and nothing was written. [latest] is the server's current state (null if the reload failed).
 * Saving is blocked until the user picks [PreferencesEditor.keepMyEdits] or [PreferencesEditor.useLatest].
 */
data class Conflict(val latest: Preferences?) {
    val message: String
        get() = if (latest != null) {
            "Your settings were changed somewhere else (now version ${latest.version}) after you opened this screen. " +
                "Nothing was overwritten and your edits are still here. Keep your edits and save again, or load the latest settings."
        } else {
            "Your settings were changed somewhere else, and Wingman couldn't load the latest version. " +
                "Nothing was overwritten and your edits are still here. Try loading the latest settings again."
        }
}

/** Shown before automation is turned on; the mode changes only on [PreferencesEditor.confirmAutomationMode]. */
data class PendingAutomation(val explanation: AutomationExplanation)

data class EditorContent(
    val form: PreferenceForm,
    /** The server version the form is based on; sent as `If-Match`. 0 = never saved. */
    val baseVersion: Int,
    val saved: Preferences,
    val channels: ChannelInfo,
    val errors: Map<FormField, String> = emptyMap(),
    val pendingAutomation: PendingAutomation? = null,
    val conflict: Conflict? = null,
    val saveStatus: SaveStatus = SaveStatus.Idle,
) {
    val isDirty: Boolean get() = form != PreferenceForm.from(saved)
    val isSaving: Boolean get() = saveStatus == SaveStatus.Saving
}

/**
 * User-wide preference editor shared by onboarding and settings. Pure Kotlin: an androidx
 * ViewModel passes `viewModelScope`; tests pass a TestScope.
 *
 * Guarantees:
 *  - Automation stays MANUAL unless the user explicitly picks a mode AND confirms its explanation.
 *  - Saves are `PUT /v1/preferences` with `If-Match` = [EditorContent.baseVersion].
 *  - A double tap on Save while a save is in flight sends one request ([ActionRunner]).
 *  - On 412 the latest server state is reloaded and shown as a [Conflict]; the user's edits are
 *    kept, and nothing is re-sent until the user decides.
 */
class PreferencesEditor(
    private val scope: CoroutineScope,
    private val api: WingmanApi,
    private val runner: ActionRunner = ActionRunner(scope),
    private val loadChannels: suspend (WingmanApi) -> ChannelInfo = { ChannelInfo.load(it) },
) {
    private val _state = MutableStateFlow<UiState<EditorContent>>(UiState.Loading)
    val state: StateFlow<UiState<EditorContent>> = _state.asStateFlow()
    private var loadJob: Job? = null

    /** The latest content, or null while loading or after a load error. */
    val content: EditorContent? get() = (_state.value as? UiState.Content)?.data

    fun load() {
        if (loadJob?.isActive == true) return
        _state.value = UiState.Loading
        loadJob = scope.launch {
            val channels = async { loadChannels(api) }
            _state.value = when (val r = api.getPreferences()) {
                is ApiResult.Success -> UiState.Content(
                    EditorContent(PreferenceForm.from(r.value), r.value.version, r.value, channels.await()),
                )
                is ApiResult.Failure -> {
                    channels.cancel()
                    UiState.Error(UiError.from(r.error))
                }
            }
        }
    }

    /* ------------------------------------------------------------ edits */

    /** Applies a form edit. Automation mode changes made this way are ignored: see [requestAutomationMode]. */
    fun edit(transform: (PreferenceForm) -> PreferenceForm) = mutate { c ->
        val next = transform(c.form)
        val safe = next.copy(automation = next.automation.copy(mode = c.form.automation.mode))
        c.copy(form = safe, errors = if (c.errors.isEmpty()) c.errors else PreferenceValidation.all(safe), saveStatus = idleUnlessSaving(c))
    }

    fun editAutomation(transform: (AutomationForm) -> AutomationForm) = edit { it.copy(automation = transform(it.automation)) }

    fun editStyle(transform: (StyleForm) -> StyleForm) = edit { it.copy(style = transform(it.style)) }

    /**
     * The user tapped an automation mode. MANUAL applies immediately (turning automation off is
     * always allowed). ASSISTED/AUTOPILOT only open an explanation; the mode is unchanged until
     * [confirmAutomationMode].
     */
    fun requestAutomationMode(mode: AutomationMode) = mutate { c ->
        if (mode == AutomationMode.MANUAL) {
            c.copy(form = c.form.withMode(mode), pendingAutomation = null, saveStatus = idleUnlessSaving(c))
        } else {
            c.copy(pendingAutomation = PendingAutomation(AutomationExplainer.explain(mode, c.form.automation, c.channels)))
        }
    }

    fun confirmAutomationMode() = mutate { c ->
        val pending = c.pendingAutomation ?: return@mutate c
        c.copy(form = c.form.withMode(pending.explanation.mode), pendingAutomation = null, saveStatus = idleUnlessSaving(c))
    }

    fun cancelAutomationMode() = mutate { it.copy(pendingAutomation = null) }

    /** Validates the given fields only (for the wizard's per-step validation). Returns true when valid. */
    fun validate(check: (PreferenceForm) -> Map<FormField, String>): Boolean {
        val c = content ?: return false
        val errors = check(c.form)
        mutate { it.copy(errors = errors) }
        return errors.isEmpty()
    }

    /* ------------------------------------------------------------ save */

    /**
     * Saves the whole form. Returns false (nothing sent) when still loading, invalid, blocked by an
     * unresolved [Conflict], or a save is already in flight (double tap).
     */
    fun save(onSaved: (Preferences) -> Unit = {}): Boolean {
        val c = content ?: return false
        if (c.conflict != null || c.isSaving) return false
        val update = when (val v = PreferenceValidation.toUpdate(c.form)) {
            is Validated.Invalid -> {
                mutate { it.copy(errors = v.errors) }
                return false
            }
            is Validated.Valid -> v.value
        }
        if (runner.stateOf(SAVE_ACTION) is ActionState.InFlight) return false
        val baseVersion = c.baseVersion
        // Set before submitting: on Main.immediate the call can complete inside submit().
        mutate { it.copy(errors = emptyMap(), saveStatus = SaveStatus.Saving) }
        return runner.submit<Preferences>(SAVE_ACTION, onResult = { onSaveResult(it, update, onSaved) }) {
            // PUT is idempotent by If-Match; the runner's idempotency key is not sent.
            api.putPreferences(update, ifMatchVersion = baseVersion)
        }
    }

    private fun onSaveResult(result: ActionState<Preferences>, sent: PreferencesUpdate, onSaved: (Preferences) -> Unit) {
        when (result) {
            is ActionState.Done -> {
                applySaved(result.value)
                onSaved(result.value)
            }
            is ActionState.Failed -> if (result.error.code == ErrorCode.PRECONDITION_FAILED) {
                scope.launch { resolvePreconditionFailed(sent, onSaved) }
            } else {
                mutate { it.copy(saveStatus = SaveStatus.Failed(result.error)) }
            }
            else -> Unit
        }
    }

    /**
     * 412: re-read the server. If it already holds exactly what we sent (an earlier attempt
     * committed but its response was lost), that IS our save. Otherwise show a [Conflict].
     */
    private suspend fun resolvePreconditionFailed(sent: PreferencesUpdate, onSaved: (Preferences) -> Unit) {
        val latest = api.getPreferences().getOrNull()
        if (latest != null && latest.matches(sent)) {
            applySaved(latest)
            onSaved(latest)
            return
        }
        mutate { it.copy(conflict = Conflict(latest), saveStatus = SaveStatus.Idle) }
    }

    /** Conflict choice: keep the user's edits and rebase them on the latest version. The next save replaces it. */
    fun keepMyEdits() = mutate { c ->
        val latest = c.conflict?.latest ?: return@mutate c
        c.copy(baseVersion = latest.version, saved = latest, conflict = null)
    }

    /** Conflict choice: discard the user's edits and show the latest server settings (reloads if needed). */
    fun useLatest() {
        val c = content ?: return
        val latest = c.conflict?.latest
        if (latest != null) {
            mutate { it.copy(form = PreferenceForm.from(latest), baseVersion = latest.version, saved = latest, conflict = null, errors = emptyMap()) }
            return
        }
        scope.launch {
            val fresh = api.getPreferences().getOrNull()
            mutate { it.copy(conflict = Conflict(fresh)) }
        }
    }

    private fun applySaved(p: Preferences) = mutate {
        it.copy(form = PreferenceForm.from(p), baseVersion = p.version, saved = p, errors = emptyMap(), conflict = null, saveStatus = SaveStatus.Saved(p.version))
    }

    private fun mutate(transform: (EditorContent) -> EditorContent) = _state.update { s ->
        if (s is UiState.Content) s.copy(data = transform(s.data)) else s
    }

    private fun idleUnlessSaving(c: EditorContent) = if (c.isSaving) c.saveStatus else SaveStatus.Idle

    companion object {
        const val SAVE_ACTION = "preferences:save"

        private fun PreferenceForm.withMode(mode: AutomationMode) = copy(automation = automation.copy(mode = mode))

        /** True when [this] server state is exactly what [sent] would produce. */
        fun Preferences.matches(sent: PreferencesUpdate): Boolean =
            intent == sent.intent && style.normalized() == sent.style.normalized() &&
                automation.normalized() == sent.automation.normalized() && dealbreakers == sent.dealbreakers.orEmpty()

        private fun com.wingman.core.model.StyleProfile.normalized() = copy(styleSamples = styleSamples.orEmpty())
        private fun com.wingman.core.model.AutomationSettings.normalized() = copy(approvalRequiredFor = approvalRequiredFor.orEmpty().sorted())
    }
}
