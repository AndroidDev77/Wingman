package com.wingman.features.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.wingman.core.model.Preferences
import com.wingman.core.ui.UiState
import com.wingman.core.ui.compose.LocalAppContainer
import com.wingman.core.ui.compose.UiStateContent
import com.wingman.core.ui.compose.wingmanViewModel
import com.wingman.features.settings.AutomationConfirmDialog
import com.wingman.features.settings.AutomationLimitsEditor
import com.wingman.features.settings.AutomationModeSelector
import com.wingman.features.settings.ChannelInfo
import com.wingman.features.settings.ChannelSummary
import com.wingman.features.settings.ConflictBanner
import com.wingman.features.settings.EditorContent
import com.wingman.features.settings.IntentPicker
import com.wingman.features.settings.PreferenceForm
import com.wingman.features.settings.PreferenceLabels
import com.wingman.features.settings.SaveStatus
import com.wingman.features.settings.StyleEditor
import com.wingman.navigation.Destination
import com.wingman.navigation.ScreenContent
import com.wingman.ui.theme.WingmanTheme

/*
 * Compose layer for the WING-013 onboarding wizard. Compiled by CI's Android job; not run on a
 * device yet. The step machine and every rule live in OnboardingWizard.kt and the settings
 * package (JVM-tested).
 */

/**
 * For the integrator. Onboarding needs its own route; when finished it goes to the Inbox.
 * `.register(Destination.Onboarding, OnboardingScreenEntry)` once `Destination.Onboarding` exists.
 */
val OnboardingScreenEntry: ScreenContent = { _, navigator ->
    OnboardingScreen(onFinished = { navigator.navigateTo(Destination.Inbox) })
}

class OnboardingViewModel(api: WingmanApi) : ViewModel() {
    val wizard = OnboardingWizard(viewModelScope, api)

    init {
        wizard.load()
    }
}

@Composable
fun OnboardingScreen(onFinished: (Preferences) -> Unit) {
    val container = LocalAppContainer.current
    val vm = wingmanViewModel(key = "onboarding") { OnboardingViewModel(container.api) }
    val state by vm.wizard.editor.state.collectAsStateWithLifecycle()
    val step by vm.wizard.step.collectAsStateWithLifecycle()
    val finished by vm.wizard.finished.collectAsStateWithLifecycle()
    LaunchedEffect(finished) { finished?.let(onFinished) }
    OnboardingContent(state, step, vm.wizard, onRetry = vm.wizard::load)
}

@Composable
fun OnboardingContent(state: UiState<EditorContent>, step: OnboardingStep, wizard: OnboardingWizard?, onRetry: () -> Unit) {
    val editor = wizard?.editor
    UiStateContent(state, onRetry = onRetry) { c ->
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Step ${step.index + 1} of ${OnboardingStep.COUNT}", style = MaterialTheme.typography.labelMedium)
            LinearProgressIndicator(progress = { (step.index + 1f) / OnboardingStep.COUNT }, modifier = Modifier.fillMaxWidth())
            Text(step.title, style = MaterialTheme.typography.headlineSmall)
            c.conflict?.let {
                ConflictBanner(it.message, "Keep my answers", { editor?.keepMyEdits() }, "Use saved settings", { editor?.useLatest() })
            }

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (step) {
                    OnboardingStep.Intent -> IntentPicker(
                        selected = c.form.intentMode,
                        customDescription = c.form.customDescription,
                        errors = c.errors,
                        onSelect = { mode -> editor?.edit { it.copy(intentMode = mode) } },
                        onCustomDescription = { text -> editor?.edit { it.copy(customDescription = text) } },
                    )
                    OnboardingStep.Style -> StyleEditor(c.form.style, c.errors) { style -> editor?.editStyle { style } }
                    OnboardingStep.Autonomy -> {
                        Text(
                            "Automation is off unless you turn it on. In Manual, Wingman only suggests replies when you ask.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        AutomationModeSelector(c.form.automation.mode, onRequest = { mode -> editor?.requestAutomationMode(mode ?: AutomationMode.MANUAL) })
                        ChannelSummary(c.channels)
                        if (c.form.automation.mode != AutomationMode.MANUAL) {
                            AutomationLimitsEditor(c.form.automation, c.errors) { a -> editor?.editAutomation { a } }
                        }
                    }
                    OnboardingStep.Review -> Review(c.form, onEdit = { wizard?.goTo(it) })
                }
            }

            if (c.saveStatus is SaveStatus.Failed) {
                Text((c.saveStatus as SaveStatus.Failed).error.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (!step.isFirst) TextButton(onClick = { wizard?.back() }, enabled = !c.isSaving) { Text("Back") }
                Spacer(Modifier.weight(1f))
                if (step == OnboardingStep.Style) TextButton(onClick = { wizard?.skipStyle() }) { Text("Skip") }
                if (step.isLast) {
                    if (c.isSaving) CircularProgressIndicator(Modifier.padding(end = 8.dp))
                    Button(onClick = { wizard?.finish() }, enabled = !c.isSaving && c.conflict == null) { Text("Save and finish") }
                } else {
                    Button(onClick = { wizard?.next() }) { Text("Next") }
                }
            }
        }
        c.pendingAutomation?.let { pending ->
            AutomationConfirmDialog(pending, onConfirm = { editor?.confirmAutomationMode() }, onCancel = { editor?.cancelAutomationMode() })
        }
    }
}

@Composable
private fun Review(form: PreferenceForm, onEdit: (OnboardingStep) -> Unit) {
    val intent = form.intentMode?.let(PreferenceLabels::intent) ?: "Not chosen"
    ReviewRow("Looking for", intent + (if (form.customDescription.isNotBlank() && form.intentMode == com.wingman.core.model.IntentMode.CUSTOM) ": ${form.customDescription.trim()}" else "")) {
        onEdit(OnboardingStep.Intent)
    }
    val s = form.style
    val styleParts = listOfNotNull(
        s.tone?.let { "tone ${PreferenceLabels.tone(it).lowercase()}" },
        s.humor?.let { "humor ${PreferenceLabels.humor(it).lowercase()}" },
        s.brevity?.let { "length ${PreferenceLabels.brevity(it).lowercase()}" },
        s.emojiFrequency?.let { "emoji ${PreferenceLabels.emoji(it).lowercase()}" },
        s.punctuation?.let { "punctuation ${PreferenceLabels.punctuation(it).lowercase()}" },
        s.responsePace?.let { "pace ${PreferenceLabels.pace(it).lowercase()}" },
    ) + listOfNotNull(s.cleanSamples.size.takeIf { it > 0 }?.let { "$it sample${if (it == 1) "" else "s"}" })
    ReviewRow("Style", if (styleParts.isEmpty()) "Not set (neutral)" else styleParts.joinToString(", ")) { onEdit(OnboardingStep.Style) }
    ReviewRow("Automation", PreferenceLabels.automation(form.automation.mode)) { onEdit(OnboardingStep.Autonomy) }
}

@Composable
private fun ReviewRow(label: String, value: String, onEdit: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = onEdit) { Text("Edit") }
    }
}

@Preview(name = "Onboarding intent step")
@Composable
private fun OnboardingPreview() = WingmanTheme {
    Surface {
        OnboardingContent(
            UiState.Content(
                EditorContent(PreferenceForm(), 0, MockFixtures.defaultPreferences, ChannelInfo.of(MockFixtures.connections)),
            ),
            OnboardingStep.Intent,
            wizard = null,
            onRetry = {},
        )
    }
}
