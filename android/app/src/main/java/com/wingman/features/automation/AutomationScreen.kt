package com.wingman.features.automation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.wingman.core.model.GlobalAutomationStatus
import com.wingman.core.ui.UiState
import com.wingman.core.ui.compose.UiStateContent
import com.wingman.features.conversation.LabelTone
import com.wingman.features.conversation.color
import com.wingman.ui.theme.WingmanTheme

/*
 * Pause / resume / takeover controls (WING-015, D-014). Compiled by CI's android job; NOT run on
 * a device. Switch positions come only from the server-confirmed status in [ContactControls] /
 * [GlobalControls]; flipping one starts a request and shows "Pausing…" until the server answers.
 */

/** Callbacks for the controls; [AutomationState] implements them in the app. */
interface AutomationActions {
    fun load()
    fun pause(contactId: String): Boolean
    fun resume(contactId: String): Boolean
    fun takeover(contactId: String): Boolean
    fun pauseAll(): Boolean
    fun resumeAll(): Boolean
}

@Composable
fun AutomationControls(state: UiState<AutomationScreenData>, holder: AutomationState) =
    AutomationControlsContent(
        state,
        object : AutomationActions {
            override fun load() = holder.load()
            override fun pause(contactId: String) = holder.pause(contactId)
            override fun resume(contactId: String) = holder.resume(contactId)
            override fun takeover(contactId: String) = holder.takeover(contactId)
            override fun pauseAll() = holder.pauseAll()
            override fun resumeAll() = holder.resumeAll()
        },
    )

/** Stateless controls: previews and Compose UI tests drive it directly. */
@Composable
fun AutomationControlsContent(state: UiState<AutomationScreenData>, actions: AutomationActions, modifier: Modifier = Modifier) {
    // Takeover asks first: it cancels queued work and can't be undone by resuming.
    var confirmTakeover by rememberSaveable { mutableStateOf<String?>(null) }
    UiStateContent(state, onRetry = actions::load, modifier = modifier, emptyMessage = "No contacts yet.") { data ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            (state as? UiState.Content)?.refreshError?.let { e -> item(key = "refresh-error") { Text(e.message, color = MaterialTheme.colorScheme.error) } }
            item(key = "global") { GlobalCard(data.global, actions) }
            item(key = "explain") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Pause", style = MaterialTheme.typography.titleSmall)
                    Text(AutomationCopy.PAUSE, style = MaterialTheme.typography.bodySmall)
                    Text("Take over", style = MaterialTheme.typography.titleSmall)
                    Text(AutomationCopy.TAKEOVER, style = MaterialTheme.typography.bodySmall)
                    Text(AutomationCopy.ALREADY_SENDING, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(data.contacts, key = { it.contactId }) { c -> ContactCard(c, actions, onTakeover = { confirmTakeover = c.contactId }) }
            if (data.contactsIncomplete) {
                item(key = "incomplete") { Text("Some contacts couldn't be listed. Pull to refresh.", style = MaterialTheme.typography.bodySmall) }
            }
        }
        confirmTakeover?.let { id ->
            val name = data.contacts.firstOrNull { it.contactId == id }?.name ?: "this person"
            AlertDialog(
                onDismissRequest = { confirmTakeover = null },
                title = { Text("Take over with $name?") },
                text = { Text(AutomationCopy.TAKEOVER) },
                confirmButton = { TextButton(onClick = { actions.takeover(id); confirmTakeover = null }) { Text("Take over") } },
                dismissButton = { TextButton(onClick = { confirmTakeover = null }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun GlobalCard(g: GlobalControls, actions: AutomationActions) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Pause everything", style = MaterialTheme.typography.titleMedium)
                    Text(g.inFlightLabel ?: g.label, style = MaterialTheme.typography.labelMedium, color = if (g.switchOn) LabelTone.WARNING.color() else LabelTone.NEUTRAL.color())
                }
                if (g.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Switch(
                    // Position = server state. A flip sends the request; the switch moves when the server confirms.
                    checked = g.switchOn,
                    onCheckedChange = { on -> if (on) actions.pauseAll() else actions.resumeAll() },
                    enabled = !g.busy && g.status != null,
                )
            }
            Text(AutomationCopy.GLOBAL_PAUSE, style = MaterialTheme.typography.bodySmall)
            g.confirmation?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            g.error?.let { Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun ContactCard(c: ContactControls, actions: AutomationActions, onTakeover: () -> Unit) {
    val known = c.status != null
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.titleMedium)
                    Text(c.inFlight?.inFlightLabel ?: c.statusLabel, style = MaterialTheme.typography.labelMedium, color = c.tone.color())
                }
                if (c.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Paused", modifier = Modifier.weight(1f))
                Switch(
                    checked = c.pausedSwitch,
                    onCheckedChange = { on -> if (on) actions.pause(c.contactId) else actions.resume(c.contactId) },
                    enabled = known && !c.busy,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("I'm taking over", modifier = Modifier.weight(1f))
                Switch(
                    checked = c.takeoverSwitch,
                    onCheckedChange = { on -> if (on) onTakeover() else actions.resume(c.contactId) },
                    enabled = known && !c.busy,
                )
            }
            c.confirmation?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            c.error?.let { Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private object NoActions : AutomationActions {
    override fun load() = Unit
    override fun pause(contactId: String) = false
    override fun resume(contactId: String) = false
    override fun takeover(contactId: String) = false
    override fun pauseAll() = false
    override fun resumeAll() = false
}

@Preview(name = "Automation controls", heightDp = 900)
@Composable
private fun ControlsPreview() = WingmanTheme {
    Surface {
        val (label, tone) = ContactControls.label(com.wingman.core.model.ContactAutomationStatus.ACTIVE, com.wingman.core.model.AutomationMode.ASSISTED, GlobalAutomationStatus.ACTIVE)
        AutomationControlsContent(
            UiState.Content(
                AutomationScreenData(
                    global = GlobalControls(GlobalAutomationStatus.ACTIVE, "Automation is on", null, null, null),
                    contacts = listOf(
                        ContactControls("c1", "Sam", com.wingman.core.model.ContactAutomationStatus.ACTIVE, com.wingman.core.model.AutomationMode.ASSISTED, label, tone, ContactAction.TAKEOVER, null, null),
                        ContactControls(
                            "c2", "Jordan", com.wingman.core.model.ContactAutomationStatus.TAKEN_OVER, null,
                            "You're in control: Wingman is off for this person", LabelTone.WARNING, null,
                            "You've taken over. Canceled 1 draft waiting for approval.", null,
                        ),
                    ),
                    contactsIncomplete = false,
                ),
            ),
            NoActions,
        )
    }
}
