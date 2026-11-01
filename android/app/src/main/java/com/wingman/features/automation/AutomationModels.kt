package com.wingman.features.automation

import com.wingman.core.model.AutomationMode
import com.wingman.core.model.ContactAutomationState
import com.wingman.core.model.ContactAutomationStatus
import com.wingman.core.model.GlobalAutomationState
import com.wingman.core.model.GlobalAutomationStatus
import com.wingman.core.ui.UiError
import com.wingman.features.conversation.LabelTone

/*
 * Pause / resume / takeover display logic (WING-015, D-014). Pure Kotlin, JVM-tested.
 *
 * Every status shown here is the last one the SERVER returned. A switch the user flips shows a
 * "Pausing…" style progress label, never the new state, until the server confirms it.
 */

/** Plain-language D-014 explanations shown next to the controls. */
object AutomationCopy {
    const val PAUSE =
        "Pausing stops automatic replies. Wingman can still prepare drafts for you to approve, and any message you approve, " +
            "or write yourself, still sends. Automatic replies that were waiting to go out are canceled."
    const val TAKEOVER =
        "Taking over means you're driving. Wingman cancels everything it had queued for this person: drafts waiting for " +
            "approval and approved replies that haven't gone out yet. It won't prepare new drafts until you hand it back. " +
            "Your own messages still send."
    const val GLOBAL_PAUSE =
        "Pause everything: stops automatic replies to everyone. Drafts you approve and messages you write still send."
    const val ALREADY_SENDING =
        "A message that's already being sent can't be recalled; its real result still shows in the activity log."
}

/** What a server-confirmed contact action did, from the counts in the response. */
object ActionSummary {
    fun contact(action: ContactAction, s: ContactAutomationState): String {
        val head = when (action) {
            ContactAction.PAUSE -> "Paused."
            ContactAction.RESUME -> "Automation is back on."
            ContactAction.TAKEOVER -> "You've taken over."
        }
        val parts = buildList {
            if (s.canceledApprovalCount > 0) add(plural(s.canceledApprovalCount, "draft waiting for approval", "drafts waiting for approval"))
            if (s.canceledMessageCount > 0) add(plural(s.canceledMessageCount, "queued message", "queued messages"))
        }
        return if (parts.isEmpty()) head else "$head Canceled ${parts.joinToString(" and ")}."
    }

    fun global(s: GlobalAutomationState): String = when (s.status) {
        GlobalAutomationStatus.PAUSED -> "All automatic replies are paused."
        GlobalAutomationStatus.ACTIVE -> "Automation is back on. Contacts you paused or took over stay that way."
    }

    private fun plural(n: Int, one: String, many: String) = if (n == 1) "1 $one" else "$n $many"
}

enum class ContactAction(val inFlightLabel: String) {
    PAUSE("Pausing…"),
    RESUME("Resuming…"),
    TAKEOVER("Taking over…"),
}

/** One contact's controls. */
data class ContactControls(
    val contactId: String,
    val name: String,
    /** Server-confirmed status; null until the server has answered for this contact. */
    val status: ContactAutomationStatus?,
    val effectiveMode: AutomationMode?,
    val statusLabel: String,
    val tone: LabelTone,
    /** Non-null while an action runs: shows progress, not the new state. */
    val inFlight: ContactAction?,
    /** Summary of the last confirmed action (from the server's counts). */
    val confirmation: String?,
    val error: UiError?,
) {
    val busy: Boolean get() = inFlight != null

    /** Actions offered for the CONFIRMED status. */
    val actions: List<ContactAction>
        get() = when (status) {
            null -> emptyList()
            ContactAutomationStatus.ACTIVE -> listOf(ContactAction.PAUSE, ContactAction.TAKEOVER)
            ContactAutomationStatus.PAUSED -> listOf(ContactAction.RESUME, ContactAction.TAKEOVER)
            ContactAutomationStatus.TAKEN_OVER -> listOf(ContactAction.RESUME)
        }

    /** Pause switch position: on only when the server says paused or taken over. */
    val pausedSwitch: Boolean get() = status == ContactAutomationStatus.PAUSED || status == ContactAutomationStatus.TAKEN_OVER

    /** Takeover switch position: on only when the server says taken over. */
    val takeoverSwitch: Boolean get() = status == ContactAutomationStatus.TAKEN_OVER

    companion object {
        fun label(status: ContactAutomationStatus?, mode: AutomationMode?, global: GlobalAutomationStatus?): Pair<String, LabelTone> =
            when (status) {
                null -> "Status unknown" to LabelTone.NEUTRAL
                ContactAutomationStatus.TAKEN_OVER -> "You're in control: Wingman is off for this person" to LabelTone.WARNING
                ContactAutomationStatus.PAUSED -> "Paused: no automatic replies" to LabelTone.WARNING
                ContactAutomationStatus.ACTIVE -> when {
                    global == GlobalAutomationStatus.PAUSED -> "Paused for everyone: no automatic replies" to LabelTone.WARNING
                    mode == AutomationMode.MANUAL -> "Active (manual: Wingman only suggests)" to LabelTone.NEUTRAL
                    mode == AutomationMode.ASSISTED -> "Active (assisted: you approve each reply)" to LabelTone.NEUTRAL
                    mode == AutomationMode.AUTOPILOT -> "Active (autopilot)" to LabelTone.SUCCESS
                    else -> "Active" to LabelTone.NEUTRAL
                }
            }
    }
}

data class GlobalControls(
    /** Server-confirmed; null until known. */
    val status: GlobalAutomationStatus?,
    val label: String,
    /** "Pausing…" / "Resuming…" while in flight. */
    val inFlightLabel: String?,
    val confirmation: String?,
    val error: UiError?,
) {
    val switchOn: Boolean get() = status == GlobalAutomationStatus.PAUSED
    val busy: Boolean get() = inFlightLabel != null
}

data class AutomationScreenData(
    val global: GlobalControls,
    val contacts: List<ContactControls>,
    /** True when contacts could not all be listed. */
    val contactsIncomplete: Boolean,
)
