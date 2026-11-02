package com.wingman.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.wingman.core.api.WingmanApi
import com.wingman.core.api.mock.MockFixtures
import com.wingman.core.model.AutomationMode
import com.wingman.core.ui.LoadableState
import com.wingman.core.ui.UiState
import com.wingman.core.ui.compose.LocalAppContainer
import com.wingman.core.ui.compose.UiStateContent
import com.wingman.core.ui.compose.wingmanViewModel
import com.wingman.features.auth.SignOutButton
import com.wingman.navigation.ScreenContent
import com.wingman.ui.theme.WingmanTheme

/*
 * Compose layer for WING-013 settings. Compiled by CI's Android job; not run on a device yet.
 * All rules live in PreferenceForm.kt / PreferencesEditor.kt / ContactOverrides.kt (JVM-tested).
 */

/** Registered by the integrator: `.register(Destination.Settings, SettingsScreenEntry)`. */
val SettingsScreenEntry: ScreenContent = { _, _ -> SettingsScreen() }

class SettingsViewModel(api: WingmanApi) : ViewModel() {
    val editor = PreferencesEditor(viewModelScope, api)
    val contacts = LoadableState(viewModelScope, isEmpty = { it: List<ContactChoice> -> it.isEmpty() }) { loadContactChoices(api) }

    init {
        editor.load()
        contacts.load()
    }
}

class ContactOverrideViewModel(api: WingmanApi, contactId: String) : ViewModel() {
    val editor = ContactOverrideEditor(viewModelScope, api, contactId)

    init {
        editor.load()
    }
}

/** A row an experimental module adds (experimental build only; the standard build has none). */
@Composable
private fun ExperimentalRow(entry: com.wingman.experimental.ExperimentalEntry) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(entry.description, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = { entry.open(context) }) { Text(entry.actionLabel) }
    }
}

@Composable
fun SettingsScreen() {
    val container = LocalAppContainer.current
    val vm = wingmanViewModel(key = "settings") { SettingsViewModel(container.api) }
    val state by vm.editor.state.collectAsStateWithLifecycle()
    val contacts by vm.contacts.state.collectAsStateWithLifecycle()
    var selectedContact by rememberSaveable { mutableStateOf<String?>(null) }

    val contactId = selectedContact
    if (contactId != null) {
        val name = (contacts as? UiState.Content)?.data?.firstOrNull { it.id == contactId }?.name ?: "Contact"
        ContactOverrideScreen(contactId, name, onBack = { selectedContact = null })
        return
    }
    SettingsContent(
        state = state,
        contacts = contacts,
        onRetry = vm.editor::load,
        onRetryContacts = vm.contacts::load,
        editor = vm.editor,
        onOpenContact = { selectedContact = it },
        // I-011 (integrator): sign out of the encrypted session. Renders nothing in mock-API
        // builds, which have no session.
        footer = {
            HorizontalDivider()
            // I-020: pair / unpair this phone's SMS as a Wingman connection.
            SectionTitle("Connections")
            com.wingman.features.connections.SmsConnectionSection()
            // WING-027/028 (I-033): dating apps through their notifications, assisted only.
            SectionTitle("Dating apps (notifications)")
            com.wingman.features.connections.NotificationConnectionSection()
            // WING-031: per-connection auto-swipe, off and explained unless swipe is VERIFIED.
            SectionTitle("Auto-swipe")
            com.wingman.features.discovery.AutoSwipeSettingsSection()
            // WING-029 (D-021, D-023, D-026): only in the experimental build with the client identity.
            for (entry in com.wingman.experimental.ExperimentalModules.settingsEntries()) {
                SectionTitle(entry.section)
                ExperimentalRow(entry)
            }
            HorizontalDivider()
            SectionTitle("Account")
            SignOutButton()
        },
    )
}

