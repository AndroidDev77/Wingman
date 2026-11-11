package com.wingman.features.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
import com.wingman.core.ui.compose.BannerTone
import com.wingman.core.ui.compose.InfoBanner
import com.wingman.core.ui.compose.LocalAppContainer
import com.wingman.core.ui.compose.SectionCard
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
import com.wingman.ui.theme.Spacing
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
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxWidth().padding(start = Spacing.screen, end = Spacing.screen, top = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    "Step ${step.index + 1} of ${OnboardingStep.COUNT}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                LinearProgressIndicator(
                    progress = { (step.index + 1f) / OnboardingStep.COUNT },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    strokeCap = StrokeCap.Round,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
                Text(
                    step.title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(top = Spacing.sm).semantics { heading() },
                )
                stepHelp(step)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                c.conflict?.let {
                    ConflictBanner(it.message, "Keep my answers", { editor?.keepMyEdits() }, "Use saved settings", { editor?.useLatest() })
                }
            }

            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screen, vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
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
                        InfoBanner("Automation is off unless you turn it on. In Manual, Wingman only suggests replies when you ask.")
                        AutomationModeSelector(c.form.automation.mode, onRequest = { mode -> editor?.requestAutomationMode(mode ?: AutomationMode.MANUAL) })
                        ChannelSummary(c.channels)
                        if (c.form.automation.mode != AutomationMode.MANUAL) {
                            AutomationLimitsEditor(c.form.automation, c.errors) { a -> editor?.editAutomation { a } }
                        }
                    }
                    OnboardingStep.Review -> Review(c.form, onEdit = { wizard?.goTo(it) })
                }
            }

            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    if (c.saveStatus is SaveStatus.Failed) {
                        InfoBanner((c.saveStatus as SaveStatus.Failed).error.message, tone = BannerTone.ERROR)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        if (!step.isFirst) TextButton(onClick = { wizard?.back() }, enabled = !c.isSaving) { Text("Back") }
                        Spacer(Modifier.weight(1f))
                        if (step == OnboardingStep.Style) TextButton(onClick = { wizard?.skipStyle() }) { Text("Skip") }
                        if (step.isLast) {
                            if (c.isSaving) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            Button(
                                onClick = { wizard?.finish() },
                                enabled = !c.isSaving && c.conflict == null,
                                modifier = Modifier.heightIn(min = Spacing.touchTarget),
                            ) { Text("Save and finish") }
                        } else {
                            Button(onClick = { wizard?.next() }, modifier = Modifier.heightIn(min = Spacing.touchTarget)) { Text("Next") }
                        }
                    }
                }
            }
        }
        c.pendingAutomation?.let { pending ->
            AutomationConfirmDialog(pending, onConfirm = { editor?.confirmAutomationMode() }, onCancel = { editor?.cancelAutomationMode() })
        }
    }
}

/** One line of help under each step title. */
private fun stepHelp(step: OnboardingStep): String? = when (step) {
    OnboardingStep.Intent -> "Wingman shapes its suggestions around your goal. You can change it later in Settings."
    OnboardingStep.Style -> "Helps suggestions sound like you."
    OnboardingStep.Autonomy -> "Choose how much Wingman does on its own. You can change this any time."
    OnboardingStep.Review -> "Check your answers. Tap Edit to change one."
}

@Composable
private fun Review(form: PreferenceForm, onEdit: (OnboardingStep) -> Unit) {
    val intent = form.intentMode?.let(PreferenceLabels::intent) ?: "Not chosen"
    val s = form.style
    val styleParts = listOfNotNull(
        s.tone?.let { "tone ${PreferenceLabels.tone(it).lowercase()}" },
        s.humor?.let { "humor ${PreferenceLabels.humor(it).lowercase()}" },
        s.brevity?.let { "length ${PreferenceLabels.brevity(it).lowercase()}" },
        s.emojiFrequency?.let { "emoji ${PreferenceLabels.emoji(it).lowercase()}" },
        s.punctuation?.let { "punctuation ${PreferenceLabels.punctuation(it).lowercase()}" },
        s.responsePace?.let { "pace ${PreferenceLabels.pace(it).lowercase()}" },
    ) + listOfNotNull(s.cleanSamples.size.takeIf { it > 0 }?.let { "$it sample${if (it == 1) "" else "s"}" })
    SectionCard {
        ReviewRow("Looking for", intent + (if (form.customDescription.isNotBlank() && form.intentMode == com.wingman.core.model.IntentMode.CUSTOM) ": ${form.customDescription.trim()}" else "")) {
            onEdit(OnboardingStep.Intent)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ReviewRow("Style", if (styleParts.isEmpty()) "Not set (neutral)" else styleParts.joinToString(", ")) { onEdit(OnboardingStep.Style) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ReviewRow("Automation", PreferenceLabels.automation(form.automation.mode)) { onEdit(OnboardingStep.Autonomy) }
    }
}

@Composable
private fun ReviewRow(label: String, value: String, onEdit: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
        TextButton(onClick = onEdit) { Text("Edit") }
    }
}

private val previewEditor = EditorContent(PreferenceForm(), 0, MockFixtures.defaultPreferences, ChannelInfo.of(MockFixtures.connections))

@Composable
private fun OnboardingPreviewFrame(step: OnboardingStep, dark: Boolean, content: EditorContent = previewEditor) = WingmanTheme(darkTheme = dark) {
    Surface { OnboardingContent(UiState.Content(content), step, wizard = null, onRetry = {}) }
}

@Preview(name = "Onboarding intent step", heightDp = 900)
@Composable
private fun OnboardingPreview() = OnboardingPreviewFrame(OnboardingStep.Intent, dark = false)

@Preview(name = "Onboarding intent step dark", heightDp = 900)
@Composable
private fun OnboardingDarkPreview() = OnboardingPreviewFrame(OnboardingStep.Intent, dark = true)

@Preview(name = "Onboarding automation step", heightDp = 900)
@Composable
private fun OnboardingAutonomyPreview() = OnboardingPreviewFrame(OnboardingStep.Autonomy, dark = false)

@Preview(name = "Onboarding review dark", heightDp = 900)
@Composable
private fun OnboardingReviewDarkPreview() = OnboardingPreviewFrame(
    OnboardingStep.Review,
    dark = true,
    content = previewEditor.copy(form = PreferenceForm.from(MockFixtures.defaultPreferences)),
)
