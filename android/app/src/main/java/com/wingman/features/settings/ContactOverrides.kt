package com.wingman.features.settings

import com.wingman.core.api.ApiResult
import com.wingman.core.api.WingmanApi
import com.wingman.core.model.AutomationMode
import com.wingman.core.model.ContactPreferenceOverride
import com.wingman.core.model.ContactPreferenceOverrideUpdate
import com.wingman.core.model.ContactPreferences
import com.wingman.core.model.ErrorCode
import com.wingman.core.model.IntentMode
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

/** Where an effective value comes from (contract precedence: override > user-wide > safe default). */
enum class ValueSource(val label: String) {
    CONTACT("Set for this contact"),
    USER("From your settings"),
    DEFAULT("Default"),
    NOT_SET("Not set"),
}

data class EffectiveRow(val label: String, val value: String, val source: ValueSource)

/**
 * The effective settings for one contact, row by row, each with its source. Computed from the
 * server's `override` and `effective` objects; the values shown are the server's `effective`
 * values, the source is derived from which override fields are non-null.
 */
object EffectiveDisplay {
    fun rows(cp: ContactPreferences): List<EffectiveRow> {
        val o = cp.override
        val eff = cp.effective
        val userSaved = eff.version > 0
        val inherited = if (userSaved) ValueSource.USER else ValueSource.DEFAULT
        val intent = eff.intent
        val intentValue = PreferenceLabels.intent(intent.mode) +
            (intent.customDescription?.takeIf { intent.mode == IntentMode.CUSTOM }?.let { ": $it" } ?: "")
        return buildList {
            add(EffectiveRow("Intent", intentValue, if (o.intent != null) ValueSource.CONTACT else inherited))
            add(
                EffectiveRow(
                    "Automation", PreferenceLabels.automation(eff.automation.mode),
                    if (o.automationMode != null) ValueSource.CONTACT else inherited,
                ),
            )
            val s = eff.style
            val os = o.style
            add(styleRow("Punctuation", s.punctuation?.let(PreferenceLabels::punctuation), os?.punctuation != null))
            add(styleRow("Emoji", s.emojiFrequency?.let(PreferenceLabels::emoji), os?.emojiFrequency != null))
            add(styleRow("Length", s.brevity?.let(PreferenceLabels::brevity), os?.brevity != null))
            add(styleRow("Humor", s.humor?.let(PreferenceLabels::humor), os?.humor != null))
            add(styleRow("Tone", s.tone?.let(PreferenceLabels::tone), os?.tone != null))
            add(styleRow("Reply pace", s.responsePace?.let(PreferenceLabels::pace), os?.responsePace != null))
            val samples = s.styleSamples.orEmpty()
            add(
                EffectiveRow(
                    "Style samples",
                    if (samples.isEmpty()) PreferenceLabels.NOT_SET else "${samples.size} sample${if (samples.size == 1) "" else "s"}",
                    when {
                        !os?.styleSamples.isNullOrEmpty() -> ValueSource.CONTACT
                        samples.isNotEmpty() -> ValueSource.USER
                        else -> ValueSource.NOT_SET
                    },
                ),
            )
        }
    }

    private fun styleRow(label: String, value: String?, fromContact: Boolean) = EffectiveRow(
        label,
        value ?: PreferenceLabels.NOT_SET,
        when {
            value == null -> ValueSource.NOT_SET
            fromContact -> ValueSource.CONTACT
            else -> ValueSource.USER
        },
    )
}

/**
 * Editable override. Each section is either inherited (off) or overridden (on). Turning a section
 * off sends `null` for it, which the server treats as "inherit from user-wide preferences".
 */
data class OverrideForm(
    val intentOverridden: Boolean = false,
    val intentMode: IntentMode? = null,
    val customDescription: String = "",
    val styleOverridden: Boolean = false,
    val style: StyleForm = StyleForm(),
    /** Null = inherit. Changed to ASSISTED/AUTOPILOT only through the confirm flow. */
    val automationMode: AutomationMode? = null,
) {
    companion object {
        fun from(o: ContactPreferenceOverride) = OverrideForm(
            intentOverridden = o.intent != null,
            intentMode = o.intent?.mode,
            customDescription = o.intent?.customDescription.orEmpty(),
            styleOverridden = o.style != null,
            style = StyleForm.from(o.style),
            automationMode = o.automationMode,
        )
    }
}

object OverrideValidation {
    fun errors(f: OverrideForm): Map<FormField, String> =
        (if (f.intentOverridden) PreferenceValidation.intent(f.intentMode, f.customDescription) else emptyMap()) +
            (if (f.styleOverridden) PreferenceValidation.style(f.style) else emptyMap())

