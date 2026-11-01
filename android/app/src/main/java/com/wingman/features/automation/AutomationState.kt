package com.wingman.features.automation

import com.wingman.core.api.ApiResult
import com.wingman.core.api.IdempotencyKeyRegistry
import com.wingman.core.api.RetryPolicy
import com.wingman.core.api.WingmanApi
import com.wingman.core.model.AutomationActionRequest
import com.wingman.core.model.AutomationMode
import com.wingman.core.model.ContactAutomationState
import com.wingman.core.model.ContactAutomationStatus
import com.wingman.core.model.GlobalAutomationState
import com.wingman.core.model.GlobalAutomationStatus
import com.wingman.core.ui.ActionRunner
import com.wingman.core.ui.ActionState
import com.wingman.core.ui.UiError
import com.wingman.core.ui.UiState
import com.wingman.features.approvals.ConversationIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A contact's status as the server last reported it. */
private data class KnownContact(val status: ContactAutomationStatus, val mode: AutomationMode?, val confirmation: String? = null)

/**
 * Pure-Kotlin pause / resume / takeover controls (WING-015, D-014).
 *
 *  - The shown status changes ONLY from a server response (the action's 200 body, or a re-read);
 *    while an action runs the row shows "Pausing…" etc., and a failure leaves the old status.
 *  - One [ActionRunner] action per (contact, action): a rapid second tap is dropped, and a retry
 *    reuses the key. Starting a DIFFERENT action on the same contact first releases the keys kept
 *    by the others, so a stale failed "pause" can never be replayed over a later "takeover".
 *  - A rejected action (e.g. 409) re-reads the contact's state from the server.
 */
