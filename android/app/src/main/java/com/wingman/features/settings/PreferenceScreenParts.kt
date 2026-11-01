package com.wingman.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wingman.core.model.ApprovalReason
import com.wingman.core.model.AutomationMode
import com.wingman.core.model.Brevity
import com.wingman.core.model.EmojiFrequency
import com.wingman.core.model.Humor
import com.wingman.core.model.IntentMode
import com.wingman.core.model.Punctuation
import com.wingman.core.model.ResponsePace
import com.wingman.core.model.Tone
import com.wingman.core.model.Weekday

/*
 * Shared, stateless preference sections used by onboarding, settings and per-contact overrides.
 * All rules (validation, automation confirmation, conflict handling) live in the JVM-tested
 * PreferenceForm.kt / PreferencesEditor.kt / ContactOverrides.kt; these composables only render.
 */

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.padding(top = 8.dp))
}

@Composable
fun FieldError(errors: Map<FormField, String>, field: FormField) {
    errors[field]?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

/** The five dating intents, each with a one-line description; custom shows a required text field. */
@Composable
fun IntentPicker(
    selected: IntentMode?,
    customDescription: String,
    errors: Map<FormField, String>,
    onSelect: (IntentMode) -> Unit,
    onCustomDescription: (String) -> Unit,
    enabled: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        IntentMode.entries.forEach { mode ->
            Row(
                Modifier.fillMaxWidth()
                    .selectable(selected = selected == mode, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(mode) })
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == mode, onClick = null, enabled = enabled)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(PreferenceLabels.intent(mode), style = MaterialTheme.typography.bodyLarge)
                    Text(PreferenceLabels.intentDescription(mode), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        FieldError(errors, FormField.INTENT_MODE)
        if (selected == IntentMode.CUSTOM) {
            OutlinedTextField(
                value = customDescription,
                onValueChange = onCustomDescription,
                label = { Text("Describe your goal (required)") },
                isError = FormField.CUSTOM_DESCRIPTION in errors,
                supportingText = {
                    Text(errors[FormField.CUSTOM_DESCRIPTION] ?: "${customDescription.trim().length}/${Limits.CUSTOM_DESCRIPTION_MAX}")
                },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A row of chips for one optional attribute. "Not set" is always available and is the default. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> OptionalChoice(
    title: String,
    options: List<T>,
    selected: T?,
    optionLabel: (T) -> String,
    onSelect: (T?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text(PreferenceLabels.NOT_SET) })
            options.forEach { option ->
                FilterChip(selected = selected == option, onClick = { onSelect(option) }, label = { Text(optionLabel(option)) })
            }
        }
    }
}

/** Explicit style attributes and samples. Nothing is pre-selected or inferred. */
@Composable
fun StyleEditor(style: StyleForm, errors: Map<FormField, String>, onChange: (StyleForm) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Optional. Anything left as \"Not set\" uses a neutral default; Wingman never guesses your style.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OptionalChoice("Tone", Tone.entries, style.tone, PreferenceLabels::tone) { onChange(style.copy(tone = it)) }
        OptionalChoice("Humor", Humor.entries, style.humor, PreferenceLabels::humor) { onChange(style.copy(humor = it)) }
        OptionalChoice("Message length", Brevity.entries, style.brevity, PreferenceLabels::brevity) { onChange(style.copy(brevity = it)) }
        OptionalChoice("Emoji", EmojiFrequency.entries, style.emojiFrequency, PreferenceLabels::emoji) { onChange(style.copy(emojiFrequency = it)) }
        OptionalChoice("Punctuation", Punctuation.entries, style.punctuation, PreferenceLabels::punctuation) { onChange(style.copy(punctuation = it)) }
        OptionalChoice("Reply pace", ResponsePace.entries, style.responsePace, PreferenceLabels::pace) { onChange(style.copy(responsePace = it)) }
        StyleSamplesEditor(style.samples, errors) { onChange(style.copy(samples = it)) }
    }
}

@Composable
fun StyleSamplesEditor(samples: List<String>, errors: Map<FormField, String>, onChange: (List<String>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Example messages you wrote (optional)", style = MaterialTheme.typography.labelLarge)
        samples.forEachIndexed { i, sample ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = sample,
                    onValueChange = { text -> onChange(samples.toMutableList().also { it[i] = text }) },
                    label = { Text("Sample ${i + 1}") },
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onChange(samples.filterIndexed { j, _ -> j != i }) }) { Text("Remove") }
            }
        }
        if (samples.size < Limits.STYLE_SAMPLES_MAX) {
            OutlinedButton(onClick = { onChange(samples + "") }) { Text("Add a sample") }
        }
        FieldError(errors, FormField.STYLE_SAMPLES)
    }
}

/**
 * Automation mode selector. Tapping a mode calls [onRequest]; the editor decides whether that
 * needs confirmation, so this can never switch automation on by itself.
 * [inheritLabel] adds an "inherit" option (per-contact overrides), selected when [selected] is null.
 */