    fun toUpdate(f: OverrideForm): Validated<ContactPreferenceOverrideUpdate> {
        val errors = errors(f)
        if (errors.isNotEmpty()) return Validated.Invalid(errors)
        return Validated.Valid(
            ContactPreferenceOverrideUpdate(
                intent = if (f.intentOverridden) PreferenceValidation.toIntent(f.intentMode!!, f.customDescription) else null,
                style = if (f.styleOverridden) f.style.toProfile() else null,
                automationMode = f.automationMode,
            ),
        )
    }
}

data class OverrideContent(
    val contactId: String,
    val server: ContactPreferences,
    val form: OverrideForm,
    val channels: ChannelInfo,
    val errors: Map<FormField, String> = emptyMap(),
    val pendingAutomation: PendingAutomation? = null,
    /** Non-null after a 412: the reloaded server state is in [server]; the user's edits are kept in [form]. */
    val conflictMessage: String? = null,
    val busy: Boolean = false,
    val actionError: UiError? = null,
    val notice: String? = null,
) {
    val effectiveRows: List<EffectiveRow> get() = EffectiveDisplay.rows(server)
    val hasOverride: Boolean get() = server.override.let { it.intent != null || it.style != null || it.automationMode != null }
    val baseVersion: Int get() = server.override.version
}

/**
 * Per-contact override editor. It only ever calls the contact preference endpoints
 * (`getContactPreferences`, `putContactPreferences`, `deleteContactPreferences`), never
 * `putPreferences`, so an override cannot alter global settings.
 */
