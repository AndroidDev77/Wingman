package com.wingman.features.onboarding

import com.wingman.core.api.ApiResult
import com.wingman.core.api.WingmanApi
import com.wingman.core.api.mock.MockFixtures
import com.wingman.core.api.mock.MockWingmanApi
import com.wingman.core.model.AutomationMode
import com.wingman.core.model.AutomationSettings
import com.wingman.core.model.EmojiFrequency
import com.wingman.core.model.Intent
import com.wingman.core.model.IntentMode
import com.wingman.core.model.Preferences
import com.wingman.core.model.PreferencesUpdate
import com.wingman.core.model.StyleProfile
import com.wingman.core.ui.UiError
import com.wingman.core.ui.UiState
import com.wingman.features.settings.ChannelInfo
import com.wingman.features.settings.FormField
import com.wingman.features.settings.PreferencesEditor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class CountingApi(val inner: MockWingmanApi = MockWingmanApi()) : WingmanApi by inner {
    val puts = mutableListOf<PreferencesUpdate>()
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun putPreferences(body: PreferencesUpdate, ifMatchVersion: Int?): ApiResult<Preferences> {
        puts += body
        gate?.await()
        return inner.putPreferences(body, ifMatchVersion)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingWizardTest {

    private fun TestScope.wizard(api: WingmanApi): OnboardingWizard {
        val w = OnboardingWizard(this, api, editor = PreferencesEditor(this, api, loadChannels = { ChannelInfo.of(MockFixtures.connections) }))
        w.load(); advanceUntilIdle()
        return w
    }

    private val OnboardingWizard.form get() = editor.content!!.form

    @Test fun eachOfTheFiveModesCanBeSelectedAndSaved() = runTest {
        IntentMode.entries.forEach { mode ->
            val api = CountingApi()
            val w = wizard(api)
            w.editor.edit { it.copy(intentMode = mode, customDescription = if (mode == IntentMode.CUSTOM) "My own goal" else "") }
            assertEquals(mode, w.form.intentMode)
            assertTrue(w.next()); assertTrue(w.next()); assertTrue(w.next())
            assertEquals(OnboardingStep.Review, w.step.value)
            assertTrue(w.finish()); advanceUntilIdle()
            assertEquals(mode, api.puts.single().intent.mode)
            assertEquals(mode, w.finished.value!!.intent.mode)
        }
    }

    @Test fun cannotLeaveIntentStepWithoutAChoice() = runTest {
        val w = wizard(CountingApi())
        assertFalse(w.next())
        assertEquals(OnboardingStep.Intent, w.step.value)
        assertTrue(FormField.INTENT_MODE in w.editor.content!!.errors)
    }

    @Test fun customWithoutDescriptionBlocksTheWizard() = runTest {
        val api = CountingApi()
        val w = wizard(api)
        w.editor.edit { it.copy(intentMode = IntentMode.CUSTOM, customDescription = "  ") }
        assertFalse(w.next())
        assertEquals(OnboardingStep.Intent, w.step.value)
        assertTrue(FormField.CUSTOM_DESCRIPTION in w.editor.content!!.errors)
        assertTrue(api.puts.isEmpty())
    }

    @Test fun finishJumpsBackToTheFirstInvalidStep() = runTest {
        val api = CountingApi()
        val w = wizard(api)
        w.editor.edit { it.copy(intentMode = IntentMode.CUSTOM, customDescription = "ok") }
        w.next(); w.next(); w.next()
        // Description cleared after passing the step (e.g. edited from Review).
        w.editor.edit { it.copy(customDescription = "") }
        assertFalse(w.finish())
        assertEquals(OnboardingStep.Intent, w.step.value)
        assertTrue(api.puts.isEmpty())
    }

    @Test fun styleIsOptionalAndNeverPrefilled() = runTest {
        val api = CountingApi()
        val w = wizard(api)
        w.editor.edit { it.copy(intentMode = IntentMode.OPEN) }
        w.next()
        assertEquals(OnboardingStep.Style, w.step.value)
        assertTrue(w.form.style.isEmpty)
        assertTrue(w.skipStyle())
        assertEquals(OnboardingStep.Autonomy, w.step.value)
        w.next(); w.finish(); advanceUntilIdle()
        val style = api.puts.single().style
        assertNull(style.punctuation); assertNull(style.emojiFrequency); assertNull(style.brevity)
        assertNull(style.humor); assertNull(style.tone); assertNull(style.responsePace)
        assertEquals(emptyList<String>(), style.styleSamples)
    }

    @Test fun explicitStyleAndSamplesAreSaved() = runTest {
        val api = CountingApi()
        val w = wizard(api)
        w.editor.edit { it.copy(intentMode = IntentMode.SERIOUS) }
        w.next()
        w.editor.editStyle { it.copy(emojiFrequency = EmojiFrequency.RARE, samples = listOf("haha that's amazing", "")) }
        w.next(); w.next(); w.finish(); advanceUntilIdle()
        val style = api.puts.single().style
        assertEquals(EmojiFrequency.RARE, style.emojiFrequency)
        assertNull(style.tone)
        assertEquals(listOf("haha that's amazing"), style.styleSamples)
    }

    @Test fun automationIsManualUnlessExplicitlyConfirmed() = runTest {
        val api = CountingApi()
        val w = wizard(api)
        w.editor.edit { it.copy(intentMode = IntentMode.OPEN) }
        w.next(); w.next()
        assertEquals(OnboardingStep.Autonomy, w.step.value)
        assertEquals(AutomationMode.MANUAL, w.form.automation.mode)
        // Opening the explanation and backing out leaves it MANUAL.
        w.editor.requestAutomationMode(AutomationMode.AUTOPILOT)
        w.editor.cancelAutomationMode()
        w.next(); w.finish(); advanceUntilIdle()
        assertEquals(AutomationMode.MANUAL, api.puts.single().automation.mode)

        val api2 = CountingApi()
        val w2 = wizard(api2)
        w2.editor.edit { it.copy(intentMode = IntentMode.OPEN) }
        w2.next(); w2.next()
        w2.editor.requestAutomationMode(AutomationMode.ASSISTED)
        w2.editor.confirmAutomationMode()
        w2.next(); w2.finish(); advanceUntilIdle()
        assertEquals(AutomationMode.ASSISTED, api2.puts.single().automation.mode)
    }

    @Test fun backNeverValidatesAndGoToOnlyGoesBackwards() = runTest {
        val w = wizard(CountingApi())
        assertFalse(w.back())
        w.editor.edit { it.copy(intentMode = IntentMode.OPEN) }
        w.next(); w.next()
        w.goTo(OnboardingStep.Review)
        assertEquals(OnboardingStep.Autonomy, w.step.value)
        w.goTo(OnboardingStep.Intent)
        assertEquals(OnboardingStep.Intent, w.step.value)
        w.next()
        assertTrue(w.back())
        assertEquals(OnboardingStep.Intent, w.step.value)
        assertFalse(w.skipStyle())
    }

    @Test fun doubleTapFinishSendsOneRequest() = runTest {
        val api = CountingApi()
        val w = wizard(api)
        w.editor.edit { it.copy(intentMode = IntentMode.CASUAL_DATING) }
        w.next(); w.next(); w.next()
        api.gate = CompletableDeferred()
        assertTrue(w.finish())
        assertFalse(w.finish())
        assertFalse(w.finish())
        api.gate!!.complete(Unit); advanceUntilIdle()
        assertEquals(1, api.puts.size)
        assertNotNull(w.finished.value)
    }

    @Test fun onboardingOnAnotherDeviceFirstIsAConflictNotAnOverwrite() = runTest {
        val api = CountingApi()
        val w = wizard(api)
        api.inner.putPreferences(PreferencesUpdate(Intent(IntentMode.SERIOUS), StyleProfile(), AutomationSettings(AutomationMode.MANUAL)), 0)
        w.editor.edit { it.copy(intentMode = IntentMode.OPEN) }
        w.next(); w.next(); w.next(); w.finish(); advanceUntilIdle()
        assertNull(w.finished.value)
        assertNotNull(w.editor.content!!.conflict)
        assertEquals(IntentMode.SERIOUS, api.inner.getPreferences().getOrNull()!!.intent.mode)
    }

    @Test fun gateOpensOnlyForNeverSavedUsers() {
        assertTrue(OnboardingGate.needsOnboarding(UiState.Content(MockFixtures.defaultPreferences)))
        assertFalse(OnboardingGate.needsOnboarding(UiState.Content(MockFixtures.defaultPreferences.copy(version = 1))))
        assertFalse(OnboardingGate.needsOnboarding(UiState.Loading))
        assertFalse(OnboardingGate.needsOnboarding(UiState.Error(UiError("x"))))
    }

    @Test fun firstLaunchCheck() = runTest {
        val api = MockWingmanApi()
        assertTrue(OnboardingGate.check(api))
        api.failNextWith()
        assertFalse(OnboardingGate.check(api)) // unknown never forces the wizard
        api.putPreferences(PreferencesUpdate(Intent(IntentMode.OPEN), StyleProfile(), AutomationSettings(AutomationMode.MANUAL)), 0)
        assertFalse(OnboardingGate.check(api))
    }
}