@Composable
fun SettingsContent(
    state: UiState<EditorContent>,
    contacts: UiState<List<ContactChoice>>,
    onRetry: () -> Unit,
    onRetryContacts: () -> Unit,
    editor: PreferencesEditor?,
    onOpenContact: (String) -> Unit,
    /** Shown after the per-contact list (the account section on the real screen). */
    footer: @Composable () -> Unit = {},
) {
    UiStateContent(state, onRetry = onRetry) { c ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
            c.conflict?.let { conflict ->
                ConflictBanner(
                    conflict.message,
                    primaryLabel = "Keep my edits",
                    onPrimary = { editor?.keepMyEdits() },
                    secondaryLabel = "Load latest",
                    onSecondary = { editor?.useLatest() },
                )
            }

            SectionTitle("What you're looking for")
            IntentPicker(
                selected = c.form.intentMode,
                customDescription = c.form.customDescription,
                errors = c.errors,
                onSelect = { mode -> editor?.edit { it.copy(intentMode = mode) } },
                onCustomDescription = { text -> editor?.edit { it.copy(customDescription = text) } },
            )
            HorizontalDivider()

            SectionTitle("Your texting style")
            StyleEditor(c.form.style, c.errors) { style -> editor?.editStyle { style } }
            HorizontalDivider()

            SectionTitle("Automation")
            AutomationModeSelector(c.form.automation.mode, onRequest = { mode -> editor?.requestAutomationMode(mode ?: AutomationMode.MANUAL) })
            ChannelSummary(c.channels)
            AutomationLimitsEditor(c.form.automation, c.errors) { a -> editor?.editAutomation { a } }

            SaveBar(c, onSave = { editor?.save() })
            HorizontalDivider()

            SectionTitle("Per-contact overrides")
            Text(
                "Change intent, style or automation for one person. Overrides never change your settings above.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ContactList(contacts, onRetryContacts, onOpenContact)
            footer()
        }
        c.pendingAutomation?.let { pending ->
            AutomationConfirmDialog(pending, onConfirm = { editor?.confirmAutomationMode() }, onCancel = { editor?.cancelAutomationMode() })
        }
    }
}