class AutomationState(
    private val scope: CoroutineScope,
    private val api: WingmanApi,
    private val keys: IdempotencyKeyRegistry = IdempotencyKeyRegistry(),
    policy: RetryPolicy = RetryPolicy(),
    sleep: (suspend (Long) -> Unit)? = null,
) {
    private val runner = ActionRunner(scope, keys, policy, sleep)

    private val load = MutableStateFlow<UiState<ConversationIndex>>(UiState.Loading)
    private val global = MutableStateFlow<GlobalAutomationState?>(null)
    private val globalConfirmation = MutableStateFlow<String?>(null)
    private val known = MutableStateFlow<Map<String, KnownContact>>(emptyMap())

    val state: StateFlow<UiState<AutomationScreenData>> =
        combine(load, global, globalConfirmation, known, runner.states) { l, g, gc, k, actions -> reduce(l, g, gc, k, actions) }
            .stateIn(scope, SharingStarted.Eagerly, UiState.Loading)

    fun load() {
        if (load.value is UiState.Content) load.update { (it as UiState.Content).copy(refreshing = true, refreshError = null) } else load.value = UiState.Loading
        scope.launch {
            val g = async { api.getGlobalAutomationState() }
            val idx = ConversationIndex.load(api)
            when (val gr = g.await()) {
                is ApiResult.Success -> global.value = gr.value
                is ApiResult.Failure -> Unit // global row shows "Status unknown"
            }
            when (idx) {
                is ApiResult.Success -> {
                    // Conversation rows carry the server's contact status; they replace what we knew.
                    known.update { old ->
                        idx.value.contacts().associate { c ->
                            c.contact.id to KnownContact(c.automation.contactStatus, c.automation.effectiveMode, old[c.contact.id]?.confirmation)
                        }
                    }
                    load.value = if (idx.value.byId.isEmpty() && global.value == null) UiState.Empty else UiState.Content(idx.value)
                }
                is ApiResult.Failure -> {
                    val err = UiError.from(idx.error)
                    load.update { if (it is UiState.Content) it.copy(refreshing = false, refreshError = err) else UiState.Error(err) }
                }
            }
        }
    }

    /* ------------------------------------------------------------ contact actions */

    fun pause(contactId: String) = contactAction(contactId, ContactAction.PAUSE)
    fun resume(contactId: String) = contactAction(contactId, ContactAction.RESUME)
    fun takeover(contactId: String) = contactAction(contactId, ContactAction.TAKEOVER)

    /** Returns false (no request) while any action for this contact is in flight. */
    fun contactAction(contactId: String, action: ContactAction, reason: String? = null): Boolean {
        if (contactBusy(contactId)) return false
        ContactAction.entries.filter { it != action }.forEach { keys.complete(contactActionId(contactId, it)) }
        val body = AutomationActionRequest(reason?.trim()?.takeIf { it.isNotEmpty() }?.take(500))
        return runner.submit<ContactAutomationState>(contactActionId(contactId, action), onResult = { r ->
            when (r) {
                is ActionState.Done -> {
                    val s = r.value
                    known.update { it + (contactId to KnownContact(s.status, s.effectiveMode, ActionSummary.contact(action, s))) }
                    // The response also carries the server's global status.
                    global.update { g -> if (g?.status == s.globalStatus) g else GlobalAutomationState(s.globalStatus, g?.changedAt) }
                }
                is ActionState.Failed -> if (!r.error.retryable) reloadContact(contactId)
                else -> Unit
            }
        }) { key ->
            when (action) {
                ContactAction.PAUSE -> api.pauseContactAutomation(contactId, body, key)
                ContactAction.RESUME -> api.resumeContactAutomation(contactId, body, key)
                ContactAction.TAKEOVER -> api.takeoverContact(contactId, body, key)
            }
        }
    }

    /* ------------------------------------------------------------ global */

    fun pauseAll(): Boolean = globalAction(pause = true)
    fun resumeAll(): Boolean = globalAction(pause = false)

    private fun globalAction(pause: Boolean): Boolean {
        if (globalBusy()) return false
        keys.complete(globalActionId(!pause))
        return runner.submit<GlobalAutomationState>(globalActionId(pause), onResult = { r ->
            when (r) {
                is ActionState.Done -> {
                    global.value = r.value
                    globalConfirmation.value = ActionSummary.global(r.value)
                }
                is ActionState.Failed -> if (!r.error.retryable) reloadGlobal()
                else -> Unit
            }
        }) { key ->
            if (pause) api.pauseAllAutomation(AutomationActionRequest(), key) else api.resumeAllAutomation(AutomationActionRequest(), key)
        }
    }

    /* ------------------------------------------------------------ internals */

    private fun contactBusy(contactId: String) =
        ContactAction.entries.any { runner.stateOf(contactActionId(contactId, it)) is ActionState.InFlight }

    private fun globalBusy() = runner.stateOf(globalActionId(true)) is ActionState.InFlight ||
        runner.stateOf(globalActionId(false)) is ActionState.InFlight

    private fun reloadContact(contactId: String) {
        scope.launch {
            (api.getContactAutomationState(contactId) as? ApiResult.Success)?.value?.let { s ->
                known.update { it + (contactId to KnownContact(s.status, s.effectiveMode, it[contactId]?.confirmation)) }
            }
        }
    }

    private fun reloadGlobal() {
        scope.launch { (api.getGlobalAutomationState() as? ApiResult.Success)?.value?.let { global.value = it } }
    }

    private fun reduce(
        l: UiState<ConversationIndex>,
        g: GlobalAutomationState?,
        gc: String?,
        k: Map<String, KnownContact>,
        actions: Map<String, ActionState<Any?>>,
    ): UiState<AutomationScreenData> {
        val idx = when (l) {
            is UiState.Content -> l.data
            UiState.Empty -> ConversationIndex.EMPTY
            UiState.Loading -> return UiState.Loading
            is UiState.Error -> return l
        }
        val globalInFlight = when {
            actions[globalActionId(true)] is ActionState.InFlight -> "Pausing everything…"
            actions[globalActionId(false)] is ActionState.InFlight -> "Resuming…"
            else -> null
        }
        val globalError = listOf(globalActionId(true), globalActionId(false))
            .firstNotNullOfOrNull { (actions[it] as? ActionState.Failed)?.error }
        val globalControls = GlobalControls(
            status = g?.status,
            label = when (g?.status) {
                null -> "Status unknown"
                GlobalAutomationStatus.ACTIVE -> "Automation is on"
                GlobalAutomationStatus.PAUSED -> "Paused for everyone"
            },
            inFlightLabel = globalInFlight,
            confirmation = gc.takeIf { globalInFlight == null && globalError == null },
            error = globalError,
        )
        val contacts = idx.contacts().map { c ->
            val id = c.contact.id
            val kc = k[id]
            val inFlight = ContactAction.entries.firstOrNull { actions[contactActionId(id, it)] is ActionState.InFlight }
            val error = ContactAction.entries.firstNotNullOfOrNull { (actions[contactActionId(id, it)] as? ActionState.Failed)?.error }
            val (label, tone) = ContactControls.label(kc?.status, kc?.mode, g?.status)
            ContactControls(
                contactId = id,
                name = c.contact.displayName,
                status = kc?.status,
                effectiveMode = kc?.mode,
                statusLabel = label,
                tone = tone,
                inFlight = inFlight,
                confirmation = kc?.confirmation.takeIf { inFlight == null && error == null },
                error = error,
            )
        }
        return UiState.Content(AutomationScreenData(globalControls, contacts, contactsIncomplete = !idx.complete))
    }

    companion object {
        fun contactActionId(contactId: String, action: ContactAction) = "automation:${action.name.lowercase()}:$contactId"
        fun globalActionId(pause: Boolean) = if (pause) "automation:pause_all" else "automation:resume_all"
    }
}