@Composable
fun AutomationModeSelector(
    selected: AutomationMode?,
    onRequest: (AutomationMode?) -> Unit,
    inheritLabel: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (inheritLabel != null) {
            ModeRow(inheritLabel, "Use the mode from your settings.", selected == null) { onRequest(null) }
        }
        AutomationMode.entries.forEach { mode ->
            ModeRow(PreferenceLabels.automation(mode), PreferenceLabels.automationDescription(mode), selected == mode) { onRequest(mode) }
        }
    }
}

@Composable
private fun ModeRow(title: String, description: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Plain-language explanation shown before automation is turned on. */
@Composable
fun AutomationConfirmDialog(pending: PendingAutomation, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val e = pending.explanation
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(e.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(e.summary)
                e.warning?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                e.points.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Turn on ${PreferenceLabels.automation(e.mode)}") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Keep current") } },
    )
}

/** Active hours, reply delays, daily limit and the message kinds that always need approval. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutomationLimitsEditor(a: AutomationForm, errors: Map<FormField, String>, onChange: (AutomationForm) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LabeledSwitch("Only send during active hours", a.activeHoursEnabled) { on ->
            onChange(
                a.copy(
                    activeHoursEnabled = on,
                    windows = if (on && a.windows.isEmpty()) listOf(WindowForm(Weekday.entries.toSet(), "09:00", "22:00")) else a.windows,
                ),
            )
        }
        if (a.activeHoursEnabled) {
            OutlinedTextField(
                value = a.timezone,
                onValueChange = { onChange(a.copy(timezone = it)) },
                label = { Text("Time zone, e.g. America/Chicago") },
                isError = FormField.TIMEZONE in errors,
                supportingText = { errors[FormField.TIMEZONE]?.let { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            a.windows.forEachIndexed { i, w ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Weekday.entries.forEach { d ->
                                FilterChip(
                                    selected = d in w.days,
                                    onClick = { onChange(a.copy(windows = a.windows.replace(i, w.copy(days = w.days.toggle(d))))) },
                                    label = { Text(PreferenceLabels.day(d)) },
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = w.start, onValueChange = { onChange(a.copy(windows = a.windows.replace(i, w.copy(start = it)))) },
                                label = { Text("From") }, singleLine = true, modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = w.end, onValueChange = { onChange(a.copy(windows = a.windows.replace(i, w.copy(end = it)))) },
                                label = { Text("Until") }, singleLine = true, modifier = Modifier.weight(1f),
                            )
                        }
                        TextButton(onClick = { onChange(a.copy(windows = a.windows.filterIndexed { j, _ -> j != i })) }) { Text("Remove window") }
                    }
                }
            }
            if (a.windows.size < Limits.WINDOWS_MAX) {
                OutlinedButton(onClick = { onChange(a.copy(windows = a.windows + WindowForm())) }) { Text("Add a time window") }
            }
            FieldError(errors, FormField.ACTIVE_WINDOWS)
        }

        LabeledSwitch("Wait a random delay before automatic replies", a.replyDelayEnabled) { onChange(a.copy(replyDelayEnabled = it)) }
        if (a.replyDelayEnabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Min seconds", a.replyDelayMin, FormField.REPLY_DELAY in errors, Modifier.weight(1f)) { onChange(a.copy(replyDelayMin = it)) }
                NumberField("Max seconds", a.replyDelayMax, FormField.REPLY_DELAY in errors, Modifier.weight(1f)) { onChange(a.copy(replyDelayMax = it)) }
            }
            FieldError(errors, FormField.REPLY_DELAY)
        }

        NumberField("Daily automatic send limit (blank = default)", a.dailySendLimit, FormField.DAILY_LIMIT in errors, Modifier.fillMaxWidth()) {
            onChange(a.copy(dailySendLimit = it))
        }
        FieldError(errors, FormField.DAILY_LIMIT)

        Text("Always ask me before sending", style = MaterialTheme.typography.labelLarge)
        PreferenceLabels.userApprovalReasons.forEach { reason ->
            ApprovalToggle(reason, reason in a.approvalRequiredFor) { on ->
                onChange(a.copy(approvalRequiredFor = if (on) a.approvalRequiredFor + reason else a.approvalRequiredFor - reason))
            }
        }
    }
}

@Composable
private fun ApprovalToggle(reason: ApprovalReason, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(PreferenceLabels.approvalReason(reason), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NumberField(label: String, value: String, isError: Boolean, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        isError = isError,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

/** Non-destructive conflict banner with the two resolutions. */
@Composable
fun ConflictBanner(message: String, primaryLabel: String, onPrimary: () -> Unit, secondaryLabel: String, onSecondary: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onPrimary) { Text(primaryLabel) }
                TextButton(onClick = onSecondary) { Text(secondaryLabel) }
            }
        }
    }
}

private fun <T> List<T>.replace(index: Int, value: T): List<T> = toMutableList().also { it[index] = value }
private fun <T> Set<T>.toggle(item: T): Set<T> = if (item in this) this - item else this + item