class ContactOverrideEditor(
    private val scope: CoroutineScope,
    private val api: WingmanApi,
    val contactId: String,
    private val runner: ActionRunner = ActionRunner(scope),
    private val loadChannels: suspend (WingmanApi) -> ChannelInfo = { ChannelInfo.load(it) },
) {
    private val _state = MutableStateFlow<UiState<OverrideContent>>(UiState.Loading)
    val state: StateFlow<UiState<OverrideContent>> = _state.asStateFlow()
    private var loadJob: Job? = null

    val content: OverrideContent? get() = (_state.value as? UiState.Content)?.data

    private val saveAction = "contact-preferences:save:$contactId"
    private val clearAction = "contact-preferences:clear:$contactId"

    fun load() {
        if (loadJob?.isActive == true) return
        _state.value = UiState.Loading
        loadJob = scope.launch {
            val channels = async { loadChannels(api) }
            _state.value = when (val r = api.getContactPreferences(contactId)) {
                is ApiResult.Success -> UiState.Content(OverrideContent(contactId, r.value, OverrideForm.from(r.value.override), channels.await()))
                is ApiResult.Failure -> {
                    channels.cancel()
                    UiState.Error(UiError.from(r.error))
                }
            }
        }
    }

    /** Form edits. The automation mode can't be raised here; use [requestAutomationMode]. */
    fun edit(transform: (OverrideForm) -> OverrideForm) = mutate { c ->
        val next = transform(c.form).copy(automationMode = c.form.automationMode)
        c.copy(form = next, errors = if (c.errors.isEmpty()) c.errors else OverrideValidation.errors(next), notice = null, actionError = null)
    }

    /** Null (inherit) and MANUAL apply at once; ASSISTED/AUTOPILOT open an explanation first. */
    fun requestAutomationMode(mode: AutomationMode?) = mutate { c ->
        if (mode == null || mode == AutomationMode.MANUAL) {
            c.copy(form = c.form.copy(automationMode = mode), pendingAutomation = null)
        } else {
            val settings = AutomationForm.from(c.server.effective.automation)
            c.copy(pendingAutomation = PendingAutomation(AutomationExplainer.explain(mode, settings, c.channels)))
        }
    }

    fun confirmAutomationMode() = mutate { c ->
        val p = c.pendingAutomation ?: return@mutate c
        c.copy(form = c.form.copy(automationMode = p.explanation.mode), pendingAutomation = null)
    }

    fun cancelAutomationMode() = mutate { it.copy(pendingAutomation = null) }

    private fun anyInFlight() = runner.stateOf(saveAction) is ActionState.InFlight || runner.stateOf(clearAction) is ActionState.InFlight

    /** Saves the override with `If-Match`. False when nothing was sent (invalid, busy, double tap). */
    fun save(): Boolean {
        val c = content ?: return false
        if (anyInFlight()) return false
        val body = when (val v = OverrideValidation.toUpdate(c.form)) {
            is Validated.Invalid -> {
                mutate { it.copy(errors = v.errors) }
                return false
            }
            is Validated.Valid -> v.value
        }
        val version = c.baseVersion
        mutate { it.copy(busy = true, errors = emptyMap(), actionError = null, notice = null) }
        return runner.submit<ContactPreferences>(saveAction, onResult = { r ->
            when (r) {
                is ActionState.Done -> mutate {
                    it.copy(server = r.value, form = OverrideForm.from(r.value.override), busy = false, conflictMessage = null, notice = "Saved for this contact.")
                }
                is ActionState.Failed -> if (r.error.code == ErrorCode.PRECONDITION_FAILED) {
                    scope.launch { onConflict(body) }
                } else {
                    mutate { it.copy(busy = false, actionError = r.error) }
                }
                else -> Unit
            }
        }) { api.putContactPreferences(contactId, body, ifMatchVersion = version) }
    }

    /** 412: reload the server state, keep the user's form, and say so. Nothing is re-sent automatically. */
    private suspend fun onConflict(sent: ContactPreferenceOverrideUpdate) {
        val latest = api.getContactPreferences(contactId).getOrNull()
        if (latest != null && latest.override.matches(sent)) {
            // An earlier attempt committed but its response was lost: that is our save, not a conflict.
            mutate { it.copy(server = latest, form = OverrideForm.from(latest.override), busy = false, conflictMessage = null, notice = "Saved for this contact.") }
            return
        }
        mutate {
            it.copy(
                server = latest ?: it.server,
                busy = false,
                conflictMessage = if (latest != null) {
                    "This contact's settings changed somewhere else. Nothing was overwritten. The current values are shown; " +
                        "your edits are still in the form. Save again to replace them, or discard your edits."
                } else {
                    "This contact's settings changed somewhere else and couldn't be reloaded. Nothing was overwritten. Try again."
                },
            )
        }
    }

    /** Conflict choice: discard edits and show the current server override. */
    fun discardEdits() = mutate { it.copy(form = OverrideForm.from(it.server.override), conflictMessage = null, errors = emptyMap()) }

    /** Conflict acknowledged: keep edits; the next [save] uses the reloaded version. */
    fun dismissConflict() = mutate { it.copy(conflictMessage = null) }

    /** Clears the override (DELETE); the contact then inherits everything from user-wide settings. */
    fun clear(): Boolean {
        if (content == null || anyInFlight()) return false
        mutate { it.copy(busy = true, actionError = null, notice = null) }
        return runner.submit<ContactPreferences>(clearAction, onResult = { r ->
            when (r) {
                is ActionState.Done -> mutate {
                    it.copy(server = r.value, form = OverrideForm.from(r.value.override), busy = false, conflictMessage = null, notice = "Override cleared. This contact now uses your settings.")
                }
                is ActionState.Failed -> mutate { it.copy(busy = false, actionError = r.error) }
                else -> Unit
            }
        }) {
            when (val d = api.deleteContactPreferences(contactId)) {
                is ApiResult.Failure -> d
                // The delete response has no body: read back the merged state the server now holds.
                is ApiResult.Success -> api.getContactPreferences(contactId)
            }
        }
    }

    private fun mutate(transform: (OverrideContent) -> OverrideContent) = _state.update { s ->
        if (s is UiState.Content) s.copy(data = transform(s.data)) else s
    }

    private fun ContactPreferenceOverride.matches(sent: ContactPreferenceOverrideUpdate) =
        intent == sent.intent && automationMode == sent.automationMode &&
            style?.copy(styleSamples = style.styleSamples.orEmpty()) == sent.style?.copy(styleSamples = sent.style.styleSamples.orEmpty())
}

/** A contact the user can set an override for. */
data class ContactChoice(val id: String, val name: String)

/**
 * Contacts for the override picker, taken from conversations (the contract has no contact list
 * route). Distinct by contact ID, sorted by name, up to [maxPages] pages.
 */
suspend fun loadContactChoices(api: WingmanApi, maxPages: Int = 5): ApiResult<List<ContactChoice>> {
    val seen = LinkedHashMap<String, ContactChoice>()
    var cursor: String? = null
    repeat(maxPages) {
        when (val r = api.listConversations(com.wingman.core.api.PageRequest(cursor, 100))) {
            is ApiResult.Failure -> return r
            is ApiResult.Success -> {
                r.value.items.forEach { seen.putIfAbsent(it.contact.id, ContactChoice(it.contact.id, it.contact.displayName)) }
                cursor = r.value.nextCursor ?: return ApiResult.Success(seen.values.sortedBy { it.name.lowercase() })
            }
        }
    }
    return ApiResult.Success(seen.values.sortedBy { it.name.lowercase() })
}