@Composable
private fun SaveBar(c: EditorContent, onSave: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onSave, enabled = !c.isSaving && c.conflict == null) { Text("Save") }
        when (val s = c.saveStatus) {
            SaveStatus.Saving -> CircularProgressIndicator(Modifier.padding(4.dp))
            is SaveStatus.Saved -> Text("Saved", style = MaterialTheme.typography.bodySmall)
            is SaveStatus.Failed -> Text(s.error.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            SaveStatus.Idle -> if (c.isDirty) Text("Unsaved changes", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (c.errors.isNotEmpty()) {
        Text("Fix the highlighted fields to save.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun ChannelSummary(channels: ChannelInfo) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Channels", style = MaterialTheme.typography.labelLarge)
            when {
                !channels.known -> Text("Channel status couldn't be loaded. No channel is treated as able to send automatically.", style = MaterialTheme.typography.bodySmall)
                channels.channels.isEmpty() -> Text("No messaging channels connected.", style = MaterialTheme.typography.bodySmall)
                else -> channels.channels.forEach { Text(it.label, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun ContactList(contacts: UiState<List<ContactChoice>>, onRetry: () -> Unit, onOpen: (String) -> Unit) {
    when (contacts) {
        UiState.Loading -> CircularProgressIndicator()
        UiState.Empty -> Text("No contacts yet.", style = MaterialTheme.typography.bodyMedium)
        is UiState.Error -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(contacts.error.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
            if (contacts.error.retryable) TextButton(onClick = onRetry) { Text("Retry") }
        }
        is UiState.Content -> Column {
            contacts.data.forEach { contact ->
                Text(
                    contact.name,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().clickable { onOpen(contact.id) }.padding(vertical = 12.dp),
                )
                HorizontalDivider()
            }
        }
    }
}

/* ------------------------------------------------------------ per-contact override */

@Composable
fun ContactOverrideScreen(contactId: String, contactName: String, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val vm = wingmanViewModel(key = "contact-preferences:$contactId") { ContactOverrideViewModel(container.api, contactId) }
    val state by vm.editor.state.collectAsStateWithLifecycle()
    ContactOverrideContent(state, contactName, onBack, onRetry = vm.editor::load, editor = vm.editor)
}

@Composable
fun ContactOverrideContent(
    state: UiState<OverrideContent>,
    contactName: String,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    editor: ContactOverrideEditor?,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(contactName, style = MaterialTheme.typography.titleLarge)
        }
        UiStateContent(state, onRetry = onRetry) { c ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                c.conflictMessage?.let {
                    ConflictBanner(it, "Keep my edits", { editor?.dismissConflict() }, "Discard my edits", { editor?.discardEdits() })
                }
                SectionTitle("In effect for $contactName")
                EffectiveTable(c.effectiveRows)
                HorizontalDivider()

                SectionTitle("Override")
                Text(
                    "Only what you switch on here differs from your settings. Everything else follows your settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OverrideSwitch("Different intent for $contactName", c.form.intentOverridden) { on -> editor?.edit { it.copy(intentOverridden = on) } }
                if (c.form.intentOverridden) {
                    IntentPicker(
                        selected = c.form.intentMode,
                        customDescription = c.form.customDescription,
                        errors = c.errors,
                        onSelect = { mode -> editor?.edit { it.copy(intentMode = mode) } },
                        onCustomDescription = { text -> editor?.edit { it.copy(customDescription = text) } },
                    )
                }
                OverrideSwitch("Different style for $contactName", c.form.styleOverridden) { on -> editor?.edit { it.copy(styleOverridden = on) } }
                if (c.form.styleOverridden) {
                    StyleEditor(c.form.style, c.errors) { style -> editor?.edit { it.copy(style = style) } }
                }
                Text("Automation for $contactName", style = MaterialTheme.typography.labelLarge)
                AutomationModeSelector(c.form.automationMode, onRequest = { editor?.requestAutomationMode(it) }, inheritLabel = "Same as my settings")

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { editor?.save() }, enabled = !c.busy) { Text("Save override") }
                    OutlinedButton(onClick = { editor?.clear() }, enabled = !c.busy && c.hasOverride) { Text("Clear override") }
                    if (c.busy) CircularProgressIndicator()
                }
                c.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                c.actionError?.let { Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            c.pendingAutomation?.let { pending ->
                AutomationConfirmDialog(pending, onConfirm = { editor?.confirmAutomationMode() }, onCancel = { editor?.cancelAutomationMode() })
            }
        }
    }
}

@Composable
private fun OverrideSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun EffectiveTable(rows: List<EffectiveRow>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                Text(row.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.35f))
                Column(Modifier.weight(0.65f)) {
                    Text(row.value, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        row.source.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (row.source == ValueSource.CONTACT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/* ------------------------------------------------------------ previews */

private val previewContent = EditorContent(
    form = PreferenceForm.from(MockFixtures.defaultPreferences),
    baseVersion = 0,
    saved = MockFixtures.defaultPreferences,
    channels = ChannelInfo.of(MockFixtures.connections),
)

@Preview(name = "Settings")
@Composable
private fun SettingsPreview() = WingmanTheme {
    Surface {
        SettingsContent(
            UiState.Content(previewContent),
            UiState.Content(listOf(ContactChoice(MockFixtures.CONTACT_SAM, "Sam"))),
            {}, {}, editor = null, onOpenContact = {},
        )
    }
}

@Preview(name = "Settings loading")
@Composable
private fun SettingsLoadingPreview() = WingmanTheme {
    Surface { SettingsContent(UiState.Loading, UiState.Loading, {}, {}, editor = null, onOpenContact = {}) }
}
